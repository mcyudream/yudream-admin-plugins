package online.yudream.base.plugin.aioffice.interfaces.controller;

/** 办公插件权限码：与 bootstrap 权限目录保持一致。 */
final class OfficePermissions {

    static final String VIEW = "plugin:ai-office:view";
    static final String MANAGE = "plugin:ai-office:manage";
    static final String GENERATE = "plugin:ai-office:generate";
    static final String FINANCE = "plugin:ai-office:finance";
    static final String ACCEPT = "plugin:ai-office:accept";
    static final String SETTLE = "plugin:ai-office:settle";

    private OfficePermissions() {
    }
}
