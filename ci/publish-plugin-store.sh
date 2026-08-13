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
  echo "[publish-plugin-store] $1" >&2
  exit 1
}

PACKAGE_VERSION="${PLUGIN_PACKAGE_VERSION:-${CI_COMMIT_TAG:-}}"
PACKAGE_VERSION=${PACKAGE_VERSION#v}
RAW_STORE_URL="${NEXUS_PLUGIN_STORE_URL:-https://nexus.yudream.online/repository/plugin-store-releases}"
MAVEN_PUBLIC_URL="${NEXUS_MAVEN_PUBLIC_URL:-https://nexus.yudream.online/repository/maven-public}"
DRY_RUN="${DRY_RUN:-}"

[ -n "$PACKAGE_VERSION" ] || fail "CI_COMMIT_TAG or PLUGIN_PACKAGE_VERSION is required"
plugin_store_select_python || fail "python3 or python is required"
if [ -z "$DRY_RUN" ]; then
  [ -n "${NEXUS_USERNAME:-}" ] || fail "NEXUS_USERNAME is required"
  [ -n "${NEXUS_PASSWORD:-}" ] || fail "NEXUS_PASSWORD is required"
  command -v curl >/dev/null 2>&1 || fail "curl is required"
fi

TMP_DIR=$(mktemp -d)
trap 'rm -rf "$TMP_DIR"' EXIT INT TERM
CATALOG_DIR="$TMP_DIR/catalog"
plugin_store_write_final_catalog "$CATALOG_DIR" "$PACKAGE_VERSION" "$MAVEN_PUBLIC_URL" "$RAW_STORE_URL" \
  || fail "unable to generate plugin store catalog"

fetch_existing_index() {
  relative_path=$1
  target="$CATALOG_DIR/existing-indexes/$relative_path"
  [ -n "$DRY_RUN" ] && return 0
  mkdir -p "$(dirname "$target")"
  status=$(curl --silent --show-error --output "$target" --write-out '%{http_code}' \
    "${RAW_STORE_URL%/}/$relative_path") || fail "unable to read existing index: $relative_path"
  case "$status" in
    200) ;;
    404) rm -f "$target" ;;
    *) fail "unexpected HTTP $status reading existing index: $relative_path" ;;
  esac
}

fetch_existing_index index.json
while IFS= read -r plugin_code; do
  fetch_existing_index "plugins/${plugin_code}/index.json"
done < "$CATALOG_DIR/plugin-indexes.txt"
plugin_store_write_indexes "$CATALOG_DIR" "$CATALOG_DIR/existing-indexes" \
  || fail "unable to merge existing plugin store indexes"

upload_file() {
  relative_path=$1
  file_path="$CATALOG_DIR/$relative_path"
  target_url="${RAW_STORE_URL%/}/$relative_path"
  if [ -n "$DRY_RUN" ]; then
    echo "[publish-plugin-store] dry-run upload $file_path -> $target_url"
    return 0
  fi
  curl --fail --silent --show-error --user "$NEXUS_USERNAME:$NEXUS_PASSWORD" \
    --upload-file "$file_path" "$target_url"
}

# A store entry becomes discoverable only after its descriptor and assets exist.
plugin_store_list_payload_files "$CATALOG_DIR" | while IFS= read -r relative_path; do
  upload_file "$relative_path"
done
plugin_store_list_plugin_indexes "$CATALOG_DIR" | while IFS= read -r relative_path; do
  upload_file "$relative_path"
done
upload_file index.json

echo "[publish-plugin-store] published Raw plugin store release $PACKAGE_VERSION to $RAW_STORE_URL"
