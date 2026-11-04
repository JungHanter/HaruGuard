# HaruGuard 고정 Release 키·APK 설치·업데이트 검증

- 적용: 모든 브랜치의 공통 Release 생성. 아래 기기 시험은 `p0b_only_vpn_force_control` 기준이다.
- 최종 갱신: 2026-11-05 (Asia/Seoul)
- 현재 상태: 기존 서명된 0.0.1 APK를 `release/apk`로 이동하고 서명 유효성을 확인했다. 현재 버전 기록은 0.0.1/code 1·lastReleaseCode 1이며 다음 성공은 0.0.2다. 에이전트는 키 생성·새 Release 빌드·기기 설정 변경을 하지 않았다. 갤탭 설치/업데이트는 사용자 시험으로 남아 있다.
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
3. **첫 성공은 `0.0.1`, 두 번째 성공은 `0.0.2`**다. 같은 코드로 실행해도 성공한 새 배포마다 패치 버전과 versionCode를 올린다. 실패하면 `version.properties`는 변경하지 않는다. 일반 Run/Debug 빌드·Gradle 동기화·코드 저장으로는 증가하지 않는다.
4. 앱 상단의 VPN 상태 바로 위에 작은 **버전 0.0.1** 등이 표시된다. 표시와 설치 판정 버전은 같은 빌드 값이다.
5. 모든 브랜치에서 프로젝트 루트의 `release/apk`에 저장된다. 숫자가 붙은 이전 APK를 유지하므로 업데이트 전후 비교에 사용한다. 테스트·Debug·unsigned 검증 산출물은 기존 브랜치 폴더에 둔다.

```text
release/apk/haruguard-0.0.1-release.apk
release/apk/haruguard-0.0.2-release.apk
```

`version.properties`는 현재 버전과 마지막 성공 Release 코드를 보관한다. 버전 변경은 소스와 함께 보관하며, 배포한 버전보다 낮게 되돌리지 않는다. 버전 관리 파일이 뒤로 돌아갔을 때 기존 APK를 덮어쓰지 않도록 같은 이름 산출물이 있으면 스크립트는 중단한다. 다른 PC/브랜치에서 배포할 때도 마지막 배포 버전보다 높은 값과 같은 고정 키를 사용해야 한다.

이 안내서는 `release/release-install-test.md`다. 기존 `.gitignore`의 `release/` 제외 설정을 유지하므로 안내서와 APK 모두 Git에 포함되지 않는다. APK 경로가 공통이므로 다른 브랜치가 같은 버전 파일을 다시 만들려고 하면 덮어쓰지 않고 중단한다.

주의할 실행 차이:

- **배포에는 위 명령을 사용한다.** Android Studio의 Run은 Debug 키를 사용하므로 고정 Release가 설치된 기기에 바로 덮어쓸 수 없다.
- `./gradlew assembleRelease` 직접 실행이나 Android Studio의 별도 서명 마법사는 자동 증가 명령이 아니다. 서명 정보가 없으면 기존 설정대로 unsigned Release를 만들 수 있다. `app-release-unsigned.apk`는 설치용이 아니다.
- 기본 명령은 `--offline`으로 기존 캐시만 사용한다. 의존성이 없다는 오류라면 그 오류를 확인하며 새 의존성/SDK를 자동 설치하지 않는다.
- 강제 종료로 `.gradle/haruguard-release.lock`이 남았다는 오류가 나오면 다른 Release 빌드가 실행 중인지 먼저 확인한다. 실행 중인 작업이 없을 때만 남은 빈 잠금 디렉터리를 지우고 다시 실행한다.

## 4. 갤탭에 APK 파일로 설치하기

**기존 Debug 앱을 새 Release로 전환하면 PIN·설정을 새로 해야 한다.** 삭제 방지/관리자가 남아 있다면 정상 부모 제거 절차로 먼저 해제한다. 초기화·감독 해제를 임의로 진행하지 않는다.

