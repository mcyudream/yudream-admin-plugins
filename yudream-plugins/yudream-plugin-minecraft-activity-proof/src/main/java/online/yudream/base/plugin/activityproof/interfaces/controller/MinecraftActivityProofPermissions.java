package online.yudream.base.plugin.activityproof.interfaces.controller;

/** 活动效果端点引用的权限码，与 bootstrap 声明的权限目录保持一致。 */
final class MinecraftActivityProofPermissions {

    static final String USER = "plugin:minecraft-activity-proof:view";
    static final String MANAGE = "plugin:minecraft-activity-proof:manage";

    private MinecraftActivityProofPermissions() {
    }
}
