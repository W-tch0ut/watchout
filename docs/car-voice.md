# 로봇카 음성 안내 (#68)

로봇카가 명령을 받았을 때와 상태가 바뀌었을 때 스피커로 말해 준다.
예) 명령 `{"cmd":"grab","target":"phone"}` → "가져올게요", 상태 `grabbing` → "물건 집는 중입니다"

**음성 명령(말 알아듣기)은 워치 앱이 한다 (#69).** 마이크가 입 가까이 있어 잘 들리고, 차와 멀어도 되고,
쓰러졌을 때도 손목에 있기 때문이다. 워치는 알아들은 말을 제스처와 같은 명령 메시지로 바꿔
보낸다. 그래서 로봇카는 명령이 제스처에서 왔는지 음성에서 왔는지 구분하지 않는다.
로봇카에는 마이크를 달지 않는다.

## 안내 문장
| 받은 것 | 말하는 문장 |
|---|---|
| 명령 `stop` | 멈출게요 (급함) |
| 명령 `approach` / `follow` / `turn` / `move` / `grab` | 갈게요 / 따라갈게요 / 돌게요 / 움직일게요 / 가져올게요 |
| 상태 `moving` / `approaching` / `searching` / `following` | 움직이는 중입니다 / 가까이 가는 중입니다 / 사람을 찾는 중입니다 / 따라가는 중입니다 |
| 상태 `grabbing` / `delivering` / `arrived` / `not_found` | 물건 집는 중입니다 / 물건을 가져가는 중입니다 / 도착했습니다 / 물건을 찾지 못했습니다 |
| 상태 `stopped` / `checking` / `alert_sent` | 멈췄습니다 / 괜찮으세요? 상태를 확인하고 있어요 / 가족에게 알렸습니다 (급함) |

- 같은 상태는 한 번만 말하고, 상태 안내 사이에는 3초 간격을 둔다. **급한 안내**는 간격과 상관없이 바로,
  기다리던 안내를 건너뛰고 말한다.
- 119는 실제로 연락하지 않으므로 안내도 "가족에게 알렸습니다"까지만 한다.
- 상태 이름은 #34 상태 메시지와 맞춘다. 문장은 `voice/announcer.py`가 기준이다.

## 노트북에서 먼저 해 보기 (부품 없이)
```
pip install -r requirements.txt
python -m voice.run
```
`{"cmd":"approach","target":"user"}`, `cmd:grab`, `state:moving` 등을 입력하면 안내가 나온다.
음성 파일이 없고 espeak-ng도 없으면 `[안내] 갈게요`처럼 글자로만 보인다.

## 로봇카 코드에 붙이기 (#42 통신이 정해지면)
```python
from voice.announcer import Announcer
from voice.audio import ClipSpeaker

announcer = Announcer(ClipSpeaker(Path("voice/clips")), time.monotonic)
announcer.on_command(command)  # 명령을 받은 곳에서 (멈춤 처리를 먼저 한 뒤)
announcer.on_state("grabbing")  # 상태가 바뀐 곳에서
```
말하기는 따로 도는 스레드에서 해서, 안내하는 동안에도 주행 코드는 멈추지 않는다.

## 부품과 배선 (라즈베리파이 5)
| 부품 | 연결 |
|---|---|
| MAX98357A 앰프 | 빵판, 아래 표 |
| 50mm 8Ω 0.5W 스피커 | 앰프의 `+` / `-` 단자 |

**전원을 끄고** 꽂는다.

| 앰프 핀 | 라즈베리파이 핀 |
|---|---|
| Vin | 5V (2번 핀) |
| GND | GND (6번 핀) |
| BCLK | GPIO18 (12번 핀) |
| LRC | GPIO19 (35번 핀) |
| DIN | GPIO21 (40번 핀) |
| GAIN | **Vin에 연결** (증폭 6dB로 낮춤, 스피커 보호) |
| SD | 연결 안 함 |

스피커 보호: 앰프는 8Ω에서 최대 약 1.4W를 낼 수 있는데 스피커는 0.5W다.
GAIN을 Vin에 연결하고(최대 약 0.7W), 프로그램이 소리 크기를 0.8배 이하(`MAX_VOLUME`)로 묶는다
(0.8² ≈ 0.64배 → 약 0.45W).

## 라즈베리파이 설정 (실물에서 확인 필요)
1. `/boot/firmware/config.txt` 맨 아래에 추가하고 재부팅:
   ```
   dtoverlay=max98357a,no-sdmode
   ```
   소리가 안 나면 대신 `dtoverlay=hifiberry-dac`를 써 본다.
2. 장치 확인: `aplay -l`, 장치 번호 보기: `python -m sounddevice`
3. 안내 음성 파일 만들기: `sudo apt install espeak-ng` 후 `python -m voice.make_clips --out voice/clips`
   - espeak-ng 목소리는 로봇 같다. 팀원이 녹음한 파일을 같은 이름(`python -m voice.make_clips --list`)으로 넣어도 된다.
   - 음성 파일은 Git에 올리지 않는다. 공유 드라이브에 둔다.
4. 시험: `python -m voice.run --clips voice/clips --speaker-device <번호>`

도커 컨테이너(`ros:humble-export`) 안에서 돌릴 때는 소리 장치를 넘겨 줘야 한다:
`docker run ... --device /dev/snd ...`
