# HaruGuard 고정 Release 키·APK 설치·업데이트 검증

- 적용: 모든 브랜치의 공통 Release 생성. 아래 기기 시험은 `p0b_only_vpn_force_control` 기준이다.
- 최종 갱신: 2026-11-05 (Asia/Seoul)
- 현재 버전 기록은 0.0.5/code 5·lastReleaseCode 5이며 다음 사용자 Release는 0.0.6이다. 앱 하단의 링크 업데이트에 Android 10 서명 조회 호환 수정을 추가했다. 이번 기능이 들어간 APK는 기존 방법으로 한 번 설치해야 하며, 이후에는 앱 안에서 업데이트할 수 있다. 서명된 릴리즈 생성·기기 설치는 사용자가 한다.
- **기존 0.0.1/0.0.2 APK는 클래스 접근 오류가 있으므로 새 시험에 사용하지 않는다.** Debug/Release 최적화 해제를 유지한다. 이미 같은 고정 키의 Release를 사용 중이면 앱 삭제·데이터 초기화·새 키 생성 없이 덮어 업데이트한다. 아래 새 설치/두 버전 예시는 0.0.6 → 0.0.7이며 실제 기기 성공은 별도 확인한다.
- 이번 검증이 끝나면 다음 단계의 VPN 앱별/도메인별 목록 개발 착수 여부를 결정한다. 종전 DO/NextDNS 중심 P1을 재개한다는 뜻이 아니다. Galaxy A17·Android/One UI 업데이트 호환성은 별도 미검증이다.

## 1. 먼저 알아둘 구분

- **고정 서명 키:** APK를 만든 사람이 같은지 Android가 확인하는 수단이다. 매번 같은 키로 업데이트한다. 앱 내부에 개인 키를 넣거나 외부 서비스에 등록하지 않는다. 빌드할 때 개인 키로 서명하고 APK에는 공개 인증서가 포함된다.
- **APK 설치:** 이번 코드의 `android:testOnly=false`는 다운로드한 APK 파일로 설치할 수 있게 한다. Release 서명 여부와는 다른 설정이다. 설치 후 VPN·접근성·일반 관리자 설정은 부모가 한다. DO/PO는 등록하지 않는다.
- **ADB 삭제 금지:** 설치 경로와 별개로 기기 사용자 0의 `com.hanter.haruguard`에 적용한다. 비공개 Android API이므로 갤탭에서 실제 적용을 확인해야 한다. 앱이 스스로 적용하는 기능이 아니다.
- **업데이트:** 같은 패키지·같은 키·더 큰 versionCode로 기존 앱 위에 설치한다. 정상 덮어 업데이트에서는 저장한 PIN·VPN 선택·설정이 유지된다. 삭제 금지는 업데이트 전후 조회와 실제 삭제 시도를 함께 검증한다.
- 현재 Debug APK와 새 고정 Release 키의 서명은 다르다. **첫 전환은 덮어 업데이트가 아니다.** 기존 앱의 정상 보호 해제·별도 ADB 삭제 금지 해제 후 제거하고 새로 설치해야 하며, 기존 PIN·설정이 지워진다. 이후 Release끼리의 덮어 업데이트를 시험한다. 이 문서는 기기 초기화를 요구하지 않는다.

## 2. 고정 키 만들기 — 최초 한 번

macOS의 **터미널**을 열고 다음을 실행한다. Android Studio 설치에 포함된 도구를 사용하며 새 프로그램을 설치하지 않는다.

```sh
mkdir -p "$HOME/.config/Android/haruguard"
chmod 700 "$HOME/.config/Android/haruguard"
"/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/keytool" \
  -genkeypair -storetype PKCS12 \
  -keystore "$HOME/.config/Android/haruguard/release.jks" \
  -alias haruguard -keyalg RSA -keysize 3072 -validity 10000 \
  -dname "CN=HaruGuard"
chmod 600 "$HOME/.config/Android/haruguard/release.jks"
```

