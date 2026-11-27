# 로봇카 접속 방법

| 항목 | 값 |
|---|---|
| 호스트 이름 | `wo-pi` |
| 사용자 | `wo` |
| 비밀번호 | 팀 채팅 참고 |

노트북을 차와 같은 와이파이에 연결한 뒤 접속한다.

## SSH

```
ssh wo@wo-pi.local
```

## 화면 (VNC)

TigerVNC에 `wo-pi.local` 입력 → 사용자 `wo`, 비밀번호 입력

## 비밀번호 없이 접속 (선택)

Windows PowerShell에서 한 번 실행 (비밀번호 한 번 입력):

```
Get-Content "$env:USERPROFILE\.ssh\id_ed25519.pub" | ssh wo@wo-pi.local "mkdir -p ~/.ssh && chmod 700 ~/.ssh && cat >> ~/.ssh/authorized_keys && chmod 600 ~/.ssh/authorized_keys"
```

`id_ed25519.pub`이 없으면 먼저 `ssh-keygen -t ed25519` 실행.

`~/.ssh/config`에 추가하면 `ssh wo-pi`로 접속할 수 있다:

```
Host wo-pi
  HostName wo-pi.local
  User wo
  ServerAliveInterval 30
```

## 참고

- 관리자 명령(`sudo`)은 같은 비밀번호를 쓴다.
- 도커는 `sudo` 없이 쓸 수 있다. 이미지: `ros:humble-export`
- 차를 쓸 때는 팀 채팅에 미리 알린다.
