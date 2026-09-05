package online.yudream.base.plugin.timeline.domain;

/**
 * 大事记事件类型：图文（默认，兼容旧数据）、纯文字、组织换届（结构化名册）、里程碑、荣誉。
 * 公开页按类型特异化渲染卡片与详情；未知历史值读取时容错回退 ARTICLE。
 */
public enum TimelineEventType {
    ARTICLE("图文"),
    TEXT("文字"),
    ELECTION("换届"),
    MILESTONE("里程碑"),
    AWARD("荣誉");

    private final String label;

    TimelineEventType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** 请求入参解析：空白视为默认图文，未知值视为非法请求（400）。 */
    public static TimelineEventType from(String raw) {
        if (raw == null || raw.isBlank()) {
            return ARTICLE;
        }
        try {
            return TimelineEventType.valueOf(raw.trim().toUpperCase());
        }
        catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("未知事件类型：" + raw);
        }
    }

    /** 文档读取容错：缺字段或未知历史值一律按图文处理，不影响老数据渲染。 */
    public static TimelineEventType fromDoc(String raw) {
        if (raw == null || raw.isBlank()) {
            return ARTICLE;
        }
        try {
            return TimelineEventType.valueOf(raw.trim().toUpperCase());
        }
        catch (IllegalArgumentException e) {
            return ARTICLE;
        }
    }
}