1. 비밀번호 입력과 확인을 요구하면 부모만 아는 비밀번호를 입력한다. 입력 중 글자가 보이지 않는 것이 정상이다. 키는 비밀번호가 동일한 PKCS12 방식으로 만든다.
2. `release.jks` 파일과 비밀번호는 부모의 안전한 별도 보관 장소에도 백업한다. 같은 이름의 키를 다시 만드는 것은 기존 키 복구가 아니다. 이 키를 잃으면 기존 설치본에 같은 서명으로 업데이트할 수 없다.
3. 기존 파일/alias가 있다는 오류가 나오면 덮어쓰거나 새 키를 만들지 말고 기존 키인지 확인한다.
4. 키 파일·비밀번호는 저장소·문서·채팅·APK 공유 폴더에 넣지 않는다. 이 프로젝트의 `.gitignore`는 `*.jks`, `*.keystore`를 제외한다. 위 경로는 저장소 밖이다.

기본 경로와 alias를 사용하면 앱에 별도로 등록할 작업은 없다. `tools/release-apk.sh`가 아래 기존 Gradle 서명 설정에 값을 연결한다.

| 빌드 환경 변수 | 기본값/전달 방식 |
|---|---|
| `HARUGUARD_KEYSTORE_FILE` | 부모 홈의 `.config/Android/haruguard/release.jks` |
| `HARUGUARD_KEY_ALIAS` | `haruguard` |
| `HARUGUARD_STORE_PASSWORD` | 빌드 시작 시 터미널에서 숨겨 입력. 파일이나 명령 인수에 기록하지 않음 |
| `HARUGUARD_KEY_PASSWORD` | 위 비밀번호와 동일. 다른 기존 키를 쓰는 경우 별도 환경 변수 필요 |

## 3. Release APK 만들기 — 배포할 때마다

```sh
cd /Users/hanter/Projects/Android/HaruGuard
bash tools/release-apk.sh
```

1. 키 비밀번호를 입력한다. APK를 설치하는 자녀나 부모 PIN과는 별개다.
2. 빌드·서명 검증·실제 APK의 패키지/버전/Debug·testOnly 여부 확인이 성공해야 최종 `완료:` 경로가 출력된다.
3. **현재 기록에서 다음 성공은 `0.0.6`, 그다음 성공은 `0.0.7`**다. 같은 코드로 실행해도 성공한 새 배포마다 패치 버전과 versionCode를 올린다. 실패하면 `version.properties`는 변경하지 않는다. 일반 Run/Debug 빌드·Gradle 동기화·코드 저장으로는 증가하지 않는다.
4. 앱 상단의 VPN 상태 바로 위에 작은 **버전 0.0.6** 등이 표시된다. 표시와 설치 판정 버전은 같은 빌드 값이다.
5. 모든 브랜치에서 프로젝트 루트의 `release/apk`에 저장된다. 숫자가 붙은 이전 APK를 유지하므로 업데이트 전후 비교에 사용한다. 테스트·Debug·unsigned 검증 산출물은 기존 브랜치 폴더에 둔다.

```text
release/apk/haruguard-0.0.6-release.apk
release/apk/haruguard-0.0.7-release.apk
```

`version.properties`는 현재 버전과 마지막 성공 Release 코드를 보관한다. 버전 변경은 소스와 함께 보관하며, 배포한 버전보다 낮게 되돌리지 않는다. 버전 관리 파일이 뒤로 돌아갔을 때 기존 APK를 덮어쓰지 않도록 같은 이름 산출물이 있으면 스크립트는 중단한다. 다른 PC/브랜치에서 배포할 때도 마지막 배포 버전보다 높은 값과 같은 고정 키를 사용해야 한다.

이 안내서는 `release/release-install-test.md`이며 현재 Git으로 추적 중이다. 배포 APK는 기존 `.gitignore`의 `release/`·`*.apk` 제외 설정을 유지해 Git에 넣지 않는다. APK 경로가 공통이므로 다른 브랜치가 같은 버전 파일을 다시 만들려고 하면 덮어쓰지 않고 중단한다.

