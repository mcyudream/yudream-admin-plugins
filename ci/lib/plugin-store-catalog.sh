#!/usr/bin/env sh

plugin_store_fail() {
  echo "[plugin-store-catalog] $1" >&2
  return 1
}

plugin_store_select_python() {
  if command -v python3 >/dev/null 2>&1 && python3 -c 'import sys' >/dev/null 2>&1; then
    PLUGIN_STORE_PYTHON=python3
  elif command -v python >/dev/null 2>&1 && python -c 'import sys' >/dev/null 2>&1; then
    PLUGIN_STORE_PYTHON=python
  else
    plugin_store_fail "python3 or python is required"
    return 1
  fi
  export PLUGIN_STORE_PYTHON
}

plugin_store_json_string() {
  value=$1
  value=$(printf '%s' "$value" | sed 's/\\/\\\\/g; s/"/\\"/g; s/	/\\t/g; s/\r/\\r/g')
  printf '"%s"' "$value"
}

plugin_store_jar_yaml_value() {
  key=$1
  jar_path=$2
  unzip -p "$jar_path" plugin.yml 2>/dev/null \
    | sed -n "s/^${key}:[[:space:]]*//p" \
    | head -n 1 \
    | sed 's/[[:space:]]*#.*$//; s/^"//; s/"$//; s/^'"'"'//; s/'"'"'$//'
}

