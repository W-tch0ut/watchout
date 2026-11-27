"""WatchOut 데모 로봇카 (PC에서 실행).

진짜 로봇카 대신 PC가 로봇카 역할을 한다. 폰·워치 데모를 로봇카 없이 끝까지 보여주기 위한 것이다.

1. MQTT 브로커를 연다 (포트 1883).
2. 같은 Wi-Fi에 "나 여기 있어요"를 1초마다 방송한다 → 폰 앱의 '로봇카 찾기'에 나타난다.
3. 폰이 전달한 명령(워치 제스처 → 이리 와 등)을 받아 화면에 차가 할 동작을 보여준다.
4. 낙상 의심을 받으면: 다가가기 → 웹캠으로 사진 → 쓰러짐 확인(데모: 자동/질문)
   → 보호자 문자 + 119 시뮬레이션 → 결과를 폰에 돌려준다 (폰이 워치에 전달).

실행 (저장소 폴더에서):
    demo\\.venv\\Scripts\\python -m demo.car
    demo\\.venv\\Scripts\\python -m demo.car --vision ask   # 쓰러짐 확인을 직접 y/n
    demo\\.venv\\Scripts\\python -m demo.car --send-sms     # 보호자에게 실제 문자 (Solapi 키)

처음 실행하면 Windows 방화벽이 Python의 네트워크 허용을 물어본다. '허용'해야 폰이 접속할 수 있다.
"""

from __future__ import annotations

import argparse
import asyncio
import json
import os
import socket
import threading
import time
from datetime import datetime
from pathlib import Path

import paho.mqtt.client as mqtt
from amqtt.broker import Broker

from demo import protocol
from demo.alert import Profile, Recipient, compose_message, plan_deliveries
from demo.sms import SolapiSender, save_to_outbox

HERE = Path(__file__).parent
OUTBOX = HERE / "outbox"


def log(msg: str) -> None:
    print(f"[{datetime.now():%H:%M:%S}] {msg}", flush=True)


def local_ip() -> str:
    """같은 Wi-Fi에서 보이는 이 PC의 주소. (실제로 패킷을 보내지는 않는다)"""
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
        s.connect(("10.255.255.255", 1))
        return s.getsockname()[0]


def load_config() -> dict:
    path = HERE / "config.json"
    if not path.exists():
        path = HERE / "config.example.json"
        log(f"⚠ demo/config.json 이 없어서 예시 설정을 씁니다 ({path.name})")
    return json.loads(path.read_text(encoding="utf-8"))


def announce_forever(car_id: str, name: str, host: str, port: int, stop: threading.Event) -> None:
    """폰 앱이 찾을 수 있게 1초마다 방송한다."""
    packet = protocol.discovery(car_id, name, host, port).encode("utf-8")
    subnet = ".".join(host.split(".")[:3]) + ".255"  # 집 공유기는 보통 /24
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
        s.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        while not stop.is_set():
            for target in ("255.255.255.255", subnet):
                try:
                    s.sendto(packet, (target, protocol.DISCOVERY_PORT))
                except OSError:
                    pass
            stop.wait(1.0)


def capture_photo(path: Path) -> Path | None:
    """웹캠으로 사진을 한 장 찍는다. 카메라가 없으면 안내 그림을 만든다. (영상은 PC 안에만 저장)"""
    try:
        import cv2
        import numpy as np
    except ImportError:
        return None
    path.parent.mkdir(parents=True, exist_ok=True)
    cap = cv2.VideoCapture(0)
    ok, frame = False, None
    if cap.isOpened():
        # 켜자마자 찍으면 노출이 안 맞아 까맣게 나온다. 1초쯤 버리고 찍는다.
        for _ in range(15):
            ok, frame = cap.read()
            time.sleep(0.05)
    cap.release()
    if not ok or frame is None:
        frame = np.zeros((360, 480, 3), dtype=np.uint8)
        cv2.putText(frame, "WatchOut demo (no camera)", (20, 180), 0, 0.8, (255, 255, 255), 2)
    cv2.putText(
        frame, datetime.now().strftime("%Y-%m-%d %H:%M:%S"), (10, 25), 0, 0.6, (0, 0, 255), 2
    )
    cv2.imwrite(str(path), frame)
    return path


