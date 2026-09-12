#!/usr/bin/env sh
set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$ROOT_DIR"

fail() {
  echo "[verify-plugin-publish-pipeline] $1" >&2
  exit 1
}

require_file() {
  file=$1
  [ -f "$file" ] || fail "missing required file: $file"
}

require_pattern() {
  pattern=$1
  message=$2
  grep -q "$pattern" .gitlab-ci.yml || fail "$message"
}

reject_pattern() {
  pattern=$1
  message=$2
  if grep -q "$pattern" .gitlab-ci.yml ci/publish-to-market.sh; then
    fail "$message"
  fi
}

echo "[verify-plugin-publish-pipeline] checking required verification scripts"
require_file "ci/verify-plugin-repo-independence.sh"
require_file "ci/verify-plugin-maven-boundary.sh"
require_file "ci/verify-core-maven-registry.sh"
require_file "ci/verify-core-npm-contracts.sh"
require_file "ci/verify-plugin-jar-assets.sh"
require_file "ci/verify-plugin-store-catalog.sh"
require_file "ci/verify-third-party-submission.sh"
require_file "ci/verify-third-party-submission-fixture.sh"
require_file "release/plugins.txt"
require_file "ci/verify-plugin-release-selection.sh"
require_file "ci/publish-to-market.sh"

echo "[verify-plugin-publish-pipeline] checking stage layout"
require_pattern '^[[:space:]]*-[[:space:]]\+validate$' "plugin CI must keep validate stage"
require_pattern '^[[:space:]]*-[[:space:]]\+build-frontend$' "plugin CI must keep build-frontend stage"
require_pattern '^[[:space:]]*-[[:space:]]\+package-plugin$' "plugin CI must keep package-plugin stage"
require_pattern '^[[:space:]]*-[[:space:]]\+publish-plugin$' "plugin CI must keep publish-plugin stage"
if grep -q '^[[:space:]]*-[[:space:]]\+verify-publish$' .gitlab-ci.yml; then
  fail "plugin CI must not keep a Nexus verify-publish stage"
fi

echo "[verify-plugin-publish-pipeline] checking validation jobs"
require_pattern '^validate:independence:$' "plugin CI must validate repository independence"
require_pattern '^validate:plugin-maven-boundary:$' "plugin CI must validate plugin Maven boundary"
require_pattern '^validate:core-maven-registry:$' "plugin CI must validate core Maven registry access"
require_pattern '^validate:core-npm-contracts:$' "plugin CI must validate core npm contracts"
require_pattern '^validate:docs:$' "plugin CI must validate documentation independence"
require_pattern '^validate:publish-pipeline:$' "plugin CI must validate its own publish pipeline shape"
require_pattern 'sh ci/verify-plugin-publish-pipeline.sh' "plugin CI must call ci/verify-plugin-publish-pipeline.sh"
require_pattern '^validate:plugin-store-catalog:$' "plugin CI must validate the plugin store catalog contract"
require_pattern 'sh ci/verify-plugin-store-catalog.sh' "plugin CI must run the plugin store catalog fixture"
require_pattern '^validate:plugin-release-selection:$' "plugin CI must validate the explicit release module selection"
require_pattern 'sh ci/verify-plugin-release-selection.sh' "plugin CI must run the release selection validator"
require_pattern '^validate:third-party-submission-fixture:$' "plugin CI must run third-party submission fixtures"
require_pattern 'sh ci/verify-third-party-submission-fixture.sh' "plugin CI must validate third-party submission fixtures"
require_pattern '^validate:third-party-submission:$' "plugin CI must validate submitted third-party materials"
require_pattern 'sh ci/verify-third-party-submission.sh' "third-party submission job must call the offline validator"
require_pattern 'library/python:3.12-alpine' "catalog/submission/store jobs must use a python-bundled image instead of per-job python installs"
require_pattern 'mirrors.aliyun.com/alpine' "python alpine jobs must rewrite apk repositories to a China-reachable mirror"
require_pattern 'apk add --no-cache curl unzip' "market publish job must add curl and unzip"
require_pattern 'apk add --no-cache unzip' "python-image validate jobs must add unzip"
require_pattern 'apt-get install -y -qq unzip' "tag package job must install unzip for the release-selection validator"
if grep -A12 '^validate:third-party-submission:' .gitlab-ci.yml | grep -Eq 'NEXUS_(USERNAME|PASSWORD)'; then
  fail "third-party submission validation must not receive Nexus write credentials"
