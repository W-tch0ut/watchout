"""demo.alert / demo.sms 테스트: 문자 내용, 119 차단, 시뮬레이션 저장, 문자 API 서명."""

import hashlib
import hmac
from datetime import datetime

from demo.alert import (
    MAX_TEXT_CHARS,
    Profile,
    Recipient,
    compose_message,
    is_blocked,
    plan_deliveries,
)
from demo.sms import save_to_outbox, solapi_auth_header

PROFILE = Profile(name="홍길동", address="서울시 어딘가", note="고혈압")


def test_message_contains_who_when_where_and_situation():
    text = compose_message(PROFILE, datetime(2026, 11, 27, 13, 5, 9), 3.04, "카메라 확인", False)

    assert text.startswith("[WatchOut] 낙상사고가 일어났습니다.")
    assert "- 대상: 홍길동" in text
    assert "- 시각: 2026-11-27 13:05:09" in text
    assert "- 장소: 서울시 어딘가" in text
    assert "워치 충격 3.0g" in text and "카메라 확인" in text
    assert "- 움직임: 없음" in text
    assert "- 참고: 고혈압" in text


def test_message_is_cut_to_sms_limit():
    long_note = Profile(name="a", address="b", note="가" * 2000)

    assert len(compose_message(long_note, datetime(2026, 1, 1), 1.0, "x", None)) == MAX_TEXT_CHARS


def test_emergency_numbers_are_blocked_in_any_format():
    for phone in ["119", " 1 1 9 ", "1-1-9", "112", "911"]:
        assert is_blocked(phone), phone
    assert not is_blocked("010-1234-5678")


def test_119_is_always_simulated_even_when_sending_real_sms():
    guardian = Recipient("보호자", "010-1234-5678")
    sneaky = Recipient("잘못 넣은 번호", "119")

    plan = plan_deliveries([guardian, sneaky], Recipient("119", "119"), send_real=True)

    assert [(d.recipient.label, d.simulate) for d in plan] == [
        ("보호자", False),
        ("잘못 넣은 번호", True),
        ("119", True),
    ]


def test_everything_is_simulated_without_send_flag():
    plan = plan_deliveries([Recipient("보호자", "010-1234-5678")], None, send_real=False)

    assert [d.simulate for d in plan] == [True]


def test_outbox_saves_text_and_copies_photo(tmp_path):
    photo = tmp_path / "p.jpg"
    photo.write_bytes(b"jpg")

    path = save_to_outbox(tmp_path / "outbox", "119", "119", "본문", photo)

    content = path.read_text(encoding="utf-8")
    assert "받는 사람: 119 (119)" in content and content.rstrip().endswith("본문")
    assert (path.parent / f"{path.stem}.jpg").read_bytes() == b"jpg"


def test_solapi_auth_header_signs_date_and_salt():
    header = solapi_auth_header("KEY", "SECRET", "2026-11-27T04:00:00Z", "abc")
    expected = hmac.new(b"SECRET", b"2026-11-27T04:00:00Zabc", hashlib.sha256).hexdigest()

    assert header == (
        f"HMAC-SHA256 apiKey=KEY, date=2026-11-27T04:00:00Z, salt=abc, signature={expected}"
    )
