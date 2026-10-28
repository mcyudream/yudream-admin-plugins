#!/usr/bin/env python3
"""Create a deterministic mcpanel-ce/1 ZIP from a CE namespace and optional plugin."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import sys
import tempfile
import zipfile

MAX_FILES = 10_000
MAX_UNPACKED = 512 * 1024**2
MAX_ZIP = 64 * 1024**2


def relative(value):
    parts = value.split('/')
    if (not value or len(value) > 1024 or any(c in value for c in '\\:')
            or any(ord(c) < 32 or ord(c) == 127 for c in value)
            or any(p in ('', '.', '..') or p.startswith('.mcpanel') for p in parts)):
        raise ValueError(f'无效相对路径：{value}')
    return value


def directory_files(root):
    if root.is_symlink() or not root.is_dir():
        raise ValueError(f'需要普通目录：{root}')
    for path in sorted(root.rglob('*')):
        if path.is_symlink():
            raise ValueError(f'不能打包符号链接：{path}')
        if path.is_file():
            rel = relative(path.relative_to(root).as_posix())
            if path.name.startswith('.env') or path.name.lower() in ('credentials.json', 'credentials.yml', 'secrets.yml', 'secrets.yaml'):
                raise ValueError(f'发现凭据文件，请从打包目录中移出：{rel}')
            yield rel, path


def build(args):
    if not re.fullmatch(r'[a-z][a-z0-9_]{0,63}', args.namespace):
        raise ValueError('namespace 需为小写字母开头，仅含小写字母、数字或下划线，最多 64 字符')
    if not args.name.strip() or len(args.name) > 128 or not args.version.strip() or len(args.version) > 64:
        raise ValueError('请填写有效名称（最多 128 字）和版本（最多 64 字）')
    plugin = args.plugin_directory or ''
    if (args.plugin_config or args.plugin_jar) and not plugin:
        raise ValueError('打包插件配置或 JAR 时需要 --plugin-directory')
    if plugin and (not re.fullmatch(r'[A-Za-z][A-Za-z0-9_-]{0,63}', plugin) or plugin.lower() == 'craftengine'):
        raise ValueError('plugin-directory 无效，不能覆盖 CraftEngine 全局目录')
    targets = []
    for rel, path in directory_files(args.ce_content):
        targets.append((f'plugins/CraftEngine/contents/{args.namespace}/{rel}', path))
    if not targets:
        raise ValueError('CE 命名空间目录中没有文件')
    if args.plugin_config:
        for rel, path in directory_files(args.plugin_config):
            targets.append((f'plugins/{plugin}/{rel}', path))
    if args.plugin_jar:
        if args.plugin_jar.is_symlink() or not args.plugin_jar.is_file() or args.plugin_jar.suffix.lower() != '.jar':
            raise ValueError('plugin-jar 需为普通 JAR 文件')
        # A stable filename prevents parallel loading of multiple versioned JARs.
        targets.append((f'plugins/{plugin}.jar', args.plugin_jar))
    if len(targets) > MAX_FILES:
        raise ValueError('文件数超过 10000')
    seen = set()
    entries = []
    total = 0
    output = args.output.resolve()
    for target, path in targets:
        relative(target)
        key = target.casefold()
        if key in seen:
            raise ValueError(f'目标重名（忽略大小写）：{target}')
        seen.add(key)
        if path.resolve() == output:
            raise ValueError('输出 ZIP 不能包含在输入目录中')
        size = path.stat().st_size
        total += size
        if total > MAX_UNPACKED:
            raise ValueError('解压后超过 512MiB')
        entries.append((target, path))
    manifest = {'format': 'mcpanel-ce/1', 'name': args.name, 'version': args.version, 'namespace': args.namespace, 'files': []}
    if plugin:
        manifest['pluginDirectory'] = plugin
    output.parent.mkdir(parents=True, exist_ok=True)
    # Capture bytes and hash together; a changed source cannot produce a mismatching package.
    with tempfile.TemporaryFile() as temporary:
        total = 0
        with zipfile.ZipFile(temporary, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
            for index, (target, path) in enumerate(sorted(entries)):
                source = f'payload/{index:05d}/{path.name}'
                info = zipfile.ZipInfo(source, date_time=(1980, 1, 1, 0, 0, 0))
                info.compress_type = zipfile.ZIP_DEFLATED
                info.external_attr = 0o100644 << 16
                digest = hashlib.sha256()
                with path.open('rb') as content, archive.open(info, 'w') as destination:
                    while data := content.read(1024 * 1024):
                        total += len(data)
                        if total > MAX_UNPACKED:
                            raise ValueError('解压后超过 512MiB（输入文件可能在打包期间增长）')
                        digest.update(data)
                        destination.write(data)
                manifest['files'].append({'source': source, 'target': target, 'sha256': digest.hexdigest()})
            info = zipfile.ZipInfo('mcpanel-package.json', date_time=(1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o100644 << 16
            manifest_bytes = json.dumps(manifest, ensure_ascii=False, indent=2).encode('utf-8')
            if len(manifest_bytes) > 1024 * 1024 or total + len(manifest_bytes) > MAX_UNPACKED:
                raise ValueError('清单超过 1MiB，或加上清单后解压内容超过 512MiB')
            archive.writestr(info, manifest_bytes)
        if temporary.tell() > MAX_ZIP:
            raise ValueError('ZIP 超过 64MiB，请缩小本次资源安装范围')
        temporary.seek(0)
        # Exclusive creation prevents accidental replacement of an existing release artifact.
        with output.open('xb') as result:
            while data := temporary.read(1024 * 1024):
                result.write(data)
    return len(entries), total


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--ce-content', type=Path, required=True, help='单个命名空间的内容目录（目录内通常有 configuration/、resourcepack/ 等）')
    parser.add_argument('--namespace', required=True)
    parser.add_argument('--name', required=True)
    parser.add_argument('--version', required=True)
    parser.add_argument('--plugin-directory', help='可选插件目录名，例如 TownPlugin')
    parser.add_argument('--plugin-config', type=Path, help='该插件的配置目录；请先移除生产凭据')
    parser.add_argument('--plugin-jar', type=Path, help='可选插件 JAR；目标文件固定为 plugins/<plugin-directory>.jar')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    try:
        count, size = build(args)
    except (OSError, ValueError) as error:
        parser.exit(1, f'打包失败：{error}\n')
    print(f'已生成 {args.output}：{count} 个文件，解压内容 {size} 字节')


if __name__ == '__main__':
    main()
