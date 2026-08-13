#!/usr/bin/env sh
set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$ROOT_DIR"
. "$ROOT_DIR/ci/lib/plugin-jar-selection.sh"

# A tag context always publishes/verifies the explicit release/plugins.txt
# selection; local runs without a tag keep the full all-module behavior.
if [ -n "${CI_COMMIT_TAG:-}" ] && ! plugin_release_only_enabled; then
  PLUGIN_RELEASE_ONLY=1
  export PLUGIN_RELEASE_ONLY
fi

fail() {
  echo "[publish-plugin-jars] $1" >&2
  exit 1
}

REPOSITORY_URL="${NEXUS_MAVEN_RELEASES_URL:-https://nexus.yudream.online/repository/maven-releases}"
PUBLIC_URL="${NEXUS_MAVEN_PUBLIC_URL:-https://nexus.yudream.online/repository/maven-public}"
PACKAGE_VERSION="${PLUGIN_PACKAGE_VERSION:-${CI_COMMIT_TAG:-}}"
DRY_RUN="${DRY_RUN:-}"
PACKAGE_VERSION=${PACKAGE_VERSION#v}

[ -n "$PACKAGE_VERSION" ] || fail "CI_COMMIT_TAG or PLUGIN_PACKAGE_VERSION is required"
if [ -z "$DRY_RUN" ]; then
  [ -n "${NEXUS_USERNAME:-}" ] || fail "NEXUS_USERNAME is required"
  [ -n "${NEXUS_PASSWORD:-}" ] || fail "NEXUS_PASSWORD is required"
fi

TMP_DIR=$(mktemp -d)
trap 'rm -rf "$TMP_DIR"' EXIT INT TERM
JAR_LIST="$TMP_DIR/jars.txt"
MANIFEST_PATH="$TMP_DIR/plugins.manifest.tsv"
CHECKSUM_PATH="$TMP_DIR/sha256sum.txt"
SETTINGS_FILE="$TMP_DIR/settings.xml"

write_final_plugin_jars "$ROOT_DIR" "$JAR_LIST" || fail "no plugin jars found under dist/plugins or yudream-plugins/*/target"
: > "$MANIFEST_PATH"
: > "$CHECKSUM_PATH"

resolve_artifact_id() {
  file_name=$(basename "$1")
  for module_dir in "$ROOT_DIR"/yudream-plugins/*; do
    [ -d "$module_dir" ] || continue
    artifact_id=$(basename "$module_dir")
    case "$file_name" in
      "$artifact_id"-*.jar) printf '%s\n' "$artifact_id"; return 0 ;;
    esac
  done
  return 1
}

cat > "$SETTINGS_FILE" <<'EOF'
<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0">
  <servers>
    <server>
      <id>nexus-releases</id>
      <username>${env.NEXUS_USERNAME}</username>
      <password>${env.NEXUS_PASSWORD}</password>
    </server>
    <server>
      <id>nexus-public</id>
      <username>${env.NEXUS_USERNAME}</username>
      <password>${env.NEXUS_PASSWORD}</password>
    </server>
  </servers>
  <profiles>
    <profile>
      <id>aliyun-plugins</id>
      <pluginRepositories>
        <pluginRepository>
          <id>aliyun-plugin</id>
          <url>https://maven.aliyun.com/repository/public</url>
        </pluginRepository>
        <pluginRepository>
          <id>nexus-plugin</id>
          <url>${env.NEXUS_MAVEN_PUBLIC_URL}</url>
        </pluginRepository>
      </pluginRepositories>
    </profile>
  </profiles>
  <activeProfiles>
    <activeProfile>aliyun-plugins</activeProfile>
  </activeProfiles>
</settings>
EOF

deploy_file() {
  file_path=$1
  artifact_id=$2
  artifact_version=$3
  packaging=$4
  shift 4
  if [ -n "$DRY_RUN" ]; then
    echo "[publish-plugin-jars] dry-run deploy online.yudream.plugins:${artifact_id}:${artifact_version}:${packaging}"
    return 0
  fi
  NEXUS_MAVEN_PUBLIC_URL="$PUBLIC_URL" mvn -s "$SETTINGS_FILE" -N org.apache.maven.plugins:maven-deploy-plugin:3.1.4:deploy-file \
    "-DrepositoryId=nexus-releases" "-Durl=$REPOSITORY_URL" \
    "-DgroupId=online.yudream.plugins" "-DartifactId=$artifact_id" \
    "-Dversion=$artifact_version" "-Dpackaging=$packaging" "-Dfile=$file_path" \
    -DgeneratePom=true "$@" -B -ntp
}

command -v unzip >/dev/null 2>&1 || fail "unzip is required to read per-plugin versions from plugin.yml"

# Each selected plugin deploys with its own plugin.yml version; the tag only
# marks this release event and versions the plugin-catalog manifest below.
while IFS= read -r jar_path; do
  artifact_id=$(resolve_artifact_id "$jar_path") || fail "unable to map plugin jar to artifactId: $jar_path"
  plugin_version=$(plugin_release_jar_version "$jar_path")
  [ -n "$plugin_version" ] || fail "unable to read plugin.yml version from selected jar: $jar_path"
  sha256=$(sha256sum "$jar_path" | awk '{print $1}')
  deployed_name="${artifact_id}-${plugin_version}.jar"
  printf '%s  %s\n' "$sha256" "$deployed_name" >> "$CHECKSUM_PATH"
  printf '%s\t%s\t%s\t%s\n' "$artifact_id" "$plugin_version" "$sha256" "$deployed_name" >> "$MANIFEST_PATH"
  deploy_file "$jar_path" "$artifact_id" "$plugin_version" jar
done < "$JAR_LIST"

# The manifest coordinate keeps the tag version; tags are unique per release event.
deploy_file "$MANIFEST_PATH" plugin-catalog "$PACKAGE_VERSION" tsv \
  "-Dfiles=$CHECKSUM_PATH" -Dclassifiers=sha256 -Dtypes=txt

echo "[publish-plugin-jars] published release $PACKAGE_VERSION with per-plugin versions to $REPOSITORY_URL"
