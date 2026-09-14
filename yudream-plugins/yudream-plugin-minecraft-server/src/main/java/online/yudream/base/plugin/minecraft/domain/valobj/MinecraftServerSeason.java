package online.yudream.base.plugin.minecraft.domain.valobj;

import java.util.UUID;

/**
 * 周目（赛季）值对象。周目级绑定 modpack -- 切换周目自然换包，
 * 历史周目的绑定保留可回切，与 mc-server 现有 currentSeason 机制直接配合。
 */
public record MinecraftServerSeason(
        String id,
        String name,
        String description,
        Long startedAt,
        Long endedAt,
        boolean current,
        int sort,
        ModpackBinding modpackBinding
) {

    public MinecraftServerSeason {
        id = id == null || id.isBlank() ? UUID.randomUUID().toString() : id.trim();
        name = requireText(name, "周目名称不能为空");
        description = trimToNull(description);
        if (startedAt != null && endedAt != null && endedAt < startedAt) {
            throw new IllegalArgumentException("周目结束时间不能早于开始时间");
        }
        modpackBinding = modpackBinding == null ? ModpackBinding.none() : modpackBinding;
    }

    /** withCurrent 复制：避免现有调用点需要的 6 元参数重载破坏。 */
    public MinecraftServerSeason withCurrent(boolean value) {
        return new MinecraftServerSeason(id, name, description, startedAt, endedAt, value, sort, modpackBinding);
    }

    /** 周目内 swap modpack 绑定 -- 一般用于管理员在同周目内推送新包版本 */
    public MinecraftServerSeason withModpackBinding(ModpackBinding binding) {
        return new MinecraftServerSeason(id, name, description, startedAt, endedAt, current, sort,
                binding == null ? ModpackBinding.none() : binding);
    }

    /** 6 元构造便捷方法：兼容历史无 modpackBinding 入参的调用点。 */
    public static MinecraftServerSeason of(String id, String name, String description,
                                          Long startedAt, Long endedAt, boolean current, int sort) {
        return new MinecraftServerSeason(id, name, description, startedAt, endedAt, current, sort, ModpackBinding.none());
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
