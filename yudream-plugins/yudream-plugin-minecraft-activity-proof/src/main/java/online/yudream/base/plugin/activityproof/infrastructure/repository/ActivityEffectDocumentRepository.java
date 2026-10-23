package online.yudream.base.plugin.activityproof.infrastructure.repository;

import online.yudream.base.plugin.activityproof.domain.aggregate.ActivityEffect;
import online.yudream.base.plugin.activityproof.domain.repo.ActivityEffectRepository;
import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 活动效果提交的文档存储实现；集合名由宿主自动加插件前缀，这里只传短名。 */
public class ActivityEffectDocumentRepository implements ActivityEffectRepository {

    private static final String EFFECTS = "effects";
    private static final int SCAN_PAGE_SIZE = 200;

    private final PluginDocumentStore documents;

    public ActivityEffectDocumentRepository(PluginDocumentStore documents) {
        this.documents = documents;
    }

    @Override
    public Optional<ActivityEffect> effect(String id) {
        return documents.findById(EFFECTS, id).map(ActivityEffectDocumentRepository::toEffect);
    }

    @Override
    public List<ActivityEffect> effectsByActivity(String activityId, int page, int size) {
        return paged(toEffects(documents.findByField(EFFECTS, "activityId", activityId, 1, Integer.MAX_VALUE)),
                page, size);
    }

    @Override
    public long countEffectsByActivity(String activityId) {
        return documents.findByField(EFFECTS, "activityId", activityId, 1, Integer.MAX_VALUE).size();
    }

    @Override
    public List<ActivityEffect> effectsBySubmitter(String userId, int page, int size) {
        return paged(toEffects(documents.findByField(EFFECTS, "submittedBy", userId, 1, Integer.MAX_VALUE)),
                page, size);
    }

    @Override
    public long countEffectsBySubmitter(String userId) {
        return documents.findByField(EFFECTS, "submittedBy", userId, 1, Integer.MAX_VALUE).size();
    }

    @Override
    public List<ActivityEffect> effects(String activityId, String status, int page, int size) {
        return paged(filter(allSorted(), activityId, status), page, size);
    }

    @Override
    public long countEffects(String activityId, String status) {
        return filter(allSorted(), activityId, status).size();
    }

    @Override
    public List<ActivityEffect> allEffects() {
        return allSorted();
    }

    @Override
    public ActivityEffect save(ActivityEffect effect) {
        return toEffect(documents.save(EFFECTS, effect.id(), effectDocument(effect)));
    }

    @Override
    public void delete(String id) {
        documents.delete(EFFECTS, id);
    }

    private List<ActivityEffect> allSorted() {
        List<ActivityEffect> effects = new ArrayList<>();
        for (int page = 1; ; page++) {
            List<Map<String, Object>> batch = documents.findAll(EFFECTS, page, SCAN_PAGE_SIZE);
            if (batch == null || batch.isEmpty()) {
                break;
            }
            batch.stream().map(ActivityEffectDocumentRepository::toEffect).forEach(effects::add);
            if (batch.size() < SCAN_PAGE_SIZE) {
                break;
            }
        }
        effects.sort(Comparator.comparingLong(ActivityEffect::submittedAt).reversed());
        return effects;
    }

    private List<ActivityEffect> toEffects(List<Map<String, Object>> documents) {
        List<ActivityEffect> effects = new ArrayList<>();
        for (Map<String, Object> document : documents) {
            effects.add(toEffect(document));
        }
        return effects;
    }

    private List<ActivityEffect> filter(List<ActivityEffect> source, String activityId, String status) {
        List<ActivityEffect> result = new ArrayList<>();
        for (ActivityEffect effect : source) {
            if (activityId != null && !activityId.isBlank() && !activityId.equals(effect.activityId())) {
                continue;
            }
            if (status != null && !status.isBlank() && !status.equals(effect.status())) {
                continue;
            }
            result.add(effect);
        }
        return result;
    }

    private List<ActivityEffect> paged(List<ActivityEffect> records, int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.max(Math.min(size <= 0 ? 20 : size, 200), 1);
        int from = Math.min((safePage - 1) * safeSize, records.size());
        int to = Math.min(from + safeSize, records.size());
        return records.subList(from, to);
    }

    private Map<String, Object> effectDocument(ActivityEffect effect) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("id", effect.id());
        document.put("activityId", effect.activityId());
        document.put("submittedBy", effect.submittedBy());
        document.put("submittedAt", effect.submittedAt());
        document.put("text", effect.text());
        document.put("status", effect.status());
        document.put("reviewedBy", effect.reviewedBy());
        document.put("reviewedAt", effect.reviewedAt());
        document.put("reviewNote", effect.reviewNote());
        List<Map<String, Object>> attachments = new ArrayList<>();
        for (ActivityEffect.Attachment attachment : effect.attachments()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("fileKey", attachment.fileKey());
            item.put("filename", attachment.filename());
            item.put("contentType", attachment.contentType());
            item.put("size", attachment.size());
            item.put("kind", attachment.kind());
            item.put("caption", attachment.caption());
            attachments.add(item);
        }
        document.put("attachments", attachments);
        return stripNulls(document);
    }

    private static ActivityEffect toEffect(Map<String, Object> document) {
        List<ActivityEffect.Attachment> attachments = new ArrayList<>();
        Object raw = document.get("attachments");
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    attachments.add(new ActivityEffect.Attachment(
                            text(map, "fileKey"), text(map, "filename"), text(map, "contentType"),
                            number(map, "size"), text(map, "kind"), text(map, "caption")));
                }
            }
        }
        return new ActivityEffect(
                text(document, "id"), text(document, "activityId"), text(document, "submittedBy"),
                number(document, "submittedAt"), text(document, "text"), attachments,
                text(document, "status"), text(document, "reviewedBy"), number(document, "reviewedAt"),
                text(document, "reviewNote"));
    }

    private static String text(Map<?, ?> document, String key) {
        Object value = document.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private static long number(Map<?, ?> document, String key) {
        Object value = document.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return value == null ? 0 : Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static Map<String, Object> stripNulls(Map<String, Object> document) {
        Map<String, Object> result = new LinkedHashMap<>();
        document.forEach((key, value) -> {
            if (value != null) {
                result.put(key, value);
            }
        });
        return result;
    }
}
