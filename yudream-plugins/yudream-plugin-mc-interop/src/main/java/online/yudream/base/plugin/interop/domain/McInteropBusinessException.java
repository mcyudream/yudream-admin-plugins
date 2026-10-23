package online.yudream.base.plugin.interop.domain;

/** 业务校验失败：宿主信封 400，消息面向管理员。 */
public class McInteropBusinessException extends RuntimeException {

    public McInteropBusinessException(String message) {
        super(message);
    }
}
