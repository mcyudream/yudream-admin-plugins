#!/usr/bin/env sh

plugin_release_fail() {
  echo "[plugin-release-selection] $1" >&2
  return 1
}

# Release-only selection is requested explicitly: either PLUGIN_RELEASE_ONLY=1
# (read release/plugins.txt) or a PLUGIN_RELEASE_MODULES override. A set but
# empty/blank PLUGIN_RELEASE_MODULES is an error, not an implicit disable.
plugin_release_only_enabled() {
  [ "${PLUGIN_RELEASE_ONLY:-}" = "1" ] || [ "${PLUGIN_RELEASE_MODULES+set}" = "set" ]
}

plugin_release_known_modules() {
  root_dir=$1
  sed -n 's:.*<module>yudream-plugins/\([^<][^<]*\)</module>.*:\1:p' "$root_dir/pom.xml" | sort
}

plugin_release_modules() {
  root_dir=$1
  if [ "${PLUGIN_RELEASE_MODULES+set}" = "set" ]; then
    printf '%s\n' "$PLUGIN_RELEASE_MODULES"
    return 0
  fi
  list_file="$root_dir/release/plugins.txt"
  [ -f "$list_file" ] || { plugin_release_fail "missing release module list: $list_file"; return 1; }
  tr -d '\r' < "$list_file"
}

# Prints the validated release module list (one artifactId per line), or fails
# on an empty selection, blank entries, duplicates, or modules that are not
# root-reactor yudream-plugins modules.
plugin_release_selected_modules() {
  root_dir=$1
  raw=$(plugin_release_modules "$root_dir") || return 1
  selected=$(printf '%s\n' "$raw" | sed 's/#.*$//' | tr ', \t' '\n\n\n' | awk 'NF')
  [ -n "$selected" ] || { plugin_release_fail "release module selection is empty"; return 1; }

  duplicates=$(printf '%s\n' "$selected" | sort | uniq -d)
  [ -z "$duplicates" ] \
    || { plugin_release_fail "duplicate release module entries: $(printf '%s' "$duplicates" | tr '\n' ' ')"; return 1; }

  known_set=" $(plugin_release_known_modules "$root_dir" | tr '\n' ' ') "
  for module in $selected; do
    case "$known_set" in
      *" $module "*) ;;
      *) plugin_release_fail "release module is not a root-reactor plugin module: $module"; return 1 ;;
    esac
  done

  printf '%s\n' "$selected"
}

# Prints "-pl :artifact1,:artifact2" for the validated selection.
plugin_release_maven_pl_args() {
  root_dir=$1
  modules=$(plugin_release_selected_modules "$root_dir") || return 1
  csv=$(for module in $modules; do printf ',:%s' "$module"; done)
  printf -- '-pl %s' "${csv#,}"
}

select_module_final_jar() {
  target_dir=$1

  shaded_jar=$(find "$target_dir" -maxdepth 1 -type f -name '*-shaded.jar' | sort | head -n 1)
  if [ -n "$shaded_jar" ]; then
    printf '%s\n' "$shaded_jar"
    return 0
  fi

  plain_jar=$(find "$target_dir" -maxdepth 1 -type f -name '*.jar' ! -name '*-sources.jar' ! -name '*-javadoc.jar' ! -name 'original-*.jar' ! -name '*-shaded.jar' | sort | head -n 1)
  if [ -n "$plain_jar" ]; then
    printf '%s\n' "$plain_jar"
  fi
}

select_flat_plugin_jars() {
  root_dir=$1
  flat_dir="$root_dir/dist/plugins"

  if [ ! -d "$flat_dir" ]; then
    return 1
  fi

  find "$flat_dir" -maxdepth 1 -type f -name '*.jar' ! -name '*-sources.jar' ! -name '*-javadoc.jar' ! -name 'original-*.jar' | sort
}

select_target_plugin_jars() {
  root_dir=$1

  find "$root_dir/yudream-plugins" -mindepth 1 -maxdepth 1 -type d | sort | while IFS= read -r module_dir; do
    target_dir="$module_dir/target"
    if [ ! -d "$target_dir" ]; then
      continue
    fi

    select_module_final_jar "$target_dir"
  done
}

# Keeps only jars whose artifactId belongs to the validated release selection.
plugin_release_filter_jars() {
  root_dir=$1
  modules=$(plugin_release_selected_modules "$root_dir") || return 1

  # jar_list arrives as the second argument to keep the read loop in the
  # current shell, so per-module presence tracking survives the loop.
  jar_list=$2
  found=" "
  while IFS= read -r jar_path; do
    file_name=$(basename "$jar_path")
    for module in $modules; do
      case "$file_name" in
        "$module"-*.jar) printf '%s\n' "$jar_path"; found="$found$module "; break ;;
      esac
    done
  done <<EOF
$jar_list
EOF

  for module in $modules; do
    case "$found" in
      *" $module "*) ;;
      *) plugin_release_fail "selected release module has no final jar in dist/plugins: $module"; return 1 ;;
    esac
  done
}

select_release_target_plugin_jars() {
  root_dir=$1
  modules=$(plugin_release_selected_modules "$root_dir") || return 1

  for module in $modules; do
    target_dir="$root_dir/yudream-plugins/$module/target"
    jar_path=
    if [ -d "$target_dir" ]; then
      jar_path=$(select_module_final_jar "$target_dir")
    fi
    if [ -z "$jar_path" ]; then
      plugin_release_fail "no final jar for selected release module: $module (expected under yudream-plugins/$module/target)"
      return 1
    fi
    printf '%s\n' "$jar_path"
  done
}

select_final_plugin_jars() {
  root_dir=$1

  flat_jars=$(select_flat_plugin_jars "$root_dir" || true)
  if [ -n "$flat_jars" ]; then
    if plugin_release_only_enabled; then
      filtered=$(plugin_release_filter_jars "$root_dir" "$flat_jars") || return 1
      [ -n "$filtered" ] || { plugin_release_fail "no selected plugin jars found in dist/plugins"; return 1; }
      printf '%s\n' "$filtered"
    else
      printf '%s\n' "$flat_jars"
    fi
    return 0
  fi

  if plugin_release_only_enabled; then
    selected_jars=$(select_release_target_plugin_jars "$root_dir") || return 1
    [ -n "$selected_jars" ] || { plugin_release_fail "no selected plugin jars found under yudream-plugins/*/target"; return 1; }
    printf '%s\n' "$selected_jars"
    return 0
  fi

  select_target_plugin_jars "$root_dir"
}

write_final_plugin_jars() {
  root_dir=$1
  output_file=$2

  : > "$output_file"
  select_final_plugin_jars "$root_dir" >> "$output_file" || return 1
  [ -s "$output_file" ]
}

copy_final_plugin_jars() {
  root_dir=$1
  output_dir=$2

  tmp_file=$(mktemp "${TMPDIR:-/tmp}/yudream-plugin-jars-XXXXXX.txt")
  trap 'rm -f "$tmp_file"' EXIT INT TERM

  if ! write_final_plugin_jars "$root_dir" "$tmp_file"; then
    rm -f "$tmp_file"
    trap - EXIT INT TERM
    return 1
  fi

  while IFS= read -r jar_path; do
    cp "$jar_path" "$output_dir/$(basename "$jar_path")"
  done < "$tmp_file"

  rm -f "$tmp_file"
  trap - EXIT INT TERM
}
