package online.yudream.base.plugin.mcnews.application;

import java.util.HashMap;
import java.util.Map;
import online.yudream.base.plugin.mcnews.infrastructure.McNewsStore;

/**
 * 插件全局设置（mc_news_settings/global）。空字符串模板/提示词表示使用内置默认值。
 * lastPollAt / lastPollSummary 由轮询服务回写，仅作状态展示。
 */
public final class McNewsSettings {
    public static final String DEFAULT_MESSAGE_TEMPLATE = """
            📰 {{title}}

            {{body}}

            🔗 {{url}}
            来源：{{sourceName}}｜{{category}}""";

    public static final String DEFAULT_AI_SYSTEM_PROMPT = """
            你是 Minecraft 社区的新闻编辑。基于给定的新闻标题与正文节选，用简体中文写一条面向玩家的推送正文：
            第一行用一句话说明这条新闻是什么；随后用『• 』开头的条目列出正文里的具体有用信息——修复了哪些 bug、新增或调整了哪些内容、重要版本号、对玩家和服务器管理员的实际影响，按正文顺序提炼，保留 MC-XXXXX 之类的编号与具体名词，有多少写多少，正文没有的不要编造。
            总长度控制在 300 字以内；不要标题行、不要链接、不要 emoji、不要任何解释或前后缀。正文缺失时只输出一句话概括。""";

    public static final int MIN_POLL_INTERVAL = 5;
    public static final int MAX_POLL_INTERVAL = 1440;
    public static final int MIN_CACHE_SIZE = 5;
    public static final int MAX_CACHE_SIZE = 200;
    public static final int MAX_AI_ITEMS = 50;
    public static final int MIN_CONTENT_CHARS = 500;
    public static final int MAX_CONTENT_CHARS = 20000;

    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_INTERVAL = "pollIntervalMinutes";
    private static final String KEY_CACHE_SIZE = "cacheSize";
    private static final String KEY_PUSH_ON_FIRST = "pushOnFirstPoll";
    private static final String KEY_AI_ENABLED = "aiEnabled";
    private static final String KEY_AI_PROVIDER = "aiProviderCode";
    private static final String KEY_AI_MODEL = "aiModelCode";
    private static final String KEY_AI_MAX_ITEMS = "aiMaxItems";
    private static final String KEY_AI_CONTENT_CHARS = "aiContentMaxChars";
    private static final String KEY_AI_PROMPT = "aiSystemPrompt";
    private static final String KEY_TEMPLATE = "messageTemplate";
    private static final String KEY_LAST_POLL_AT = "lastPollAt";
    private static final String KEY_LAST_POLL_SUMMARY = "lastPollSummary";

    private final McNewsStore store;

    public McNewsSettings(McNewsStore store) {
        this.store = store;
    }

    private Map<String, Object> doc() {
        return store.find(McNewsStore.COL_SETTINGS, McNewsStore.DOC_SETTINGS).orElse(Map.of());
    }

    public boolean enabled() {
        return McNewsStore.bool(doc(), KEY_ENABLED, true);
    }

    public int pollIntervalMinutes() {
        return clamp(McNewsStore.intOr(doc(), KEY_INTERVAL, 30), MIN_POLL_INTERVAL, MAX_POLL_INTERVAL);
    }

    public int cacheSize() {
        return clamp(McNewsStore.intOr(doc(), KEY_CACHE_SIZE, 50), MIN_CACHE_SIZE, MAX_CACHE_SIZE);
    }

    public boolean pushOnFirstPoll() {
        return McNewsStore.bool(doc(), KEY_PUSH_ON_FIRST, false);
    }

    public boolean aiEnabled() {
        return McNewsStore.bool(doc(), KEY_AI_ENABLED, true);
    }

    public String aiProviderCode() {
        return McNewsStore.str(doc(), KEY_AI_PROVIDER);
    }

    public String aiModelCode() {
        return McNewsStore.str(doc(), KEY_AI_MODEL);
    }

    public int aiMaxItems() {
        return clamp(McNewsStore.intOr(doc(), KEY_AI_MAX_ITEMS, 10), 0, MAX_AI_ITEMS);
    }

    /** AI 整合时截取的文章正文最大字符数（默认 6000）。 */
    public int aiContentMaxChars() {
        return clamp(McNewsStore.intOr(doc(), KEY_AI_CONTENT_CHARS, 6000), MIN_CONTENT_CHARS, MAX_CONTENT_CHARS);
    }

    /** AI 系统提示词：未配置时返回内置默认。 */
    public String aiSystemPrompt() {
        return McNewsStore.strOr(doc(), KEY_AI_PROMPT, DEFAULT_AI_SYSTEM_PROMPT);
    }