주의할 실행 차이:

- **배포에는 위 명령을 사용한다.** Android Studio의 Run은 Debug 키를 사용하므로 고정 Release가 설치된 기기에 바로 덮어쓸 수 없다.
- `./gradlew assembleRelease` 직접 실행이나 Android Studio의 별도 서명 마법사는 자동 증가 명령이 아니다. 서명 정보가 없으면 기존 설정대로 unsigned Release를 만들 수 있다. `app-release-unsigned.apk`는 설치용이 아니다.
- 기본 명령은 `--offline`으로 기존 캐시만 사용한다. 의존성이 없다는 오류라면 그 오류를 확인하며 새 의존성/SDK를 자동 설치하지 않는다.
- 강제 종료로 `.gradle/haruguard-release.lock`이 남았다는 오류가 나오면 다른 Release 빌드가 실행 중인지 먼저 확인한다. 실행 중인 작업이 없을 때만 남은 빈 잠금 디렉터리를 지우고 다시 실행한다.

## 4. 갤탭에 APK 파일로 설치하기

사용자의 현재 설치 순서는 **최초 ADB 설치 → ADB 삭제 금지 → Family Link 활성화 → 이후 앱 안에서 APK 업데이트**다. 이번 업데이트 기능을 넣은 첫 APK도 기존 설치 경로로 한 번 올린다. 같은 고정 키의 기존 Release 위에는 다음처럼 덮어 설치한다. 기기 ID는 현재 `adb devices -l` 결과와 일치하는지 확인한다.

```sh
/Users/hanter/Library/Android/sdk/platform-tools/adb -s R9TT5022YXV install -r release/apk/haruguard-0.0.6-release.apk
```

아래는 APK 파일을 직접 열어 설치할 때의 공통 절차다. 앱 내부 업데이트는 5.1절을 따른다.

**기존 Debug 앱을 새 Release로 전환하면 PIN·설정을 새로 해야 한다.** 삭제 방지/관리자가 남아 있다면 정상 부모 제거 절차로 먼저 해제한다. 초기화·감독 해제를 임의로 진행하지 않는다.

1. 부모 Family Link에서 갤탭의 **알 수 없는 출처의 앱 / Apps from unknown sources** 허용 여부를 확인한다. 메뉴가 없거나 관리자 정책으로 막히면 설치 성공으로 가정하지 않고 그 오류부터 확인한다.
2. 위 `haruguard-0.0.6-release.apk` **파일만** 부모가 관리하는 다운로드 링크 또는 파일 전송으로 갤탭에 전달한다. 키 파일은 전달하지 않는다.
3. 갤탭에서 APK를 연다. Chrome으로 열었으면 Chrome, 내 파일에서 열었으면 내 파일의 **이 출처 허용 / Allow from this source**를 필요한 동안 허용한다. 메뉴 위치는 OS에 따라 다르므로 설치창의 설정 이동을 사용한다.
4. 설치 후 HaruGuard를 열어 **버전 0.0.6**을 확인한다. 앱이 설치되지 않음/서명 충돌/관리자 제한이 나오면 오류를 구분한다. 기존 앱을 지우라는 안내를 업데이트 성공으로 취급하지 않는다. 기존 0.0.2의 충돌 복구를 시험하는 경우도 제거 없이 같은 키의 최신 버전으로 덮어 업데이트하고 기존 PIN·VPN/보호 선택을 확인한다.
5. [Android API 시험 안내](../agent_artifacts/branches/p0b_only_vpn_force_control/android-api-test.md)의 2절대로 PIN·VPN·일반 관리자·접근성·배터리 예외 등을 새로 설정하고 보호 ON·지금 잠그기를 확인한다.
6. 다운로드 설치에서는 시스템의 **제한된 설정 허용 / Allow restricted settings**가 추가로 필요할 수 있다. 접근성이 실제 **연결됨**인지 확인한다. 시스템/Family Link가 허용하지 않으면 완료로 표시하지 않는다.

