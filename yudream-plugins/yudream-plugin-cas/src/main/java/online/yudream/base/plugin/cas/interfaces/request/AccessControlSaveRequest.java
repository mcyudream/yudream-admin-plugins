package online.yudream.base.plugin.cas.interfaces.request;

/** 访问控制配置保存请求；字段缺省表示保留原值。 */
public record AccessControlSaveRequest(Boolean requireBinding) {
}
