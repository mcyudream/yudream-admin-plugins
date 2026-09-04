package online.yudream.base.plugin.timeline.infrastructure;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import online.yudream.base.plugin.timeline.domain.TimelineEvent;
import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;

public final class TimelineEventRepository extends AbstractDocumentRepository<TimelineEvent> {
    public static final String COLLECTION = "timeline_events";

    /** 事件时间倒序（新→旧）；同日期内 sort 大者靠前，再按创建时间倒序。 */
    private static final Comparator<TimelineEvent> TIMELINE_ORDER = Comparator
            .comparing(TimelineEvent::eventDate, Comparator.reverseOrder())
            .thenComparing(TimelineEvent::sort, Comparator.reverseOrder())
            .thenComparing(TimelineEvent::createdAt, Comparator.reverseOrder());

    public TimelineEventRepository(PluginDocumentStore documents) {
        super(documents, COLLECTION);
    }

    public void save(TimelineEvent event) {
        save(event.id(), event, TimelineEvent::toDoc);
    }

    public Optional<TimelineEvent> findById(String id) {
        return findDoc(id).map(TimelineEvent::fromDoc);
    }

    public List<TimelineEvent> listAll() {
        return scanAllDocs().stream()
                .map(TimelineEvent::fromDoc)
                .sorted(TIMELINE_ORDER)
                .toList();
    }
}
