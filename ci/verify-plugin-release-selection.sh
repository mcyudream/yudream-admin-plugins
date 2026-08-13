#!/usr/bin/env sh
set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$ROOT_DIR"
. "$ROOT_DIR/ci/lib/plugin-jar-selection.sh"

fail() {
  echo "[verify-plugin-release-selection] $1" >&2
  exit 1
}

PACKAGE_VERSION="${PLUGIN_PACKAGE_VERSION:-${CI_COMMIT_TAG:-}}"
PACKAGE_VERSION=${PACKAGE_VERSION#v}

FIXTURE_DIR=$(mktemp -d)
JAR_LIST="$FIXTURE_DIR/jars.txt"
trap 'rm -rf "$FIXTURE_DIR"' EXIT INT TERM

echo "[verify-plugin-release-selection] validating checked-in release/plugins.txt"
modules=$(plugin_release_selected_modules "$ROOT_DIR") \
  || fail "release/plugins.txt is empty, duplicated, or lists non-reactor modules"
module_count=$(printf '%s\n' "$modules" | wc -l | tr -d ' ')
known_count=$(plugin_release_known_modules "$ROOT_DIR" | wc -l | tr -d ' ')
echo "[verify-plugin-release-selection] release list selects $module_count of $known_count reactor plugin modules"
printf '%s\n' "$modules" | sed 's/^/[verify-plugin-release-selection]   /'

echo "[verify-plugin-release-selection] checking selection fixtures"
# Fixture roots must exercise their own release/plugins.txt, never the
# caller's PLUGIN_RELEASE_MODULES/PLUGIN_RELEASE_ONLY environment.
(
unset PLUGIN_RELEASE_MODULES PLUGIN_RELEASE_ONLY

write_fixture_root() {
  fixture=$1
  mkdir -p "$fixture/release"
  cat > "$fixture/pom.xml" <<'EOF'
<project>
  <modules>
    <module>yudream-plugins/mod-a</module>
    <module>yudream-plugins/mod-b</module>
  </modules>
</project>
EOF
}

expect_reject() {
  description=$1
  fixture=$2
  if plugin_release_selected_modules "$fixture" >/dev/null 2>&1; then
    fail "fixture should have been rejected: $description"
  fi
  echo "[verify-plugin-release-selection] rejected fixture: $description"
}

valid_fixture="$FIXTURE_DIR/valid"
write_fixture_root "$valid_fixture"
printf 'mod-a\n# comment\n\nmod-b\n' > "$valid_fixture/release/plugins.txt"
fixture_modules=$(plugin_release_selected_modules "$valid_fixture") \
  || fail "valid fixture selection was rejected"
[ "$fixture_modules" = "$(printf 'mod-a\nmod-b')" ] \
  || fail "valid fixture selection mismatch: $fixture_modules"

duplicate_fixture="$FIXTURE_DIR/duplicate"
write_fixture_root "$duplicate_fixture"
printf 'mod-a\nmod-a\n' > "$duplicate_fixture/release/plugins.txt"
expect_reject "duplicate module entries" "$duplicate_fixture"

unknown_fixture="$FIXTURE_DIR/unknown"
write_fixture_root "$unknown_fixture"
printf 'mod-a\nmod-c\n' > "$unknown_fixture/release/plugins.txt"
expect_reject "module outside the root reactor" "$unknown_fixture"

empty_fixture="$FIXTURE_DIR/empty"
write_fixture_root "$empty_fixture"
printf '# no modules\n\n' > "$empty_fixture/release/plugins.txt"
expect_reject "empty selection" "$empty_fixture"

missing_fixture="$FIXTURE_DIR/missing"
write_fixture_root "$missing_fixture"
rm -rf "$missing_fixture/release"
expect_reject "missing release/plugins.txt" "$missing_fixture"

echo "[verify-plugin-release-selection] checking PLUGIN_RELEASE_MODULES override"
override_modules=$(PLUGIN_RELEASE_MODULES='mod-b, mod-a' plugin_release_selected_modules "$valid_fixture") \
  || fail "valid comma-separated override was rejected"
[ "$override_modules" = "$(printf 'mod-b\nmod-a')" ] \
  || fail "override selection mismatch: $override_modules"
if PLUGIN_RELEASE_MODULES='mod-a mod-a' plugin_release_selected_modules "$valid_fixture" >/dev/null 2>&1; then
  fail "duplicate override entries should be rejected"
fi
if PLUGIN_RELEASE_MODULES='mod-c' plugin_release_selected_modules "$valid_fixture" >/dev/null 2>&1; then
  fail "override module outside the reactor should be rejected"
fi
if PLUGIN_RELEASE_MODULES='  ' plugin_release_selected_modules "$valid_fixture" >/dev/null 2>&1; then
  fail "blank override should be rejected"
fi
echo "[verify-plugin-release-selection] override validation OK"
)

maven_args=$(plugin_release_maven_pl_args "$ROOT_DIR") || fail "unable to build Maven -pl arguments"
case "$maven_args" in
  '-pl :'*) ;;
  *) fail "unexpected Maven selection arguments: $maven_args" ;;
esac
echo "[verify-plugin-release-selection] Maven reactor arguments: $maven_args -am"

# Plugin versions are independent of the release tag. When a release event is
# selected (CI_COMMIT_TAG/PLUGIN_PACKAGE_VERSION), every selected module must
# have a final jar whose plugin.yml version is a valid stable SemVer; without
# a release event, jar checks run best-effort and the list validation stands.
if ! plugin_release_only_enabled; then
  PLUGIN_RELEASE_ONLY=1
  export PLUGIN_RELEASE_ONLY
fi

if ! write_final_plugin_jars "$ROOT_DIR" "$JAR_LIST" 2>/dev/null; then
  if [ -n "$PACKAGE_VERSION" ]; then
    write_final_plugin_jars "$ROOT_DIR" "$JAR_LIST" \
      || fail "no selected plugin jars available for release event $PACKAGE_VERSION"
  fi
  echo "[verify-plugin-release-selection] selected jars absent or incomplete locally; list-only validation OK"
  exit 0
fi
command -v unzip >/dev/null 2>&1 || fail "unzip is required to read plugin.yml from selected jars"

if [ -n "$PACKAGE_VERSION" ]; then
  echo "[verify-plugin-release-selection] release event $PACKAGE_VERSION; validating per-plugin stable SemVer versions"
else
  echo "[verify-plugin-release-selection] validating per-plugin stable SemVer versions"
fi
while IFS= read -r jar_path; do
  jar_version=$(plugin_release_jar_version "$jar_path")
  [ -n "$jar_version" ] || fail "unable to read plugin.yml version from selected jar: $jar_path"
  printf '%s' "$jar_version" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+$' \
    || fail "plugin.yml version in $(basename "$jar_path") is not a stable SemVer (prerelease/build metadata are not allowed): $jar_version"
  echo "[verify-plugin-release-selection]   $(basename "$jar_path") plugin.yml version $jar_version"
done < "$JAR_LIST"

echo "[verify-plugin-release-selection] OK"