이 APK 설치 과정에는 개발자옵션이 필요 없다. 다음 ADB 삭제 금지 적용에는 USB 디버깅과 PC의 연결 승인이 필요하다. 갤탭에서 Family Link가 개발자옵션을 막고 부모 허용 메뉴가 없는 문제는 현재 해결되지 않았으며, 이 경우 다음 단계는 **BLOCKED / 미실행**으로 기록한다.

## 5. ADB 삭제 금지 후 두 버전으로 시험하기

기기/에뮬레이터가 함께 연결되어 있으므로 **대상 기기 ID를 반드시 지정**한다. 아래 명령의 `GALAXY_TAB_ID`는 그대로 실행하는 값이 아니라 `devices -l`에 표시된 갤탭 ID로 바꿔야 한다. `-e`는 에뮬레이터 선택이므로 갤탭 시험에 사용하지 않는다.

```sh
/Users/hanter/Library/Android/sdk/platform-tools/adb devices -l
```

프로젝트 폴더에서 다음을 실행한다. 기존 부모 콘솔 도구이며 새 권한을 앱에 부여하거나 DO/PO를 등록하지 않는다. 도구 JAR를 기기에 복사하므로 `status`도 기기 파일 복사를 포함한다.

```sh
bash agent_artifacts/branches/p0_minimum_dpc_collate_with_family_link/tools/uninstall-guard.sh GALAXY_TAB_ID status
bash agent_artifacts/branches/p0_minimum_dpc_collate_with_family_link/tools/uninstall-guard.sh GALAXY_TAB_ID block
```

성공 출력은 `after: com.hanter.haruguard user=0 blocked=true`다. 오류라면 해당 기기에서 적용 성공으로 보지 않는다.

| 순서 | 조작 | 기대 결과·기록 |
|---|---|---|
| R01 정상 Release 기준 | ADB로 업데이트 기능이 포함된 0.0.6 설치. 기존 같은 키의 Release는 삭제 없이 덮어 업데이트 | 화면 버전 0.0.6, 접근성 연결·VPN 필터·보호 ON. 업데이트라면 기존 PIN/설정 유지. 미실행 상태에서 성공 표시하지 않음 |
| R02 삭제 금지 | 갤탭에 `block` 후 재조회 | `blocked=true`. 비공개 API 권한/API 오류가 있으면 원문 구분 |
| R03 시스템 삭제 방지 분리 | PIN → 설정을 5분간 열기 → 설치된 앱/스토어에서 HaruGuard 삭제 시도 | 접근성의 화면 보호가 풀린 상태에서도 시스템의 관리자/정책 삭제 차단 확인. 활성 일반 관리자 때문인지 구분하려면 부모가 일반 관리자까지 해제한 별도 시험이 필요. 그 시험에서는 ADB 값이 true인지 먼저 재조회하고 성공 시 앱이 실제 삭제될 수 있음을 이해한 뒤 진행 |
| R04 새 Release 생성 | 같은 키로 `bash tools/release-apk.sh` 재실행 | 기준 0.0.6 이후에는 0.0.7 APK 생성. 코드 변경 없이 버전만 달라도 업데이트 시험 가능 |
| R05 APK 덮어 업데이트 | 기존 앱 삭제 없이 0.0.7 APK를 OneDrive/Dropbox에 올리고 아래 5.1절대로 앱에서 다운로드·업데이트 승인 | 기존 앱의 업데이트로 인식. 삭제를 요구하면 중단하고 서명 확인. 정상 Android 설치 확인창이 떠야 함 |
| R06 데이터·정책 유지 | 앱 복귀 후 기존 PIN 인증, VPN ON/OFF 선택·보호 설정·권한 확인. ADB 사용 가능하면 `status` 재조회 | 화면 버전 0.0.7, 새 PIN 생성 화면이 나오지 않음. ADB 가능 시 `blocked=true`도 확인. 업데이트 후 프로세스가 재생성되면 다시 PIN을 요구하는 것은 정상 |
| R07 실제 삭제·재부팅 | R03과 같은 삭제 진입, 정상 재부팅 후 앱 먼저 열지 않고 VPN/통신 확인, 필요 시 다시 `status` | 업데이트 후 삭제 차단·재부팅 복구 확인. 단순 값 true와 실제 차단을 함께 기록. 부모 OFF의 자동 재활성화 여부는 별도 기존 시험과 구분 |
| R08 Family Link 회귀 | 기존 앱 승인·시간 제한·원격 잠금 등 평소 기능 확인 | 계정 로그인만으로 공존 완료 판정하지 않음 |

