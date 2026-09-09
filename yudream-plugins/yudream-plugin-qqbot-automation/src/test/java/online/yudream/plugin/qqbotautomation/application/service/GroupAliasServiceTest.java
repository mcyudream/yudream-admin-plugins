package online.yudream.plugin.qqbotautomation.application.service;

import online.yudream.base.plugin.spi.system.FrameworkServices;
import online.yudream.base.plugin.spi.system.messaging.PluginMessageContent;
import online.yudream.base.plugin.spi.system.messaging.PluginMessageResult;
import online.yudream.base.plugin.spi.system.messaging.PluginMessagingGroup;
import online.yudream.base.plugin.spi.system.messaging.PluginMessagingService;
import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GroupAliasServiceTest {

    @Test
    void listPrefersLocalAliasOverOpenidWhenOfficialNameIsMissing() {
        InMemoryDocuments documents = new InMemoryDocuments();
        GroupAliasService service = new GroupAliasService(documents, framework(List.of(
                new PluginMessagingGroup("33E820F28C651174394254D0CCCA0E9B", "33E820F28C651174394254D0CCCA0E9B"),
                new PluginMessagingGroup("E9207C648EFA2DA99000EAD052A9B40C", "E9207C648EFA2DA99000EAD052A9B40C")
        ), null));
        service.save("conn-a", "33E820F28C651174394254D0CCCA0E9B", "告警群");

        List<Map<String, Object>> rows = service.list("conn-a");

        assertEquals("告警群", rows.getFirst().get("name"));
        assertEquals("告警群", rows.getFirst().get("alias"));
        assertEquals("", rows.getFirst().get("sourceName"));
        assertEquals("E9207C648EFA2DA99000EAD052A9B40C", rows.get(1).get("name"));
    }

    @Test
    void identifySendsOpenidAndAliasIntoTheSelectedGroup() {
        InMemoryDocuments documents = new InMemoryDocuments();
        AtomicReference<String> channelId = new AtomicReference<>();
        AtomicReference<PluginMessageContent> content = new AtomicReference<>();
        GroupAliasService service = new GroupAliasService(documents, framework(List.of(
                new PluginMessagingGroup("g-open", "g-open")
        ), (connection, channel, message) -> {
            channelId.set(channel);
            content.set(message);
            return new PluginMessageResult(List.of("m-1"), true, false);
        }));
        service.save("conn-a", "g-open", "运营群");

        Map<String, Object> result = service.identify("conn-a", "g-open");

        assertEquals(true, result.get("sent"));
        assertEquals("g-open", channelId.get());
        assertTrue(content.get().content().contains("openid：g-open"));
        assertTrue(content.get().content().contains("后台备注：运营群"));
        assertEquals("group", content.get().referrer().get("message_scene"));
    }

    @Test
    void blankAliasRemovesStoredNote() {
        InMemoryDocuments documents = new InMemoryDocuments();
        GroupAliasService service = new GroupAliasService(documents, framework(List.of(
                new PluginMessagingGroup("g-open", "读书会")
        ), null));
        service.save("conn-a", "g-open", "临时名");
        service.save("conn-a", "g-open", "  ");

        Map<String, Object> row = service.list("conn-a").getFirst();
        assertEquals("读书会", row.get("name"));
        assertEquals("", row.get("alias"));
        assertEquals("读书会", row.get("sourceName"));
    }

    @Test
    void rejectsAliasLongerThanFortyCharacters() {
        GroupAliasService service = new GroupAliasService(new InMemoryDocuments(), framework(List.of(), null));
        assertThrows(IllegalArgumentException.class, () -> service.save("conn-a", "g-open", "一二三四五六七八九十一二三四五六七八九十一二三四五六七八九十一二三四五六七八九十1"));
    }

    private FrameworkServices framework(List<PluginMessagingGroup> groups, IdentifyHandler identify) {
        PluginMessagingService messaging = (PluginMessagingService) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{PluginMessagingService.class},
                (proxy, method, args) -> {
                    if ("groups".equals(method.getName())) {
                        return groups;
                    }
                    if ("sendToChannel".equals(method.getName())) {
                        if (identify == null) {
                            throw new IllegalStateException("identify not expected");
                        }
                        return CompletableFuture.completedFuture(identify.send(String.valueOf(args[0]), String.valueOf(args[1]), (PluginMessageContent) args[2]));
                    }
                    return null;
                });
        return (FrameworkServices) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{FrameworkServices.class},
                (proxy, method, args) -> "messaging".equals(method.getName()) ? messaging : null);
    }

    @FunctionalInterface
    private interface IdentifyHandler {
        PluginMessageResult send(String connectionId, String channelId, PluginMessageContent content);
    }

    private static final class InMemoryDocuments implements PluginDocumentStore {
        private final Map<String, Map<String, Object>> values = new HashMap<>();

        @Override
        public Map<String, Object> save(String collection, String id, Map<String, Object> document) {
            Map<String, Object> copy = new HashMap<>(document);
            values.put(key(collection, id), copy);
            return copy;
        }

        @Override
        public Optional<Map<String, Object>> findById(String collection, String id) {
            return Optional.ofNullable(values.get(key(collection, id))).map(HashMap::new);
        }

        @Override
        public List<Map<String, Object>> findAll(String collection, int page, int size) {
            List<Map<String, Object>> matching = values.entrySet().stream()
                    .filter(entry -> entry.getKey().startsWith(collection + ":"))
                    .<Map<String, Object>>map(entry -> new HashMap<>(entry.getValue()))
                    .toList();
            int from = Math.min(Math.max(page - 1, 0) * size, matching.size());
            return new ArrayList<>(matching.subList(from, Math.min(from + size, matching.size())));
        }

        @Override
        public List<Map<String, Object>> findByField(String collection, String field, Object value, int page, int size) {
            List<Map<String, Object>> matching = values.entrySet().stream()
                    .filter(entry -> entry.getKey().startsWith(collection + ":"))
                    .map(Map.Entry::getValue)
                    .filter(document -> java.util.Objects.equals(document.get(field), value))
                    .<Map<String, Object>>map(HashMap::new)
                    .toList();
            int from = Math.min(Math.max(page - 1, 0) * size, matching.size());
            return new ArrayList<>(matching.subList(from, Math.min(from + size, matching.size())));
        }

        @Override
        public long count(String collection) {
            return values.keySet().stream().filter(key -> key.startsWith(collection + ":")).count();
        }

        @Override
        public void delete(String collection, String id) {
            values.remove(key(collection, id));
        }

        private String key(String collection, String id) {
            return collection + ":" + id;
        }
    }
}
