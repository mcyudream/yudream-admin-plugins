package online.yudream.base.plugin.mcpanel.domain.enumerate;

/**
 * 节点在线状态。M1 只有 online/offline 两态，实例级 unknown 随 M2 引入。
 */
public enum NodeStatus {

    ONLINE("online"),
    OFFLINE("offline");

    private final String code;

    NodeStatus(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static NodeStatus fromCode(String code) {
        for (NodeStatus status : values()) {
            if (status.code.equalsIgnoreCase(code)) {
                return status;
            }
        }
        return OFFLINE;
    }
}