fi

echo "[verify-plugin-publish-pipeline] checking package/publish chain"
require_pattern 'PLUGIN_RELEASE_ONLY=1' "tag pipelines must restrict packaging/publish to the release list"
require_pattern 'plugin_release_maven_pl_args' "tag packaging must derive the Maven -pl module list from the release selection"
require_pattern 'MAVEN_AM="-am"' "tag packaging must build selected modules with reactor dependencies via -am"
grep -q 'plugin_release_only_enabled' ci/lib/plugin-jar-selection.sh \
  || fail "plugin jar selection must support explicit release-only filtering"
grep -q 'not a stable SemVer' ci/verify-plugin-release-selection.sh \
  || fail "release selection validator must reject non-stable-SemVer plugin.yml versions"
grep -q 'plugin_release_jar_version' ci/verify-plugin-release-selection.sh \
  || fail "release selection validator must read per-plugin versions from plugin.yml"
require_pattern '^package:plugins:$' "plugin CI must keep package:plugins job"
require_pattern 'PACKAGE_MAVEN_REPO' "plugin CI package job must use a dedicated clean Maven local repository"
require_pattern 'sh ci/verify-plugin-jar-assets.sh' "plugin CI package job must verify plugin jar assets"
require_pattern 'copy_final_plugin_jars "\$PWD" "\$PWD/dist/plugins"' "plugin CI package job must flatten final plugin jars into dist/plugins"
require_pattern '^[[:space:]]*-[[:space:]]\+dist/plugins/\*\.jar$' "plugin CI package artifacts must expose flat dist/plugins jars"
if grep -E '^publish:plugin-jars:|^publish:plugin-store:|^verify:published-plugin-jars:|^verify:published-plugin-store:' .gitlab-ci.yml; then
  fail "plugin CI must not publish plugin jars or store catalogs to Nexus"
fi
grep -q 'plugins/\${plugin_code}/versions/\${plugin_version}\.json' ci/lib/plugin-store-catalog.sh \
  || fail "store descriptors must use plugins/{code}/versions/{version}.json"
if grep -q 'must equal release version' ci/lib/plugin-store-catalog.sh; then
  fail "plugin versions are independent of the tag; no tag equality checks may remain"
fi

echo "[verify-plugin-publish-pipeline] checking Nexus read-only package routing"
require_pattern 'NEXUS_MAVEN_PUBLIC_URL' "plugin CI must pull Maven artifacts through Nexus maven-public"
require_pattern 'NEXUS_NPM_PUBLIC_URL' "plugin CI must pull npm artifacts through Nexus npm-public"
if grep -Eq '^[^#]*NEXUS_(USERNAME|PASSWORD)' .gitlab-ci.yml ci/publish-to-market.sh; then
  fail "plugin CI must not use Nexus write credentials for market publication"
fi
if grep -Eq 'NEXUS_(USERNAME|PASSWORD)' ci/verify-core-maven-registry.sh ci/verify-core-npm-contracts.sh; then
  fail "plugin read paths must not require protected publish credentials"
fi
if grep -q '<mirrorOf>' .gitlab-ci.yml settings.xml.example; then
  fail "plugin builds must preserve explicit Aliyun-to-Nexus repository ordering"
fi
grep -q 'https://maven.aliyun.com/repository/public' .gitlab-ci.yml || fail "plugin builds must resolve third-party Maven dependencies from Aliyun"
grep -q '<id>nexus-plugin</id>' settings.xml.example || fail "plugin Maven plugins must fall back from Aliyun to Nexus"
grep -q '<id>nexus-plugin</id>' .gitlab-ci.yml || fail "plugin CI Maven plugins must fall back from Aliyun to Nexus"
grep -q '<id>nexus-plugin</id>' ci/verify-core-maven-registry.sh \
  || fail "ci/verify-core-maven-registry.sh Maven plugins must fall back from Aliyun to Nexus"
