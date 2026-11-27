"""run 테스트: 키보드 입력 한 줄 → 명령/상태 해석."""

import pytest

from voice.run import parse_device, parse_line


def test_state_line():
    assert parse_line("state:moving") == ("state", "moving")


def test_short_command_line():
    assert parse_line(" cmd:stop ") == ("command", {"cmd": "stop"})


def test_json_command_line():
    assert parse_line('{"cmd": "grab", "target": "phone"}') == (
        "command",
        {"cmd": "grab", "target": "phone"},
    )


@pytest.mark.parametrize("line", ["hello", "{broken", '{"target": "phone"}', "[1, 2]"])
def test_unknown_lines(line):
    assert parse_line(line) is None


def test_parse_device():
    assert parse_device("2") == 2
    assert parse_device("MAX98357A") == "MAX98357A"
    assert parse_device(None) is None
