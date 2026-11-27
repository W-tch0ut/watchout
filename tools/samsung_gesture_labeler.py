"""삼성 유니버설 제스처가 인식된 순간의 센서 값을 잘라내는 도구.

삼성 접근성 앱은 유니버설 제스처를 인식할 때마다 로그를 남긴다.

    1795742707.790 17425 17676 I WUG::ActionManagerImpl: action performed : NEXTITEM

워치 앱의 "삼성 제스처 수집" 화면은 센서 값을 실제 시각과 함께 기록한다(session__*.csv).
수집이 끝나면 PC에서 워치 로그를 꺼낸다(`adb logcat -d -b main -v epoch`).
이 도구는 로그의 인식 시각에 맞춰 세션 파일에서 센서 값을 잘라내고, 라벨을 붙여 저장한 뒤
제스처별로 센서 값이 얼마나 컸는지 요약한다.

    python -m tools.samsung_gesture_labeler --session session__X.csv --log logcat.txt \
        --out data/samsung

보통은 tools/collect_samsung_gestures.ps1 이 로그 꺼내기부터 분석까지 해 준다.

삼성 내부 판단 기준(임계값, 모델)은 볼 수 없다. 이 도구가 주는 것은
"삼성이 인식했다고 판단한 순간의 센서 값"이고, 기준은 이 값을 모아서 거꾸로 추정한다.
"""

from __future__ import annotations

import argparse
import math
import re
import statistics
import struct
from collections.abc import Iterable
from dataclasses import dataclass
from pathlib import Path

GRAVITY = 9.81

# 유니버설 제스처 "동작 → 제스처" 대응. 팀 워치(SM-R935N)의 설정에서 확인한 값이다.
#   엄지 검지 맞대기 → 다음 항목, 두 번 맞대기 → 이전 항목, 주먹 쥐기 → 한 번 누르기,
#   주먹 두 번 쥐기 → 액션 메뉴, 팔 앞뒤로 → 이전 화면으로 이동
# 워치 설정에서 제스처마다 동작을 바꿨다면 --map 으로 바꿔 준다.
DEFAULT_ACTION_TO_GESTURE = {
    "NEXTITEM": "pinch",
    "PREVITEM": "double_pinch",
    "TAP": "fist",
    "SHOW_ACTION_MENU": "double_fist",
    "BACK": "arm_back_forth",
}

_ACTION_RE = re.compile(
    r"^\s*(\d+\.\d+)\s+\d+\s+\d+\s+\w\s+WUG::ActionManagerImpl:\s*action performed\s*:\s*(\w+)"
)
_ACTIVATION_RE = re.compile(
    r"^\s*(\d+\.\d+)\s+\d+\s+\d+\s+\w\s+WUG::ActivationControllerImpl:\s*"
    r"activation onGestureDetected.*smartGestureType:\s*(\w+)"
)

Sample = tuple[int, tuple[float, ...]]  # (epoch_ms, 값들)


@dataclass(frozen=True)
class SamsungEvent:
    """삼성이 제스처를 인식한 순간 하나."""

    epoch_ms: int
    action: str  # 로그에 찍힌 동작 이름 (예: NEXTITEM)
    gesture: str  # 우리가 붙이는 라벨 (예: pinch)


@dataclass(frozen=True)
class WindowStats:
    """인식 순간 주변 구간의 센서 값 요약."""

    gesture: str
    epoch_ms: int
    imu_rate_hz: float
    peak_gyro: float  # 회전 속도 크기의 최댓값 (rad/s)
    gyro_axis_peak: tuple[float, float, float]  # 축별 |자이로| 최댓값
    peak_acc_dev: float  # |가속도 크기 - 중력| 최댓값 (m/s²)
    acc_axis_range: tuple[float, float, float]  # 축별 (최대 - 최소)
    ppg_range: float | None  # 심박 원시 신호 채널 PPG_CHANNEL의 (최대 - 최소). 기록 안 했으면 None


def parse_logcat(
    lines: Iterable[str], action_map: dict[str, str] | None = None
) -> list[SamsungEvent]:
    """`adb logcat -v epoch` 로그에서 삼성 제스처 인식 순간을 뽑는다.

    대응표에 없는 동작(BACK, INJECT_KEY_BACK 등)은 어떤 제스처인지 알 수 없어서 버린다.
    활성화 제스처(손목 두 번 흔들기 등)는 `activation_<종류>`로 붙인다.
    워치 연결이 끊겼다 다시 붙으면 같은 줄이 두 번 기록될 수 있다.
    그래서 시각과 동작이 같으면 한 번만 센다.
    """
    mapping = DEFAULT_ACTION_TO_GESTURE if action_map is None else action_map
    events: list[SamsungEvent] = []
    seen: set[tuple[int, str]] = set()
    for line in lines:
        if m := _ACTION_RE.match(line):
            action = m.group(2)
            if action not in mapping:
                continue
            event = SamsungEvent(_to_ms(m.group(1)), action, mapping[action])
        elif m := _ACTIVATION_RE.match(line):
            kind = m.group(2)
            event = SamsungEvent(_to_ms(m.group(1)), kind, f"activation_{kind.lower()}")
        else:
            continue
        if (event.epoch_ms, event.action) not in seen:
            seen.add((event.epoch_ms, event.action))
            events.append(event)
    return sorted(events, key=lambda e: e.epoch_ms)


