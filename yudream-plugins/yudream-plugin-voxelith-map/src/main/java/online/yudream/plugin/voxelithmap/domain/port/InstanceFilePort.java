package online.yudream.plugin.voxelithmap.domain.port;

import online.yudream.plugin.voxelithmap.domain.value.InstanceDirEntry;

import java.util.List;

/**
 * 面板实例文件读取端口（出站端口）。
 *
 * <p>只声明导入链路真正需要的四件能力：列目录、打包、分块读回、清理临时包。实现由
 * {@code infrastructure.mcpanel} 提供，并按类名反射查找面板的可选契约；当前面板（0.22.6）
 * 没有该契约时实现报告 {@link #available()} 为 {@code false}，导入自动退回浏览器搬运——
 * 插件主体（渲染、标注、公开查看器）不依赖它。</p>
 *
 * <p>路径语义：实例根目录的相对路径，根目录用 {@code "."}。实现方负责拒绝越界路径。</p>
 */
public interface InstanceFilePort {

    /** 面板实例文件访问是否可用（面板插件已安装且启用）。 */
    boolean available();

    /** 列目录；目录不存在返回空列表。 */
    List<InstanceDirEntry> listDir(String instanceId, String path);

    /**
     * 把实例内若干路径打包成 zip（落在实例根目录下的 {@code dest}）。
     *
     * @return 产物字节数；拿不到大小时返回 0（调用方退回顺序探测）
     */
    long packToZip(String instanceId, List<String> paths, String dest);

    /** 分块读回打包产物的一段字节；已到末尾返回空数组。 */
    byte[] readArchiveRange(String instanceId, String path, long offset, int length);

    /** 删除实例内的文件（清理打包产物）。 */
    void deleteFile(String instanceId, String path);
}
