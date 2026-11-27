"""음성 안내 시험 프로그램.

통신(#42)이 정해지기 전이라, 로봇카가 받을 명령과 상태를 키보드로 흉내 낸다.

    python -m voice.run --clips voice/clips
    > {"cmd": "approach", "target": "user"}     ← 워치에서 명령이 온 것처럼 (JSON 그대로)
    > cmd:grab                                   ← 짧게 쓰기
    > state:grabbing                             ← 로봇카 상태가 바뀐 것처럼

#42에서 통신이 정해지면, 명령을 받는 곳에서 Announcer.on_command()를,
상태가 바뀌는 곳에서 Announcer.on_state()를 부르면 된다.
"""

from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path

from voice.announcer import Announcer

STATE_PREFIX = "state:"
CMD_PREFIX = "cmd:"

Event = tuple[str, object]  # ("state", "moving") 또는 ("command", {"cmd": ...})


def parse_line(line: str) -> Event | None:
    """입력 한 줄 → 사건. 알 수 없는 줄은 None."""
    line = line.strip()
    if line.startswith(STATE_PREFIX):
        return ("state", line[len(STATE_PREFIX) :].strip())
    if line.startswith(CMD_PREFIX):
        return ("command", {"cmd": line[len(CMD_PREFIX) :].strip()})
    if line.startswith("{"):
        try:
            message = json.loads(line)
        except json.JSONDecodeError:
            return None
        if isinstance(message, dict) and "cmd" in message:
            return ("command", message)
    return None


def parse_device(value: str | None) -> str | int | None:
    """장치 번호("2")는 숫자로, 이름("MAX98357A")은 글자 그대로."""
    if value is None:
        return None
    return int(value) if value.isdigit() else value


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description="로봇카 음성 안내 시험 (#68)")
    p.add_argument("--clips", type=Path, default=Path("voice/clips"), help="안내 음성 파일 폴더")
    p.add_argument("--volume", type=float, default=0.6, help="소리 크기 0~0.8 (스피커 보호)")
    p.add_argument("--speaker-device", help="스피커 장치 번호나 이름 (python -m sounddevice)")
    args = p.parse_args(argv)

    from voice.audio import ClipSpeaker

    speaker = ClipSpeaker(args.clips, args.volume, parse_device(args.speaker_device))
    announcer = Announcer(speaker, time.monotonic)

    print("명령(JSON 또는 cmd:이름)이나 state:상태 를 입력하세요. 빈 줄 + Enter 로 끝.")
    for line in sys.stdin:
        if not line.strip():
            break
        event = parse_line(line)
        if event is None:
            print("  (알 수 없는 입력)")
        elif event[0] == "state":
            announcer.on_state(str(event[1]))
        else:
            announcer.on_command(event[1])  # type: ignore[arg-type]
        while speaker.busy:  # 안내가 끝날 때까지 기다린다
            time.sleep(0.05)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
