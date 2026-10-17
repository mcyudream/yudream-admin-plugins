package online.yudream.plugin.voxelithmap.domain.value;

/**
 * 实例目录条目（面板实例文件的跨插件读取结果）。
 *
 * <p>只保留导入链路真正需要的信息：文件名、字节数、是否目录。节点信息、挂载、环境变量等
 * 运维细节不属于本插件的关注点。</p>
 *
 * @param name      文件名（不含目录部分）
 * @param sizeBytes 字节数；面板拿不到时为 0
 * @param directory 是否目录
 */
public record InstanceDirEntry(String name, long sizeBytes, boolean directory) {

    public InstanceDirEntry {
        name = name == null ? "" : name;
        sizeBytes = Math.max(0, sizeBytes);
    }
}
