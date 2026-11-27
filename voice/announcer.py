"""명령·로봇카 상태 → 스피커로 말할 문장.

두 가지를 안내한다.
1. 명령을 받았을 때의 짧은 대답. 예) {"cmd": "approach"} → "갈게요"
   워치 제스처로 온 명령이든 워치 음성으로 온 명령이든 똑같이 대답한다.
2. 로봇카 상태가 바뀌었을 때. 예) "grabbing" → "물건 집는 중입니다"
   같은 상태가 계속 들어와도 한 번만 말하고, 너무 자주 말하지 않게 간격을 둔다.

멈춤·응급처럼 중요한 안내(urgent)는 간격과 상관없이 바로 말한다.
문장마다 짧은 id가 있다. 스피커는 id로 미리 만든 음성 파일(<id>.wav)을 찾아 튼다.
"""

from __future__ import annotations

from collections.abc import Callable, Mapping
from dataclasses import dataclass
from typing import Protocol


@dataclass(frozen=True)
class Phrase:
    id: str  # 음성 파일 이름 (<id>.wav)
    text: str  # 파일이 없을 때 읽어 줄 글자
    urgent: bool = False  # True면 간격·중복 검사 없이 바로 말한다


# 로봇카 상태 → 안내 문장. 상태 이름은 #34 상태 메시지와 맞춘다.
STATE_PHRASES: dict[str, Phrase] = {
    "moving": Phrase("state_moving", "움직이는 중입니다"),
    "approaching": Phrase("state_approaching", "가까이 가는 중입니다"),
    "searching": Phrase("state_searching", "사람을 찾는 중입니다"),
    "following": Phrase("state_following", "따라가는 중입니다"),
    "grabbing": Phrase("state_grabbing", "물건 집는 중입니다"),
    "delivering": Phrase("state_delivering", "물건을 가져가는 중입니다"),
    "arrived": Phrase("state_arrived", "도착했습니다"),
    "not_found": Phrase("state_not_found", "물건을 찾지 못했습니다"),
    "stopped": Phrase("state_stopped", "멈췄습니다", urgent=True),
    "checking": Phrase("state_checking", "괜찮으세요? 상태를 확인하고 있어요", urgent=True),
    # 119는 실제로 연락하지 않는다. 안내도 가족 알림만 말한다.
    "alert_sent": Phrase("state_alert_sent", "가족에게 알렸습니다", urgent=True),
}

# 명령 이름(cmd) → 받았다는 대답. 명령 형식은 #34 기준.
COMMAND_PHRASES: dict[str, Phrase] = {
    "stop": Phrase("cmd_stop", "멈출게요", urgent=True),
    "approach": Phrase("cmd_approach", "갈게요"),
    "follow": Phrase("cmd_follow", "따라갈게요"),
    "turn": Phrase("cmd_turn", "돌게요"),
    "move": Phrase("cmd_move", "움직일게요"),
    "grab": Phrase("cmd_grab", "가져올게요"),
}


def all_phrases() -> list[Phrase]:
    return [*STATE_PHRASES.values(), *COMMAND_PHRASES.values()]


class Speaker(Protocol):
    """스피커. 실제 기기는 audio.ClipSpeaker, 테스트는 가짜를 쓴다."""

    def say(self, phrase: Phrase) -> None: ...


class Announcer:
    """명령을 받거나 상태가 바뀌면 안내 문장을 말한다."""

    def __init__(
        self,
        speaker: Speaker,
        clock: Callable[[], float],
        min_interval_s: float = 3.0,
    ) -> None:
        self._speaker = speaker
        self._clock = clock
        self._min_interval_s = min_interval_s
        self._last_state: str | None = None
        self._last_time: float | None = None

    def on_command(self, command: Mapping[str, object]) -> Phrase | None:
        """명령을 받았다는 대답. 사람이 방금 시킨 일이라 간격 검사 없이 바로 말한다.

        대답은 상태 안내의 간격 계산에 넣지 않는다. "갈게요" 바로 뒤에 로봇카가
        "가까이 가는 중입니다"를 알려도 빠뜨리지 않게 하기 위해서다.
        """
        phrase = COMMAND_PHRASES.get(str(command.get("cmd")))
        if phrase is None:
            return None  # 모르는 명령은 대답하지 않는다
        self._speaker.say(phrase)
        return phrase

    def on_state(self, state: str) -> Phrase | None:
        """로봇카 상태를 받는다. 말한 문장을, 말하지 않았으면 None을 돌려준다."""
        phrase = STATE_PHRASES.get(state)
        if phrase is None:
            return None  # 안내할 필요 없는 상태 (예: idle)
        now = self._clock()
        if not phrase.urgent:
            if state == self._last_state:
                return None  # 같은 상태를 또 말하지 않는다
            if self._last_time is not None and now - self._last_time < self._min_interval_s:
                return None  # 너무 자주 말하지 않는다
        self._last_state = state
        self._last_time = now
        self._speaker.say(phrase)
        return phrase
