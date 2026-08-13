#!/usr/bin/env sh
set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
SUBMISSION_DIR=${SUBMISSION_DIR:-"$ROOT_DIR/submissions/third-party"}

fail() {
  echo "[verify-third-party-submission] $1" >&2
  exit 1
}

case "$SUBMISSION_DIR" in
  "$ROOT_DIR"/submissions/third-party|"$ROOT_DIR"/submissions/third-party/*) ;;
  *) fail "SUBMISSION_DIR must remain under submissions/third-party" ;;
esac

[ -d "$SUBMISSION_DIR" ] || fail "missing submission directory: $SUBMISSION_DIR"

if command -v python3 >/dev/null 2>&1 && python3 -c 'import sys' >/dev/null 2>&1; then
  PYTHON=python3
elif command -v python >/dev/null 2>&1 && python -c 'import sys' >/dev/null 2>&1; then
  PYTHON=python
else
  fail "python3 or python is required"
fi

"$PYTHON" - "$SUBMISSION_DIR" <<'PY'
import hashlib
import json
import os
import re
import sys
import zipfile
from urllib.parse import urlsplit

root = os.path.realpath(sys.argv[1])
SEMVER = re.compile(r"(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)$")
RANGE = re.compile(r"(?:(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)|[\^~](?:0|[1-9]\d*)\.(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)|(?:0|[1-9]\d*)\.[xX]|(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)\.[xX]|>=(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)\.(?:0|[1-9]\d*) <(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)\.(?:0|[1-9]\d*))$")
CODE = re.compile(r"[a-z0-9]+(?:-[a-z0-9]+)*$")
MAIN = re.compile(r"[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)+$")


def fail(message):
    raise SystemExit(f"[verify-third-party-submission] {message}")


SPDX_LICENSES = {
    "Apache-2.0", "MIT", "BSD-2-Clause", "BSD-3-Clause", "ISC", "MPL-2.0", "EPL-2.0",
    "GPL-2.0-only", "GPL-2.0-or-later", "GPL-3.0-only", "GPL-3.0-or-later",
    "LGPL-2.1-only", "LGPL-2.1-or-later", "LGPL-3.0-only", "LGPL-3.0-or-later",
    "AGPL-3.0-only", "AGPL-3.0-or-later", "Unlicense", "CC0-1.0",
}
STRING_LIMITS = {"licenseId": 64, "author.id": 128, "author.name": 256, "author.url": 2048, "source.repository": 2048, "source.commit": 40, "releaseNotes": 4000}


def require_text(value, field):
    if not isinstance(value, str) or not value or len(value) > STRING_LIMITS[field] or any(ord(char) < 32 or ord(char) == 127 for char in value):
        fail(f"{field} must be a non-empty bounded string without control characters")


def require_https_url(value, field):
    require_text(value, field)
    parsed = urlsplit(value)
    if parsed.scheme != "https" or not parsed.netloc or parsed.username or parsed.password or parsed.fragment:
        fail(f"{field} must be an HTTPS URL without userinfo or fragment")


def validate_author(value):
    if not isinstance(value, dict) or set(value) != {"id", "name", "url"}:
        fail("author must contain exactly id, name, url")
    for field in value:
        require_text(value[field], f"author.{field}")
    require_https_url(value["url"], "author.url")


def validate_source(value):
    if not isinstance(value, dict) or set(value) != {"repository", "commit"}:
        fail("source must contain exactly repository, commit")
    require_https_url(value["repository"], "source.repository")
    require_text(value["commit"], "source.commit")
    if not re.fullmatch(r"[0-9a-f]{40}", value["commit"]):
        fail("source.commit must be 40 lowercase hexadecimal characters")


def safe_file(relative, label):
    if not isinstance(relative, str) or not relative or "\\" in relative or relative.startswith("/"):
        fail(f"unsafe {label} path")
    normalized = os.path.normpath(relative)
    if normalized in (".", "..") or normalized.startswith(".." + os.sep) or os.path.isabs(normalized):
        fail(f"unsafe {label} path")
    path = os.path.join(root, normalized)
    try:
        escaped = os.path.commonpath((root, os.path.realpath(path))) != root
    except ValueError:
        escaped = True
    if os.path.islink(path) or not os.path.isfile(path) or escaped:
        fail(f"missing, symbolic-link, or escaped {label}: {relative}")
    return path


def load_json(relative, label):
    try:
        with open(safe_file(relative, label), encoding="utf-8") as handle:
            value = json.load(handle)
    except (OSError, json.JSONDecodeError) as error:
        fail(f"invalid {label}: {error}")
    if not isinstance(value, dict):
        fail(f"{label} must be an object")
    return value


def scalar(value, field, source):
    if not value or value.startswith(("|", ">", "&", "*", "!", "[", "{")):
        fail(f"unsupported {field} declaration in {source}")
    if (value[0:1], value[-1:]) in (("'", "'"), ('"', '"')):
        value = value[1:-1]
    if not value or "\t" in value or "\x00" in value:
        fail(f"invalid {field} declaration in {source}")
    return value


def parse_plugin_yml(text, source):
    values, dependencies, current = {}, {"depend": [], "softdepend": []}, None
    allowed = {"name", "version", "main", "displayName", "description", "depend", "softdepend"}
    for raw in text.splitlines():
        if not raw.strip() or raw.lstrip().startswith("#"):
            continue
        if raw[0].isspace():
            match = re.fullmatch(r"\s+-\s+([^\s#]+)\s*(?:#.*)?", raw)
            if current and match:
                dependencies[current].append(match.group(1))
                continue
            fail(f"unsupported YAML list declaration in {source}")
        current = None
        match = re.fullmatch(r"([A-Za-z][A-Za-z0-9]*):\s*(.*?)\s*(?:#.*)?", raw)
        if not match:
            fail(f"unsupported YAML declaration in {source}")
        key, value = match.groups()
        if key not in allowed or key in values:
            fail(f"unsupported or duplicate {key} declaration in {source}")
        if key in dependencies:
            if value:
                fail(f"{key} must use canonical YAML list syntax in {source}")
            values[key] = True
            current = key
        else:
            values[key] = scalar(value, key, source)
    for key in ("name", "version", "main"):
        if key not in values:
            fail(f"{source} requires {key}")
    if not isinstance(values["name"], str) or not CODE.fullmatch(values["name"]):
        fail(f"{source} name must be lowercase kebab-case")
    if not SEMVER.fullmatch(values["version"]):
        fail(f"{source} version must be stable SemVer")
    if not MAIN.fullmatch(values["main"]):
        fail(f"{source} main must be a qualified Java class name")
    all_dependencies = []
    seen = set()
    for key, required in (("depend", True), ("softdepend", False)):
        for code in dependencies[key]:
            if not CODE.fullmatch(code) or code in seen:
                fail(f"{source} has invalid or duplicate dependency: {code}")
            seen.add(code)
            all_dependencies.append({"code": code, "range": "^" + values["version"], "required": required})
    return values, all_dependencies


def validate_metadata(metadata, dependencies, source):
    allowed = {"code", "version", "main", "displayName", "description", "compatibility", "dependencies"}
    if set(metadata) - allowed or not {"code", "version", "main"} <= set(metadata):
        fail(f"{source} must contain only supported fields including code, version, main")
    if not isinstance(metadata["code"], str) or not CODE.fullmatch(metadata["code"]):
        fail(f"{source} code must be lowercase kebab-case")
    if not isinstance(metadata["version"], str) or not SEMVER.fullmatch(metadata["version"]):
        fail(f"{source} version must be stable SemVer")
    if not isinstance(metadata["main"], str) or not MAIN.fullmatch(metadata["main"]):
        fail(f"{source} main must be a qualified Java class name")
    for field in ("displayName", "description"):
        if field in metadata and (not isinstance(metadata[field], str) or not metadata[field].strip()):
            fail(f"{source} {field} must be a non-empty string")
    if "compatibility" in metadata:
        compatibility = metadata["compatibility"]
        if not isinstance(compatibility, dict) or set(compatibility) - {"host", "spi", "frontendSdk"}:
            fail(f"{source} compatibility is invalid")
        if any(not isinstance(value, str) or not RANGE.fullmatch(value) for value in compatibility.values()):
            fail(f"{source} compatibility must use supported stable SemVer ranges")
    if "dependencies" in metadata:
        if metadata["dependencies"] != dependencies:
            fail(f"{source} dependencies must match plugin.yml depend/softdepend semantics")


def validate_store(store, metadata, dependencies):
    if set(store) - {"icon", "screenshots", "compatibility", "dependencies"}:
        fail("store.json contains unsupported fields")
    resources = []
    if "icon" in store:
        resources.append(store["icon"])
    if "screenshots" in store:
        if not isinstance(store["screenshots"], list) or not all(isinstance(item, str) and item for item in store["screenshots"]):
            fail("store.json screenshots must be an array of non-empty paths")
        resources.extend(store["screenshots"])
    for resource in resources:
        safe_file(resource, "store resource")
    if len(set(resources)) != len(resources):
        fail("store.json resources must not repeat paths")
    if "compatibility" in store:
        compatibility = store["compatibility"]
        if not isinstance(compatibility, dict) or set(compatibility) - {"host", "spi", "frontendSdk"}:
            fail("store.json compatibility is invalid")
        if any(not isinstance(value, str) or not RANGE.fullmatch(value) for value in compatibility.values()):
            fail("store.json compatibility must use supported stable SemVer ranges")
    if "dependencies" in store:
        if not isinstance(store["dependencies"], list) or store["dependencies"] != dependencies:
            fail("store.json dependencies must match plugin.yml depend/softdepend semantics")
    return resources


submission = load_json("submission.json", "submission.json")
allowed_submission = {"schemaVersion", "plugin", "store", "jar", "sha256", "license", "resources", "licenseId", "author", "source", "releaseNotes"}
required_submission = {"schemaVersion", "plugin", "store", "jar", "sha256", "license"}
if set(submission) - allowed_submission or not required_submission <= set(submission):
    fail("submission.json must contain only supported fields including required artifact fields")
if submission["schemaVersion"] != 1:
    fail("submission.json schemaVersion must be 1")
if submission["plugin"] != "plugin.yml" or submission["store"] != "store.json" or submission["jar"] != "plugin.jar":
    fail("submission.json must use canonical plugin.yml, store.json, and plugin.jar paths")
plugin_file = safe_file("plugin.yml", "plugin.yml")
store = load_json("store.json", "store.json")
jar_path = safe_file("plugin.jar", "plugin.jar")
license_path = safe_file(submission["license"], "license")
if os.path.getsize(license_path) == 0:
    fail("license must not be empty")
if "licenseId" in submission:
    require_text(submission["licenseId"], "licenseId")
    if submission["licenseId"] not in SPDX_LICENSES:
        fail("licenseId must be a supported SPDX identifier")
if "author" in submission:
    validate_author(submission["author"])
if "source" in submission:
    validate_source(submission["source"])
if "releaseNotes" in submission:
    require_text(submission["releaseNotes"], "releaseNotes")
with open(plugin_file, encoding="utf-8") as handle:
    plugin_yml, expected_dependencies = parse_plugin_yml(handle.read(), "plugin.yml")
metadata = {
    "code": plugin_yml["name"], "version": plugin_yml["version"], "main": plugin_yml["main"],
    **({"displayName": plugin_yml["displayName"]} if "displayName" in plugin_yml else {}),
    **({"description": plugin_yml["description"]} if "description" in plugin_yml else {}),
}
validate_metadata(metadata, expected_dependencies, "plugin.yml")
store_resources = validate_store(store, metadata, expected_dependencies)
resources = submission.get("resources", [])
if not isinstance(resources, list) or not all(isinstance(item, str) and item for item in resources):
    fail("submission.json resources must be a string array")
if resources != store_resources:
    fail("submission.json resources must exactly list store.json resources in order")
if not isinstance(submission["sha256"], str) or not re.fullmatch(r"[0-9a-f]{64}", submission["sha256"]):
    fail("submission.json sha256 must be lowercase hexadecimal SHA-256")
with open(jar_path, "rb") as handle:
    if hashlib.sha256(handle.read()).hexdigest() != submission["sha256"]:
        fail("submission.json sha256 does not match plugin.jar")
try:
    with zipfile.ZipFile(jar_path) as archive:
        names = set()
        for info in archive.infolist():
            name = info.filename
            if name in names or name.startswith("/") or "\\" in name or any(part in ("", "..") for part in name.split("/")):
                fail("JAR contains duplicate or unsafe archive path")
            names.add(name)
            if name.startswith("online/yudream/plugin/spi/") or name.startswith("META-INF/services/online.yudream.plugin.spi"):
                fail("JAR must not embed YuDream plugin SPI classes or services")
        if "plugin.yml" not in names:
            fail("JAR must contain one root plugin.yml")
        try:
            jar_yml, jar_dependencies = parse_plugin_yml(archive.read("plugin.yml").decode("utf-8"), "JAR plugin.yml")
        except UnicodeDecodeError:
            fail("JAR plugin.yml must be UTF-8")
except zipfile.BadZipFile:
    fail("plugin.jar must be a valid JAR/ZIP archive")
if (jar_yml["name"], jar_yml["version"], jar_yml["main"], jar_dependencies) != (plugin_yml["name"], plugin_yml["version"], plugin_yml["main"], expected_dependencies):
    fail("plugin.yml code/version/main/dependencies must match JAR plugin.yml")
print("[verify-third-party-submission] OK")
PY
