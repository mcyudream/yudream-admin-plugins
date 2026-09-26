#!/bin/sh
# 最小 unzip 替身（CI 专用）：release-selection 校验只需要 `unzip -p <jar> <entry>`
# 读取 JAR 内的 plugin.yml。基于 JDK 自带的 jar 工具实现，供没有 unzip 且
# apt/apk 安装不便的 CI 镜像（maven:3.9-eclipse-temurin-21）使用。
# 其余 unzip 用法一律不支持（显式报错，避免静默错误结果）。
if [ "$1" = "-p" ] && [ "$#" -eq 3 ]; then
  jar_path=$2
  case "$jar_path" in
    /*) ;;
    *) jar_path="$(pwd)/$jar_path" ;;
  esac
  tmp=$(mktemp -d) || exit 1
  (
   cd "$tmp" || exit 1
   jar xf "$jar_path" "$3" 2>/dev/null || exit 1
   cat "$3"
  ) || { rm -rf "$tmp"; exit 1; }
  rm -rf "$tmp"
  exit 0
fi
echo "unzip shim: unsupported usage: $*" >&2
exit 1
