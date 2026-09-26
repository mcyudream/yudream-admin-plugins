#!/usr/bin/env sh
# 插件依赖图必须无环（depend + softdepend 一起看）。
#
# 宿主启用插件时会递归启用硬/软依赖，遇到环会抛「插件依赖存在循环：<code>」并把该可选依赖
# 整体丢弃（只留一行 WARN）——依赖方声明过的能力会静默失效，非常难定位。这里在本地把同一张
# 图建出来，任何环都直接失败并打印环路径。
set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$ROOT_DIR"

fail() {
  echo "[verify-plugin-dependency-acyclic] $1" >&2
  exit 1
}

TMP_DIR=$(mktemp -d 2>/dev/null || mktemp -d -t plugin-acyclic)
trap 'rm -rf "$TMP_DIR"' EXIT

EDGES="$TMP_DIR/edges.tsv"
: > "$EDGES"

for plugin_yml in yudream-plugins/*/src/main/resources/plugin.yml; do
  [ -f "$plugin_yml" ] || continue
  # plugin.yml 的 name 就是全局插件 code（依赖图用 code，不用模块名）。
  awk -v source="$plugin_yml" '
    function flush() {
      if (owner != "" && key != "") {
        for (i = 0; i < count; i++) print owner "\t" key "\t" items[i]
      }
    }
    /^[A-Za-z][A-Za-z0-9_-]*:/ { flush(); key = ""; count = 0 }
    /^name:[ \t]*/ {
      value = $0
      sub(/^name:[ \t]*/, "", value)
      gsub(/[ \t]+$/, "", value)
      owner = value
    }
    /^depend:[ \t]*$/ { key = "depend"; count = 0; next }
    /^softdepend:[ \t]*$/ { key = "softdepend"; count = 0; next }
    /^[ \t]+-[ \t]*[A-Za-z0-9_.-]+[ \t]*$/ && key != "" {
      value = $0
      sub(/^[ \t]+-[ \t]*/, "", value)
      gsub(/[ \t]+$/, "", value)
      items[count++] = value
    }
    END { flush() }
  ' "$plugin_yml" >> "$EDGES"
done

[ -s "$EDGES" ] || fail "未从 yudream-plugins/*/src/main/resources/plugin.yml 解析出任何依赖（检查解析规则）"

# 与 ci/publish-to-market.sh 一致：python3 优先，回退 python。
# Windows 上存在 python3 的 Store 别名桩：能 command -v 到却跑不起来，所以连可用性一起验。
ACYCLIC_PYTHON=""
if command -v python3 >/dev/null 2>&1 && python3 -c 'import json' >/dev/null 2>&1; then
  ACYCLIC_PYTHON=python3
elif command -v python >/dev/null 2>&1 && python -c 'import json' >/dev/null 2>&1; then
  ACYCLIC_PYTHON=python
else
  fail "需要可用的 python3 或 python 来跑依赖环检测"
fi

"$ACYCLIC_PYTHON" - "$EDGES" <<'PY'
import sys
from collections import defaultdict

graph = defaultdict(list)
plugins = set()
for line in open(sys.argv[1], encoding="utf-8"):
    owner, kind, target = line.rstrip("\n").split("\t")
    plugins.add(owner)
    graph[owner].append((kind, target))

WHITE, GREY, BLACK = 0, 1, 2
color = defaultdict(int)

for root in sorted(plugins):
    if color[root] != WHITE:
        continue
    color[root] = GREY
    path = [root]
    stack = [(root, iter(sorted(graph.get(root, []))))]
    while stack:
        current, children = stack[-1]
        for kind, target in children:
            if color[target] == GREY:
                cycle = path[path.index(target):] + [f"{target}（{kind}）"]
                print(
                    "[verify-plugin-dependency-acyclic] 插件依赖存在环："
                    + " → ".join(cycle),
                    file=sys.stderr,
                )
                print(
                    "[verify-plugin-dependency-acyclic] 修法：把消费方向反过来——被聚合方"
                    "以扩展点贡献，聚合方（适配器/门面）不声明对被聚合方的依赖；宿主会丢弃环上"
                    "的可选依赖，能力静默失效",
                    file=sys.stderr,
                )
                sys.exit(1)
            if color[target] == WHITE:
                color[target] = GREY
                path.append(target)
                stack.append((target, iter(sorted(graph.get(target, [])))))
                break
        else:
            color[current] = BLACK
            path.pop()
            stack.pop()

print(f"[verify-plugin-dependency-acyclic] OK（{len(plugins)} 个插件，依赖图无环）")
PY
