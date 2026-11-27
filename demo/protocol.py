"""워치 ↔ 로봇카 메시지 규약 (데모용 제안).

워치 쪽 android/gesture/.../CarProtocol.kt 와 같은 내용이다. 한쪽을 바꾸면 다른 쪽도 바꾼다.

- watchout/cars/{id}/cmd (워치 → 차)
    {"cmd": "approach", "target": "user", "source": "gesture", "gesture": "부르기", "ts": ...}
    앞부분(cmd와 값)은 워치 음성 명령(#71 RobotCommand)·CLAUDE.md 예시와 같은 형식이다.
    {"cmd": "stop"}  {"cmd": "grab", "target": "phone"}  {"cmd": "turn", "angle_deg": 90}
    {"cmd": "move", "dist_cm": 30}  {"cmd": "follow", "target": "user"}
- watchout/cars/{id}/status (차 → 워치)
    {"state": "moving", "msg": "사용자에게 가는 중"}
- watchout/cars/{id}/fall (워치 → 차)
    {"event": "fall_suspected", "ts": ..., "impact_g": 3.1, "source": "sensor"}
- watchout/cars/{id}/fall/result (차 → 워치)
    {"confirmed": true, "msg": "보호자에게 알렸습니다"}

로봇카 찾기: 차가 UDP 방송(포트 47474)으로 1초마다 자기를 알린다.
"""

from __future__ import annotations

import json
from dataclasses import dataclass, field

DISCOVERY_PORT = 47474
DISCOVERY_TYPE = "watchout_car"

# 물건 이름(target) → 한국어 (워치 #71 GrabTarget과 같은 id)
GRAB_TARGETS: dict[str, str] = {
    "phone": "휴대폰",
    "medicine": "약",
    "remote": "리모컨",
    "glasses": "안경",
    "cup": "컵",
}


def _object_particle(word: str) -> str:
    """받침이 있으면 '을', 없으면 '를' (휴대폰을, 리모컨을, 컵을 / 약을 / 안경을)"""
    last = word[-1:] if word else ""
    if "가" <= last <= "힣":
        return "을" if (ord(last) - ord("가")) % 28 else "를"
    return "을(를)"


def describe(cmd: str, args: dict) -> str:
    """명령 → 데모 로봇카가 화면에 보여줄 설명 (실제 차에서는 HW팀이 주행 코드로 바꾼다)"""
    if cmd == "stop":
        return "멈춥니다"
    if cmd == "approach":
        return "사용자 쪽으로 다가갑니다"
    if cmd == "follow":
        return "사용자를 따라갑니다"
    if cmd == "go_home":
        return "제자리로 돌아갑니다"
    if cmd == "grab":
        target = args.get("target")
        name = GRAB_TARGETS.get(str(target), str(target))
        return f"로봇팔로 {name}{_object_particle(name)} 집어 옵니다"
    if cmd == "turn" and isinstance(args.get("angle_deg"), int | float):
        a = args["angle_deg"]
        return f"{'왼쪽' if a > 0 else '오른쪽'}으로 {abs(a):g}° 돕니다"
    if cmd == "move" and isinstance(args.get("dist_cm"), int | float):
        d = args["dist_cm"]
        return f"{'앞으로' if d > 0 else '뒤로'} {abs(d):g}cm 갑니다"
    return f"모르는 명령({cmd})"


def cmd_topic(car_id: str) -> str:
    return f"watchout/cars/{car_id}/cmd"


def status_topic(car_id: str) -> str:
    return f"watchout/cars/{car_id}/status"


def fall_topic(car_id: str) -> str:
    return f"watchout/cars/{car_id}/fall"


def fall_result_topic(car_id: str) -> str:
    return f"watchout/cars/{car_id}/fall/result"


# 명령의 값이 아닌 꼬리표 필드 (어디서 왔는지, 언제)
_META_FIELDS = {"cmd", "source", "gesture", "ts"}


@dataclass(frozen=True)
class Command:
    cmd: str
    source: str
    gesture: str | None
    ts: int
    args: dict = field(default_factory=dict)  # target, angle_deg, dist_cm 등

    @property
    def description(self) -> str:
        return describe(self.cmd, self.args)


@dataclass(frozen=True)
class FallEvent:
    ts: int
    impact_g: float
    source: str


def parse_command(payload: bytes | str) -> Command | None:
    """명령 메시지를 읽는다. 형식이 틀리면 None (차는 모르는 메시지를 무시한다)"""
    data = _load(payload)
    if not data or not isinstance(data.get("cmd"), str):
        return None
    return Command(
        cmd=data["cmd"],
        source=str(data.get("source", "")),
        gesture=data.get("gesture"),
        ts=int(data.get("ts", 0)),
        args={k: v for k, v in data.items() if k not in _META_FIELDS},
    )


def parse_fall(payload: bytes | str) -> FallEvent | None:
    data = _load(payload)
    if not data or data.get("event") != "fall_suspected":
        return None
    return FallEvent(
        ts=int(data.get("ts", 0)),
        impact_g=float(data.get("impact_g", 0.0)),
        source=str(data.get("source", "")),
    )


def status(state: str, msg: str) -> str:
    return json.dumps({"state": state, "msg": msg}, ensure_ascii=False)


def fall_result(confirmed: bool, msg: str) -> str:
    return json.dumps({"confirmed": confirmed, "msg": msg}, ensure_ascii=False)


def discovery(car_id: str, name: str, host: str, port: int) -> str:
    return json.dumps(
        {"type": DISCOVERY_TYPE, "id": car_id, "name": name, "host": host, "port": port},
        ensure_ascii=False,
    )


def _load(payload: bytes | str) -> dict | None:
    try:
        text = payload.decode("utf-8") if isinstance(payload, bytes) else payload
        data = json.loads(text)
    except (UnicodeDecodeError, json.JSONDecodeError):
        return None
    return data if isinstance(data, dict) else None
