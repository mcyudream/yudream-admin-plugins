package online.yudream.plugin.voxelithmap.domain.value;

/**
 * 地图项目绑定的一个模组 jar 引用。
 *
 * <p>mod jar 与存档 / client jar 同一思路：字节留在宿主平台文件里，项目只记 id；
 * {@code name} 是上传时的原始文件名（宿主对象存储不保留原名），用于详情展示与任务日志。</p>
 *
 * <p>渲染侧的语义（与 Voxelith 上游约定一致）：mod jar 叠在原版 client jar 之上参与
 * 资源包栈（{@code packs[0] = 原版，其余 = mod}），其方块模型经 headless 采集进
 * {@code models.json.gz}；采集缓存分桶键也会把 mod jar 的 id 算进去——换模组必须换桶，
 * 否则新模组的方块会静默缺几何。</p>
 *
 * @param fileId 宿主平台文件 id（必填）
 * @param name   上传时的原始文件名（可空，仅展示用）
 */
public record ModJarRef(String fileId, String name) {

    public ModJarRef {
        if (fileId == null || fileId.isBlank()) {
            throw new IllegalArgumentException("模组 jar 的平台文件 id 不能为空");
        }
        fileId = fileId.trim();
        name = name == null ? "" : name.trim();
    }

    /** 展示名：有原名用原名，否则退回文件 id。 */
    public String displayName() {
        return name.isBlank() ? fileId : name;
    }
}
