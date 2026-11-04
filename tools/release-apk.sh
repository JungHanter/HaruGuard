#!/bin/bash
# 고정 키로 서명한 APK만 배포 폴더에 복사하고, 성공했을 때만 버전을 기록한다.
# https://developer.android.com/studio/publish/app-signing
set -euo pipefail

project_dir="$(cd "$(dirname "$0")/.." && pwd)"
cd "$project_dir"
fail() { printf '%s\n' "$*" >&2; exit 1; }

branch="$(git branch --show-current)"
[[ -n "$branch" ]] || fail '브랜치가 없는 상태입니다. 배포할 브랜치를 먼저 선택하세요.'
release_dir="$project_dir/release"
version_file="$project_dir/version.properties"
original_version="$(cat "$version_file")"
property() { sed -n "s/^$1=//p" "$version_file"; }
version_name="$(property versionName)"
version_code="$(property versionCode)"
last_release="$(property lastReleaseCode)"
[[ "$version_name" =~ ^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$ ]] || fail 'versionName 형식을 확인하세요.'
[[ "$version_code" =~ ^[1-9][0-9]{0,9}$ && "$last_release" =~ ^(0|[1-9][0-9]{0,9})$ ]] || fail '버전 코드 형식을 확인하세요.'
(( version_code <= 2100000000 && last_release <= version_code )) || fail '버전 코드 순서나 최댓값을 확인하세요.'

# 같은 버전을 이미 배포했다면 다음 패치 버전으로 올린다. 실패하면 파일은 바꾸지 않는다.
if (( last_release == version_code )); then
    IFS=. read -r major minor patch <<< "$version_name"
    (( ${#major} <= 9 && ${#minor} <= 9 && ${#patch} <= 9 )) || fail '버전 숫자가 너무 큽니다.'
    version_name="$major.$minor.$((patch + 1))"
    version_code=$((version_code + 1))
fi
(( version_code <= 2100000000 )) || fail 'versionCode 최댓값에 도달했습니다.'

export JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
[[ -x "$JAVA_HOME/bin/java" ]] || fail 'Android Studio의 JDK 또는 JAVA_HOME을 확인하세요.'
sdk_dir="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
build_tools="${HARUGUARD_BUILD_TOOLS_DIR:-}"
if [[ -z "$build_tools" ]]; then
    for candidate in "$sdk_dir"/build-tools/*; do
        if [[ -x "$candidate/apksigner" && -x "$candidate/aapt2" ]]; then build_tools="$candidate"; fi
    done
fi
[[ -x "$build_tools/apksigner" && -x "$build_tools/aapt2" ]] || fail '설치된 Android SDK build-tools를 찾지 못했습니다.'

export HARUGUARD_KEYSTORE_FILE="${HARUGUARD_KEYSTORE_FILE:-$HOME/.config/Android/haruguard/release.jks}"
export HARUGUARD_KEY_ALIAS="${HARUGUARD_KEY_ALIAS:-haruguard}"
[[ -f "$HARUGUARD_KEYSTORE_FILE" ]] || fail '고정 서명 키 파일이 없습니다. Release 설치 안내에 따라 먼저 키를 만드세요.'
# 비밀번호를 명령 인수나 파일에 기록하지 않고 터미널에서 숨겨 입력받는다.
if [[ -z "${HARUGUARD_STORE_PASSWORD:-}" ]]; then
    read -r -s -p '서명 키 비밀번호: ' HARUGUARD_STORE_PASSWORD < /dev/tty
    printf '\n'
fi
export HARUGUARD_STORE_PASSWORD
export HARUGUARD_KEY_PASSWORD="${HARUGUARD_KEY_PASSWORD:-$HARUGUARD_STORE_PASSWORD}"
[[ -n "$HARUGUARD_STORE_PASSWORD" && -n "$HARUGUARD_KEY_PASSWORD" ]] || fail '비밀번호가 비어 있습니다.'

# 동시에 두 배포가 같은 버전이나 APK를 덮어쓰지 않도록 잠근다.
mkdir -p "$project_dir/.gradle" "$release_dir/apk"
lock_dir="$project_dir/.gradle/haruguard-release.lock"
mkdir "$lock_dir" 2>/dev/null || fail '다른 Release 작업이 실행 중입니다. 중단된 작업이면 .gradle/haruguard-release.lock을 확인하세요.'
output="$release_dir/apk/haruguard-$version_name-release.apk"
output_created=false
complete=false
version_tmp=''
cleanup() {
    if [[ "$output_created" == true && "$complete" != true ]]; then rm -f "$output"; fi
    if [[ -n "$version_tmp" ]]; then rm -f "$version_tmp"; fi
    rmdir "$lock_dir"
}
trap cleanup EXIT
# 다른 작업이 비밀번호 입력 중 먼저 완료됐을 수도 있으므로 잠금 후 다시 확인한다.
[[ "$(cat "$version_file")" == "$original_version" ]] || fail '버전 파일이 바뀌었습니다. Release 명령을 다시 실행하세요.'
[[ ! -e "$output" ]] || fail '같은 버전 APK가 이미 있습니다. 덮어쓰지 않습니다. version.properties와 기존 APK를 확인하세요.'
printf 'HaruGuard %s Release 빌드 중…\n' "$version_name"
bash ./gradlew --offline :app:assembleRelease \
    "-PharuguardVersionName=$version_name" "-PharuguardVersionCode=$version_code"
apk="$project_dir/app/build/outputs/apk/release/app-release.apk"
[[ -f "$apk" ]] || fail '서명된 app-release.apk가 없습니다. 서명 설정을 확인하세요.'
"$build_tools/apksigner" verify "$apk"
badging="$("$build_tools/aapt2" dump badging "$apk")"
[[ "$badging" == *"package: name='com.hanter.haruguard' versionCode='$version_code' versionName='$version_name'"* ]] || fail 'APK의 실제 버전이 요청한 버전과 다릅니다.'
[[ "$badging" != *'application-debuggable'* ]] || fail 'Debug APK는 배포할 수 없습니다.'
manifest="$("$build_tools/aapt2" dump xmltree "$apk" --file AndroidManifest.xml)"
test_only="$(printf '%s\n' "$manifest" | sed -n '/android:testOnly/p')"
if [[ "$test_only" == *'=true'* || "$test_only" == *'0xffffffff'* ]]; then
    fail 'testOnly APK는 다운로드 설치용으로 배포할 수 없습니다.'
fi
[[ "$(cat "$version_file")" == "$original_version" ]] || fail '빌드 중 버전 파일이 바뀌었습니다. 버전을 기록하지 않습니다.'
cp "$apk" "$output"
output_created=true
version_tmp="$(mktemp "$project_dir/.gradle/release-version.XXXXXX")"
printf 'versionName=%s\nversionCode=%s\nlastReleaseCode=%s\n' \
    "$version_name" "$version_code" "$version_code" > "$version_tmp"
mv "$version_tmp" "$version_file"
version_tmp=''
complete=true
printf '완료: %s\n다음 Release 명령에서는 패치 버전이 자동 증가합니다.\n' "$output"
