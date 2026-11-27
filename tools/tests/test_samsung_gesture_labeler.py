"""samsung_gesture_labeler 테스트.

실제 로그·센서 파일 대신 코드 안에서 만든 작은 가짜 데이터를 쓴다.
"""

import struct

import pytest

from tools.samsung_gesture_labeler import (
    PPG_EMPTY,
    SamsungEvent,
    _ppg_range,
    count_unmapped_actions,
    cut_window,
    decode_ppg,
    main,
    parse_logcat,
    parse_session,
    summarize,
    window_stats,
    write_window,
)

LOG = [
    "--------- beginning of main",
    "  1000.000 17425 17677 I WUG::ActivationControllerImpl: activation onGestureDetected "
    "gestureForActivation: OCO2 smartGestureType: OCO2",
    "  1002.500 17425 17676 I WUG::ActionManagerImpl: action performed : NEXTITEM",
    "  1004.250 17425 17521 I WUG::ActionManagerImpl: action performed : TAP",
    "  1005.000 17425 17505 I WUG::ActionManagerImpl: action performed : BACK",
    "  1005.010 17425 17425 I WUG::ActionManagerImpl: action performed : INJECT_KEY_BACK",
    "  1005.500 17425 17505 I WUG::ActionManagerImpl: action performed : SCROLL_UP",
    "  1006.000   544   949 I input_interaction: Interaction with: something",
]


def make_session(
    start_ms: int, end_ms: int, spike_at: int | None = None, ppg: bool = True
) -> list[str]:
    """200Hz 가속도·자이로 + 25Hz 심박. spike_at 주변 0.2초 동안 자이로 X가 3 rad/s로 튄다."""
    lines = ["# watchout session v1,hand=LEFT", "epoch_ms,sensor,values..."]
    for t in range(start_ms, end_ms + 1, 5):
        spike = spike_at is not None and abs(t - spike_at) <= 100
        lines.append(f"{t},acc,0.0,0.0,{9.81 + (2.0 if spike else 0.0)}")
        lines.append(f"{t},gyr,{3.0 if spike else 0.0},0.0,0.0")
        if ppg and t % 40 == 0:
            # 실제 워치처럼 정수 광센서 값을 float 비트에 담는다. 채널 0은 버리는 값, 채널 1이 신호
            value = 3_000_000 + (50 if spike else 0)
            lines.append(f"{t},ppg,{int_bits(PPG_EMPTY)!r},{int_bits(value)!r}")
    return lines


def int_bits(i: int) -> float:
    """int32 비트를 float32로 읽은 값 (삼성 HR Raw Sensor 형식)"""
    return struct.unpack("<f", struct.pack("<i", i))[0]


def test_parse_logcat_maps_actions_and_activation():
    events = parse_logcat(LOG)

    assert events == [
        SamsungEvent(1_000_000, "OCO2", "activation_oco2"),
        SamsungEvent(1_002_500, "NEXTITEM", "pinch"),
        SamsungEvent(1_004_250, "TAP", "fist"),
        SamsungEvent(1_005_000, "BACK", "arm_back_forth"),
    ]


def test_parse_logcat_custom_map_adds_back():
    events = parse_logcat(LOG, {"BACK": "double_fist"})

    assert [(e.action, e.gesture) for e in events] == [
        ("OCO2", "activation_oco2"),
        ("BACK", "double_fist"),
    ]


def test_parse_logcat_drops_duplicates_from_reconnect():
    # 워치 연결이 끊겼다 다시 붙으면 남아 있던 로그가 한 번 더 기록된다.
    events = parse_logcat(LOG + LOG)

    assert [e.gesture for e in events] == ["activation_oco2", "pinch", "fist", "arm_back_forth"]
    assert count_unmapped_actions(LOG + LOG) == {"SCROLL_UP": 1}


def test_count_unmapped_actions_skips_inject_key_back():
    assert count_unmapped_actions(LOG) == {"SCROLL_UP": 1}
    assert count_unmapped_actions(LOG, {"BACK": "double_fist"}) == {
        "NEXTITEM": 1,
        "TAP": 1,
        "SCROLL_UP": 1,
    }


def test_main_tells_which_actions_were_unmapped(tmp_path, capsys):
    log = tmp_path / "log.txt"
    log.write_text("\n".join(LOG), encoding="utf-8")
    session = tmp_path / "session.csv"
    session.write_text("\n".join(make_session(998_000, 1_007_000)), encoding="utf-8")

    main(["--session", str(session), "--log", str(log)])

    assert "어떤 제스처인지 몰라서 뺀 동작: SCROLL_UP 1개" in capsys.readouterr().out


def test_parse_session_groups_by_sensor_in_time_order():
    session = parse_session(
        ["# info", "epoch_ms,sensor,values...", "20,gyr,1,2,3", "10,acc,4,5,6", "5,acc,7,8,9"]
    )

    assert session["acc"] == [(5, (7.0, 8.0, 9.0)), (10, (4.0, 5.0, 6.0))]
    assert session["gyr"] == [(20, (1.0, 2.0, 3.0))]


