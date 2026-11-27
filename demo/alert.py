"""낙상이 확인되면 보낼 문자를 만들고, 받을 사람을 정한다.

- 보호자에게는 실제 문자를 보낼 수 있다 (--send-sms, 문자 API 키가 있을 때).
- 119·112 같은 긴급 번호에는 **절대 실제로 보내지 않는다** (CLAUDE.md 규칙). 항상 시뮬레이션이다.
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from datetime import datetime

# 실제로 연락하면 안 되는 긴급 번호 (국내·국외)
BLOCKED_NUMBERS = {"119", "112", "110", "111", "113", "117", "118", "122", "125", "911", "999"}

MAX_TEXT_CHARS = 1000  # 문자 API 본문 제한 (한글 1,000자)


@dataclass(frozen=True)
class Profile:
    """보호 대상자 정보 (demo/config.json, Git에 올리지 않는다)"""

    name: str
    address: str
    note: str = ""


@dataclass(frozen=True)
class Recipient:
    label: str
    phone: str


@dataclass(frozen=True)
class Delivery:
    recipient: Recipient
    simulate: bool  # True면 실제로 보내지 않고 outbox에만 남긴다
    reason: str


def normalize_phone(phone: str) -> str:
    return re.sub(r"\D", "", phone)


def is_blocked(phone: str) -> bool:
    """긴급 번호인지. 앞뒤 공백·하이픈을 빼고 비교한다."""
    return normalize_phone(phone) in BLOCKED_NUMBERS


def plan_deliveries(
    guardians: list[Recipient], emergency: Recipient | None, send_real: bool
) -> list[Delivery]:
    """누구에게 실제로 보내고 누구에게 시뮬레이션할지 정한다."""
    out = []
    for g in guardians:
        if is_blocked(g.phone):
            out.append(Delivery(g, True, "긴급 번호는 실제로 보내지 않음"))
        elif send_real:
            out.append(Delivery(g, False, "보호자에게 실제 문자"))
        else:
            out.append(Delivery(g, True, "시뮬레이션 (--send-sms 없음)"))
    if emergency is not None:
        out.append(Delivery(emergency, True, "119는 항상 시뮬레이션"))
    return out


def compose_message(
    profile: Profile,
    when: datetime,
    impact_g: float,
    confirmed_by: str,
    moving: bool | None,
) -> str:
    """보호자·119 시뮬레이션에 보낼 문자 본문"""
    lines = [
        "[WatchOut] 낙상사고가 일어났습니다.",
        f"- 대상: {profile.name}",
        f"- 시각: {when:%Y-%m-%d %H:%M:%S}",
        f"- 장소: {profile.address}",
        f"- 상황: 워치 충격 {impact_g:.1f}g, 10초간 응답 없음, {confirmed_by}",
    ]
    if moving is not None:
        lines.append(f"- 움직임: {'있음' if moving else '없음'}")
    if profile.note:
        lines.append(f"- 참고: {profile.note}")
    lines.append("현장 사진을 함께 보냅니다.")
    text = "\n".join(lines)
    return text[:MAX_TEXT_CHARS]