plugin_store_resolve_module_dir() {
  jar_path=$1
  file_name=$(basename "$jar_path")

  for module_dir in "$ROOT_DIR"/yudream-plugins/*; do
    [ -d "$module_dir" ] || continue
    artifact_id=$(basename "$module_dir")
    case "$file_name" in
      "$artifact_id"-*.jar) printf '%s\n' "$module_dir"; return 0 ;;
    esac
  done
  plugin_store_fail "unable to map plugin jar to module: $jar_path"
}

plugin_store_jar_dependencies() {
  jar_path=$1
  plugin_version=$2
  "$PLUGIN_STORE_PYTHON" - "$jar_path" "$plugin_version" <<'PY'
import re
import subprocess
import sys

jar_path, version = sys.argv[1:]
try:
    text = subprocess.check_output(["unzip", "-p", jar_path, "plugin.yml"], stderr=subprocess.DEVNULL).decode("utf-8")
except (subprocess.CalledProcessError, UnicodeDecodeError):
    raise SystemExit(f"unable to read UTF-8 plugin.yml from JAR: {jar_path}")

sections = {"depend": True, "softdepend": False}
dependencies = []
seen = set()
current = None
for line in text.splitlines():
    match = re.fullmatch(r"(depend|softdepend):\s*(?:#.*)?", line)
    if match:
        current = match.group(1)
        continue
    if re.match(r"\S", line):
        current = None
        continue
    if current is None:
        continue
    match = re.fullmatch(r"\s+-\s+([^\s#]+)\s*(?:#.*)?", line)
    if not match:
        if line.strip() and not line.lstrip().startswith("#"):
            raise SystemExit(f"unsupported {current} declaration in JAR plugin.yml: {jar_path}")
        continue
    code = match.group(1)
    if code in seen:
        raise SystemExit(f"duplicate dependency code in JAR plugin.yml: {code}")
    seen.add(code)
    dependencies.append({"code": code, "range": f"^{version}", "required": sections[current]})

import json
print(json.dumps(dependencies, ensure_ascii=False, separators=(",", ":")))
PY
}

plugin_store_validate_dependencies_match() {
  expected_json=$1
  actual_json=$2
  "$PLUGIN_STORE_PYTHON" - "$expected_json" "$actual_json" <<'PY'
import json
import sys

expected, actual = (json.loads(value) for value in sys.argv[1:])
expected_by_code = {item["code"]: item["required"] for item in expected}
actual_by_code = {item["code"]: item["required"] for item in actual}
if expected_by_code != actual_by_code:
    raise SystemExit("store.json dependencies must match JAR plugin.yml depend/softdepend code and required semantics")
PY
}

plugin_store_merge_compatibility() {
  override_json=$1
  "$PLUGIN_STORE_PYTHON" - "$override_json" <<'PY'
import json
import sys

compatibility = {"host": "^1.0.0", "spi": "^2.6.0", "frontendSdk": "^1.0.1"}
compatibility.update(json.loads(sys.argv[1]))
print(json.dumps(compatibility, ensure_ascii=False, separators=(",", ":")))
PY
}

plugin_store_store_json_fields() {
  store_file=$1
  output_kind=$2
  "$PLUGIN_STORE_PYTHON" - "$store_file" "$output_kind" <<'PY'
import json
import re
import sys

path, output_kind = sys.argv[1:]
SEMVER = r"(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)"
WILDCARD = r"(?:[xX]|(?:0|[1-9]\d*)\.[xX]|(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)\.[xX])"
RANGE = re.compile(
    rf"(?:{SEMVER}|[\^~]{SEMVER}|{WILDCARD}|>={SEMVER} <{SEMVER})$"
)

def fail(message):
    raise SystemExit(f"invalid store.json {path}: {message}")

def require_range(value, field):
    if not isinstance(value, str) or not RANGE.fullmatch(value):
        fail(f"{field} must use supported stable SemVer range syntax")

SPDX_LICENSES = {
    "Apache-2.0", "MIT", "BSD-2-Clause", "BSD-3-Clause", "ISC", "MPL-2.0", "EPL-2.0",
    "GPL-2.0-only", "GPL-2.0-or-later", "GPL-3.0-only", "GPL-3.0-or-later",
    "LGPL-2.1-only", "LGPL-2.1-or-later", "LGPL-3.0-only", "LGPL-3.0-or-later",
    "AGPL-3.0-only", "AGPL-3.0-or-later", "Unlicense", "CC0-1.0",
}
STRING_LIMITS = {"license": 64, "releaseNotes": 4000, "source.repository": 2048, "source.commit": 40}

def require_text(value, field):
    if not isinstance(value, str) or not value or len(value) > STRING_LIMITS[field] or any(ord(char) < 32 or ord(char) == 127 for char in value):
        fail(f"{field} must be a non-empty bounded string without control characters")

def require_https_url(value, field):
    require_text(value, field)
    from urllib.parse import urlsplit
    parsed = urlsplit(value)
    if parsed.scheme != "https" or not parsed.netloc or parsed.username or parsed.password or parsed.fragment:
        fail(f"{field} must be an HTTPS URL without userinfo or fragment")

def require_source(value):
    if not isinstance(value, dict) or set(value) != {"repository", "commit"}:
        fail("source must contain exactly repository, commit")
    require_https_url(value["repository"], "source.repository")
    require_text(value["commit"], "source.commit")
    if not re.fullmatch(r"[0-9a-f]{40}", value["commit"]):
        fail("source.commit must be 40 lowercase hexadecimal characters")

try:
    with open(path, encoding="utf-8") as handle:
        value = json.load(handle)
except (OSError, json.JSONDecodeError) as error:
    raise SystemExit(f"invalid store.json {path}: {error}")
if not isinstance(value, dict):
    fail("must be an object")
unknown = set(value) - {"icon", "screenshots", "compatibility", "dependencies", "license", "source", "releaseNotes"}
if unknown:
    fail("contains unsupported field(s): " + ", ".join(sorted(unknown)))
if "license" in value:
    require_text(value["license"], "license")
    if value["license"] not in SPDX_LICENSES:
        fail("license must be a supported SPDX identifier")
if "source" in value:
    require_source(value["source"])
if "releaseNotes" in value:
    require_text(value["releaseNotes"], "releaseNotes")
if "icon" in value:
    icon = value["icon"]
    if not isinstance(icon, str) or not icon:
        fail("icon must be a non-empty local string path")
if "screenshots" in value:
    screenshots = value["screenshots"]
    if not isinstance(screenshots, list) or any(not isinstance(item, str) or not item for item in screenshots):
        fail("screenshots must be an array of non-empty local string paths")
compatibility = value.get("compatibility")
if compatibility is not None:
    if not isinstance(compatibility, dict):
        fail("compatibility must be an object")
    unknown = set(compatibility) - {"host", "spi", "frontendSdk"}
    if unknown:
        fail("compatibility contains unsupported field(s): " + ", ".join(sorted(unknown)))
    for key, range_value in compatibility.items():
        require_range(range_value, f"compatibility.{key}")
dependencies = value.get("dependencies")
if dependencies is not None:
    if not isinstance(dependencies, list):
        fail("dependencies must be an array")
    codes = set()
    for index, dependency in enumerate(dependencies):
        if not isinstance(dependency, dict) or set(dependency) != {"code", "range", "required"}:
            fail(f"dependencies[{index}] must contain exactly code, range, required")
        code = dependency["code"]
        if not isinstance(code, str) or not code.strip():
            fail(f"dependencies[{index}].code must be a non-empty string")
        if code in codes:
            fail(f"dependencies contains duplicate code: {code}")
        codes.add(code)
        require_range(dependency["range"], f"dependencies[{index}].range")
        if not isinstance(dependency["required"], bool):
            fail(f"dependencies[{index}].required must be a boolean")
if output_kind == "resources":
    if "icon" in value:
        print("icon\t" + value["icon"])
    for screenshot in value.get("screenshots", []):
        print("screenshot\t" + screenshot)
elif output_kind == "metadata":
    for key in ("compatibility", "dependencies", "license", "source", "releaseNotes"):
        if key in value:
            print(key + "\t" + json.dumps(value[key], ensure_ascii=False, separators=(",", ":")))
else:
    raise SystemExit(f"unsupported store.json output kind: {output_kind}")
PY
}

plugin_store_store_json_resources() {
  plugin_store_store_json_fields "$1" resources
}

plugin_store_store_json_metadata() {
  plugin_store_store_json_fields "$1" metadata
}

plugin_store_copy_resource() {
  module_dir=$1
  output_dir=$2
  plugin_code=$3
  source_path=$4
  resource_type=$5
  resource_records=$6

  case "$source_path" in
    ''|/*|*..*|*\\*|*'//'*) plugin_store_fail "unsafe $resource_type path in store.json for $plugin_code: $source_path" || return 1 ;;
  esac
  source_root="$module_dir/src/main/resources"
  source_file="$source_root/$source_path"
  resource_component_path=$source_root
  old_ifs=$IFS
  IFS=/
  for resource_component in $source_path; do
    resource_component_path="$resource_component_path/$resource_component"
    [ ! -L "$resource_component_path" ] \
      || plugin_store_fail "symbolic-link $resource_type resource is not allowed for $plugin_code: $source_path" || { IFS=$old_ifs; return 1; }
  done
  IFS=$old_ifs
  [ -f "$source_file" ] \
    || plugin_store_fail "missing or non-regular $resource_type resource for $plugin_code: $source_path" || return 1
  source_root_real=$(CDPATH= cd -- "$source_root" && pwd -P) || return 1
  source_file_real=$(CDPATH= cd -- "$(dirname "$source_file")" && pwd -P)/$(basename "$source_file") || return 1
  case "$source_file_real" in
    "$source_root_real"/*) ;;
    *) plugin_store_fail "escaped $resource_type resource root for $plugin_code: $source_path" || return 1 ;;
  esac

  raw_path="plugins/${plugin_code}/assets/${source_path}"
  destination="$output_dir/$raw_path"
  mkdir -p "$(dirname "$destination")"
  cp "$source_file" "$destination"
  printf '%s\t%s\t%s\n' "$plugin_code" "$resource_type" "$raw_path" >> "$resource_records"
  printf '%s\n' "$raw_path"
}

plugin_store_write_catalog() {
  output_dir=$1
  maven_public_url=$2
  raw_store_url=$3
  jar_list=$4
  records_file="$output_dir/records.tsv"
  resource_records="$output_dir/resources.tsv"

  plugin_store_select_python || return 1
  mkdir -p "$output_dir/plugins"
  : > "$records_file"
  : > "$resource_records"

  while IFS= read -r jar_path; do
    module_dir=$(plugin_store_resolve_module_dir "$jar_path") || return 1
    plugin_code=$(plugin_store_jar_yaml_value name "$jar_path")
    plugin_version=$(plugin_store_jar_yaml_value version "$jar_path")
    plugin_main=$(plugin_store_jar_yaml_value main "$jar_path")
    display_name=$(plugin_store_jar_yaml_value displayName "$jar_path")
    description=$(plugin_store_jar_yaml_value description "$jar_path")
    [ -n "$plugin_code" ] || plugin_store_fail "JAR plugin.yml name is required: $jar_path" || return 1
    [ -n "$plugin_version" ] || plugin_store_fail "JAR plugin.yml version is required: $jar_path" || return 1
    [ -n "$plugin_main" ] || plugin_store_fail "JAR plugin.yml main is required: $jar_path" || return 1
    # Plugin versions are independent of the release tag: the descriptor
    # releaseVersion and Maven coordinates always use this plugin.yml version.

    icon_path=
    license_json=
    source_json=
    release_notes_json=
    compatibility_json='{"host":"^1.0.0","spi":"^2.6.0","frontendSdk":"^1.0.1"}'
    jar_dependencies_json=$(plugin_store_jar_dependencies "$jar_path" "$plugin_version") || return 1
    dependencies_json=$jar_dependencies_json
    screenshots_file="$output_dir/screenshots-${plugin_code}.txt"
    : > "$screenshots_file"
    store_file="$module_dir/src/main/resources/store.json"
    if [ -f "$store_file" ]; then
      plugin_store_store_json_resources "$store_file" | tr -d '\r' | while IFS="$(printf '\t')" read -r resource_type source_path; do
        resource_path=$(plugin_store_copy_resource "$module_dir" "$output_dir" "$plugin_code" "$source_path" "$resource_type" "$resource_records") || exit 1
        printf '%s\t%s\n' "$resource_type" "$resource_path"
      done > "$output_dir/resources-${plugin_code}.txt" || return 1
      while IFS="$(printf '\t')" read -r resource_type resource_path; do
        case "$resource_type" in
          icon) icon_path=$resource_path ;;
          screenshot) printf '%s\n' "$resource_path" >> "$screenshots_file" ;;
        esac
      done < "$output_dir/resources-${plugin_code}.txt"
      while IFS="$(printf '\t')" read -r metadata_type metadata_json; do
        case "$metadata_type" in
          compatibility) compatibility_json=$(plugin_store_merge_compatibility "$metadata_json") || return 1 ;;
          dependencies)
            plugin_store_validate_dependencies_match "$jar_dependencies_json" "$metadata_json" || return 1
            dependencies_json=$metadata_json
            ;;
          license) license_json=$metadata_json ;;
          source) source_json=$metadata_json ;;
          releaseNotes) release_notes_json=$metadata_json ;;
        esac
      done <<EOF
$(plugin_store_store_json_metadata "$store_file" | tr -d '\r')
EOF
    fi

    artifact_id=$(basename "$module_dir")
    jar_sha256=$(sha256sum "$jar_path" | awk '{print $1}')
    jar_file_name="${artifact_id}-${plugin_version}.jar"
    maven_path="online/yudream/plugins/${artifact_id}/${plugin_version}/${jar_file_name}"
    descriptor_path="plugins/${plugin_code}/versions/${plugin_version}.json"
    descriptor_file="$output_dir/$descriptor_path"
    mkdir -p "$(dirname "$descriptor_file")"
    {
      printf '{\n  "schemaVersion": 1,\n  "plugin": {\n    "code": '; plugin_store_json_string "$plugin_code"
      printf ',\n    "version": '; plugin_store_json_string "$plugin_version"
      printf ',\n    "main": '; plugin_store_json_string "$plugin_main"
      [ -z "$display_name" ] || { printf ',\n    "displayName": '; plugin_store_json_string "$display_name"; }
      [ -z "$description" ] || { printf ',\n    "description": '; plugin_store_json_string "$description"; }
      printf ',\n    "publisher": {"id":"yudream","name":"YuDream","url":"https://yudream.online","verified":true}'
      [ -z "$license_json" ] || printf ',\n    "license": %s' "$license_json"
      [ -z "$source_json" ] || printf ',\n    "source": %s' "$source_json"
      [ -z "$release_notes_json" ] || printf ',\n    "releaseNotes": %s' "$release_notes_json"
      [ -z "$icon_path" ] || { printf ',\n    "icon": '; plugin_store_json_string "$icon_path"; }
      if [ -s "$screenshots_file" ]; then
        printf ',\n    "screenshots": ['
        first=true
        while IFS= read -r screenshot_path; do
          [ "$first" = true ] || printf ', '
          first=false
          plugin_store_json_string "$screenshot_path"
        done < "$screenshots_file"
        printf ']'
      fi
      [ -z "$compatibility_json" ] || printf ',\n    "compatibility": %s' "$compatibility_json"
      [ -z "$dependencies_json" ] || printf ',\n    "dependencies": %s' "$dependencies_json"
      printf '\n  }'
      printf ',\n  "releaseVersion": '; plugin_store_json_string "$plugin_version"
      printf ',\n  "jar": {\n    "mavenCoordinates": '; plugin_store_json_string "online.yudream.plugins:${artifact_id}:${plugin_version}:jar"
      printf ',\n    "url": '; plugin_store_json_string "${maven_public_url%/}/${maven_path}"
      printf ',\n    "sha256": '; plugin_store_json_string "$jar_sha256"
      printf '\n  }\n}\n'
    } > "$descriptor_file"
    printf '%s\t%s\t%s\t%s\t%s\n' "$plugin_code" "$plugin_version" "$descriptor_path" "$jar_sha256" "$maven_path" >> "$records_file"
  done < "$jar_list"

  sort -o "$records_file" "$records_file"
  cut -f 1 "$records_file" | uniq > "$output_dir/plugin-indexes.txt"
  plugin_store_write_indexes "$output_dir" "$output_dir/existing-indexes"
}

plugin_store_write_indexes() {
  output_dir=$1
  existing_dir=$2
  records_file="$output_dir/records.tsv"
  plugin_indexes="$output_dir/plugin-indexes.txt"

  "$PLUGIN_STORE_PYTHON" - "$output_dir" "$existing_dir" "$records_file" "$plugin_indexes" <<'PY'
import json
import os
import sys

output_dir, existing_dir, records_file, plugin_indexes_file = sys.argv[1:]
def load(path, empty):
    if not os.path.exists(path):
        return empty
    try:
        with open(path, encoding="utf-8") as handle:
            return json.load(handle)
    except (OSError, json.JSONDecodeError) as error:
        raise SystemExit(f"invalid existing index {path}: {error}")
def dump(path, value):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as handle:
        json.dump(value, handle, ensure_ascii=False, indent=2)
        handle.write("\n")

records = []
with open(records_file, encoding="utf-8") as handle:
    for line in handle:
        code, version, descriptor, _sha256, _maven = line.rstrip("\n").split("\t")
        records.append((code, version, descriptor))
affected = [line.strip() for line in open(plugin_indexes_file, encoding="utf-8") if line.strip()]
existing_root = load(os.path.join(existing_dir, "index.json"), {"schemaVersion": 1, "plugins": []})
if not isinstance(existing_root, dict) or not isinstance(existing_root.get("plugins", []), list):
    raise SystemExit("existing root index has invalid schema")
root_plugins = {}
for entry in existing_root["plugins"]:
    if not isinstance(entry, dict) or not isinstance(entry.get("code"), str) or not isinstance(entry.get("index"), str):
        raise SystemExit("existing root index has invalid plugin entry")
    root_plugins[entry["code"]] = entry
for code in affected:
    existing = load(os.path.join(existing_dir, "plugins", code, "index.json"), {"schemaVersion": 1, "pluginCode": code, "versions": []})
    if not isinstance(existing, dict) or existing.get("pluginCode", code) != code or not isinstance(existing.get("versions", []), list):
        raise SystemExit(f"existing plugin index has invalid schema: {code}")
    versions = {}
    for entry in existing["versions"]:
        if not isinstance(entry, dict) or not isinstance(entry.get("releaseVersion"), str) or not isinstance(entry.get("descriptor"), str):
            raise SystemExit(f"existing plugin index has invalid version entry: {code}")
        versions[entry["releaseVersion"]] = entry
    for record_code, version, descriptor in records:
        if record_code == code:
            versions[version] = {"releaseVersion": version, "descriptor": descriptor}
    dump(os.path.join(output_dir, "plugins", code, "index.json"), {
        "schemaVersion": 1,
        "pluginCode": code,
        "versions": [versions[version] for version in sorted(versions)],
    })
    root_plugins[code] = {"code": code, "index": f"plugins/{code}/index.json"}
dump(os.path.join(output_dir, "index.json"), {
    "schemaVersion": 1,
    "plugins": [root_plugins[code] for code in sorted(root_plugins)],
})
PY
}

plugin_store_write_final_catalog() {
  output_dir=$1
  maven_public_url=$2
  raw_store_url=$3
  jar_list="$output_dir/jars.txt"
  mkdir -p "$output_dir"
  write_final_plugin_jars "$ROOT_DIR" "$jar_list" || plugin_store_fail "no plugin jars found under dist/plugins or yudream-plugins/*/target" || return 1
  plugin_store_write_catalog "$output_dir" "$maven_public_url" "$raw_store_url" "$jar_list"
}

plugin_store_list_files() {
  catalog_dir=$1
  find "$catalog_dir" -type f ! -name 'records.tsv' ! -name 'resources.tsv' ! -name 'jars.txt' ! -name 'plugin-indexes.txt' ! -name 'screenshots-*.txt' ! -name 'resources-*.txt' ! -path '*/existing-indexes/*' | sed "s#^$catalog_dir/##" | sort
}

plugin_store_list_payload_files() {
  catalog_dir=$1
  plugin_store_list_files "$catalog_dir" | grep -vE '(^index\.json$|^plugins/[^/]+/index\.json$)'
}

plugin_store_list_plugin_indexes() {
  catalog_dir=$1
  plugin_store_list_files "$catalog_dir" | grep -E '^plugins/[^/]+/index\.json$'
}
