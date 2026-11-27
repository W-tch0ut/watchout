"""announcer 테스트: 명령·상태 → 안내 문장, 반복·간격 규칙."""

from voice.announcer import COMMAND_PHRASES, Announcer, all_phrases


class FakeSpeaker:
    def __init__(self):
        self.said = []

    def say(self, phrase):
        self.said.append(phrase.text)


class FakeClock:
    def __init__(self):
        self.now = 0.0

    def __call__(self):
        return self.now


def make():
    speaker, clock = FakeSpeaker(), FakeClock()
    return Announcer(speaker, clock, min_interval_s=3.0), speaker, clock


def test_state_phrases():
    announcer, speaker, _ = make()
    announcer.on_state("moving")
    assert speaker.said == ["움직이는 중입니다"]


def test_grabbing_phrase():
    announcer, speaker, _ = make()
    announcer.on_state("grabbing")
    assert speaker.said == ["물건 집는 중입니다"]


def test_same_state_is_said_once():
    announcer, speaker, clock = make()
    announcer.on_state("moving")
    clock.now = 10
    announcer.on_state("moving")
    assert speaker.said == ["움직이는 중입니다"]


def test_too_soon_is_skipped():
    announcer, speaker, clock = make()
    announcer.on_state("moving")
    clock.now = 1
    assert announcer.on_state("searching") is None
    clock.now = 4
    announcer.on_state("searching")
    assert speaker.said == ["움직이는 중입니다", "사람을 찾는 중입니다"]


def test_urgent_state_is_always_said():
    announcer, speaker, clock = make()
    announcer.on_state("moving")
    clock.now = 0.5
    announcer.on_state("stopped")
    announcer.on_state("stopped")
    assert speaker.said == ["움직이는 중입니다", "멈췄습니다", "멈췄습니다"]


def test_command_reply():
    announcer, speaker, _ = make()
    announcer.on_command({"cmd": "grab", "target": "phone"})
    assert speaker.said == ["가져올게요"]


def test_stop_reply_is_urgent():
    assert COMMAND_PHRASES["stop"].urgent


def test_unknown_command_is_silent():
    announcer, speaker, _ = make()
    assert announcer.on_command({"cmd": "dance"}) is None
    assert announcer.on_command({}) is None
    assert speaker.said == []


def test_state_right_after_command_reply_is_said():
    announcer, speaker, _ = make()
    announcer.on_command({"cmd": "approach", "target": "user"})
    announcer.on_state("approaching")
    assert speaker.said == ["갈게요", "가까이 가는 중입니다"]


def test_unknown_state_is_silent():
    announcer, speaker, _ = make()
    assert announcer.on_state("idle") is None
    assert speaker.said == []


def test_phrase_ids_are_unique():
    ids = [p.id for p in all_phrases()]
    assert len(ids) == len(set(ids))


def test_no_real_119_announcement():
    # 119는 실제로 연락하지 않는다
    assert all("119" not in p.text for p in all_phrases())
