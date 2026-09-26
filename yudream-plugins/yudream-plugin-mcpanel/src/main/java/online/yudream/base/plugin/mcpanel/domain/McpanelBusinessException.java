package online.yudream.base.plugin.mcpanel.domain;

/**
 * 面板业务异常：code 用于前端/机器判定，httpStatus 用于 HTTP 映射。
 */
public class McpanelBusinessException extends RuntimeException {

    /** 节点错误码：实例在节点上的容器/数据不存在（创建未完成、从未启动或被外部删除）。 */
    public static final String CODE_INSTANCE_NOT_FOUND = "instance.notFound";

    private final String code;
    private final int httpStatus;

    public McpanelBusinessException(String code, int httpStatus, String message) {
        super(message);
        this.code = code;
        this.httpStatus = httpStatus;
    }

    public static McpanelBusinessException notFound(String message) {
        return new McpanelBusinessException("node-not-found", 404, message);
    }

    public static McpanelBusinessException invalid(String message) {
        return new McpanelBusinessException("invalid-request", 400, message);
    }

    public String code() {
        return code;
    }

    public int httpStatus() {
        return httpStatus;
    }
}
