package online.yudream.base.plugin.cas.infrastructure.repository;

import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;
import online.yudream.base.plugin.cas.domain.aggregate.AccessControl;
import online.yudream.base.plugin.cas.domain.repo.AccessControlRepository;
import online.yudream.base.plugin.cas.infrastructure.support.DocValues;

import java.util.LinkedHashMap;
import java.util.Map;

/** 访问控制配置持久化（collection {@code access-control}）。 */
public final class AccessControlDocumentRepository implements AccessControlRepository {

    static final String COLLECTION = "access-control";
    static final String ID = "global";

    private final PluginDocumentStore documents;

    public AccessControlDocumentRepository(PluginDocumentStore documents) {
        this.documents = documents;
    }

    @Override
    public AccessControl get() {
        return documents.findById(COLLECTION, ID)
                .map(document -> new AccessControl(DocValues.bool(document, "requireBinding", false)))
                .orElseGet(AccessControl::defaults);
    }

    @Override
    public AccessControl save(AccessControl control) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("requireBinding", control.requireBinding());
        return new AccessControl(
                DocValues.bool(documents.save(COLLECTION, ID, DocValues.stripNulls(document)), "requireBinding", false));
    }
}