R03에서 일반 관리자를 해제해 구분 시험을 했다면 그 상태로 자녀에게 전달하지 않는다. 일반 관리자를 다시 활성화하고 접근성 연결·설정 보호 ON·지금 잠그기를 확인한다. 유지보수 중에는 개발자옵션이 이미 ON인 상태를 유지하고, 필요한 마지막 ADB 확인을 마친 뒤 부모가 개발자옵션을 OFF로 한다. Family Link가 재활성화를 막을 수 있으므로 이번 시험 중에는 먼저 끄지 않는다.

**삭제 금지 해제는 부모의 복구 경로다.** 아래는 삭제 금지만 풀고 앱은 삭제하지 않는다. 제거가 필요할 때 부모의 VPN/일반 관리자/접근성 해제 절차와 함께 사용한다.

```sh
bash agent_artifacts/branches/p0_minimum_dpc_collate_with_family_link/tools/uninstall-guard.sh GALAXY_TAB_ID unblock
```

### 5.1 앱 하단에서 링크로 업데이트하기

**Android 10의 기존 0.0.4/0.0.5 설치본은 서명 정보를 읽지 못해 같은 키도 거부하는 문제가 있다.** 수정된 0.0.6을 같은 고정 키로 만들어 ADB 또는 외부의 APK 설치 화면에서 한 번 덮어 업데이트한다. 기존 앱의 업데이트 기능으로 이 수정 자체를 적용하려고 반복하지 않는다. 앱 삭제·새 키 생성·정책 해제는 필요하지 않다. 이후 더 높은 0.0.7로 아래 링크 업데이트를 시험한다.

1. 같은 고정 키로 만든 **더 높은 versionCode의 APK 한 파일**을 OneDrive 또는 Dropbox에 올린다. 폴더·ZIP·AAB가 아닌 APK의 공유 링크를 복사한다. 로그인·비밀번호 입력 없이 다운로드 가능한 공유여야 한다.
2. HaruGuard → 부모 PIN 인증 → 화면 맨 아래 **앱 업데이트** → **업데이트 APK 링크**에 붙여넣기 → **다운로드**. 진행량과 오류 안내가 같은 위치에 나온다.
3. 다운로드 완료 후 **설치할 버전** 확인 → **설치하기**. 같은 패키지·같은 현재 서명 키·더 높은 버전·현재 Android 지원·testOnly=false를 앱에서 먼저 확인한다. Android가 최종 서명/설치 검사를 한다. Debug와 Release의 서명이 다르면 이 경로로 전환할 수 없다.
4. 처음에는 HaruGuard의 **이 출처 허용 / Allow from this source** 화면이 열린다. 부모가 허용하고 앱으로 돌아와 **설치하기**를 다시 누른다. Family Link·OS 정책이 금지하면 앱이 우회하지 않으며 그 오류를 기록한다.
5. Android 설치창에서 **업데이트 / Update**를 승인한다. 앱의 설치 버튼은 기존 부모 설정을 최대 5분간 개방하며 이미 개방 중이면 기한을 늘리지 않는다. 다운로드 자체는 설정 보호를 개방하지 않는다. 화면 잠금·만료 시 부모 PIN을 다시 입력한다.
6. 설치가 끝나면 HaruGuard를 열어 새 버전·기존 PIN·VPN/보호 설정·관리자·접근성 연결을 확인하고 **지금 잠그기**. 재설치 방식에 따른 제한된 설정/접근성 재확인은 기존 절차대로 부모가 한다. 설치창 취소·복귀를 설치 성공으로 표시하지 않는다.
7. ADB 사용 가능 시 삭제 금지 값을 업데이트 전후 재조회한다. 감독 뒤 ADB가 끊겼으면 실제 삭제 차단을 확인하되 값을 재조회한 것으로 기록하지 않는다. 신규 Family Link 등록을 포함한 전체 순서는 실기기 시험이 필요하다.