def count_unmapped_actions(
    lines: Iterable[str], action_map: dict[str, str] | None = None
) -> dict[str, int]:
    """대응표에 없어서 버려진 동작을 센다. 사용자가 `--map`으로 지정할 수 있게 알려주는 용도.

    INJECT_KEY_BACK은 BACK 직후에 따라 나오는 내부 동작이라 세지 않는다.
    """
    mapping = DEFAULT_ACTION_TO_GESTURE if action_map is None else action_map
    seen: set[tuple[str, str]] = set()
    counts: dict[str, int] = {}
    for line in lines:
        if (m := _ACTION_RE.match(line)) and m.group(2) not in mapping:
            stamp, action = m.group(1), m.group(2)
            if action != "INJECT_KEY_BACK" and (stamp, action) not in seen:
                seen.add((stamp, action))
                counts[action] = counts.get(action, 0) + 1
    return counts


def parse_session(lines: Iterable[str]) -> dict[str, list[Sample]]:
    """세션 파일(`epoch_ms,sensor,값...`)을 센서별 시간순 목록으로 읽는다."""
    out: dict[str, list[Sample]] = {}
    for raw in lines:
        line = raw.strip()
        if not line or line.startswith("#") or line.startswith("epoch_ms"):
            continue
        parts = line.split(",")
        out.setdefault(parts[1], []).append((int(parts[0]), tuple(float(v) for v in parts[2:])))
    for samples in out.values():
        samples.sort(key=lambda s: s[0])
    return out


def cut_window(
    samples: list[Sample], center_ms: int, before_ms: int, after_ms: int, slack_ms: int = 100
) -> list[Sample] | None:
    """[center - before, center + after] 구간을 잘라낸다.

    구간 앞뒤가 기록 범위 밖이면(slack_ms 이상 비면) 불완전한 구간이라 None을 돌려준다.
    """
    start, end = center_ms - before_ms, center_ms + after_ms
    if not samples or samples[0][0] > start + slack_ms or samples[-1][0] < end - slack_ms:
        return None
    return [s for s in samples if start <= s[0] <= end]


def window_stats(
    event: SamsungEvent,
    session: dict[str, list[Sample]],
    before_ms: int = 1500,
    after_ms: int = 300,
) -> WindowStats | None:
    """인식 순간 주변 구간을 요약한다. 가속도·자이로가 구간을 덮지 못하면 None."""
    acc = cut_window(session.get("acc", []), event.epoch_ms, before_ms, after_ms)
    gyr = cut_window(session.get("gyr", []), event.epoch_ms, before_ms, after_ms)
    if not acc or not gyr:
        return None
    ppg = cut_window(session.get("ppg", []), event.epoch_ms, before_ms, after_ms)

    span_s = (acc[-1][0] - acc[0][0]) / 1000
    return WindowStats(
        gesture=event.gesture,
        epoch_ms=event.epoch_ms,
        imu_rate_hz=(len(acc) - 1) / span_s if span_s > 0 else 0.0,
        peak_gyro=max(_norm(v) for _, v in gyr),
        gyro_axis_peak=_axis(lambda i: max(abs(v[i]) for _, v in gyr)),
        peak_acc_dev=max(abs(_norm(v) - GRAVITY) for _, v in acc),
        acc_axis_range=_axis(lambda i: max(v[i] for _, v in acc) - min(v[i] for _, v in acc)),
        ppg_range=_ppg_range(ppg),
    )


# 삼성 "HR Raw Sensor"는 정수(광센서 원시 값)를 float 배열에 비트 그대로 넣어서 보낸다.
# 실수로 읽으면 2.9e-39 같은 아주 작은 수가 되므로, 비트를 int32로 다시 풀어야 한다.
# 팀 워치(SM-R935N)에서 확인: 채널 1~5가 광센서 값으로 크게 변한다.
# 2097152(2^21)는 "값 없음" 표시다.
PPG_CHANNEL = 1
PPG_EMPTY = 2_097_152


def decode_ppg(value: float) -> int:
    """float에 비트 그대로 담긴 int32 값을 꺼낸다."""
    return struct.unpack("<i", struct.pack("<f", value))[0]


def _ppg_range(ppg: list[Sample] | None) -> float | None:
    if not ppg:
        return None
    values = [decode_ppg(v[PPG_CHANNEL]) for _, v in ppg if len(v) > PPG_CHANNEL]
    values = [x for x in values if x != PPG_EMPTY]
    return float(max(values) - min(values)) if values else None


