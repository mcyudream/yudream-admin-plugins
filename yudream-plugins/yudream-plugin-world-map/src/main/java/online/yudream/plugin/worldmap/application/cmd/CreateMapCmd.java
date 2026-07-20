package online.yudream.plugin.worldmap.application.cmd;

/**
 * 创建地图命令。
 *
 * @param worldFileId     平台文件 ID（存档 zip，经 /api/files/upload 上传）
 * @param clientJarFileId 平台文件 ID（客户端 jar，可空——空则渲染时从镜像下载）
 */
public record CreateMapCmd(
        String name,
        String dimension,
        String worldFileId,
        String clientJarFileId,
        Boolean stripNetherCeiling
) {
}
