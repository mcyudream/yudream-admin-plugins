package online.yudream.base.plugin.material.infrastructure;

import online.yudream.base.plugin.spi.system.FrameworkServices;
import online.yudream.base.plugin.spi.system.storage.PluginStoredFile;

/** 宿主平台文件（/api/files 上传）只读桥：插件 HTTP 层无 multipart，上传先落宿主再按 fileId 复制进插件命名空间。 */
public final class PlatformFileIntake {
    private final FrameworkServices framework;

    public PlatformFileIntake(FrameworkServices framework) {
        this.framework = framework;
    }

    public PluginStoredFile require(String fileId) {
        return framework.platformFile(fileId)
                .orElseThrow(() -> new IllegalArgumentException("平台文件不存在或已被清理，请重新上传"));
    }
}
