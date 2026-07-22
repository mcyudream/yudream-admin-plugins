package online.yudream.plugin.webcard.application;

import online.yudream.base.plugin.spi.system.secret.PluginSecretStore;
import online.yudream.plugin.webcard.domain.WebCardModels.*;
import online.yudream.plugin.webcard.domain.WebCardRepository;
import online.yudream.plugin.webcard.infrastructure.SecretHeaderStore;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static online.yudream.plugin.webcard.application.WebCardApplicationService.*;
import static org.junit.jupiter.api.Assertions.*;

class WebCardApplicationServiceCascadeDeleteTest {
    @Test
    void deletingTemplateClearsAllVersionsAndReferences() {
        MemoryRepository repository = new MemoryRepository();
        WebCardApplicationService service = service(repository, new ArrayList<>());
        long now = System.currentTimeMillis();
        repository.save(SITES, "site", new Site("site", "Site", true, List.of("example.com"), AccessMode.PUBLIC_HTTP,
                List.of(), null, SourceType.HTML, List.of(), "template", now, now));
        repository.save(TEMPLATES, "template", new Template("template", "site", "Template", TemplateMode.STRUCTURED, "version-201", "version-201", now, now));
        for (int index = 1; index <= 201; index++) {
            String versionId = "version-" + index;
            repository.save(VERSIONS, versionId, new TemplateVersion(versionId, "template", index, null, TemplateMode.STRUCTURED,
                    "{}", "", "", Map.of(), "TEST", "", true, now));
        }
        repository.save(CONTENTS, "content", new ContentRecord("content", "site", "https://example.com/1", "1", Map.of(), "version-201", now, now));
        repository.save(DELIVERIES, "delivery", new DeliveryRecord("delivery", "content", "binding", "version-201", DeliveryStage.DELIVERED, null, 1, null, 0, now, now));
        repository.save(BINDINGS, "binding", new GroupBinding("binding", "site", "connection", "qq", "bot", "group", true,
                "version-201", null, null, 0, 0, 0, now, now));

        service.deleteTemplate("template");

        assertTrue(repository.find(TEMPLATES, "template", Template.class).isEmpty());
        assertTrue(repository.findBy(VERSIONS, "templateId", "template", 1, 200, TemplateVersion.class).isEmpty());
        assertTrue(repository.find(CONTENTS, "content", ContentRecord.class).isEmpty());
        assertTrue(repository.find(DELIVERIES, "delivery", DeliveryRecord.class).isEmpty());
        assertNull(repository.find(SITES, "site", Site.class).orElseThrow().defaultTemplateId());
        assertNull(repository.find(BINDINGS, "binding", GroupBinding.class).orElseThrow().templateVersionId());
    }

    @Test
    void deletingSiteRemovesOwnedRecordsAndSecret() {
        MemoryRepository repository = new MemoryRepository();
        List<String> deletedSecrets = new ArrayList<>();
        WebCardApplicationService service = service(repository, deletedSecrets);
        long now = System.currentTimeMillis();
        repository.save(SITES, "site", new Site("site", "Site", true, List.of("example.com"), AccessMode.CUSTOM_HEADERS,
                List.of("Authorization"), "headers:site", SourceType.HTML, List.of(), null, now, now));
        repository.save(RULES, "site", new ParseRules("site", SourceType.HTML, List.of(), null, null, null, null, null));
        repository.save(JOBS, "job", new CrawlJob("job", "site", "https://example.com/feed", SourceType.RSS, true, 10, 3, now, null, 0, false, now, now));
        repository.save(BINDINGS, "binding", new GroupBinding("binding", "site", "connection", "qq", "bot", "group", true, null, null, null, 0, 0, 0, now, now));
        repository.save(CONTENTS, "content", new ContentRecord("content", "site", "https://example.com/1", "1", Map.of(), null, now, now));
        repository.save(DELIVERIES, "delivery", new DeliveryRecord("delivery", "content", "binding", null, DeliveryStage.DELIVERED, null, 1, null, 0, now, now));

        service.deleteSite("site");

        assertTrue(repository.find(SITES, "site", Site.class).isEmpty());
        assertTrue(repository.find(RULES, "site", ParseRules.class).isEmpty());
        assertEquals(0, repository.count(JOBS));
        assertEquals(0, repository.count(BINDINGS));
        assertEquals(0, repository.count(CONTENTS));
        assertEquals(0, repository.count(DELIVERIES));
        assertEquals(List.of("headers:site"), deletedSecrets);
    }

