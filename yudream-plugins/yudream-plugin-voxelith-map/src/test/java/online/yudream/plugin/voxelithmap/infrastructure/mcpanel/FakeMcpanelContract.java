package online.yudream.plugin.voxelithmap.infrastructure.mcpanel;

import java.util.List;

/**
 * 测试用面板契约：只保留适配器反射调用所需的方法名与签名。
 *
 * <p>真实契约当前尚未随 mcpanel 发布，地图插件不能引用它的类型；测试用这个本地接口验证
 * 「按类名反射查找 + 方法映射」这一层，不依赖面板的真实 JAR。</p>
 */
public interface FakeMcpanelContract {

    record Entry(String name, long size, boolean directory) {
    }

    List<Entry> listDir(String instanceId, String path);

    long packToZip(String instanceId, List<String> paths, String dest);

    byte[] readArchiveRange(String instanceId, String path, long offset, int length);

    void deleteFile(String instanceId, String path);
}
