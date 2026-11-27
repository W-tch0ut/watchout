"""문자 보내기: 시뮬레이션(outbox 폴더에 저장)과 Solapi 실제 발송.

Solapi 실제 발송은 API 키가 필요하다 (환경 변수 SOLAPI_API_KEY, SOLAPI_API_SECRET).
키는 코드나 Git에 넣지 않는다. 발신번호는 Solapi에 미리 등록해야 한다(발신번호 사전등록제).

⚠ 실제 발송 코드는 Solapi 공개 API 문서를 보고 작성했고, 아직 실제 키로 시험하지 않았다.
"""

from __future__ import annotations

import base64
import hashlib
import hmac
import json
import secrets
import shutil
import urllib.request
from datetime import UTC, datetime
from pathlib import Path

SOLAPI_URL = "https://api.solapi.com"


def save_to_outbox(outbox: Path, to: str, label: str, text: str, photo: Path | None) -> Path:
    """시뮬레이션: 보낼 내용을 파일로 남긴다. 사진이 있으면 옆에 복사한다."""
    outbox.mkdir(parents=True, exist_ok=True)
    stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    path = outbox / f"sms__{stamp}__{label}.txt"
    lines = [f"받는 사람: {label} ({to})", f"사진: {photo.name if photo else '없음'}", "", text]
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    if photo is not None and photo.exists():
        shutil.copy(photo, outbox / f"{path.stem}{photo.suffix}")
    return path


def solapi_auth_header(api_key: str, api_secret: str, date: str, salt: str) -> str:
    """Solapi HMAC-SHA256 인증 헤더 (date + salt 를 secret으로 서명)"""
    signature = hmac.new(
        api_secret.encode("utf-8"), (date + salt).encode("utf-8"), hashlib.sha256
    ).hexdigest()
    return f"HMAC-SHA256 apiKey={api_key}, date={date}, salt={salt}, signature={signature}"


class SolapiSender:
    """Solapi로 문자(사진이 있으면 MMS)를 보낸다."""

    def __init__(self, api_key: str, api_secret: str, sender: str):
        self.api_key = api_key
        self.api_secret = api_secret
        self.sender = sender

    def send(self, to: str, text: str, photo: Path | None) -> dict:
        message: dict = {"to": to, "from": self.sender, "text": text}
        if photo is not None and photo.exists():
            message["imageId"] = self._upload_image(photo)
            message["subject"] = "WatchOut 낙상 알림"
        return self._post("/messages/v4/send", {"message": message})

    def _upload_image(self, photo: Path) -> str:
        data = base64.b64encode(photo.read_bytes()).decode("ascii")
        result = self._post("/storage/v1/files", {"file": data, "type": "MMS"})
        return result["fileId"]

    def _post(self, path: str, body: dict) -> dict:
        date = datetime.now(UTC).isoformat(timespec="seconds").replace("+00:00", "Z")
        salt = secrets.token_hex(16)
        req = urllib.request.Request(
            SOLAPI_URL + path,
            data=json.dumps(body).encode("utf-8"),
            headers={
                "Authorization": solapi_auth_header(self.api_key, self.api_secret, date, salt),
                "Content-Type": "application/json",
            },
            method="POST",
        )
        with urllib.request.urlopen(req, timeout=15) as resp:
            return json.loads(resp.read().decode("utf-8"))