class DemoCar:
    def __init__(self, args: argparse.Namespace, config: dict):
        self.args = args
        self.config = config
        self.car_id = args.id
        self.client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, client_id=f"car-{args.id}")
        self.client.on_connect = self._on_connect
        self.client.on_message = self._on_message
        self.busy = threading.Lock()

    # ---------- MQTT ----------

    def start(self) -> None:
        self.client.connect("127.0.0.1", self.args.port)
        self.client.loop_start()

    def stop(self) -> None:
        self.client.loop_stop()
        self.client.disconnect()

    def _on_connect(self, client, userdata, flags, reason_code, properties) -> None:
        client.subscribe(protocol.cmd_topic(self.car_id), qos=1)
        client.subscribe(protocol.fall_topic(self.car_id), qos=1)
        self._status("idle", "대기 중")

    def _status(self, state: str, msg: str) -> None:
        self.client.publish(protocol.status_topic(self.car_id), protocol.status(state, msg), qos=1)

    def _on_message(self, client, userdata, message) -> None:
        if message.topic == protocol.cmd_topic(self.car_id):
            cmd = protocol.parse_command(message.payload)
            if cmd is not None:
                self._handle_command(cmd)
        elif message.topic == protocol.fall_topic(self.car_id):
            event = protocol.parse_fall(message.payload)
            if event is not None:
                # 낙상 처리는 오래 걸리므로 따로 돌린다 (그동안 '멈춰' 같은 명령도 받을 수 있게)
                threading.Thread(target=self._handle_fall, args=(event,), daemon=True).start()

    # ---------- 명령 ----------

    def _handle_command(self, cmd: protocol.Command) -> None:
        who = f"제스처 '{cmd.gesture}'" if cmd.gesture else "버튼"
        icon = "🦾" if cmd.cmd == "grab" else "🚗"
        log(f"{icon} [{who}] {cmd.cmd} → {cmd.description}")
        state = "idle" if cmd.cmd == "stop" else "moving"
        self._status(state, cmd.description)

    # ---------- 낙상 ----------

    def _handle_fall(self, event: protocol.FallEvent) -> None:
        if not self.busy.acquire(blocking=False):
            log("⚠ 이미 낙상을 처리하는 중이라 이번 신호는 건너뜁니다")
            return
        try:
            log(f"🆘 낙상 의심 수신 (충격 {event.impact_g:.1f}g, {event.source})")
            self._status("moving", "사용자에게 가는 중")
            log("🚗 사용자를 찾아 다가가는 중… (데모: 3초)")
            time.sleep(3)
            self._status("checking", "카메라로 확인 중")
            photo = capture_photo(OUTBOX / f"fall__{datetime.now():%Y%m%d-%H%M%S}.jpg")
            log(f"📷 사진: {photo if photo else '카메라 없음'}")
            confirmed = self._vision_check()
            if not confirmed:
                log("👀 쓰러진 모습이 아님 → 알리지 않음")
                self._result(False, "쓰러진 모습이 보이지 않았어요")
                return
            log("✅ 쓰러짐 확인 → 보호자·119(시뮬레이션)에 알림")
            sent = self._notify(event, photo)
            self._result(True, sent)
        finally:
            self._status("idle", "대기 중")
            self.busy.release()

    def _vision_check(self) -> bool:
        mode = self.args.vision
        if mode == "auto":
            log("🧠 쓰러짐 판단: (데모) 자동으로 '쓰러짐'")
            return True
        if mode == "deny":
            log("🧠 쓰러짐 판단: (데모) 자동으로 '아님'")
            return False
        answer = input("🧠 카메라에 쓰러진 사람이 보이나요? [y/n] ").strip().lower()
        return answer.startswith("y")

    def _notify(self, event: protocol.FallEvent, photo: Path | None) -> str:
        cfg = self.config
        profile = Profile(**cfg["user"])
        guardians = [Recipient(g["name"], g["phone"]) for g in cfg.get("guardians", [])]
        emergency = Recipient("119", cfg.get("emergency_number", "119"))
        text = compose_message(
            profile,
            datetime.fromtimestamp(event.ts / 1000) if event.ts else datetime.now(),
            event.impact_g,
            "로봇카 카메라로 쓰러짐 확인",
            moving=False,
        )
        print("-" * 40 + "\n" + text + "\n" + "-" * 40, flush=True)

        sender = self._sender()
        results = []
        for d in plan_deliveries(guardians, emergency, send_real=sender is not None):
            if d.simulate or sender is None:
                path = save_to_outbox(OUTBOX, d.recipient.phone, d.recipient.label, text, photo)
                log(f"📨 (시뮬레이션) {d.recipient.label} → {path.name}  [{d.reason}]")
                results.append(f"{d.recipient.label}(시뮬레이션)")
            else:
                try:
                    sender.send(d.recipient.phone, text, photo)
                    log(f"📨 문자 보냄 → {d.recipient.label}")
                    results.append(d.recipient.label)
                except Exception as e:  # noqa: BLE001 - 데모: 실패해도 나머지는 계속
                    log(f"❌ 문자 실패 → {d.recipient.label}: {e}")
                    results.append(f"{d.recipient.label}(실패)")
        return "알림: " + ", ".join(results)

    def _sender(self) -> SolapiSender | None:
        if not self.args.send_sms:
            return None
        key, secret = os.environ.get("SOLAPI_API_KEY"), os.environ.get("SOLAPI_API_SECRET")
        sender = self.config.get("sms_sender", "")
        if not (key and secret and sender):
            log(
                "⚠ --send-sms 인데 SOLAPI_API_KEY/SECRET 또는 sms_sender 가 없어서 시뮬레이션합니다"
            )
            return None
        return SolapiSender(key, secret, sender)

    def _result(self, confirmed: bool, msg: str) -> None:
        self.client.publish(
            protocol.fall_result_topic(self.car_id), protocol.fall_result(confirmed, msg), qos=1
        )


