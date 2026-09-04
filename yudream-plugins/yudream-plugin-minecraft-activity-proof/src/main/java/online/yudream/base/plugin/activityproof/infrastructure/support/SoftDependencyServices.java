package online.yudream.base.plugin.activityproof.infrastructure.support;

import java.util.Optional;
import online.yudream.base.plugin.minecraft.api.PluginMinecraftService;
import online.yudream.base.plugin.questionbank.api.QuestionBankApi;
import online.yudream.base.plugin.skin.api.PluginSkinService;
import online.yudream.base.plugin.spi.core.PluginContext;

/**
 * 软依赖服务定位：跨插件 API 类型引用集中在本类，先经 dependencyAvailable 做无类检查，
 * 通过后才解析 API 类字面量，避免 provider 缺失/禁用时抛 NoClassDefFoundError；
 * API 对象每次现取，不跨 provider disable/reload 缓存。
 */
public final class SoftDependencyServices {

    public static final String MINECRAFT_PLUGIN = "minecraft-server";
    public static final String SKIN_PLUGIN = "yudream-skin";
    public static final String QUESTION_BANK_PLUGIN = "questionbank";

    private SoftDependencyServices() {
    }

    public static Optional<PluginMinecraftService> minecraft(PluginContext context) {
        if (context == null || !context.dependencyAvailable(MINECRAFT_PLUGIN)) {
            return Optional.empty();
        }
        try {
            return context.service(MINECRAFT_PLUGIN, PluginMinecraftService.class);
        } catch (LinkageError e) {
            return Optional.empty();
        }
    }

    public static Optional<PluginSkinService> skin(PluginContext context) {
        if (context == null || !context.dependencyAvailable(SKIN_PLUGIN)) {
            return Optional.empty();
        }
        try {
            return context.service(SKIN_PLUGIN, PluginSkinService.class);
        } catch (LinkageError e) {
            return Optional.empty();
        }
    }

    public static Optional<QuestionBankApi> questionBank(PluginContext context) {
        if (context == null || !context.dependencyAvailable(QUESTION_BANK_PLUGIN)) {
            return Optional.empty();
        }
        try {
            return context.service(QUESTION_BANK_PLUGIN, QuestionBankApi.class);
        } catch (LinkageError e) {
            return Optional.empty();
        }
    }
}
