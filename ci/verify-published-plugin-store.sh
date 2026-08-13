#!/usr/bin/env sh
set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$ROOT_DIR"
. "$ROOT_DIR/ci/lib/plugin-jar-selection.sh"
. "$ROOT_DIR/ci/lib/plugin-store-catalog.sh"

# A tag context always publishes/verifies the explicit release/plugins.txt
# selection; local runs without a tag keep the full all-module behavior.
if [ -n "${CI_COMMIT_TAG:-}" ] && ! plugin_release_only_enabled; then
  PLUGIN_RELEASE_ONLY=1
  export PLUGIN_RELEASE_ONLY
fi

fail() {
  echo "[verify-published-plugin-store] $1" >&2
  exit 1
}

RAW_STORE_URL="${NEXUS_PLUGIN_STORE_URL:-https://nexus.yudream.online/repository/plugin-store-releases}"
MAVEN_PUBLIC_URL="${NEXUS_MAVEN_PUBLIC_URL:-https://nexus.yudream.online/repository/maven-public}"
PACKAGE_VERSION="${PLUGIN_PACKAGE_VERSION:-${CI_COMMIT_TAG:-}}"
DRY_RUN="${DRY_RUN:-}"
PACKAGE_VERSION=${PACKAGE_VERSION#v}

[ -n "$PACKAGE_VERSION" ] || fail "CI_COMMIT_TAG or PLUGIN_PACKAGE_VERSION is required"
plugin_store_select_python || fail "python3 or python is required"
if [ -z "$DRY_RUN" ]; then
  command -v curl >/dev/null 2>&1 || fail "curl is required for published plugin store verification"
fi
TMP_DIR=$(mktemp -d)
trap 'rm -rf "$TMP_DIR"' EXIT INT TERM
CATALOG_DIR="$TMP_DIR/catalog"
mkdir -p "$CATALOG_DIR"
plugin_store_write_final_catalog "$CATALOG_DIR" "$MAVEN_PUBLIC_URL" "$RAW_STORE_URL" \
  || fail "unable to generate local plugin store catalog"

fetch_url() {
  url=$1
  output_file=$2
  if [ -n "$DRY_RUN" ]; then
    echo "[verify-published-plugin-store] dry-run fetch $url"
    return 0
  fi
  curl -fsSL "$url" -o "$output_file" || fail "unable to fetch published resource: $url"
}

validate_json_equal() {
  expected_file=$1
  published_file=$2
  "$PLUGIN_STORE_PYTHON" - "$expected_file" "$published_file" <<'PY'
import json
import sys
try:
    with open(sys.argv[1], encoding="utf-8") as handle:
        expected = json.load(handle)
    with open(sys.argv[2], encoding="utf-8") as handle:
        published = json.load(handle)
except (OSError, json.JSONDecodeError) as error:
    raise SystemExit(error)
if expected != published:
    raise SystemExit("published JSON does not match expected current resource")
PY
}

validate_index_contains_current() {
  index_file=$1
  plugin_code=$2
  plugin_version=$3
  descriptor_path=$4
  "$PLUGIN_STORE_PYTHON" - "$index_file" "$plugin_code" "$plugin_version" "$descriptor_path" <<'PY'
import json
import sys
try:
    with open(sys.argv[1], encoding="utf-8") as handle:
        index = json.load(handle)
except (OSError, json.JSONDecodeError) as error:
    raise SystemExit(error)
code, version, descriptor = sys.argv[2:]
if index.get("pluginCode") != code:
    raise SystemExit("published plugin index has unexpected pluginCode")
if not any(entry.get("releaseVersion") == version and entry.get("descriptor") == descriptor for entry in index.get("versions", [])):
    raise SystemExit("published plugin index lacks current version")
PY
}

validate_root_contains_current() {
  index_file=$1
  plugin_code=$2
  "$PLUGIN_STORE_PYTHON" - "$index_file" "$plugin_code" <<'PY'
import json
import sys
try:
    with open(sys.argv[1], encoding="utf-8") as handle:
        index = json.load(handle)
except (OSError, json.JSONDecodeError) as error:
    raise SystemExit(error)
code = sys.argv[2]
if not any(entry.get("code") == code and entry.get("index") == f"plugins/{code}/index.json" for entry in index.get("plugins", [])):
    raise SystemExit("published root index lacks current plugin")
PY
}

# Descriptors and assets are immutable current-release payloads; only indexes are merged history.
plugin_store_list_payload_files "$CATALOG_DIR" | while IFS= read -r catalog_path; do
  expected_file="$CATALOG_DIR/$catalog_path"
  published_file="$TMP_DIR/published-$(printf '%s' "$catalog_path" | tr '/' '_')"
  fetch_url "${RAW_STORE_URL%/}/$catalog_path" "$published_file"
  if [ -z "$DRY_RUN" ]; then
    case "$catalog_path" in
      *.json) validate_json_equal "$expected_file" "$published_file" || fail "published descriptor does not match local output: $catalog_path" ;;
      *) cmp -s "$expected_file" "$published_file" || fail "published asset does not match local output: $catalog_path" ;;
    esac
  fi
done

while IFS="$(printf '\t')" read -r plugin_code record_plugin_version descriptor_path jar_sha256 maven_path; do
  if [ -z "$DRY_RUN" ]; then
    plugin_index="$TMP_DIR/plugin-index-${plugin_code}.json"
    root_index="$TMP_DIR/root-index.json"
    fetch_url "${RAW_STORE_URL%/}/plugins/${plugin_code}/index.json" "$plugin_index"
    validate_index_contains_current "$plugin_index" "$plugin_code" "$record_plugin_version" "$descriptor_path" \
      || fail "published plugin index does not contain current version: $plugin_code"
    fetch_url "${RAW_STORE_URL%/}/index.json" "$root_index"
    validate_root_contains_current "$root_index" "$plugin_code" \
      || fail "published root index does not contain current plugin: $plugin_code"
  else
    echo "[verify-published-plugin-store] dry-run verify plugin index plugins/${plugin_code}/index.json contains $record_plugin_version"
    echo "[verify-published-plugin-store] dry-run verify root index contains $plugin_code"
  fi
  jar_url="${MAVEN_PUBLIC_URL%/}/$maven_path"
  downloaded_jar="$TMP_DIR/${plugin_code}-${record_plugin_version}.jar"
  fetch_url "$jar_url" "$downloaded_jar"
  if [ -z "$DRY_RUN" ]; then
    downloaded_sha256=$(sha256sum "$downloaded_jar" | awk '{print $1}')
    [ "$jar_sha256" = "$downloaded_sha256" ] \
      || fail "published Maven jar checksum mismatch: $plugin_code $record_plugin_version"
  fi
done < "$CATALOG_DIR/records.tsv"

if [ -n "$DRY_RUN" ]; then
  echo "[verify-published-plugin-store] OK (dry-run)"
else
  echo "[verify-published-plugin-store] OK"
fi