def test_cut_window_rejects_when_recording_does_not_cover_window():
    samples = [(t, (0.0,)) for t in range(1000, 3001, 10)]

    assert cut_window(samples, 2000, 500, 200)[0][0] == 1500
    assert cut_window(samples, 1100, 1500, 300) is None  # 앞쪽이 기록 시작 전
    assert cut_window(samples, 2900, 1500, 300) is None  # 뒤쪽이 기록 끝 후
    assert cut_window([], 2000, 500, 200) is None


def test_window_stats_measures_spike_before_recognition():
    session = parse_session(make_session(0, 5000, spike_at=2300))
    event = SamsungEvent(2500, "NEXTITEM", "pinch")

    s = window_stats(event, session)

    assert s is not None
    assert s.imu_rate_hz == pytest.approx(200, rel=0.01)
    assert s.peak_gyro == pytest.approx(3.0)
    assert s.gyro_axis_peak == (3.0, 0.0, 0.0)
    assert s.peak_acc_dev == pytest.approx(2.0, abs=1e-6)
    assert s.ppg_range == pytest.approx(50.0)


def test_decode_ppg_recovers_int_from_float_bits_written_by_watch():
    # 워치가 Float.toString으로 쓴 값 (팀 워치 실제 데이터에서 가져온 문자열)
    assert decode_ppg(float("2.938736E-39")) == PPG_EMPTY
    assert decode_ppg(int_bits(3_086_916)) == 3_086_916
    assert decode_ppg(int_bits(83)) == 83


def test_ppg_range_ignores_empty_marker():
    session = parse_session(
        [
            f"0,ppg,0.0,{int_bits(PPG_EMPTY)!r}",
            f"10,ppg,0.0,{int_bits(3_000_100)!r}",
            f"20,ppg,0.0,{int_bits(3_000_000)!r}",
        ]
    )

    assert _ppg_range(session["ppg"]) == 100.0
    assert _ppg_range([]) is None


def test_window_stats_without_ppg_and_outside_recording():
    session = parse_session(make_session(0, 5000, spike_at=2300, ppg=False))

    assert window_stats(SamsungEvent(2500, "TAP", "fist"), session).ppg_range is None
    assert window_stats(SamsungEvent(9000, "TAP", "fist"), session) is None


def test_summarize_shows_one_row_per_gesture():
    session = parse_session(make_session(0, 8000, spike_at=None))
    stats = [window_stats(SamsungEvent(t, "TAP", "fist"), session) for t in (2000, 4000)]
    stats.append(window_stats(SamsungEvent(6000, "NEXTITEM", "pinch"), session))

    table = summarize(stats).splitlines()

    assert len(table) == 3  # 머리줄 + fist + pinch
    assert table[1].startswith("fist") and " 2 " in table[1]
    assert table[2].startswith("pinch")


def test_write_window_uses_recognition_time_as_zero(tmp_path):
    session = parse_session(make_session(0, 5000, spike_at=2300))
    event = SamsungEvent(2500, "NEXTITEM", "pinch")

    path = write_window(tmp_path, event, session, before_ms=100, after_ms=50)

    lines = path.read_text(encoding="utf-8").splitlines()
    assert path.name == "samsung_pinch__2500.csv"
    assert lines[0].startswith("# label=samsung_pinch,action=NEXTITEM,epoch_ms=2500")
    assert lines[1] == "t_ms,sensor,values..."
    times = [int(line.split(",")[0]) for line in lines[2:]]
    assert min(times) == -100 and max(times) == 50


def test_main_end_to_end(tmp_path, capsys):
    log = tmp_path / "log.txt"
    log.write_text("\n".join(LOG), encoding="utf-8")
    session = tmp_path / "session.csv"
    session.write_text(
        "\n".join(make_session(998_000, 1_007_000, spike_at=1_002_300)), encoding="utf-8"
    )

    code = main(["--session", str(session), "--log", str(log), "--out", str(tmp_path / "out")])

    out = capsys.readouterr().out
    assert code == 0
    assert "삼성 인식 4개" in out
    assert "pinch" in out and "fist" in out and "arm_back_forth" in out
    assert len(list((tmp_path / "out").glob("samsung_*.csv"))) == 4


def test_main_reads_utf16_log_from_powershell(tmp_path, capsys):
    log = tmp_path / "log.txt"
    log.write_bytes("\n".join(LOG).encode("utf-16"))
    session = tmp_path / "session.csv"
    session.write_text("\n".join(make_session(998_000, 1_007_000)), encoding="utf-8")

    assert main(["--session", str(session), "--log", str(log)]) == 0
    assert "삼성 인식 4개" in capsys.readouterr().out


def test_main_reports_when_nothing_overlaps(tmp_path, capsys):
    log = tmp_path / "log.txt"
    log.write_text("\n".join(LOG), encoding="utf-8")
    session = tmp_path / "session.csv"
    session.write_text("\n".join(make_session(0, 3000)), encoding="utf-8")

    assert main(["--session", str(session), "--log", str(log)]) == 1
    assert "겹치는 구간이 없습니다" in capsys.readouterr().out
