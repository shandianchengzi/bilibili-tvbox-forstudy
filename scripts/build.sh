#!/usr/bin/env bash
# Compile a native TVBox spider: Android classes.dex inside a jar, not JVM bytecode.
set -euo pipefail

PROJECT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$PROJECT_DIR"
SDK_DIR="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
BUILD_TOOLS_VERSION="${BUILD_TOOLS_VERSION:-35.0.0}"
ANDROID_JAR="$SDK_DIR/platforms/android-35/android.jar"
D8="$SDK_DIR/build-tools/$BUILD_TOOLS_VERSION/d8"

if ! command -v javac >/dev/null; then
  printf '%s\n' 'ERROR: JDK 17 is required. Install a JDK, not only a Java runtime.' >&2
  exit 1
fi
if [[ -z "$SDK_DIR" || ! -f "$ANDROID_JAR" || ! -x "$D8" ]]; then
  printf '%s\n' 'ERROR: Set ANDROID_SDK_ROOT and install: sdkmanager "platforms;android-35" "build-tools;35.0.0"' >&2
  exit 1
fi

mkdir -p .deps build dist
ZXING_VERSION=3.5.3
ZXING_JAR=".deps/zxing-core-$ZXING_VERSION.jar"
ZXING_URL="https://repo.maven.apache.org/maven2/com/google/zxing/core/$ZXING_VERSION/core-$ZXING_VERSION.jar"
if [[ ! -s "$ZXING_JAR" || ! -s "$ZXING_JAR.sha1" ]]; then
  curl --fail --silent --show-error --location --connect-timeout 20 --max-time 180 --retry 3 \
    "$ZXING_URL" --output "$ZXING_JAR.tmp"
  curl --fail --silent --show-error --location --connect-timeout 20 --max-time 60 --retry 3 \
    "$ZXING_URL.sha1" --output "$ZXING_JAR.sha1.tmp"
  mv "$ZXING_JAR.tmp" "$ZXING_JAR"
  mv "$ZXING_JAR.sha1.tmp" "$ZXING_JAR.sha1"
fi
python3 - "$ZXING_JAR" <<'PY'
import hashlib, pathlib, re, sys
jar = pathlib.Path(sys.argv[1])
expected = pathlib.Path(str(jar) + '.sha1').read_text().strip().lower()
if not re.fullmatch(r'[0-9a-f]{40}', expected):
    raise SystemExit('ERROR: invalid Maven Central dependency checksum')
if hashlib.sha1(jar.read_bytes()).hexdigest() != expected:
    raise SystemExit('ERROR: ZXing checksum mismatch; remove .deps/zxing-core-3.5.3.jar* and retry')
PY

# Only reset generated Java output; preserve the restored crawler catalog.
rm -rf build/stubs build/classes build/dex
mkdir -p build/stubs build/classes build/dex
mapfile -t STUB_SOURCES < <(find compile-stubs -name '*.java' -type f | LC_ALL=C sort)
mapfile -t MAIN_SOURCES < <(find src/main/java -name '*.java' -type f | LC_ALL=C sort)
if [[ ${#MAIN_SOURCES[@]} -eq 0 ]]; then
  printf '%s\n' 'ERROR: no Java plugin source files found' >&2
  exit 1
fi
javac -source 8 -target 8 -Xlint:-options -encoding UTF-8 \
  -classpath "$ANDROID_JAR" -d build/stubs "${STUB_SOURCES[@]}"
javac -source 8 -target 8 -Xlint:-options -encoding UTF-8 \
  -classpath "$ANDROID_JAR:build/stubs:$ZXING_JAR" -d build/classes "${MAIN_SOURCES[@]}"
bash scripts/test_java.sh

# Separate archives guarantee the host Spider class cannot enter program input.
python3 - <<'PY'
import pathlib, zipfile
for source, output in [('classes', 'plugin-classes.jar'), ('stubs', 'host-stubs.jar')]:
    root = pathlib.Path('build') / source
    with zipfile.ZipFile(pathlib.Path('build') / output, 'w', zipfile.ZIP_DEFLATED) as jar:
        for path in sorted(root.rglob('*.class')):
            info = zipfile.ZipInfo(path.relative_to(root).as_posix(), (1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            jar.writestr(info, path.read_bytes())
PY
"$D8" --release --min-api 21 --lib "$ANDROID_JAR" --classpath build/host-stubs.jar \
  --output build/dex build/plugin-classes.jar "$ZXING_JAR"

python3 - <<'PY'
import pathlib, zipfile
dexes = sorted(pathlib.Path('build/dex').glob('*.dex'))
if len(dexes) != 1 or dexes[0].name != 'classes.dex':
    raise SystemExit('ERROR: expected one classes.dex; TVBox single-dex plugin limit exceeded')
entries = {'classes.dex': dexes[0].read_bytes()}
notices = pathlib.Path('THIRD_PARTY_NOTICES.md')
if notices.exists():
    entries['META-INF/THIRD_PARTY_NOTICES.md'] = notices.read_bytes()
for license_path in sorted(pathlib.Path('licenses').glob('*.txt')):
    entries['META-INF/licenses/' + license_path.name] = license_path.read_bytes()
with zipfile.ZipFile('dist/bili-study.jar', 'w', zipfile.ZIP_DEFLATED) as jar:
    for name, content in sorted(entries.items()):
        info = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
        info.compress_type = zipfile.ZIP_DEFLATED
        jar.writestr(info, content)
PY
python3 scripts/verify.py --jar dist/bili-study.jar
printf '%s\n' 'Built dist/bili-study.jar'