Dropbox는 기존 `rlkey` 등을 보존하며 `dl=1`로 바꾼다. OneDrive/SharePoint는 `download=1`, 일부 기존 OneDrive 주소는 `/download` 형식을 시도하고 HTTPS 리다이렉트를 따른다. **모든 OneDrive 공유 형식의 직접 다운로드를 보장하지 않는다.** 로그인·미리보기 HTML이 내려오면 APK로 설치하지 않고 오류를 표시한다. 이 경우 로그인 없이 파일을 받을 수 있는 직접 링크 또는 Dropbox 파일 공유 링크로 다시 시험한다. 웹 로그인·Microsoft Graph 인증·브라우저 페이지 자동화는 구현하지 않았다.

파일은 앱 전용 캐시에 받으므로 사진/저장소의 광범위한 접근 권한은 요청하지 않는다. 최대 256MB·연결/읽기 15초 제한·전체 약 15분 제한을 적용한다. 앱 잠금/회전 중에도 같은 프로세스의 다운로드는 이어가지만, OS가 프로세스를 종료한 다운로드는 재개하지 않으므로 다시 받는다. 완성된 캐시 파일은 다음 앱 실행 때 재검증하며 캐시 삭제 시 다시 받아야 한다. 다운로드 완료만으로 설치창을 띄우거나 무인 설치하지 않는다.

### 5.2 링크 업데이트 추가 시험

아래 항목은 실제 클라우드 파일·기기에서 **전부 NOT_RUN**이다.

| ID | 조작 | 기대 결과 |
|---|---|---|
| U01 | Dropbox 단일 APK 공유 링크 입력·다운로드·설치 | 웹 페이지 없이 파일 다운로드, 정상 Update 확인창, 새 버전 표시 |
| U02 | OneDrive 짧은/긴 파일 공유 링크로 각각 다운로드 | 지원되는 공개 링크는 다운로드. 로그인/미리보기 반환은 명확한 오류, 잘못된 파일 설치 금지 |
| U03 | HaruGuard 설치 출처 허용 OFF에서 설치하기, 거부/허용 각각 시험 | 거부 시 설치되지 않음. 허용 후 앱 복귀·설치하기 재선택으로 진행. Family Link 제한은 별도 기록 |
| U04 | 같은/이전 버전, 다른 앱, 다른 서명 APK, HTML 링크 각각 입력 | 설치창을 열지 않고 이유 안내. 실제 고정 키와 Debug 키를 혼용하지 않음 |
| U05 | 다운로드 중 취소·네트워크 끊김·화면 회전·앱 왕복 | 부분 파일을 설치하지 않음, 오류/취소 뒤 재시도 가능. 회전은 같은 프로세스에서 계속 진행 |
| U06 | 다운로드 후 지금 잠그기·인증 만료·화면 잠금, 설치창 취소 | 잠긴 상태에서 설치 시작 불가, 재인증 후 설치 가능. 취소를 성공 표시하지 않음 |
| U07 | 설치 출처 화면과 설치창에서 5분 개방 확인·다른 설정 왕복 | 기존 개방 기한 유지. 수동/자동 잠금 후 기존 설정 보호 복원. 개발자옵션 활성화 예외도 유지 |
| U08 | Family Link 활성화·USB 디버깅 OFF 상태에서 링크 업데이트 | 정상 설치 또는 정책 오류 기록. 성공 후 기존 PIN·VPN 복구·접근성/관리자·실제 삭제 방지 확인 |

