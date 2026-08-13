#!/usr/bin/env sh
set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
if command -v python3 >/dev/null 2>&1 && python3 -c 'import sys' >/dev/null 2>&1; then
  PYTHON=python3
elif command -v python >/dev/null 2>&1 && python -c 'import sys' >/dev/null 2>&1; then
  PYTHON=python
else
  echo "[verify-third-party-submission-fixture] python3 or python is required" >&2
  exit 1
fi
FIXTURE_PARENT="$ROOT_DIR/submissions/third-party"
mkdir -p "$FIXTURE_PARENT"
TMP_DIR=$(mktemp -d "$FIXTURE_PARENT/.fixture.XXXXXX")
trap 'rm -rf "$TMP_DIR"' EXIT INT TERM

make_submission() {
  target=$1
  mkdir -p "$target/assets"
  printf 'MIT License\n' > "$target/LICENSE"
  printf '<svg/>\n' > "$target/assets/icon.svg"
  cat > "$target/plugin.yml" <<'EOF'
name: example-plugin
version: 1.2.3
main: example.plugin.ExamplePlugin
depend:
  - provider-plugin
softdepend:
  - optional-plugin
EOF
  cat > "$target/store.json" <<'EOF'
{
  "icon": "assets/icon.svg",
  "compatibility": { "host": "^1.0.0" },
  "dependencies": [
    { "code": "provider-plugin", "range": "^1.2.3", "required": true },
    { "code": "optional-plugin", "range": "^1.2.3", "required": false }
  ]
}
EOF
  "$PYTHON" - "$target/plugin.jar" <<'PY'
import sys
import zipfile
with zipfile.ZipFile(sys.argv[1], "w") as archive:
    archive.writestr("plugin.yml", "name: example-plugin\nversion: 1.2.3\nmain: example.plugin.ExamplePlugin\ndepend:\n  - provider-plugin\nsoftdepend:\n  - optional-plugin\n")
    archive.writestr("example/plugin/ExamplePlugin.class", b"not-a-real-class")
PY
  sha256=$(sha256sum "$target/plugin.jar" | awk '{print $1}')
  cat > "$target/submission.json" <<EOF
{
  "schemaVersion": 1,
  "plugin": "plugin.yml",
  "store": "store.json",
  "jar": "plugin.jar",
  "sha256": "$sha256",
  "license": "LICENSE",
  "licenseId": "MIT",
  "author": {"id": "example-author", "name": "Example Author", "url": "https://example.com/authors/example"},
  "source": {"repository": "https://example.com/plugins/example-plugin", "commit": "0123456789abcdef0123456789abcdef01234567"},
  "releaseNotes": "Initial release.",
  "resources": ["assets/icon.svg"]
}
EOF
}

assert_valid() {
  SUBMISSION_DIR=$1 sh "$ROOT_DIR/ci/verify-third-party-submission.sh" >/dev/null
}

assert_invalid() {
  if SUBMISSION_DIR=$1 sh "$ROOT_DIR/ci/verify-third-party-submission.sh" >/dev/null 2>&1; then
    echo "[verify-third-party-submission-fixture] invalid fixture was accepted: $2" >&2
    exit 1
  fi
}

VALID="$TMP_DIR/valid"
make_submission "$VALID"
assert_valid "$VALID"

HASH_BAD="$TMP_DIR/hash-bad"
cp -R "$VALID" "$HASH_BAD"
"$PYTHON" - "$HASH_BAD/submission.json" <<'PY'
import json
import sys
path = sys.argv[1]
value = json.load(open(path, encoding="utf-8"))
value["sha256"] = "0" * 64
json.dump(value, open(path, "w", encoding="utf-8"))
PY
assert_invalid "$HASH_BAD" "hash mismatch"

PATH_BAD="$TMP_DIR/path-bad"
cp -R "$VALID" "$PATH_BAD"
"$PYTHON" - "$PATH_BAD/submission.json" <<'PY'
import json
import sys
path = sys.argv[1]
value = json.load(open(path, encoding="utf-8"))
value["license"] = "../LICENSE"
json.dump(value, open(path, "w", encoding="utf-8"))
PY
assert_invalid "$PATH_BAD" "escaped path"

SPI_BAD="$TMP_DIR/spi-bad"
cp -R "$VALID" "$SPI_BAD"
"$PYTHON" - "$SPI_BAD/plugin.jar" <<'PY'
import sys
import zipfile
with zipfile.ZipFile(sys.argv[1], "a") as archive:
    archive.writestr("online/yudream/plugin/spi/Injected.class", b"forbidden")
PY
sha256sum "$SPI_BAD/plugin.jar" | awk '{print $1}' > "$TMP_DIR/spi.sha"
"$PYTHON" - "$SPI_BAD/submission.json" "$TMP_DIR/spi.sha" <<'PY'
import json
import sys
path, sha_path = sys.argv[1:]
value = json.load(open(path, encoding="utf-8"))
value["sha256"] = open(sha_path, encoding="utf-8").read().strip()
json.dump(value, open(path, "w", encoding="utf-8"))
PY
assert_invalid "$SPI_BAD" "embedded SPI"

MISMATCH_BAD="$TMP_DIR/mismatch-bad"
cp -R "$VALID" "$MISMATCH_BAD"
printf '%s\n' 'version: 1.2.4' >> "$MISMATCH_BAD/plugin.yml"
assert_invalid "$MISMATCH_BAD" "duplicate/mismatched metadata"

metadata_bad() {
  target=$1
  label=$2
  expression=$3
  cp -R "$VALID" "$target"
  "$PYTHON" - "$target/submission.json" "$expression" <<'PY'
import json
import sys
path, expression = sys.argv[1:]
value = json.load(open(path, encoding="utf-8"))
exec(expression, {}, {"value": value})
json.dump(value, open(path, "w", encoding="utf-8"))
PY
  assert_invalid "$target" "$label"
}

metadata_bad "$TMP_DIR/http-url" "non-HTTPS author URL" 'value["author"]["url"] = "http://example.com"'
metadata_bad "$TMP_DIR/userinfo-url" "author URL userinfo" 'value["author"]["url"] = "https://user@example.com"'
metadata_bad "$TMP_DIR/fragment-url" "source URL fragment" 'value["source"]["repository"] = "https://example.com/repo#readme"'
metadata_bad "$TMP_DIR/commit-upper" "uppercase commit" 'value["source"]["commit"] = "A" * 40'
metadata_bad "$TMP_DIR/commit-short" "short commit" 'value["source"]["commit"] = "a" * 39'
metadata_bad "$TMP_DIR/author-verified" "author self-verification" 'value["author"]["verified"] = True'
metadata_bad "$TMP_DIR/license-id" "invalid SPDX license" 'value["licenseId"] = "MIT OR Apache-2.0"'
metadata_bad "$TMP_DIR/control-text" "release notes control character" 'value["releaseNotes"] = "bad" + chr(1) + "text"'
metadata_bad "$TMP_DIR/long-name" "author name length" 'value["author"]["name"] = "a" * 257'
metadata_bad "$TMP_DIR/publisher-control" "publisher control" 'value["publisher"] = {"verified": True}'
metadata_bad "$TMP_DIR/artifact-control" "artifact URL control" 'value["url"] = "https://example.com/plugin.jar"'
metadata_bad "$TMP_DIR/credential-control" "credential control" 'value["credentials"] = {"token": "secret"}'
metadata_bad "$TMP_DIR/index-control" "index control" 'value["index"] = "index.json"'

printf '%s\n' '[verify-third-party-submission-fixture] OK'