    /** 推送消息模板：未配置时返回内置默认。 */
    public String messageTemplate() {
        return McNewsStore.strOr(doc(), KEY_TEMPLATE, DEFAULT_MESSAGE_TEMPLATE);
    }

    public long lastPollAt() {
        return McNewsStore.longOr(doc(), KEY_LAST_POLL_AT, 0);
    }

    public String lastPollSummary() {
        return McNewsStore.str(doc(), KEY_LAST_POLL_SUMMARY);
    }

    public void recordPoll(long at, String summary) {
        Map<String, Object> doc = new HashMap<>(doc());
        doc.remove("_id");
        doc.remove("pluginCode");
        doc.put(KEY_LAST_POLL_AT, at);
        doc.put(KEY_LAST_POLL_SUMMARY, summary);
        store.save(McNewsStore.COL_SETTINGS, McNewsStore.DOC_SETTINGS, doc);
    }

    public Map<String, Object> view() {
        Map<String, Object> view = new HashMap<>();
        view.put(KEY_ENABLED, enabled());
        view.put(KEY_INTERVAL, pollIntervalMinutes());
        view.put(KEY_CACHE_SIZE, cacheSize());
        view.put(KEY_PUSH_ON_FIRST, pushOnFirstPoll());
        view.put(KEY_AI_ENABLED, aiEnabled());
        view.put(KEY_AI_PROVIDER, aiProviderCode());
        view.put(KEY_AI_MODEL, aiModelCode());
        view.put(KEY_AI_MAX_ITEMS, aiMaxItems());
        view.put(KEY_AI_CONTENT_CHARS, aiContentMaxChars());
        view.put(KEY_AI_PROMPT, McNewsStore.str(doc(), KEY_AI_PROMPT));
        view.put(KEY_TEMPLATE, McNewsStore.str(doc(), KEY_TEMPLATE));
        view.put(KEY_LAST_POLL_AT, lastPollAt());
        view.put(KEY_LAST_POLL_SUMMARY, lastPollSummary());
        view.put("defaultTemplate", DEFAULT_MESSAGE_TEMPLATE);
        view.put("defaultAiPrompt", DEFAULT_AI_SYSTEM_PROMPT);
        return view;
    }

    /**
     * 合并更新：null 字段保持不变；模板/提示词传空字符串表示恢复内置默认。
     */
    public void update(Boolean enabled, Integer pollIntervalMinutes, Integer cacheSize, Boolean pushOnFirstPoll,
                       Boolean aiEnabled, String aiProviderCode, String aiModelCode, Integer aiMaxItems,
                       Integer aiContentMaxChars, String aiSystemPrompt, String messageTemplate) {
        Map<String, Object> doc = new HashMap<>(doc());
        doc.remove("_id");
        doc.remove("pluginCode");
        if (enabled != null) {
            doc.put(KEY_ENABLED, enabled);
        }
        if (pollIntervalMinutes != null) {
            doc.put(KEY_INTERVAL, clamp(pollIntervalMinutes, MIN_POLL_INTERVAL, MAX_POLL_INTERVAL));
        }
        if (cacheSize != null) {
            doc.put(KEY_CACHE_SIZE, clamp(cacheSize, MIN_CACHE_SIZE, MAX_CACHE_SIZE));
        }
        if (pushOnFirstPoll != null) {
            doc.put(KEY_PUSH_ON_FIRST, pushOnFirstPoll);
        }
        if (aiEnabled != null) {
            doc.put(KEY_AI_ENABLED, aiEnabled);
        }
        if (aiProviderCode != null) {
            applyOptional(doc, KEY_AI_PROVIDER, aiProviderCode);
        }
        if (aiModelCode != null) {
            applyOptional(doc, KEY_AI_MODEL, aiModelCode);
        }
        if (aiMaxItems != null) {
            doc.put(KEY_AI_MAX_ITEMS, clamp(aiMaxItems, 0, MAX_AI_ITEMS));
        }
        if (aiContentMaxChars != null) {
            doc.put(KEY_AI_CONTENT_CHARS, clamp(aiContentMaxChars, MIN_CONTENT_CHARS, MAX_CONTENT_CHARS));
        }
        if (aiSystemPrompt != null) {
            applyOptional(doc, KEY_AI_PROMPT, aiSystemPrompt);
        }
        if (messageTemplate != null) {
            applyOptional(doc, KEY_TEMPLATE, messageTemplate);
        }
        store.save(McNewsStore.COL_SETTINGS, McNewsStore.DOC_SETTINGS, doc);
    }

    private void applyOptional(Map<String, Object> doc, String key, String value) {
        if (value.isBlank()) {
            doc.remove(key);
        }
        else {
            doc.put(key, value.trim());
        }
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