    @Test
    void clientCannotOverwritePublishedVersionOrTemplatePointers() {
        MemoryRepository repository = new MemoryRepository();
        WebCardApplicationService service = service(repository, new ArrayList<>());
        long now = System.currentTimeMillis();
        repository.save(SITES, "site", new Site("site", "Site", true, List.of("example.com"), AccessMode.PUBLIC_HTTP,
                List.of(), null, SourceType.HTML, List.of(), "template", now, now));
        repository.save(TEMPLATES, "template", new Template("template", "site", "Template", TemplateMode.STRUCTURED, "published", "published", now, now));
        repository.save(VERSIONS, "published", new TemplateVersion("published", "template", 7, null, TemplateMode.STRUCTURED,
                "{}", "", "", Map.of(), "TEST", "published", true, now));

        Template savedTemplate = service.saveTemplate(new Template("template", "site", "Renamed", TemplateMode.ADVANCED,
                "attacker-draft", "attacker-published", 0, 0));
        TemplateVersion savedVersion = service.saveVersion(new TemplateVersion("published", "template", 99, null, TemplateMode.ADVANCED,
                "{}", "<article id=\"web-card\">New</article>", "", Map.of(), "MANUAL", "new", true, 0));

        assertEquals("published", savedTemplate.draftVersionId());
        assertEquals("published", savedTemplate.publishedVersionId());
        assertNotEquals("published", savedVersion.id());
        assertEquals(8, savedVersion.version());
        assertEquals("published", repository.find(VERSIONS, "published", TemplateVersion.class).orElseThrow().summary());
        assertFalse(savedVersion.previewPassed());
        ParseRules foreignRules = new ParseRules("other-site", SourceType.HTML, List.of(), null, null, null, null, null);
        assertThrows(IllegalArgumentException.class, () -> service.saveVersion(new TemplateVersion(null, "template", 0, foreignRules,
                TemplateMode.STRUCTURED, "{}", "", "", Map.of(), "MANUAL", "foreign", false, 0)));

        WebCardApplicationService previewService = WebCardApplicationService.forTesting(repository, null, null, null, (value, fields) -> "rendered");
        TemplateVersion verified = (TemplateVersion) previewService.preview("published", Map.of("title", "Verified")).get("version");
        assertNotEquals("published", verified.id());
        assertTrue(verified.previewPassed());
        assertEquals(Map.of(), repository.find(VERSIONS, "published", TemplateVersion.class).orElseThrow().fixture());
    }

    private static WebCardApplicationService service(MemoryRepository repository, List<String> deletedSecrets) {
        PluginSecretStore secretStore = (PluginSecretStore) Proxy.newProxyInstance(
                PluginSecretStore.class.getClassLoader(), new Class<?>[]{PluginSecretStore.class}, (proxy, method, args) -> {
                    if (method.getName().equals("delete")) deletedSecrets.add((String) args[0]);
                    if (method.getReturnType().equals(Optional.class)) return Optional.empty();
                    if (method.getReturnType().equals(boolean.class)) return true;
                    return null;
                });
        return new WebCardApplicationService(repository, new SecretHeaderStore(secretStore), null, null, null, null);
    }

    private static final class MemoryRepository implements WebCardRepository {
        private final Map<String, Map<String, Object>> collections = new LinkedHashMap<>();

        @Override public <T> T save(String collection, String id, T value) { collections.computeIfAbsent(collection, ignored -> new LinkedHashMap<>()).put(id, value); return value; }
        @Override public <T> Optional<T> find(String collection, String id, Class<T> type) { return Optional.ofNullable(type.cast(collection(collection).get(id))); }
        @Override public <T> List<T> page(String collection, int page, int size, Class<T> type) { return slice(collection(collection).values().stream().map(type::cast).toList(), page, size); }
        @Override public <T> List<T> findBy(String collection, String field, Object value, int page, int size, Class<T> type) {
            List<T> matches = collection(collection).values().stream().map(type::cast).filter(record -> value.equals(read(record, field))).toList();
            return slice(matches, page, size);
        }
        @Override public long count(String collection) { return collection(collection).size(); }
        @Override public <T> boolean updateIfFieldAtMost(String collection, String id, String field, long maximum, T value) { save(collection, id, value); return true; }
        @Override public void delete(String collection, String id) { collection(collection).remove(id); }

        private Map<String, Object> collection(String name) { return collections.computeIfAbsent(name, ignored -> new LinkedHashMap<>()); }
        private static Object read(Object record, String field) {
            try { return record.getClass().getMethod(field).invoke(record); }
            catch (ReflectiveOperationException exception) { throw new AssertionError(exception); }
        }
        private static <T> List<T> slice(List<T> values, int page, int size) {
            int start = Math.min(values.size(), Math.max(0, page - 1) * size);
            return values.subList(start, Math.min(values.size(), start + size));
        }
    }
}
