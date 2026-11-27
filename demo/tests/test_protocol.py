"""demo.protocol 테스트: 워치(CarProtocol.kt)가 보내는 형식을 그대로 읽는지 본다."""

import json

from demo import protocol


def test_topics_match_watch_side():
    assert protocol.cmd_topic("demo-car") == "watchout/cars/demo-car/cmd"
    assert protocol.status_topic("demo-car") == "watchout/cars/demo-car/status"
    assert protocol.fall_topic("demo-car") == "watchout/cars/demo-car/fall"
    assert protocol.fall_result_topic("demo-car") == "watchout/cars/demo-car/fall/result"


def test_parse_command_sent_by_watch():
    # CarProtocolTest.kt 의 기대값과 같은 문자열
    payload = (
        '{"cmd":"approach","target":"user","source":"gesture","gesture":"부르기","ts":1795000000000}'
    ).encode()

    cmd = protocol.parse_command(payload)

    assert cmd == protocol.Command(
        "approach", "gesture", "부르기", 1_795_000_000_000, {"target": "user"}
    )
    assert cmd.description == "사용자 쪽으로 다가갑니다"


def test_voice_command_format_from_issue_71():
    # 워치 음성 명령(#71 RobotCommand.toJson)은 source·ts 없이 온다
    cases = {
        '{"cmd":"stop"}': "멈춥니다",
        '{"cmd":"grab","target":"phone"}': "로봇팔로 휴대폰을 집어 옵니다",
        '{"cmd":"turn","angle_deg":90}': "왼쪽으로 90° 돕니다",
        '{"cmd":"turn","angle_deg":-90}': "오른쪽으로 90° 돕니다",
        '{"cmd":"move","dist_cm":-30}': "뒤로 30cm 갑니다",
        '{"cmd":"follow","target":"user"}': "사용자를 따라갑니다",
    }
    for payload, text in cases.items():
        cmd = protocol.parse_command(payload)
        assert cmd.source == ""
        assert cmd.description == text, payload


def test_object_particle():
    assert protocol.describe("grab", {"target": "cup"}) == "로봇팔로 컵을 집어 옵니다"
    assert protocol.describe("grab", {"target": "box"}) == "로봇팔로 box을(를) 집어 옵니다"
    assert protocol.describe("grab", {"target": "x리모"}) == "로봇팔로 x리모를 집어 옵니다"


def test_parse_command_from_button_and_unknown_command():
    cmd = protocol.parse_command('{"cmd":"fly","source":"button","gesture":null,"ts":1}')

    assert cmd.gesture is None
    assert cmd.description == "모르는 명령(fly)"


def test_bad_messages_are_ignored():
    assert protocol.parse_command(b"\xff\xfe") is None
    assert protocol.parse_command("not json") is None
    assert protocol.parse_command("[1,2]") is None
    assert protocol.parse_command('{"source":"button"}') is None
    assert protocol.parse_fall('{"event":"other"}') is None


def test_parse_fall():
    event = protocol.parse_fall(
        '{"event":"fall_suspected","ts":5,"impact_g":3.25,"source":"test_button"}'
    )

    assert event == protocol.FallEvent(5, 3.25, "test_button")


def test_messages_to_watch_keep_korean():
    assert json.loads(protocol.status("moving", "가는 중")) == {"state": "moving", "msg": "가는 중"}
    assert '"msg": "알림 보냄"' in protocol.fall_result(True, "알림 보냄")
    assert json.loads(protocol.fall_result(False, "아님"))["confirmed"] is False


def test_discovery_packet_matches_watch_parser():
    data = json.loads(protocol.discovery("demo-car", "데모", "192.168.0.34", 1883))

    assert data == {
        "type": "watchout_car",
        "id": "demo-car",
        "name": "데모",
        "host": "192.168.0.34",
        "port": 1883,
    }
