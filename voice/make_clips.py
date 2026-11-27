"""안내 음성 파일(<id>.wav)을 한 번에 만든다.

    python -m voice.make_clips --out voice/clips          (espeak-ng 필요)
    python -m voice.make_clips --list                     (만들 파일 목록만 보기)

espeak-ng 목소리는 로봇 같다. 더 자연스럽게 하고 싶으면 목록의 문장을 직접 녹음하거나
다른 TTS로 만들어서 같은 이름(<id>.wav, 16비트)으로 폴더에 넣으면 된다.
음성 파일은 Git에 올리지 않는다(.gitignore). 팀 공유 드라이브에 둔다.
"""

from __future__ import annotations

import argparse
import shutil
import subprocess
from pathlib import Path

from voice.announcer import all_phrases


def clip_list() -> list[tuple[str, str]]:
    """(파일 이름, 문장) 목록."""
    return [(f"{p.id}.wav", p.text) for p in all_phrases()]


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description="안내 음성 파일 만들기")
    p.add_argument("--out", type=Path, default=Path("voice/clips"))
    p.add_argument("--list", action="store_true", help="만들 파일 목록만 출력")
    args = p.parse_args(argv)

    if args.list:
        for name, text in clip_list():
            print(f"{name}\t{text}")
        return 0

    if not shutil.which("espeak-ng"):
        print("espeak-ng가 없습니다. 라즈베리파이: sudo apt install espeak-ng")
        return 1
    args.out.mkdir(parents=True, exist_ok=True)
    for name, text in clip_list():
        path = args.out / name
        subprocess.run(["espeak-ng", "-v", "ko", "-s", "150", "-w", str(path), text], check=True)
        print(f"만듦: {path}  ({text})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
