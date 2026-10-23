package online.yudream.base.plugin.cas.domain.aggregate;

/**
 * 访问控制配置：未绑定统一身份认证时是否限制使用其他功能。
 * <p>插件已不再维护「认证属性 → 学生档案字段」的映射（不向学生档案插件写入任何信息），
 * 因此这里只剩绑定门禁一个开关。</p>
 */
public final class AccessControl {

    private final boolean requireBinding;

    public AccessControl(boolean requireBinding) {
        this.requireBinding = requireBinding;
    }

    public static AccessControl defaults() {
        return new AccessControl(false);
    }

    public boolean requireBinding() {
        return requireBinding;
    }

    public AccessControl withRequireBinding(boolean value) {
        return new AccessControl(value);
    }
}
