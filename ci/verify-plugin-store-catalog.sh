#!/usr/bin/env sh
set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
. "$ROOT_DIR/ci/lib/plugin-store-catalog.sh"

fail() {
  echo "[verify-plugin-store-catalog] $1" >&2
  exit 1
}

plugin_store_select_python || fail "python3 or python is required"
TMP_DIR=$(mktemp -d)
trap 'rm -rf "$TMP_DIR"' EXIT INT TERM
FIXTURE_ROOT="$TMP_DIR/fixture"
MODULE_DIR="$FIXTURE_ROOT/yudream-plugins/yudream-plugin-example"
RESOURCE_DIR="$MODULE_DIR/src/main/resources"
mkdir -p "$RESOURCE_DIR"
printf 'icon' > "$RESOURCE_DIR/icon.svg"
cat > "$RESOURCE_DIR/store.json" <<'EOF'
{
  "icon": "icon.svg",
  "license": "MIT",
  "source": {"repository": "https://example.com/plugins/example", "commit": "0123456789abcdef0123456789abcdef01234567"},
  "releaseNotes": "Initial release.",
  "compatibility": {
    "host": "~1.0.0"
  },
  "dependencies": [
    { "code": "provider-plugin", "range": "~1.2.3", "required": true },
    { "code": "optional-plugin", "range": "2.x", "required": false }
  ]
}
EOF
JAR_PATH="$TMP_DIR/yudream-plugin-example-1.2.3.jar"
"$PLUGIN_STORE_PYTHON" - "$JAR_PATH" <<'PY'
import sys
import zipfile
with zipfile.ZipFile(sys.argv[1], "w") as archive:
    archive.writestr("plugin.yml", "name: example\nversion: 1.2.3\nmain: example.Main\ndepend:\n  - provider-plugin\nsoftdepend:\n  - optional-plugin\n")
PY
printf '%s\n' "$JAR_PATH" > "$TMP_DIR/jars.txt"
LEGACY_MODULE_DIR="$FIXTURE_ROOT/yudream-plugins/yudream-plugin-legacy"
mkdir -p "$LEGACY_MODULE_DIR/src/main/resources"
# Deliberately different from the example plugin version: plugin versions are
# independent of each other and of any tag/release-event version.
LEGACY_JAR_PATH="$TMP_DIR/yudream-plugin-legacy-0.9.0.jar"
"$PLUGIN_STORE_PYTHON" - "$LEGACY_JAR_PATH" <<'PY'
import sys
import zipfile
with zipfile.ZipFile(sys.argv[1], "w") as archive:
    archive.writestr("plugin.yml", "name: legacy\nversion: 0.9.0\nmain: legacy.Main\n")
PY
printf '%s\n' "$LEGACY_JAR_PATH" >> "$TMP_DIR/jars.txt"

ORIGINAL_ROOT_DIR=$ROOT_DIR
ROOT_DIR=$FIXTURE_ROOT
plugin_store_write_catalog "$TMP_DIR/catalog" "https://maven.example" "https://raw.example" "$TMP_DIR/jars.txt" \
  || fail "catalog generation failed"
ROOT_DIR=$ORIGINAL_ROOT_DIR

"$PLUGIN_STORE_PYTHON" - "$TMP_DIR/catalog/plugins/example/versions/1.2.3.json" <<'PY'
import json
import sys
with open(sys.argv[1], encoding="utf-8") as handle:
    descriptor = json.load(handle)
plugin = descriptor["plugin"]
assert plugin["compatibility"] == {
    "host": "~1.0.0", "spi": "^2.6.0", "frontendSdk": "^1.0.1"
}
assert plugin["dependencies"] == [
    {"code": "provider-plugin", "range": "~1.2.3", "required": True},
    {"code": "optional-plugin", "range": "2.x", "required": False},
]
assert plugin["icon"] == "plugins/example/assets/icon.svg"
assert plugin["publisher"] == {"id": "yudream", "name": "YuDream", "url": "https://yudream.online", "verified": True}
assert plugin["license"] == "MIT"
assert plugin["source"] == {"repository": "https://example.com/plugins/example", "commit": "0123456789abcdef0123456789abcdef01234567"}
assert plugin["releaseNotes"] == "Initial release."
assert "compatibility" not in descriptor
assert "dependencies" not in descriptor
PY

"$PLUGIN_STORE_PYTHON" - "$TMP_DIR/catalog/plugins/legacy/versions/0.9.0.json" <<'PY'
import json
import sys
with open(sys.argv[1], encoding="utf-8") as handle:
    descriptor = json.load(handle)
assert descriptor["plugin"] == {
    "code": "legacy", "version": "0.9.0", "main": "legacy.Main",
    "publisher": {"id": "yudream", "name": "YuDream", "url": "https://yudream.online", "verified": True},
    "compatibility": {"host": "^1.0.0", "spi": "^2.6.0", "frontendSdk": "^1.0.1"},
    "dependencies": [],
}
assert descriptor["releaseVersion"] == "0.9.0"
assert "icon" not in descriptor["plugin"]
PY

