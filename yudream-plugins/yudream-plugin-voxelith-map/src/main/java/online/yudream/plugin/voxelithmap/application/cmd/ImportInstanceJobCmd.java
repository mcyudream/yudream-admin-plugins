package online.yudream.plugin.voxelithmap.application.cmd;

/**
 * 从面板实例导入世界并新建地图（异步作业入参）。
 *
 * @param instanceId   面板实例 id（导入来源，也会作为「同步最新存档」的来源记录）
 * @param instanceName 实例显示名（任务主体与地图名兜底；只用于展示，不参与查找）
 * @param name         地图名称；留空时用实例名
 * @param version      存档版本；留空时由服务端从实例根目录 jar 文件名判定
 * @param withMods     是否一并导入实例 mods 目录（失败只记日志，不阻断世界导入）
 * @param worldDirs    实例里存在哪些维度目录（world / world_nether / world_the_end），每个建一张地图；
 *                     空 = 只导入主世界（兼容旧入参）
 */
public record ImportInstanceJobCmd(String instanceId, String instanceName, String name,
                                   String version, boolean withMods, java.util.List<String> worldDirs) {

    public ImportInstanceJobCmd {
        worldDirs = worldDirs == null ? java.util.List.of() : java.util.List.copyOf(worldDirs);
    }
}
