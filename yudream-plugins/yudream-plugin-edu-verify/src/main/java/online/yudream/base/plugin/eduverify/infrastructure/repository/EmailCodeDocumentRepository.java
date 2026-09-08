package online.yudream.base.plugin.eduverify.infrastructure.repository;

import online.yudream.base.plugin.eduverify.domain.aggregate.EmailCode;
import online.yudream.base.plugin.eduverify.domain.repo.EmailCodeRepository;
import online.yudream.base.plugin.eduverify.infrastructure.support.DocValues;
import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public class EmailCodeDocumentRepository implements EmailCodeRepository {

    private static final String COLLECTION = "email_codes";

    private final PluginDocumentStore documents;

    public EmailCodeDocumentRepository(PluginDocumentStore documents) {
        this.documents = documents;
    }

    @Override
    public EmailCode save(EmailCode code) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("codeHash", code.codeHash());
        document.put("expiresAt", code.expiresAt());
        document.put("attempts", code.attempts());
        document.put("sendCount", code.sendCount());
        document.put("dayBucket", code.dayBucket());
        document.put("lastSendAt", code.lastSendAt());
        document.put("createdAt", code.createdAt());
        return toCode(documents.save(COLLECTION, code.id(), document));
    }

    @Override
    public Optional<EmailCode> findByEmail(String emailLower) {
        if (emailLower == null || emailLower.isBlank()) {
            return Optional.empty();
        }
        return documents.findById(COLLECTION, emailLower.trim()).map(this::toCode);
    }

    @Override
    public void delete(String emailLower) {
        documents.delete(COLLECTION, emailLower);
    }

    private EmailCode toCode(Map<String, Object> document) {
        return new EmailCode(
                DocValues.string(document, "id"),
                DocValues.string(document, "codeHash"),
                DocValues.number(document, "expiresAt", 0L),
                DocValues.integer(document, "attempts", 0),
                DocValues.integer(document, "sendCount", 0),
                DocValues.string(document, "dayBucket"),
                DocValues.number(document, "lastSendAt", 0L),
                DocValues.number(document, "createdAt", 0L)
        );
    }
}
