# 폰 앱 로그인·보호자 연락처 (#49)

보호자(가족)가 폰 앱에 **로그인**하고 **보호자 연락처를 등록·수정**하는 기능이다.

- 코드: `android/mobile` (폰 앱)
- 보안 규칙: `firebase/firestore.rules`
- 설계: `docs/uml/mobile-login.puml` (클래스 구조, 구글 로그인 흐름, 연락처 저장 흐름)

## 1. 한 번만 하는 Firebase 설정

Firebase 프로젝트: `watchout-fa6fa` (Authentication에서 Google, 이메일/비밀번호 사용 설정 완료)

### 1-1. Android 앱 등록
1. Firebase 콘솔 → 프로젝트 개요 → **앱 추가 → Android**
2. Android 패키지 이름: `com.watchout.mobile`, 앱 닉네임: `WatchOut 폰`
3. **디버그 서명 인증서 SHA-1** 넣기 (구글 로그인에 꼭 필요)

```powershell
cd android
.\gradlew :mobile:signingReport
```

출력에서 `Variant: debug` 아래 `SHA1:` 값을 복사해 넣는다.
개발 PC마다 SHA-1이 다르다. 팀원 PC에서 구글 로그인을 하려면 그 PC의 SHA-1도
**프로젝트 설정 → 내 앱 → 디지털 지문 추가**로 넣는다.

4. `google-services.json`을 내려받아 **`android/mobile/google-services.json`** 에 둔다.
   - ⚠️ **절대 커밋하지 않는다.** `.gitignore`에 들어 있다. 팀원에게는 공유 드라이브로 준다.
   - SHA-1을 나중에 추가했다면 파일을 다시 내려받는다.

### 1-2. Firestore 만들기
1. Firebase 콘솔 → **Firestore Database → 데이터베이스 만들기**
2. 위치: `asia-northeast3 (서울)`, **프로덕션 모드**로 시작
3. **규칙** 탭에 `firebase/firestore.rules` 내용을 붙여넣고 **게시**

## 2. 빌드·실행

```powershell
cd android
.\gradlew :mobile:testDebugUnitTest   # 단위 테스트 (폰 없이 PC에서)
.\gradlew :mobile:installDebug        # 연결된 폰에 설치
```

- `google-services.json`이 없어도 빌드와 테스트는 된다. 앱을 실행하면 로그인할 때 "설정 파일이 없어요"라고 나온다.
- 에뮬레이터를 쓰면 **Google Play가 들어 있는 이미지**를 고르고, 에뮬레이터에 구글 계정을 추가한다.

## 3. 저장하는 데이터

| 위치 | 필드 | 설명 |
|---|---|---|
| `users/{uid}/guardians/{자동 ID}` | `name` | 이름 1~20자 |
| | `relation` | 관계 1~10자 (예: 딸) |
| | `phone` | 휴대폰 번호 **숫자만** (`01012345678`) |
| | `updatedAt` | 서버 시각 |

- 한 사람당 보호자는 최대 5명이다 (앱에서 검사).

## 4. 보안 (CLAUDE.md "보안·개인정보")

| 요구 | 어떻게 지키나 |
|---|---|
| 보호자 연락처 암호화 저장 | Firestore가 디스크에 자동으로 암호화(AES-256)하고, 통신은 HTTPS로 암호화한다. 보안 규칙으로 **본인만** 읽고 쓴다. |
| 로그인 토큰 안전 보관 | Firebase SDK가 앱 전용 저장소에 보관하고 알아서 갱신한다. 앱 코드가 토큰을 직접 꺼내 저장하거나 로그로 남기지 않는다. `allowBackup="false"`로 백업·기기 복사를 막는다. |
| 키를 코드에 쓰지 않기 | `google-services.json`은 커밋하지 않는다. 웹 클라이언트 ID도 이 파일에서 자동으로 만들어진다. |
| 연락처를 로그로 보내지 않기 | 연락처 값은 Logcat에 찍지 않는다. |

> 폰 안의 키(Android Keystore)로 연락처를 한 번 더 잠그는 방법도 있지만, 그러면 서버가 연락처를 읽지 못해
> 응급 알림(#50)을 보낼 수 없다. 그래서 이번에는 Firestore 암호화 + 보안 규칙을 쓴다.
