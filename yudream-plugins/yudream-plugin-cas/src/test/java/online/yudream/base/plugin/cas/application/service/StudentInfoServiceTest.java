package online.yudream.base.plugin.cas.application.service;

import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;
import online.yudream.base.plugin.spi.system.user.PluginUserProfile;
import online.yudream.base.plugin.spi.system.user.PluginUserService;
import online.yudream.base.plugin.cas.domain.aggregate.StudentArchive;
import online.yudream.base.plugin.cas.domain.aggregate.StudentProfile;
import online.yudream.base.plugin.cas.domain.enumerate.SsoProtocol;
import online.yudream.base.plugin.cas.domain.service.SsoProtocolClient;
import online.yudream.base.plugin.cas.domain.service.StudentArchiveQuery;
import online.yudream.base.plugin.cas.infrastructure.repository.StudentProfileDocumentRepository;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StudentInfoServiceTest {

    @Test
    void recordAutoDetectsIdentityAttributes() {
        PagedDocuments documents = new PagedDocuments();
        StudentInfoService service = service(documents);
        service.record(identity("20230101", "张三", Map.of(
                "cn", "张三",
                "department", "信息工程学院",
                "major", "计算机科学与技术",
                "className", "计算机23-1班",
                "mail", "zhang@taru.edu.cn",
                "mobile", "13900000000"
        )), SsoProtocol.CAS);

        Optional<Map<String, Object>> detail = service.detail("20230101");
        assertTrue(detail.isPresent());
        Map<String, Object> view = detail.get();
        assertEquals("张三", view.get("name"));
        assertEquals("zhang@taru.edu.cn", view.get("email"));
        assertEquals("13900000000", view.get("phone"));
        assertEquals("CAS", view.get("protocol"));
        assertEquals(1L, view.get("loginCount"));
        assertTrue(String.valueOf(view.get("rawAttributes")).contains("department"));
        // 学院 / 班级不再由本插件保存：不向学生档案插件映射任何信息
        assertFalse(view.containsKey("dept"));
        assertFalse(view.containsKey("major"));
        assertFalse(view.containsKey("grade"));
        assertFalse(view.containsKey("className"));
    }

    @Test
    void recordFallsBackToNicknameAndKeepsExistingValues() {
        PagedDocuments documents = new PagedDocuments();
        StudentInfoService service = service(documents);
        service.record(identity("20230102", "李四", Map.of("mail", "li@taru.edu.cn")), SsoProtocol.OIDC);
        assertEquals("李四", service.detail("20230102").orElseThrow().get("name"));

        // 第二次登录没带属性：保留旧值，只累加次数
        service.record(identity("20230102", "", Map.of()), SsoProtocol.OIDC);
        Map<String, Object> detail = service.detail("20230102").orElseThrow();
        assertEquals("李四", detail.get("name"));
        assertEquals("li@taru.edu.cn", detail.get("email"));
        assertEquals(2L, detail.get("loginCount"));
    }

    @Test
    void pageAndSearch() {
        PagedDocuments documents = new PagedDocuments();
        StudentInfoService service = service(documents);
        service.record(identity("20230104", "赵六", Map.of("cn", "赵六", "mail", "zhao@taru.edu.cn")), SsoProtocol.CAS);
        service.record(identity("20230105", "钱七", Map.of("cn", "钱七")), SsoProtocol.CAS);

        Map<String, Object> all = service.page(1, 20, null);
        assertEquals(2L, all.get("total"));

        Map<String, Object> hit = service.page(1, 20, "赵六");
        assertEquals(1L, hit.get("total"));
        assertEquals("赵六", ((List<Map<String, Object>>) hit.get("items")).get(0).get("name"));

        Map<String, Object> mailHit = service.page(1, 20, "zhao@");
        assertEquals(1L, mailHit.get("total"));

        Map<String, Object> miss = service.page(1, 20, "不存在的人");
        assertEquals(0L, miss.get("total"));
    }

    @Test
    void pageAttachesBindingAndStudentArchive() {
        PagedDocuments documents = new PagedDocuments();
        BindingQueryService bindings = new BindingQueryService(
                users(uid -> "20230106".equals(uid)
                        ? Optional.of(new PluginUserProfile(42L, "godot", "Godot", "g@example.com", "", null, null, "ACTIVE"))
                        : Optional.empty()),
                "cas");
        StudentArchiveQuery archives = new StudentArchiveQuery() {
            @Override
            public boolean available() {
                return true;
            }

            @Override
            public Optional<StudentArchive> findByStudentNo(String studentNo) {
                return "20230106".equals(studentNo)
                        ? Optional.of(new StudentArchive(studentNo, "孙八", "计算机23-1班", "信息工程学院"))
                        : Optional.empty();
            }
        };
        StudentInfoService service = new StudentInfoService(
                new StudentProfileDocumentRepository(documents), bindings, archives);
        service.record(identity("20230106", "孙八", Map.of("cn", "孙八")), SsoProtocol.CAS);
        service.record(identity("20230107", "周九", Map.of("cn", "周九")), SsoProtocol.CAS);

        List<Map<String, Object>> items = (List<Map<String, Object>>) service.page(1, 20, null).get("items");

        Map<String, Object> bound = item(items, "20230106");
        Map<String, Object> binding = (Map<String, Object>) bound.get("binding");
        assertEquals(true, binding.get("bound"));
        assertEquals("godot", binding.get("username"));
        assertEquals("42", binding.get("userId"));
        Map<String, Object> archive = (Map<String, Object>) bound.get("archive");
        assertEquals(true, archive.get("filled"));
        assertEquals("信息工程学院", archive.get("college"));
        assertEquals("计算机23-1班", archive.get("className"));

        Map<String, Object> unbound = item(items, "20230107");
        assertEquals(false, ((Map<String, Object>) unbound.get("binding")).get("bound"));
        assertEquals(false, ((Map<String, Object>) unbound.get("archive")).get("filled"));
        assertTrue(service.archiveAvailable());
    }

    @Test
    void archiveUnavailableDegradesWithoutBreakingPage() {
        PagedDocuments documents = new PagedDocuments();
        StudentInfoService service = new StudentInfoService(new StudentProfileDocumentRepository(documents));
        service.record(identity("20230108", "吴十", Map.of("cn", "吴十")), SsoProtocol.CAS);

        Map<String, Object> page = service.page(1, 20, null);
        Map<String, Object> item = ((List<Map<String, Object>>) page.get("items")).get(0);
        Map<String, Object> archive = (Map<String, Object>) item.get("archive");

        assertEquals(false, archive.get("available"));
        assertEquals(false, archive.get("filled"));
        assertFalse(service.archiveAvailable());
        assertFalse(item.containsKey("binding")); // 宿主绑定查询缺失时不附加该字段
    }

    private static Map<String, Object> item(List<Map<String, Object>> items, String socialUid) {
        return items.stream().filter(row -> socialUid.equals(row.get("socialUid"))).findFirst().orElseThrow();
    }

    private static StudentInfoService service(PagedDocuments documents) {
        return new StudentInfoService(new StudentProfileDocumentRepository(documents));
    }

    private static PluginUserService users(java.util.function.Function<String, Optional<PluginUserProfile>> lookup) {
        return (PluginUserService) Proxy.newProxyInstance(
                StudentInfoServiceTest.class.getClassLoader(),
                new Class<?>[]{PluginUserService.class},
                (proxy, method, args) -> {
                    if ("findByExternalIdentity".equals(method.getName())) {
                        return lookup.apply((String) args[2]);
                    }
                    if ("toString".equals(method.getName())) {
                        return "FakePluginUserService";
                    }
                    if ("hashCode".equals(method.getName())) {
                        return System.identityHashCode(proxy);
                    }
                    if ("equals".equals(method.getName())) {
                        return proxy == args[0];
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private static SsoProtocolClient.ExternalIdentity identity(String uid, String nickname, Map<String, String> attributes) {
        return new SsoProtocolClient.ExternalIdentity(uid, nickname, "", "", "", attributes);
    }

    /** 支持分页 findAll 的内存文档桩。 */
    private static final class PagedDocuments implements PluginDocumentStore {
        private final Map<String, Map<String, Object>> store = new ConcurrentHashMap<>();

        @Override
        public Map<String, Object> save(String collection, String id, Map<String, Object> document) {
            Map<String, Object> copy = new LinkedHashMap<>(document);
            store.put(collection + "/" + id, copy);
            return copy;
        }

        @Override
        public Optional<Map<String, Object>> findById(String collection, String id) {
            Map<String, Object> document = store.get(collection + "/" + id);
            return document == null ? Optional.empty() : Optional.of(new LinkedHashMap<>(document));
        }

        @Override
        @SuppressWarnings("unchecked")
        public List<Map<String, Object>> findAll(String collection, int page, int size) {
            List<Map<String, Object>> items = new ArrayList<>();
            for (Map.Entry<String, Map<String, Object>> entry : store.entrySet()) {
                if (entry.getKey().startsWith(collection + "/")) {
                    items.add(new LinkedHashMap<>(entry.getValue()));
                }
            }
            items.sort(Comparator.comparing(d -> String.valueOf(d.get("socialUid"))));
            int from = Math.min((Math.max(page, 1) - 1) * Math.max(size, 1), items.size());
            int to = Math.min(from + Math.max(size, 1), items.size());
            return items.subList(from, to);
        }

        @Override
        public List<Map<String, Object>> findByField(String collection, String field, Object value, int page, int size) {
            return List.of();
        }

        @Override
        public long count(String collection) {
            return store.keySet().stream().filter(k -> k.startsWith(collection + "/")).count();
        }

        @Override
        public void delete(String collection, String id) {
            store.remove(collection + "/" + id);
        }
    }
}
