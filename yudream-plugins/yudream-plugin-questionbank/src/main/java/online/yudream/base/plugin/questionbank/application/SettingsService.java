package online.yudream.base.plugin.questionbank.application;

import java.util.HashMap;
import java.util.Map;
import online.yudream.base.plugin.questionbank.domain.DocValues;
import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;

/**
 * 插件全局设置（qb_settings/global）。practiceEnabled 控制自由刷题入口（关闭后题单作答不受影响）；
 * aiProviderCode/aiModelCode 为 AI 判分可选的供应商与模型，留空走宿主默认配置。
 */
public final class SettingsService {
    private static final String COLLECTION = "qb_settings";
    private static final String DOC_ID = "global";
    private static final String KEY_PRACTICE_ENABLED = "practiceEnabled";
    private static final String KEY_AI_PROVIDER = "aiProviderCode";
    private static final String KEY_AI_MODEL = "aiModelCode";
    private static final String KEY_QQ_GROUPS = "qqGroups";
    private static final String KEY_QQ_DEFAULT_GROUP = "qqDefaultGroup";
    private static final String KEY_QQ_ANSWER_SECONDS = "qqAnswerSeconds";
    private static final String KEY_QQ_AI_GRADING = "qqAiGrading";
    private static final int DEFAULT_QQ_ANSWER_SECONDS = 60;

    private final PluginDocumentStore documents;

    public SettingsService(PluginDocumentStore documents) {
        this.documents = documents;
    }

    public boolean practiceEnabled() {
        return documents.findById(COLLECTION, DOC_ID)
                .map(doc -> DocValues.bool(doc, KEY_PRACTICE_ENABLED, true))
                .orElse(true);
    }

    /** AI 判分使用的供应商编码；未配置返回 null，由宿主选择默认供应商。 */
    public String aiProviderCode() {
        return setting(KEY_AI_PROVIDER);
    }

    /** AI 判分使用的模型编码；未配置返回 null，由宿主选择默认模型。 */
    public String aiModelCode() {
        return setting(KEY_AI_MODEL);
    }

    /** QQ 抽题分组：名称 → 分类/标签规则（Map 含 name/categoryId/tags）。 */
    public java.util.List<Map<String, Object>> qqGroups() {
        return documents.findById(COLLECTION, DOC_ID)
                .map(doc -> DocValues.mapList(doc, KEY_QQ_GROUPS))
                .orElse(java.util.List.of());
    }

    /** QQ 抽题默认分组名；未配置返回 null（表示全库抽题）。 */
    public String qqDefaultGroup() {
        return setting(KEY_QQ_DEFAULT_GROUP);
    }

    /** QQ 答题限时（秒），默认 60。 */
    public int qqAnswerSeconds() {
        return documents.findById(COLLECTION, DOC_ID)
                .map(doc -> DocValues.integerOr(doc, KEY_QQ_ANSWER_SECONDS, DEFAULT_QQ_ANSWER_SECONDS))
                .orElse(DEFAULT_QQ_ANSWER_SECONDS);
    }

    /** QQ 简答题是否走 AI 判分，默认开启；关闭或 AI 不可用时公布参考答案。 */
    public boolean qqAiGrading() {
        return documents.findById(COLLECTION, DOC_ID)
                .map(doc -> DocValues.bool(doc, KEY_QQ_AI_GRADING, true))
                .orElse(true);
    }

    public Map<String, Object> settingsView() {
        Map<String, Object> view = new HashMap<>();
        view.put(KEY_PRACTICE_ENABLED, practiceEnabled());
        view.put(KEY_AI_PROVIDER, aiProviderCode());
        view.put(KEY_AI_MODEL, aiModelCode());
        view.put(KEY_QQ_GROUPS, qqGroups());
        view.put(KEY_QQ_DEFAULT_GROUP, qqDefaultGroup());
        view.put(KEY_QQ_ANSWER_SECONDS, qqAnswerSeconds());
        view.put(KEY_QQ_AI_GRADING, qqAiGrading());
        return view;
    }

    /** 合并更新：null 字段保持不变；provider/model/defaultGroup 传空字符串表示清空。 */
    public void update(Boolean practiceEnabled, String aiProviderCode, String aiModelCode,
                       java.util.List<Map<String, Object>> qqGroups, String qqDefaultGroup,
                       Integer qqAnswerSeconds, Boolean qqAiGrading) {
        Map<String, Object> doc = new HashMap<>(documents.findById(COLLECTION, DOC_ID).orElse(Map.of()));
        doc.remove("id");
        doc.remove("_id");
        doc.remove("pluginCode");
        if (practiceEnabled != null) {
            doc.put(KEY_PRACTICE_ENABLED, practiceEnabled);
        }
        if (aiProviderCode != null) {
            applyOptional(doc, KEY_AI_PROVIDER, aiProviderCode);
        }
        if (aiModelCode != null) {
            applyOptional(doc, KEY_AI_MODEL, aiModelCode);
        }
        if (qqGroups != null) {
            java.util.List<Map<String, Object>> normalized = new java.util.ArrayList<>();
            for (Map<String, Object> group : qqGroups) {
                if (group == null) {
                    continue;
                }
                String name = DocValues.str(group, "name");
                if (name == null || name.isBlank()) {
                    continue;
                }
                Map<String, Object> entry = new HashMap<>();
                entry.put("name", name.trim());
                String categoryId = DocValues.str(group, "categoryId");
                if (categoryId != null && !categoryId.isBlank()) {
                    entry.put("categoryId", categoryId.trim());
                }
                entry.put("tags", DocValues.stringList(group, "tags"));
                normalized.add(entry);
            }
            doc.put(KEY_QQ_GROUPS, normalized);
        }
        if (qqDefaultGroup != null) {
            applyOptional(doc, KEY_QQ_DEFAULT_GROUP, qqDefaultGroup);
        }
        if (qqAnswerSeconds != null) {
            doc.put(KEY_QQ_ANSWER_SECONDS, Math.max(10, Math.min(600, qqAnswerSeconds)));
        }
        if (qqAiGrading != null) {
            doc.put(KEY_QQ_AI_GRADING, qqAiGrading);
        }
        documents.save(COLLECTION, DOC_ID, doc);
    }

    /** 空串视为清空；文档存储不接受 null 值，必须移除键。 */
    private void applyOptional(Map<String, Object> doc, String key, String value) {
        if (value.isBlank()) {
            doc.remove(key);
        }
        else {
            doc.put(key, value.trim());
        }
    }

    /** 刷题开关关闭时阻断自由练习入口。 */
    public void requirePracticeEnabled() {
        if (!practiceEnabled()) {
            throw new IllegalStateException("自由刷题功能已关闭，请通过题单进行作答");
        }
    }

    private String setting(String key) {
        return documents.findById(COLLECTION, DOC_ID)
                .map(doc -> DocValues.str(doc, key))
                .orElse(null);
    }
}
