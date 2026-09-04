package online.yudream.base.plugin.timeline.application;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import online.yudream.base.plugin.timeline.domain.TimelineEvent;
import online.yudream.base.plugin.timeline.infrastructure.Ids;
import online.yudream.base.plugin.timeline.infrastructure.TimelineEventRepository;

/** 事件用例：边界校验、字段归一、CRUD 与公开查询。 */
public final class TimelineEventService {
    private static final int MAX_IMAGES = 12;
    private static final int MAX_DETAIL_LENGTH = 50_000;
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("uuuu-MM-dd")
            .withResolverStyle(ResolverStyle.STRICT);

    private final TimelineEventRepository events;

    public TimelineEventService(TimelineEventRepository events) {
        this.events = events;
    }

    /** 管理端分页查询：关键词匹配标题/简述/展示文案，status 支持 published/draft。 */
    public PageResult<TimelineEvent> queryAdmin(String keyword, String status, int page, int size) {
        String needle = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        List<TimelineEvent> filtered = events.listAll().stream()
                .filter(event -> needle.isEmpty()
                        || event.title().toLowerCase(Locale.ROOT).contains(needle)
                        || event.summary().toLowerCase(Locale.ROOT).contains(needle)
                        || event.dateLabel().toLowerCase(Locale.ROOT).contains(needle))
                .filter(event -> {
                    if ("published".equals(status)) {
                        return event.published();
                    }
                    if ("draft".equals(status)) {
                        return !event.published();
                    }
                    return true;
                })
                .toList();
        int from = Math.min((page - 1) * size, filtered.size());
        int to = Math.min(from + size, filtered.size());
        return PageResult.of(filtered.subList(from, to), filtered.size());
    }

    /** 公开端：仅已发布事件，按时间轴顺序（新→旧）全量返回。 */
    public List<TimelineEvent> listPublished() {
        return events.listAll().stream().filter(TimelineEvent::published).toList();
    }

    public TimelineEvent require(String id) {
        if (id == null || id.isBlank()) {
            throw new NotFoundException("事件不存在或已删除");
        }
        return events.findById(id).orElseThrow(() -> new NotFoundException("事件不存在或已删除"));
    }

    /** 公开端详情：未发布的事件对外等同不存在。 */
    public TimelineEvent requirePublished(String id) {
        TimelineEvent event = require(id);
        if (!event.published()) {
            throw new NotFoundException("事件不存在或尚未发布");
        }
        return event;
    }

    public TimelineEvent create(TimelineEventPayload payload) {
        NormalizedEvent normalized = normalize(payload);
        long now = System.currentTimeMillis();
        TimelineEvent event = new TimelineEvent(Ids.newId(), normalized.title(), normalized.summary(),
                normalized.eventDate(), normalized.dateLabel(), normalized.coverImage(), normalized.images(),
                normalized.detail(), normalized.published(), normalized.sort(), now, now);
        events.save(event);
        return event;
    }

    public TimelineEvent update(String id, TimelineEventPayload payload) {
        TimelineEvent existing = require(id);
        NormalizedEvent normalized = normalize(payload);
        TimelineEvent updated = new TimelineEvent(existing.id(), normalized.title(), normalized.summary(),
                normalized.eventDate(), normalized.dateLabel(), normalized.coverImage(), normalized.images(),
                normalized.detail(), normalized.published(), normalized.sort(),
                existing.createdAt(), System.currentTimeMillis());
        events.save(updated);
        return updated;
    }

    public TimelineEvent setPublished(String id, boolean published) {
        TimelineEvent existing = require(id);
        TimelineEvent updated = new TimelineEvent(existing.id(), existing.title(), existing.summary(),
                existing.eventDate(), existing.dateLabel(), existing.coverImage(), existing.images(),
                existing.detail(), published, existing.sort(), existing.createdAt(), System.currentTimeMillis());
        events.save(updated);
        return updated;
    }

    public void delete(String id) {
        require(id);
        events.delete(id);
    }

    private NormalizedEvent normalize(TimelineEventPayload payload) {
        if (payload == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        String title = trimTo(payload.title(), "");
        if (title.isEmpty()) {
            throw new IllegalArgumentException("标题不能为空");
        }
        if (title.length() > 60) {
            throw new IllegalArgumentException("标题不能超过 60 字");
        }
        String summary = trimTo(payload.summary(), "");
        if (summary.length() > 200) {
            throw new IllegalArgumentException("简述不能超过 200 字");
        }
        String eventDate = trimTo(payload.eventDate(), "");
        if (eventDate.isEmpty()) {
            throw new IllegalArgumentException("事件时间不能为空");
        }
        try {
            eventDate = LocalDate.parse(eventDate, DATE_FORMAT).format(DATE_FORMAT);
        }
        catch (Exception e) {
            throw new IllegalArgumentException("事件时间格式不正确，应为 YYYY-MM-DD");
        }
        String dateLabel = trimTo(payload.dateLabel(), "");
        if (dateLabel.length() > 40) {
            throw new IllegalArgumentException("时间展示文案不能超过 40 字");
        }
        String coverImage = normalizeImageUrl(payload.coverImage());
        List<String> images = new ArrayList<>();
        for (String image : payload.images() == null ? List.<String>of() : payload.images()) {
            String normalized = normalizeImageUrl(image);
            if (!normalized.isEmpty()) {
                images.add(normalized);
            }
        }
        if (images.size() > MAX_IMAGES) {
            throw new IllegalArgumentException("图集最多 " + MAX_IMAGES + " 张图片");
        }
        String detail = trimTo(payload.detail(), "");
        if (detail.length() > MAX_DETAIL_LENGTH) {
            throw new IllegalArgumentException("详情内容过长");
        }
        boolean published = payload.published() != null && payload.published();
        int sort = payload.sort() == null ? 0 : Math.max(-9999, Math.min(9999, payload.sort()));
        return new NormalizedEvent(title, summary, eventDate, dateLabel, coverImage, List.copyOf(images), detail,
                published, sort);
    }

    /** 图片地址只接受本站文件相对路径或 http(s) 绝对地址，防 javascript: 等伪协议注入。 */
    private static String normalizeImageUrl(String raw) {
        String value = trimTo(raw, "");
        if (value.isEmpty()) {
            return "";
        }
        if (value.length() > 500) {
            throw new IllegalArgumentException("图片地址过长");
        }
        if (value.startsWith("/api/files/") || value.startsWith("https://") || value.startsWith("http://")) {
            return value;
        }
        throw new IllegalArgumentException("图片地址不合法，请通过上传添加图片");
    }

    private static String trimTo(String value, String fallback) {
        return value == null ? fallback : value.trim();
    }

    /** 归一后的不可变字段组。 */
    private record NormalizedEvent(String title, String summary, String eventDate, String dateLabel,
                                   String coverImage, List<String> images, String detail,
                                   boolean published, int sort) {
    }
}
