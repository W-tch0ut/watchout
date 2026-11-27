"""스피커를 직접 다루는 부분 (기기가 있어야 소리가 난다).

ClipSpeaker: 미리 만든 음성 파일(<id>.wav)을 튼다. 파일이 없으면 espeak-ng로 읽고,
espeak-ng도 없으면 글자로만 보여 준다.

sounddevice는 소리를 낼 때만 필요하므로 함수 안에서 import 한다. 그래서 스피커가 없어도
소리 크기 계산 같은 로직 테스트는 돌아간다.
"""

from __future__ import annotations

import array
import io
import queue
import shutil
import subprocess
import sys
import threading
import wave
from collections.abc import Callable
from pathlib import Path

from voice.announcer import Phrase

# 스피커 보호: 0.5W 스피커에 MAX98357A(8Ω, GAIN→Vin 6dB 연결 시 최대 약 0.7W)를 쓴다.
# 소리 크기를 0.8배로 묶으면 출력은 0.8² ≈ 0.64배 → 약 0.45W로 스피커 정격 안에 들어온다.
MAX_VOLUME = 0.8
_INT16_MIN, _INT16_MAX = -32768, 32767


def clamp_volume(volume: float) -> float:
    return max(0.0, min(volume, MAX_VOLUME))


def scale_pcm16(data: bytes, volume: float) -> bytes:
    """16비트 소리 데이터의 크기를 volume배로 줄인다 (MAX_VOLUME을 넘지 않음)."""
    v = clamp_volume(volume)
    samples = array.array("h")
    samples.frombytes(data)
    if sys.byteorder == "big":  # wav는 little-endian
        samples.byteswap()
    for i, s in enumerate(samples):
        samples[i] = max(_INT16_MIN, min(_INT16_MAX, int(s * v)))
    if sys.byteorder == "big":
        samples.byteswap()
    return samples.tobytes()


def read_wav(data: bytes) -> tuple[bytes, int, int]:
    """wav 파일 내용 → (소리 데이터, 샘플레이트, 채널 수). 16비트 wav만 받는다."""
    with wave.open(io.BytesIO(data)) as w:
        if w.getsampwidth() != 2:
            raise ValueError("16비트 wav만 지원한다")
        return w.readframes(w.getnframes()), w.getframerate(), w.getnchannels()


class ClipSpeaker:
    """문장 id로 음성 파일을 찾아 틀어 준다.

    따로 도는 스레드에서 틀어서, 말하는 동안에도 로봇카 프로그램이 멈추지 않는다.
    """

    def __init__(
        self,
        clips_dir: Path,
        volume: float = 0.6,
        device: str | int | None = None,
        log: Callable[[str], None] = print,
    ) -> None:
        self._clips_dir = clips_dir
        self._volume = clamp_volume(volume)
        self._device = device
        self._log = log
        self._queue: queue.Queue[Phrase] = queue.Queue()
        self._playing = threading.Event()
        threading.Thread(target=self._worker, daemon=True).start()

    @property
    def busy(self) -> bool:
        return self._playing.is_set() or not self._queue.empty()

    def say(self, phrase: Phrase) -> None:
        if phrase.urgent:  # 급한 안내는 기다리던 안내를 건너뛰고 먼저
            while not self._queue.empty():
                try:
                    self._queue.get_nowait()
                except queue.Empty:
                    break
        self._playing.set()  # 큐에 넣는 순간부터 busy
        self._queue.put(phrase)

    def _worker(self) -> None:
        while True:
            phrase = self._queue.get()
            self._playing.set()
            try:
                self._play(phrase)
            except Exception as e:  # 스피커 문제로 음성 기능 전체가 멈추면 안 된다
                self._log(f"[스피커 오류] {e}")
            finally:
                if self._queue.empty():
                    self._playing.clear()

    def _load(self, phrase: Phrase) -> bytes | None:
        clip = self._clips_dir / f"{phrase.id}.wav"
        if clip.exists():
            return clip.read_bytes()
        if shutil.which("espeak-ng"):
            return subprocess.run(
                ["espeak-ng", "-v", "ko", "--stdout", phrase.text],
                check=True,
                capture_output=True,
            ).stdout
        return None

    def _play(self, phrase: Phrase) -> None:
        self._log(f"[안내] {phrase.text}")
        wav = self._load(phrase)
        if wav is None:
            return  # 음성 파일도 espeak-ng도 없으면 글자로만 보여 준다
        pcm, rate, channels = read_wav(wav)
        import sounddevice as sd

        with sd.RawOutputStream(
            samplerate=rate, channels=channels, dtype="int16", device=self._device
        ) as out:
            out.write(scale_pcm16(pcm, self._volume))
