#!/usr/bin/env bash
# JVM regression tests link Android API stubs but execute real org.json code.
set -euo pipefail
PROJECT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$PROJECT_DIR"
SDK_DIR="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
ANDROID_JAR="$SDK_DIR/platforms/android-35/android.jar"
JSON_JAR=.deps/json-20240303.jar
JSON_URL=https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar
mkdir -p .deps build/test-classes
if [[ ! -s "$JSON_JAR" || ! -s "$JSON_JAR.sha1" ]]; then
  curl --fail --silent --show-error --location --connect-timeout 20 --max-time 180 --retry 3 \
    "$JSON_URL" --output "$JSON_JAR.tmp"
  curl --fail --silent --show-error --location --connect-timeout 20 --max-time 60 --retry 3 \
    "$JSON_URL.sha1" --output "$JSON_JAR.sha1.tmp"
  mv "$JSON_JAR.tmp" "$JSON_JAR"
  mv "$JSON_JAR.sha1.tmp" "$JSON_JAR.sha1"
fi
python3 - "$JSON_JAR" <<'PY'
import hashlib, pathlib, re, sys
jar = pathlib.Path(sys.argv[1])
expected = pathlib.Path(str(jar) + '.sha1').read_text().strip().lower()
if not re.fullmatch(r'[0-9a-f]{40}', expected) or hashlib.sha1(jar.read_bytes()).hexdigest() != expected:
    raise SystemExit('ERROR: org.json test dependency checksum mismatch; remove .deps/json-20240303.jar* and retry')
PY
mapfile -t TEST_SOURCES < <(find tests/java -name '*.java' -type f | LC_ALL=C sort)
if [[ ${#TEST_SOURCES[@]} -eq 0 ]]; then
  printf '%s\n' 'ERROR: expected Java integration tests in tests/java' >&2
  exit 1
fi
TEST_CLASSPATH="build/classes:build/stubs:$JSON_JAR:$ANDROID_JAR:.deps/zxing-core-3.5.3.jar"
javac -source 8 -target 8 -Xlint:-options -encoding UTF-8 \
  -classpath "$TEST_CLASSPATH" -d build/test-classes "${TEST_SOURCES[@]}"
mapfile -t TEST_CLASSES < <(python3 - <<'PY'
import pathlib, re
for source in sorted(pathlib.Path('tests/java').rglob('*.java')):
    package = re.search(r'^\s*package\s+([\w.]+)\s*;', source.read_text(), re.MULTILINE)
    print((package.group(1) + '.' if package else '') + source.stem)
PY
)
for test_class in "${TEST_CLASSES[@]}"; do
  java -ea -cp "build/test-classes:$TEST_CLASSPATH" "$test_class"
done