def summarize(stats: list[WindowStats]) -> str:
    """제스처별 평균±표준편차 표를 만든다."""
    rows = [
        "제스처            개수  최대회전(rad/s)  자이로 X/Y/Z 최대    가속 이탈(m/s²)  심박 변화폭"
    ]
    for gesture in sorted({s.gesture for s in stats}):
        group = [s for s in stats if s.gesture == gesture]
        axes = "/".join(
            f"{statistics.fmean(s.gyro_axis_peak[i] for s in group):.2f}" for i in range(3)
        )
        ppg = [s.ppg_range for s in group if s.ppg_range is not None]
        rows.append(
            f"{gesture:<17} {len(group):>4}  {_mean_sd([s.peak_gyro for s in group]):>15}  "
            f"{axes:>19}  {_mean_sd([s.peak_acc_dev for s in group]):>15}  "
            f"{_mean_sd(ppg) if ppg else '-':>11}"
        )
    return "\n".join(rows)


def write_window(
    out_dir: Path,
    event: SamsungEvent,
    session: dict[str, list[Sample]],
    before_ms: int = 1500,
    after_ms: int = 300,
) -> Path | None:
    """구간 하나를 `samsung_<제스처>__<시각>.csv`로 저장한다. 시간은 인식 순간을 0으로 한다."""
    pieces = {
        name: cut_window(samples, event.epoch_ms, before_ms, after_ms)
        for name, samples in session.items()
    }
    if not pieces.get("acc") or not pieces.get("gyr"):
        return None
    out_dir.mkdir(parents=True, exist_ok=True)
    path = out_dir / f"samsung_{event.gesture}__{event.epoch_ms}.csv"
    rows = sorted(
        (t - event.epoch_ms, name, values)
        for name, samples in pieces.items()
        if samples
        for t, values in samples
    )
    lines = [
        f"# label=samsung_{event.gesture},action={event.action},"
        f"epoch_ms={event.epoch_ms},before_ms={before_ms},after_ms={after_ms}",
        "t_ms,sensor,values...",
        *(f"{t},{name}," + ",".join(f"{v:.5f}" for v in values) for t, name, values in rows),
    ]
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return path


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument(
        "--session", required=True, type=Path, help="워치에서 가져온 session__*.csv"
    )
    parser.add_argument(
        "--log", required=True, type=Path, help="adb logcat -v epoch 로 꺼낸 워치 로그"
    )
    parser.add_argument("--out", type=Path, help="잘라낸 구간을 저장할 폴더 (없으면 요약만)")
    parser.add_argument("--before", type=int, default=1500, help="인식 시각 이전 ms (기본 1500)")
    parser.add_argument("--after", type=int, default=300, help="인식 시각 이후 ms (기본 300)")
    parser.add_argument(
        "--map",
        action="append",
        default=[],
        metavar="동작=제스처",
        help="동작→제스처 대응 추가/변경 (예: --map BACK=double_fist)",
    )
    args = parser.parse_args(argv)

    mapping = dict(DEFAULT_ACTION_TO_GESTURE)
    for item in args.map:
        action, _, gesture = item.partition("=")
        mapping[action.strip()] = gesture.strip()

    # 워치 로그에는 한글이 섞여 있다. 깨진 글자는 무시한다.
    log_lines = _read_lines(args.log)
    events = parse_logcat(log_lines, mapping)
    unmapped = count_unmapped_actions(log_lines, mapping)
    if unmapped:
        listed = ", ".join(f"{a} {n}개" for a, n in sorted(unmapped.items()))
        print(f"어떤 제스처인지 몰라서 뺀 동작: {listed}")
        print("  워치 설정에서 그 동작에 연결된 제스처를 확인하고 --map 동작=제스처 로 지정하세요.")
    session = parse_session(_read_lines(args.session))
    print(
        f"삼성 인식 {len(events)}개, 센서: "
        + ", ".join(f"{k} {len(v)}개" for k, v in session.items())
    )

    stats = []
    for event in events:
        s = window_stats(event, session, args.before, args.after)
        if s is None:
            print(f"  건너뜀: {event.gesture} @ {event.epoch_ms} (세션 기록 범위 밖)")
            continue
        stats.append(s)
        if args.out:
            write_window(args.out, event, session, args.before, args.after)

    if not stats:
        print("겹치는 구간이 없습니다. 로그와 세션을 같은 시간에 기록했는지 확인하세요.")
        return 1
    print(summarize(stats))
    if args.out:
        print(f"구간 {len(stats)}개를 {args.out} 에 저장했습니다.")
    return 0


def _read_lines(path: Path) -> list[str]:
    # PowerShell로 저장하면 UTF-16이나 BOM 붙은 UTF-8일 수 있다.
    data = path.read_bytes()
    if data[:2] in (b"\xff\xfe", b"\xfe\xff"):
        return data.decode("utf-16", errors="replace").splitlines()
    return data.decode("utf-8-sig", errors="replace").splitlines()


def _to_ms(seconds: str) -> int:
    return round(float(seconds) * 1000)


def _norm(v: tuple[float, ...]) -> float:
    return math.sqrt(sum(x * x for x in v[:3]))


def _axis(f) -> tuple[float, float, float]:
    return (f(0), f(1), f(2))


def _mean_sd(values: list[float]) -> str:
    if len(values) == 1:
        return f"{values[0]:.2f}"
    return f"{statistics.fmean(values):.2f}±{statistics.stdev(values):.2f}"


if __name__ == "__main__":
    raise SystemExit(main())