1. 부모 Family Link에서 갤탭의 **알 수 없는 출처의 앱 / Apps from unknown sources** 허용 여부를 확인한다. 메뉴가 없거나 관리자 정책으로 막히면 설치 성공으로 가정하지 않고 그 오류부터 확인한다.
2. 위 `haruguard-0.0.1-release.apk` **파일만** 부모가 관리하는 다운로드 링크 또는 파일 전송으로 갤탭에 전달한다. 키 파일은 전달하지 않는다.
3. 갤탭에서 APK를 연다. Chrome으로 열었으면 Chrome, 내 파일에서 열었으면 내 파일의 **이 출처 허용 / Allow from this source**를 필요한 동안 허용한다. 메뉴 위치는 OS에 따라 다르므로 설치창의 설정 이동을 사용한다.
4. 설치 후 HaruGuard를 열어 **버전 0.0.1**을 확인한다. 앱이 설치되지 않음/서명 충돌/관리자 제한이 나오면 오류를 구분한다. 기존 앱을 지우라는 안내를 업데이트 성공으로 취급하지 않는다.
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
| R01 최초 Release | APK 파일로 0.0.1 설치·부모 초기 설정 | 화면 버전 0.0.1, 접근성 연결·VPN 필터·보호 ON. 미실행 상태에서 성공 표시하지 않음 |
| R02 삭제 금지 | 갤탭에 `block` 후 재조회 | `blocked=true`. 비공개 API 권한/API 오류가 있으면 원문 구분 |
| R03 시스템 삭제 방지 분리 | PIN → 설정을 5분간 열기 → 설치된 앱/스토어에서 HaruGuard 삭제 시도 | 접근성의 화면 보호가 풀린 상태에서도 시스템의 관리자/정책 삭제 차단 확인. 활성 일반 관리자 때문인지 구분하려면 부모가 일반 관리자까지 해제한 별도 시험이 필요. 그 시험에서는 ADB 값이 true인지 먼저 재조회하고 성공 시 앱이 실제 삭제될 수 있음을 이해한 뒤 진행 |
| R04 새 Release 생성 | 같은 키로 `bash tools/release-apk.sh` 재실행 | 0.0.2 APK 생성. 코드 변경 없이 버전만 달라도 업데이트 시험 가능 |
| R05 APK 덮어 업데이트 | 기존 앱 삭제 없이 0.0.2 파일을 열어 업데이트 승인 | 기존 앱의 업데이트로 인식. 삭제를 요구하면 중단하고 서명 확인. 필요 시 부모의 5분 설정 개방 이용 |
| R06 데이터·정책 유지 | 앱 복귀 후 기존 PIN 인증, VPN ON/OFF 선택·보호 설정·권한 확인. `status` 재조회 | 화면 버전 0.0.2, 새 PIN 생성 화면이 나오지 않음, `blocked=true`. 업데이트 후 프로세스가 재생성되면 다시 PIN을 요구하는 것은 정상 |
| R07 실제 삭제·재부팅 | R03과 같은 삭제 진입, 정상 재부팅 후 앱 먼저 열지 않고 VPN/통신 확인, 필요 시 다시 `status` | 업데이트 후 삭제 차단·재부팅 복구 확인. 단순 값 true와 실제 차단을 함께 기록. 부모 OFF의 자동 재활성화 여부는 별도 기존 시험과 구분 |
| R08 Family Link 회귀 | 기존 앱 승인·시간 제한·원격 잠금 등 평소 기능 확인 | 계정 로그인만으로 공존 완료 판정하지 않음 |

R03에서 일반 관리자를 해제해 구분 시험을 했다면 그 상태로 자녀에게 전달하지 않는다. 일반 관리자를 다시 활성화하고 접근성 연결·설정 보호 ON·지금 잠그기를 확인한다. 유지보수 중에는 개발자옵션이 이미 ON인 상태를 유지하고, 필요한 마지막 ADB 확인을 마친 뒤 부모가 개발자옵션을 OFF로 한다. Family Link가 재활성화를 막을 수 있으므로 이번 시험 중에는 먼저 끄지 않는다.

**삭제 금지 해제는 부모의 복구 경로다.** 아래는 삭제 금지만 풀고 앱은 삭제하지 않는다. 제거가 필요할 때 부모의 VPN/일반 관리자/접근성 해제 절차와 함께 사용한다.

```sh
bash agent_artifacts/branches/p0_minimum_dpc_collate_with_family_link/tools/uninstall-guard.sh GALAXY_TAB_ID unblock
```

## 6. 결과와 다음 단계

- 기존 0.0.1 Release APK: **서명 검증 PASS(v2)**, 새 경로로 이동 전후 파일 해시 동일. 실제 고정 키 생성/서명 빌드는 에이전트가 실행하지 않았다. 다른 버전과의 서명 일치와 기기 업데이트는 아직 미검증이다.
- R01~R08 갤탭 검증: **NOT_RUN** — 사용자가 직접 시험. 갤탭 개발자옵션 제한으로 ADB가 안 되면 R02 이후 ADB 관련 부분은 BLOCKED로 구분.
- 사용자 기존 보고: M01~M16 확인, M17 보류, M21 미실행 선택, 재부팅 후 동작 확인. Play Store의 미사용 앱 자동 권한 삭제 경로는 변경 가능하다고 보고했으며 현재 보호 누락이다. 이를 전체 시험 PASS로 확대하지 않는다.
- 갤탭 Release 설치/업데이트/삭제 금지 결과 확인 후 다음 VPN 목록 개발 범위를 확정한다. A17, 최신 One UI, OS 업데이트, 배터리/장시간 유지 시험은 여전히 별도 검증이다.

공식 참고: [APK 서명과 고정 키](https://developer.android.com/studio/publish/app-signing), [앱 버전](https://developer.android.com/studio/publish/versioning), [업데이트 조건](https://developer.android.com/google/play/app-updates), [testOnly](https://developer.android.com/guide/topics/manifest/application-element#testOnly), [제한된 설정](https://support.google.com/android/answer/12623953?hl=en).
