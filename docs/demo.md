# WatchOut 데모: 폰 · 워치 · 로봇카 (#42)

폰 하나, 워치 하나, PC 한 대로 아래 기능을 처음부터 끝까지 보여주는 데모다.
실제 로봇카(HW팀) 대신 **PC가 로봇카 역할**을 한다. 메시지 형식이 같아서, 나중에 실제 차가 같은 메시지를 받게 하면 된다.

| 요구사항 | 데모에서 |
|---|---|
| 앱에서 로봇카를 골라 통신 켜기 | **폰 앱 → 🚗 로봇카 → 로봇카 찾기** → 목록에서 누르면 **폰이 로봇카에 연결**한다 (Wi-Fi 방송으로 자동 발견, IP 입력 없음). 워치는 연결 상태만 본다 |
| 원하는 제스처를 자유롭게 등록·학습 | **폰 앱 → ✋ 제스처 → 새 제스처 등록**: 폰에 "워치를 확인하세요!" → 폰의 '시연'을 누를 때마다 워치가 녹화. 3~5번이면 등록 (DTW, 학습 시간 없음) |
| 앱이 백그라운드여도 제스처 → 차 동작 | 폰이 로봇카에 연결되면 워치의 **포그라운드 서비스**가 센서를 계속 읽는다. 알아들은 명령은 워치 → 폰 → 로봇카로 간다. 화면을 꺼도 된다 |
| 손짓으로 부르면 다가오기 | "부르기" 제스처를 **이리 와(approach)** 명령에 연결 |
| 낙상 감지 → 로봇카가 비전으로 확인 | 워치 충격 감지 → **괜찮으세요? 팝업 10초** → 폰을 거쳐 로봇카에 알림 → 다가가서 **사진 + 쓰러짐 확인** |
| 보호자·119 연락, 문자 + 사진 | 보호자 문자(기본은 시뮬레이션, 키가 있으면 Solapi 실제 발송), **119는 항상 시뮬레이션** |
| 보호자·문자 내용 고치기 | **폰 앱 → 👪 보호자**(Firestore, #49), **✉️ 문자**(이름·주소·참고, 미리보기. 지금은 폰에만 저장 → 나중에 로봇카·서버가 읽게 한다) |

> **갤럭시 워치 기본 낙상 감지**: 갤럭시 워치에도 낙상 감지(SOS)가 있지만, 삼성이 정한 연락처로 직접 SOS를 보내고
> 우리 앱이 결과를 받는 공식 통로가 확실하지 않다. 데모는 워치 센서로 직접 충격을 감지하고,
> 시연용 **낙상 테스트** 버튼(개발자 모드)을 둔다.

## 구성

```
💻 PC "데모 로봇카" (python -m demo.car)
 ├ UDP 방송 47474 (1초마다 {"type":"watchout_car", ...})
 └ MQTT 브로커 1883 + 로봇카 프로그램 (명령 표시, 낙상 → 📷 웹캠 사진 → 📨 보호자 문자 / 119 시뮬레이션)
        ▲  MQTT (cmd, fall ▶ / ◀ status, fall/result)
        │
📱 폰 앱 (본인 모드)  CarConnectionService ── 로봇카와 연결하는 것은 폰뿐
 ├ 🚗 로봇카: 찾기·연결·끊기, 최근 일
 ├ ✋ 제스처: 목록·삭제·등록 버튼 (시연 버튼은 폰에)
 ├ 👪 보호자 · ✉️ 문자 설정
        ▲  Wear OS Data Layer (블루투스)
        │   워치 → 폰: /watchout/msg/cmd, /watchout/msg/fall, /watchout/msg/reg_state, /watchout/gestures
        │   폰 → 워치: /watchout/status, /watchout/msg/fall_result, /watchout/msg/reg
        ▼
⌚ 워치 (Wear OS)  PhoneLinkListener → WatchOutService (백그라운드), RemoteRegistrar (등록 녹화)
 └ 화면: 🚗 로봇카(상태만) · ✋ 내 제스처(목록)  + 팝업: 괜찮으세요? / 제스처 등록   (개발자 모드: 제목 7번)
```

> **폰 ↔ 워치(Data Layer) 조건**: 폰 앱과 워치 앱의 applicationId가 같아야 한다. 그래서 워치 앱의
> applicationId도 `com.watchout.mobile`이다(코드 패키지는 `com.watchout.wear`). **서명도 같아야 해서
> 폰 앱과 워치 앱을 같은 PC(같은 디버그 키)에서 빌드해 설치해야 한다.** 폰과 워치는 Galaxy Wearable로 연결돼 있어야 한다.

설계 그림: `docs/uml/phone-watch-link.puml`(폰·워치·로봇카 전체, 연결·등록 흐름), `docs/uml/demo-watch-car.puml`(워치, 낙상 흐름)

## 메시지 규약 (제안, #34에서 확정)

### 폰 ↔ 로봇카 (MQTT)

코드: `android/gesture/.../CarProtocol.kt`, `demo/protocol.py` (둘이 같아야 한다)

| 토픽 | 방향 | 예시 |
|---|---|---|
| `watchout/cars/{id}/cmd` | 폰 → 차 | `{"cmd":"approach","target":"user","source":"gesture","gesture":"부르기","ts":1795…}` |
| `watchout/cars/{id}/status` | 차 → 폰 | `{"state":"moving","msg":"사용자 쪽으로 다가갑니다"}` |
| `watchout/cars/{id}/fall` | 폰 → 차 | `{"event":"fall_suspected","ts":…,"impact_g":3.0,"source":"sensor"}` |
| `watchout/cars/{id}/fall/result` | 차 → 폰 | `{"confirmed":true,"msg":"알림: 보호자, 119(시뮬레이션)"}` |
| UDP 방송 47474 | 차 → 모두 | `{"type":"watchout_car","id":"demo-car","name":"…","host":"192.168.0.34","port":1883}` |

명령은 **워치 음성 명령(#71 `RobotCommand`)·CLAUDE.md 예시와 같은 형식**이다. 제스처든 음성이든 로봇카는 같은 메시지를 받는다.
`{"cmd":"stop"}`, `{"cmd":"approach","target":"user"}`, `{"cmd":"follow","target":"user"}`, `{"cmd":"grab","target":"phone"}`,
`{"cmd":"turn","angle_deg":90}`(왼쪽 +, 오른쪽 -), `{"cmd":"move","dist_cm":30}`(앞 +, 뒤 -), `{"cmd":"go_home"}`(제안, #71에는 없음).
제스처로 보낼 때는 뒤에 `source`·`gesture`·`ts`를 덧붙인다. 로봇카는 모르는 필드를 무시한다.
명령은 QoS 1(최소 한 번 전달)로 보낸다. 워치가 만든 명령·낙상 메시지는 폰이 **그대로** 로봇카에 전달한다.

### 폰 ↔ 워치 (Data Layer)

코드: `android/gesture/.../WatchLink.kt` (폰·워치 공용)

| 경로 | 종류 | 방향 | 내용 |
|---|---|---|---|
| `/watchout/status` | 데이터 | 폰 → 워치 | 로봇카 연결 상태 `{"state":"connected","car":"…","msg":"…"}` (연결되면 워치 감지 시작, 끊기면 멈춤) |
| `/watchout/gestures` | 데이터 | 워치 → 폰 | 내 제스처 목록 (한 줄에 `id·이름·명령·시연 수`) |
| `/watchout/msg/cmd` | 메시지 | 워치 → 폰 | 명령 (위 MQTT 명령과 같은 글자) |
| `/watchout/msg/fall` | 메시지 | 워치 → 폰 | 낙상 의심 (위 MQTT fall과 같은 글자) |
| `/watchout/msg/fall_result` | 메시지 | 폰 → 워치 | 로봇카의 낙상 확인 결과 |
| `/watchout/msg/reg` | 메시지 | 폰 → 워치 | 등록 조작 `{"action":"start","name":"부르기","command":"approach"}` / `record` / `save` / `cancel` / `delete` |
| `/watchout/msg/reg_state` | 메시지 | 워치 → 폰 | 등록 진행 `{"phase":"added","count":3,…}` (ready·countdown·recording·added·failed·saved·cancelled) |

데이터는 마지막 값이 남는 "상태", 메시지는 한 번 보내고 끝나는 "사건"이다. 센서 원본은 폰으로 보내지 않는다(동작 모양만 워치에 저장).

## 실행

### 1. PC 데모 로봇카 (처음 한 번 준비)

```powershell
python -m venv demo\.venv
demo\.venv\Scripts\python -m pip install -r demo\requirements.txt
copy demo\config.example.json demo\config.json   # 이름·주소·보호자 번호를 고친다 (Git에 안 올라감)
```

### 2. 실행

```powershell
demo\.venv\Scripts\python -m demo.car
```

- 처음 실행하면 **Windows 방화벽**이 Python 허용을 묻는다. **개인 네트워크 허용**을 눌러야 폰이 접속한다.
- `--vision ask`: 쓰러짐 확인을 콘솔에서 y/n으로 직접 (기본 `auto`는 항상 "쓰러짐")
- `--send-sms`: 보호자에게 실제 문자. 환경 변수 `SOLAPI_API_KEY`, `SOLAPI_API_SECRET`과
  `config.json`의 `sms_sender`(Solapi에 등록한 발신번호)가 필요하다. **119는 이 옵션이 있어도 보내지 않는다.**
- 사진·문자(시뮬레이션)는 `demo\outbox\`에 저장된다. (Git에 안 올라감)

### 3. 폰·워치 설치

`android/mobile/google-services.json`(Firebase 설정, Git에 안 올라감 — `docs/mobile-login.md`)이 있어야 폰에서 로그인된다.

```powershell
cd android
.\gradlew :mobile:installDebug   # 폰 (USB 또는 무선 디버깅으로 연결)
.\gradlew :wear:installDebug     # 워치
```

### 4. 시연

1. PC와 폰을 **같은 Wi-Fi**에 둔다. 폰과 워치는 Galaxy Wearable로 연결돼 있어야 한다.
2. 워치 앱을 한 번 연다 (알림 권한을 묻으면 허용).
3. **폰 앱 → 로그인 → 본인(환자) 모드 → 🚗 로봇카 → 로봇카 찾기** → "데모 로봇카(PC)"를 누른다 (알림 권한 허용).
   폰에 **"연결됨 ✓"**, 워치 첫 화면 🚗에도 **"연결됨"**이 뜬다. 워치에 "눌러서 감지를 켜 주세요" 알림이 뜨면 누른다.
4. **폰 앱 → ✋ 제스처 → 새 제스처 등록 → 부르기** → 폰에 "⌚ 워치를 확인하세요!", 워치에 등록 화면
   → 폰의 **[시연]** → 워치가 3·2·1 진동 후 **"● 지금!"**(긴 진동) → 워치 찬 손으로 손짓 한 번. 5번 반복 → **[저장]**
5. 손짓으로 부르면 폰의 "최근 일"과 PC 화면에 `🚗 [제스처 '부르기'] approach → 사용자 쪽으로 다가갑니다`가 뜬다.
   워치 화면을 꺼도 동작한다.
6. 낙상 감지는 로봇카에 연결되면 뒤에서 늘 돌고, 감지되면 **"괜찮으세요?" 팝업만** 뜬다.
   시연은 **개발자 모드**에서 한다: 워치 첫 화면 **WatchOut 글자를 7번 연달아** 누르기 →
   **🆘 낙상 감지 데모 → 낙상 테스트** → 10초 그대로 두면 PC가 사진을 찍고 알림 → 폰·워치에 "보호자에게 알렸어요"

> 워치 일반 화면에는 **로봇카(상태) · 내 제스처**만 보인다. 실시간 인식, 데이터 모으기, 삼성 제스처 수집, 착용 손,
> 낙상 감지 데모, 워치에서 직접 제스처 등록, 버튼 명령은 개발자 모드에서만 보인다.

## 한계 (데모라서)

- 쓰러짐 확인은 **데모용 자동/수동 판단**이다. 실제 비전 AI(MediaPipe 등)는 S2 담당(#46).
- 낙상 감지 기준값(자유낙하 0.5g 미만 150ms → 1초 안에 3g 충격 → 2.5초 정지)은 실제 넘어짐 데이터로 맞춘 값이 아니다. 손짓 오감지를 막으려고 자유낙하를 반드시 요구한다. 시연은 개발자 모드의 **낙상 테스트** 버튼으로 한다.
- 워치는 화면이 꺼져도 돌도록 CPU를 깨워 둔다(wake lock). **배터리를 많이 쓴다.**
- 폰이 꺼지거나 블루투스가 끊기면 워치 명령이 로봇카로 가지 않는다 (워치는 로봇카와 직접 연결하지 않는다).
- 문자 설정(✉️)은 아직 폰에만 저장된다. 로봇카(데모 PC)는 `demo/config.json`의 이름·주소를 쓴다.
- Solapi 실제 발송 코드는 공개 문서대로 작성했지만 **실제 키로 시험하지 않았다.**
- 내 제스처 오인식 방지: 시연보다 훨씬 약한 움직임(타자 등)은 비교하지 않고, 기준 거리는 "가만히 있음"까지
  거리의 75%를 넘지 않게 하고, 한 번 인식하면 2초 쉰다. 시연을 들쭉날쭉하게 하면 잘 안 알아들으니 **같은 크기·속도로** 시연한다.
- 내 제스처(DTW)는 시연 3~5번으로 바로 쓰는 방식이다. 데이터가 쌓이면 TFLite 모델(#44)로 바꾼다.