참고: [Dropbox 직접 다운로드](https://help.dropbox.com/share/force-download), [OneDrive/SharePoint 옵션에 대한 Microsoft 지원 답변](https://learn.microsoft.com/en-us/answers/questions/5190893/force-one-drive-link-to-open-as-desktop-app-and-no), [설치 출처 확인 API](https://developer.android.com/reference/android/content/pm/PackageManager#canRequestPackageInstalls()), [FileProvider](https://developer.android.com/reference/androidx/core/content/FileProvider).

## 6. 결과와 다음 단계

- 기존 0.0.1/0.0.2 Release APK: 서명 유효 여부와 별도로 **클래스 접근 위반 확인**, 0.0.2는 갤탭 초기화 충돌 로그로 FAIL 확인. 서명/빌드 성공만으로 기동 성공을 판단하지 않는다.
- 최신 링크 업데이트 코드: 단위 테스트 **122개 PASS**(기존 98 + 업데이트 관련 24)·Debug/unsigned Release 빌드 PASS, Debug/Release Lint 오류 0(기존 경고 37/35), DEX의 동일 클래스 접근 위반 모두 0개. 검증 APK는 소스 버전 0.0.5/code 5이며 실제 배포 명령은 별도로 0.0.6을 만든다. 서명 키 사용·버전 증가·설치는 이번 작업에서 수행하지 않았다.
- 사용자 단축 링크 → Dropbox 직접 파일 다운로드를 PC에서 확인했고 파일은 로컬 0.0.5와 일치했다. 갤탭 설치 0.0.4와 다운로드 0.0.5의 실제 서명 인증서도 같았다. 단축 URL 때문에 서명이 바뀐 것은 아니다. Android 10의 공식 파서가 인증서 수집에 GET_SIGNATURES를 요구하는 차이를 반영했다. Android 15 공식 구현은 두 조회 옵션 중 하나로 수집하며, Android 17 실기기 결과는 아직 없다.
- 기기용 회귀 테스트에서 실제 앱 APK를 .part/.apk로 복사해 운영 코드로 서명을 읽고 설치본과 비교하도록 추가했다. 테스트 APK 빌드는 PASS, 기기 설치/실행은 NOT_RUN. 기존 순수 단위 테스트는 Android OS 파서의 이 동작을 검증하지 않았으므로 121개 PASS가 호환성 검증 완료를 뜻하지 않았다.
- U01~U08 실제 공유 파일 다운로드·Android 설치 화면·Family Link 설치 정책·설치 뒤 삭제 방지 유지: **NOT_RUN**. 자동 테스트는 링크/전송/후보 APK 판정 규칙을 검사하며 실제 클라우드·시스템 설치 성공을 대체하지 않는다.
- R01~R08 갤탭 검증: **NOT_RUN** — 사용자가 직접 시험. 갤탭 개발자옵션 제한으로 ADB가 안 되면 R02 이후 ADB 관련 부분은 BLOCKED로 구분.
- 사용자 기존 보고: M01~M16 확인, M17 보류, M21 미실행 선택, 재부팅 후 동작 확인. Play Store의 미사용 앱 자동 권한 삭제 경로는 변경 가능하다고 보고했으며 현재 보호 누락이다. 이를 전체 시험 PASS로 확대하지 않는다.
- 갤탭 Release 설치/업데이트/삭제 금지 결과 확인 후 다음 VPN 목록 개발 범위를 확정한다. A17, 최신 One UI, OS 업데이트, 배터리/장시간 유지 시험은 여전히 별도 검증이다.

공식 참고: [APK 서명과 고정 키](https://developer.android.com/studio/publish/app-signing), [앱 버전](https://developer.android.com/studio/publish/versioning), [업데이트 조건](https://developer.android.com/google/play/app-updates), [testOnly](https://developer.android.com/guide/topics/manifest/application-element#testOnly), [제한된 설정](https://support.google.com/android/answer/12623953?hl=en).