async def run(args: argparse.Namespace) -> None:
    config = load_config()
    broker = Broker(
        {
            "listeners": {"default": {"type": "tcp", "bind": f"0.0.0.0:{args.port}"}},
            "plugins": {
                "amqtt.plugins.authentication.AnonymousAuthPlugin": {"allow_anonymous": True}
            },
        }
    )
    await broker.start()
    host = args.host or local_ip()
    stop = threading.Event()
    threading.Thread(
        target=announce_forever, args=(args.id, args.name, host, args.port, stop), daemon=True
    ).start()
    car = DemoCar(args, config)
    car.start()
    log(f"🚗 '{args.name}' 준비 완료 — 브로커 {host}:{args.port}")
    log(f"   UDP 방송 포트 {protocol.DISCOVERY_PORT} 로 1초마다 알리는 중")
    log("   폰 앱: 🚗 로봇카 → 로봇카 찾기. 끝내려면 Ctrl+C")
    try:
        while True:
            await asyncio.sleep(1)
    finally:
        stop.set()
        car.stop()
        await broker.shutdown()


def main() -> None:
    parser = argparse.ArgumentParser(description="WatchOut 데모 로봇카 (PC)")
    parser.add_argument("--id", default="demo-car")
    parser.add_argument("--name", default="데모 로봇카(PC)")
    parser.add_argument("--host", help="폰에 알려줄 이 PC 주소 (기본: 자동)")
    parser.add_argument("--port", type=int, default=1883)
    parser.add_argument(
        "--vision",
        choices=["auto", "ask", "deny"],
        default="auto",
        help="쓰러짐 확인 방식 (데모. 실제 비전 AI는 S2 담당 #46)",
    )
    parser.add_argument("--send-sms", action="store_true", help="보호자에게 실제 문자 (Solapi)")
    args = parser.parse_args()
    try:
        asyncio.run(run(args))
    except KeyboardInterrupt:
        log("끝")


if __name__ == "__main__":
    main()