if grep -Eq 'maven-dependency-plugin[^[:space:]]*:get|dependency:get|remoteRepositories=' .gitlab-ci.yml; then
  fail "plugin CI must not prefetch Maven artifacts outside the configured repository order"
fi
grep -q 'remoteRepositories=nexus-public' ci/verify-core-maven-registry.sh || fail "SPI verification must explicitly resolve YuDream artifacts from Nexus"
grep -q 'yudream\.plugin\.spi\.version' ci/verify-core-maven-registry.sh || fail "core Maven verification must derive the SPI version from the plugin root POM"
if grep -q 'YUDREAM_PLUGIN_SPI_VERSION:-1.0-SNAPSHOT' ci/verify-core-maven-registry.sh; then
  fail "core Maven verification must not default to a hard-coded SPI snapshot"
fi
reject_pattern 'packages/generic' "plugin publishing must not use GitLab Generic Package Registry"
reject_pattern 'JOB-TOKEN:' "plugin publishing must not authenticate to a registry with GitLab job tokens"
if grep -R -Eq 'gitlab-maven|gitlab\.yudream\.online/api/v4/projects|CI_JOB_TOKEN|CORE_PACKAGE_(USER|TOKEN)|packages/(maven|npm)' \
  .gitlab-ci.yml .npmrc.example settings.xml.example \
  ci/publish-to-market.sh \
  ci/verify-core-maven-registry.sh ci/verify-core-npm-contracts.sh; then
  fail "plugin package routing must not use GitLab Package Registry"
fi

echo "[verify-plugin-publish-pipeline] checking publish rules"
if grep -Eq 'NEXUS_(USERNAME|PASSWORD)|publish-plugin|verify-published' ci/verify-third-party-submission.sh ci/verify-third-party-submission-fixture.sh; then
  fail "third-party validation scripts must stay offline and credential-free"
fi

echo "[verify-plugin-publish-pipeline] checking self-hosted market publication"
require_pattern '^publish:market:$' "plugin CI must keep publish:market job"
require_pattern 'sh ci/publish-to-market.sh' "plugin CI market job must upload selected jars via publish-to-market.sh"
require_pattern 'resource_group: yudream-plugin-market' "market publication must use a serial resource_group"
grep -q 'write_final_plugin_jars' ci/publish-to-market.sh \
  || fail "market publish must select jars through plugin-jar-selection"
grep -q 'YUDREAM_MARKET_URL' ci/publish-to-market.sh \
  || fail "market publish must require YUDREAM_MARKET_URL"
grep -q 'YUDREAM_MARKET_API_KEY' ci/publish-to-market.sh \
  || fail "market publish must require YUDREAM_MARKET_API_KEY"
grep -q 'X-API-Key' ci/publish-to-market.sh \
  || fail "market publish must authenticate with X-API-Key"
if grep -q 'example-plugin-\*\.jar' .gitlab-ci.yml ci/publish-to-market.sh; then
  fail "market publish must not hard-code example-plugin-*.jar"
fi
if grep -Eq 'NEXUS_(USERNAME|PASSWORD)' ci/publish-to-market.sh; then
  fail "self-hosted market publish must not use Nexus write credentials"
fi
market_job_block=$(awk '$0 == "publish:market:" { found=1 } found { print } found && NR > 1 && $0 ~ /^[^[:space:]][^:]*:$/ && $0 != "publish:market:" { exit }' .gitlab-ci.yml)
printf '%s\n' "$market_job_block" | grep -q 'export PLUGIN_RELEASE_ONLY=1' \
  || fail "publish:market must publish only the release/plugins.txt selection"
printf '%s\n' "$market_job_block" | grep -q 'CI_COMMIT_REF_PROTECTED == "true"' \
  || fail "publish:market must require a protected tag/ref"
printf '%s\n' "$market_job_block" | grep -q 'YUDREAM_MARKET_URL' \
  || fail "publish:market must stay unscheduled until YUDREAM_MARKET_URL is configured"

echo "[verify-plugin-publish-pipeline] OK"