check_range() {
  range=$1
  expected=$2
  printf '{"compatibility":{"host":"%s"}}\n' "$range" > "$TMP_DIR/range-store.json"
  if plugin_store_store_json_metadata "$TMP_DIR/range-store.json" >/dev/null 2>&1; then
    actual=accepted
  else
    actual=rejected
  fi
  [ "$actual" = "$expected" ] || fail "range $range was $actual, expected $expected"
}

for range in '1.2.3' '^1.2.3' '~1.2.3' '>=1.2.3 <2.0.0' '1.x' '1.2.X'; do
  check_range "$range" accepted
done
for range in '1.0.0-alpha' '1.0.0+build' '>=1.0.0 <2.0.0 || >=3.0.0 <4.0.0' '[1.0.0,2.0.0)' '>=1.0.0' '1.2' '^1.2' '1.2.3 2.0.0' '1.*' '1.2.*'; do
  check_range "$range" rejected
done

printf '{"dependencies":[{"code":"duplicate","range":"1.0.0","required":true},{"code":"duplicate","range":"1.0.0","required":false}]}' > "$TMP_DIR/invalid-store.json"
if plugin_store_store_json_metadata "$TMP_DIR/invalid-store.json" >/dev/null 2>&1; then
  fail "duplicate dependency code was accepted"
fi
printf '{"icon":"icon.svg"}' > "$TMP_DIR/legacy-store.json"
plugin_store_store_json_metadata "$TMP_DIR/legacy-store.json" > "$TMP_DIR/legacy-metadata.txt" \
  || fail "legacy store.json fields were rejected"
[ ! -s "$TMP_DIR/legacy-metadata.txt" ] || fail "legacy store.json unexpectedly emitted compatibility metadata"
printf '{"dependencies":[{"code":"provider-plugin","range":"^1.2.3","required":false}]}' > "$TMP_DIR/conflicting-store.json"
if plugin_store_validate_dependencies_match '[{"code":"provider-plugin","range":"^1.2.3","required":true}]' "$(cat "$TMP_DIR/conflicting-store.json")" >/dev/null 2>&1; then
  fail "store.json dependency required semantic conflict was accepted"
fi
plugin_store_validate_dependencies_match '[{"code":"provider-plugin","range":"^1.2.3","required":true}]' '[{"code":"provider-plugin","range":"~1.2.3","required":true}]' \
  || fail "store.json dependency range override was rejected"

check_metadata() {
  json=$1
  expected=$2
  printf '%s' "$json" > "$TMP_DIR/metadata-store.json"
  if plugin_store_store_json_metadata "$TMP_DIR/metadata-store.json" >/dev/null 2>&1; then
    actual=accepted
  else
    actual=rejected
  fi
  [ "$actual" = "$expected" ] || fail "metadata $json was $actual, expected $expected"
}

for json in \
  '{"license":"MIT"}' \
  '{"source":{"repository":"https://example.com/repo","commit":"0123456789abcdef0123456789abcdef01234567"}}' \
  '{"releaseNotes":"Release notes"}'; do
  check_metadata "$json" accepted
done
for json in \
  '{"publisher":{"id":"untrusted"}}' \
  '{"license":"MIT OR Apache-2.0"}' \
  '{"source":{"repository":"http://example.com/repo","commit":"0123456789abcdef0123456789abcdef01234567"}}' \
  '{"source":{"repository":"https://user@example.com/repo","commit":"0123456789abcdef0123456789abcdef01234567"}}' \
  '{"source":{"repository":"https://example.com/repo#fragment","commit":"0123456789abcdef0123456789abcdef01234567"}}' \
  '{"source":{"repository":"https://example.com/repo","commit":"0123456789abcdef0123456789abcdef0123456"}}' \
  '{"source":{"repository":"https://example.com/repo","commit":"0123456789abcdef0123456789abcdef0123456A"}}' \
  '{"releaseNotes":"bad\u0001text"}'; do
  check_metadata "$json" rejected
done
if plugin_store_validate_dependencies_match '[{"code":"provider-plugin","range":"^1.2.3","required":true}]' '[{"code":"unexpected-plugin","range":"^1.2.3","required":true}]' >/dev/null 2>&1; then
  fail "store.json dependency code conflict was accepted"
fi

LEGACY_DESCRIPTOR="$TMP_DIR/legacy-descriptor.json"
cat > "$LEGACY_DESCRIPTOR" <<'EOF'
{
  "schemaVersion": 1,
  "releaseVersion": "1.2.3",
  "plugin": {
    "code": "example",
    "version": "1.2.3",
    "main": "example.Main"
  },
  "jar": {
    "mavenCoordinates": "online.yudream.plugins:yudream-plugin-example:1.2.3:jar",
    "url": "https://maven.example/online/yudream/plugins/yudream-plugin-example/1.2.3/yudream-plugin-example-1.2.3.jar",
    "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
  }
}
EOF
"$PLUGIN_STORE_PYTHON" - "$LEGACY_DESCRIPTOR" <<'PY'
import json
import sys
with open(sys.argv[1], encoding="utf-8") as handle:
    descriptor = json.load(handle)
assert descriptor["plugin"] == {
    "code": "example", "version": "1.2.3", "main": "example.Main"
}
assert "compatibility" not in descriptor["plugin"]
assert "dependencies" not in descriptor["plugin"]
PY

printf '[verify-plugin-store-catalog] OK\n'
