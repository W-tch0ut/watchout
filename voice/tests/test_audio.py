"""audio 테스트: 기기 없이 확인할 수 있는 부분(소리 크기 줄이기, wav 읽기)만.

실제 녹음 파일 대신 코드 안에서 만든 짧은 숫자 데이터를 쓴다.
"""

import array
import io
import wave

import pytest

from voice.audio import MAX_VOLUME, clamp_volume, read_wav, scale_pcm16


def pcm(*samples):
    return array.array("h", samples).tobytes()


def samples(data):
    a = array.array("h")
    a.frombytes(data)
    return list(a)


def test_scale_halves_samples():
    assert samples(scale_pcm16(pcm(1000, -1000, 0), 0.5)) == [500, -500, 0]


def test_volume_is_capped_to_protect_speaker():
    assert clamp_volume(1.0) == MAX_VOLUME
    assert clamp_volume(-1) == 0.0
    out = samples(scale_pcm16(pcm(32767, -32768), 5.0))
    assert out == [int(32767 * MAX_VOLUME), int(-32768 * MAX_VOLUME)]


def make_wav(sampwidth=2, rate=16000, channels=1, frames=b"\x00\x00" * 4):
    buf = io.BytesIO()
    with wave.open(buf, "wb") as w:
        w.setsampwidth(sampwidth)
        w.setframerate(rate)
        w.setnchannels(channels)
        w.writeframes(frames)
    return buf.getvalue()


def test_read_wav():
    data, rate, channels = read_wav(make_wav(rate=22050))
    assert (len(data), rate, channels) == (8, 22050, 1)


def test_read_wav_rejects_8bit():
    with pytest.raises(ValueError):
        read_wav(make_wav(sampwidth=1, frames=b"\x80" * 4))
