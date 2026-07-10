#!/usr/bin/env sh
set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$ROOT_DIR"

failed=0

report_matches() {
  label=$1
  pattern=$2
  shift 2
  matches=$(rg -n --no-messages "$pattern" "$@" 2>/dev/null || true)
  if [ -n "$matches" ]; then
    echo "[verify-plugin-development-conformance] ${label}" >&2
    echo "$matches" >&2
    failed=1
  fi
}

echo "[verify-plugin-development-conformance] checking shared frontend UI contracts"
for package_json in yudream-frontend/packages/plugin-*/package.json; do
  [ -f "$package_json" ] || continue
  if ! grep -q '"@yudream/components"' "$package_json"; then
    echo "[verify-plugin-development-conformance] missing @yudream/components: $package_json" >&2
    failed=1
  fi
done

report_matches \
  "management/tabular pages must use FaTable instead of raw HTML tables" \
  '<table([[:space:]>])' \
  yudream-frontend/packages -g '*.vue'

report_matches \
  "plugin forms must use @yudream/components instead of raw HTML controls" \
  '<(input|select|textarea)([[:space:]>])' \
  yudream-frontend/packages -g '*.vue'

report_matches \
  "prefer Fa components when @yudream/components provides an equivalent" \
  '<a-(table|button|input|input-number|select|textarea|radio|radio-group|checkbox|modal|pagination)([[:space:]>])' \
  yudream-frontend/packages -g '*.vue'

table_pages=$(rg -l '<FaTable([[:space:]>])' yudream-frontend/packages -g '*.vue' 2>/dev/null || true)
for table_page in $table_pages; do
  if ! grep -q 'table-root-class="[^"]*rounded-lg[^"]*overflow-hidden' "$table_page"; then
    echo "[verify-plugin-development-conformance] FaTable must use the framework management-table container: $table_page" >&2
    failed=1
  fi
done

pagination_pages=$(rg -l '<FaPagination([[:space:]>])' yudream-frontend/packages -g '*.vue' 2>/dev/null || true)
for pagination_page in $pagination_pages; do
  pagination_tags=$(sed -n '/<FaPagination/,/\/>/p' "$pagination_page")
  if ! printf '%s\n' "$pagination_tags" | grep -q 'class="[^"]*mt-3'; then
    echo "[verify-plugin-development-conformance] FaPagination must be separated from its table with mt-3: $pagination_page" >&2
    failed=1
  fi
done

report_matches \
  "personal HTTP flows must not gain cross-user scope from management permission" \
  'hasPermission\([^)]*MANAGE_PERMISSION' \
  yudream-plugins -g '*HttpFacade.java'

report_matches \
  "personal frontend flows must not switch datasets based on management permission" \
  'v-if="[^"]*canManage|canManage\.value[[:space:]]*\?[[:space:]]*load|if[[:space:]]*\(canManage\.value\)' \
  yudream-frontend/packages -g '*.ts' -g '*.vue'

report_matches \
  "personal API paths must use /me instead of /my" \
  'path[[:space:]]*=[[:space:]]*"/my|`/my/' \
  yudream-plugins yudream-frontend/packages -g '*.java' -g '*.ts'

if [ "$failed" -ne 0 ]; then
  echo "[verify-plugin-development-conformance] FAILED" >&2
  exit 1
fi

echo "[verify-plugin-development-conformance] OK"
