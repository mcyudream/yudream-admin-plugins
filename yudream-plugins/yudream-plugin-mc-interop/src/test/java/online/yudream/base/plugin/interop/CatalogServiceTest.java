package online.yudream.base.plugin.interop;

import online.yudream.base.plugin.interop.application.service.CatalogService;
import online.yudream.base.plugin.interop.domain.McInteropBusinessException;
import online.yudream.base.plugin.interop.domain.aggregate.CatalogEntry;
import online.yudream.base.plugin.interop.infrastructure.repository.InteropRepository;
import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 出口契约：fail-closed 校验矩阵、发布范围、ETag 稳定性与 revision 单调。 */
class CatalogServiceTest {

    @Test
    void rejectsLoaderOutsideTheEnum() {
        CatalogService catalog = catalog();
        assertThrows(McInteropBusinessException.class, () -> catalog.saveEntry(
                entry("a", builder -> builder.loader("fabric-mirror"))));
    }

    @Test
    void rejectsHttpArtifactAndMissingHashes() {
        CatalogService catalog = catalog();
        assertThrows(McInteropBusinessException.class, () -> catalog.saveEntry(
                entry("a", builder -> builder.artifactUrl("http://cdn.example.com/pack.mrpack"))));
        assertThrows(McInteropBusinessException.class, () -> catalog.saveEntry(
                entry("a", builder -> builder.sha1(null).sha512(null))));
        assertThrows(McInteropBusinessException.class, () -> catalog.saveEntry(
                entry("a", builder -> builder.sha1("zzzz"))));
    }

    @Test
    void plainServerEntryNeedsNoRevisionAndDropsArtifactFields() {
        CatalogService catalog = catalog();
        CatalogEntry saved = catalog.saveEntry(
                entry("creative", builder -> builder.revision(9L).artifactUrl(null).artifactFormat(null).sha1(null)));
        assertNull(saved.revision());
        assertNull(saved.artifactUrl());
        assertNull(saved.sha1());
    }

    @Test
    void unpublishedEntriesNeverLeaveTheExit() {
        CatalogService catalog = catalog();
        catalog.saveEntry(entry("hidden", builder -> builder.published(false)));
        catalog.saveEntry(entry("shown", builder -> builder.published(true)));

        Map<String, Object> document = catalog.siteDocument();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> packs = (List<Map<String, Object>>) document.get("packs");
        assertEquals(1, packs.size());
        assertEquals("shown", packs.get(0).get("id"));
    }

    @Test
    void exitShapeFollowsTheProtocol() {
        CatalogService catalog = catalog();
        catalog.saveSettings("yda", "余梦", "测试站", "mc.yudream.app");
        catalog.savePeer("sitmc", "SIT-Minecraft", null, "skin.sitmc.club",
                "https://skin.sitmc.club/.well-known/mc-interop-v1.json", null);
        catalog.saveEntry(entry("survival", builder -> builder));

        Map<String, Object> document = catalog.siteDocument();

        @SuppressWarnings("unchecked")
        Map<String, Object> site = (Map<String, Object>) document.get("site");
        assertEquals("yda", site.get("id"));
        assertEquals("mc.yudream.app", site.get("domain"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> packs = (List<Map<String, Object>>) document.get("packs");
        @SuppressWarnings("unchecked")
        Map<String, Object> artifact = (Map<String, Object>) packs.get(0).get("artifact");
        assertEquals("mrpack", artifact.get("format"));
        // 协议 §4：两个哈希键恒输出、未知写 null
        assertTrue(artifact.containsKey("sha1") && artifact.containsKey("sha512"));

        @SuppressWarnings("unchecked")
        Map<String, Object> server = (Map<String, Object>) packs.get(0).get("server");
        assertEquals("play.example.com", server.get("address"));
        // 端口未填不得擅自补 25565
        assertTrue(!server.containsKey("port"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> peers = (List<Map<String, Object>>) document.get("peers");
        assertEquals(1, peers.size());
        assertEquals("sitmc", peers.get(0).get("siteId"));
        assertEquals("https://skin.sitmc.club/.well-known/mc-interop-v1.json",
                peers.get(0).get("manifestUrl"));
        // updatedAt 只在顶层（ISO-8601 UTC）
        assertTrue(String.valueOf(document.get("updatedAt")).endsWith("Z"));
    }

    @Test
    void etagIsStableAndContentSensitive() {
        CatalogService catalog = catalog();
        catalog.saveEntry(entry("a", builder -> builder));
        String first = catalog.etag();
        assertEquals(first, catalog.etag());

        catalog.saveEntry(entry("a", builder -> builder.name("改名")));
        assertNotEquals(first, catalog.etag());
    }

    @Test
    void revisionAutoIncrementsWhenContentChanges() {
        CatalogService catalog = catalog();
        CatalogEntry v1 = catalog.saveEntry(entry("pack", builder -> builder.revision(3L)));
        assertEquals(3L, v1.revision());

        // 同 revision 重提但内容未变：不递增
        CatalogEntry same = catalog.saveEntry(entry("pack", builder -> builder.revision(3L)));
        assertEquals(3L, same.revision());

        // 内容变化而 revision 未跟上：强制单调 +1
        CatalogEntry next = catalog.saveEntry(entry("pack",
                builder -> builder.revision(3L).sha512("a".repeat(128)).sha1(null)));
        assertEquals(4L, next.revision());
    }

    @Test
    void unionIdentityWinsAndBlankUnionFallsBackToLocal() {
        InteropRepository repository = new InteropRepository(new InMemoryDocuments());
        CatalogService catalog = new CatalogService(repository,
                () -> new online.yudream.base.plugin.interop.domain.aggregate.SiteIdentityRecord(
                        "sitmc-union", "联合站名", "https://mc.yudream.app"));
        // 未写本地设置：union 身份直接生效，origin 剥离 scheme 成根域名
        Map<String, Object> view = catalog.settings();
        assertEquals("sitmc-union", view.get("siteId"));
        assertEquals("union", view.get("identitySource"));
        assertEquals("mc.yudream.app", view.get("domain"));
        Map<String, Object> document = catalog.siteDocument();
        @SuppressWarnings("unchecked")
        Map<String, Object> site = (Map<String, Object>) document.get("site");
        assertEquals("sitmc-union", site.get("id"));
        assertEquals("mc.yudream.app", site.get("domain"));

        // union 在位但 siteCode 空白：回退本地设置
        CatalogService fallback = new CatalogService(repository,
                () -> new online.yudream.base.plugin.interop.domain.aggregate.SiteIdentityRecord("", "", ""));
        fallback.saveSettings("local-id", "本地名", null, "local.example.com");
        assertEquals("local-id", fallback.settings().get("siteId"));
        assertEquals("local", fallback.settings().get("identitySource"));

        // union 缺失（null 供应商）：本地设置生效
        CatalogService standalone = new CatalogService(repository);
        standalone.saveSettings("solo", "独立名", null, "solo.example.com");
        assertEquals("solo", standalone.settings().get("siteId"));
        assertEquals("local", standalone.settings().get("identitySource"));
    }

    @Test
    void importFromAdapterConvertsAndSkips() {
        InteropRepository repository = new InteropRepository(new InMemoryDocuments());
        CatalogService catalog = new CatalogService(repository,
                () -> new online.yudream.base.plugin.interop.domain.aggregate.SiteIdentityRecord(
                        "yda", "余梦", "https://mc.yudream.app"),
                () -> List.of(
                        new online.yudream.base.plugin.interop.domain.aggregate.AdapterCatalogEntry(
                                "survival", "生存世界", "1", "1.21.10", "fabric", "0.16.9",
                                "a".repeat(128), 1024L, "/mip/api/packs/survival/versions/1/mrpack",
                                "srv-1", "晨曦生存服", "mc.yudream.club"),
                        new online.yudream.base.plugin.interop.domain.aggregate.AdapterCatalogEntry(
                                "fabric-loader-entry", "Fabric 原始标识", "1", "1.21.10", "fabric-loader", "0.16.9",
                                "b".repeat(128), 2048L, "/mip/api/packs/fle/versions/1/mrpack",
                                "srv-1", "晨曦生存服", "mc.yudream.club"),
                        new online.yudream.base.plugin.interop.domain.aggregate.AdapterCatalogEntry(
                                "no-server-pack", "纯整合包", "1", "1.21.10", "fabric", "0.16.9",
                                "c".repeat(128), 4096L, "/mip/api/packs/nsp/versions/1/mrpack",
                                null, null, null),
                        new online.yudream.base.plugin.interop.domain.aggregate.AdapterCatalogEntry(
                                "bad", "坏条目", "1", "1.21.10", "quilt-beta", null,
                                null, null, "/mip/api/packs/bad/versions/1/mrpack",
                                "srv-1", "晨曦生存服", "mc.yudream.club")));

        Map<String, Object> body = catalog.importFromAdapter();

        assertEquals(3, body.get("imported"));
        CatalogEntry imported = repository.findEntry("survival.srv-1").orElseThrow();
        assertEquals("https://mc.yudream.app/api/plugins/ymcl-adapter/mip/api/packs/survival/versions/1/mrpack",
                imported.artifactUrl());
        assertEquals("yap-mip", imported.distSystem());
        assertEquals("survival", imported.distPackId());
        assertTrue(imported.published());
        assertEquals(1, ((List<?>) body.get("skipped")).size());
        // fabric-loader 原始标识归一化为 fabric
        CatalogEntry fabric = repository.findEntry("fabric-loader-entry.srv-1").orElseThrow();
        assertEquals("fabric", fabric.loader());
        // 纯整合包不绑服务器：serverAddress 可空
        CatalogEntry noServer = repository.findEntry("no-server-pack.null").orElseThrow();
        assertEquals(null, noServer.serverAddress());
    }

    @Test
    void peerViewsExposeSiteIdAndBlockKeepsDeclaration() {
        CatalogService catalog = catalog();
        catalog.savePeer("sitmc", "SIT-Minecraft", null, "skin.sitmc.club",
                "https://skin.sitmc.club/.well-known/mc-interop-v1.json", null);

        // 管理面视图归一化：siteId 来自文档 id，缺省 enabled=启用（删除无反应的根因即缺 siteId）
        List<Map<String, Object>> peers = catalog.peers();
        assertEquals(1, peers.size());
        assertEquals("sitmc", peers.get(0).get("siteId"));
        assertEquals(Boolean.TRUE, peers.get(0).get("enabled"));

        // 编辑（不带 enabled）不得覆盖屏蔽状态
        catalog.setPeerEnabled("sitmc", false);
        catalog.savePeer("sitmc", "SIT-Minecraft", null, "skin.sitmc.club",
                "https://skin.sitmc.club/.well-known/mc-interop-v1.json", null);
        assertEquals(Boolean.FALSE, catalog.peers().get(0).get("enabled"));

        // 屏蔽保留声明：出口 peers 列表仍在，但互采跳过（无外呼）
        Map<String, Object> document = catalog.siteDocument();
        assertEquals(1, ((List<?>) document.get("peers")).size());
        assertTrue(((List<?>) document.get("packs")).isEmpty());

        // 手动拉取返回逐站结果：屏蔽站标注未采合
        Map<String, Object> summary = catalog.refreshPeers();
        assertEquals(0L, summary.get("packs"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) summary.get("results");
        assertEquals(1, results.size());
        assertEquals("sitmc", results.get(0).get("siteId"));
        assertEquals(Boolean.FALSE, results.get(0).get("enabled"));
        assertEquals(Boolean.TRUE, results.get(0).get("ok"));

        // 删除真正移除（此前因 siteId 缺失在前端被静默跳过）
        catalog.deletePeer("sitmc");
        assertTrue(catalog.peers().isEmpty());
    }

    @Test
    void refreshReportsPerPeerFailureWithoutBlockingOthers() {
        CatalogService catalog = catalog();
        catalog.saveEntry(entry("own", builder -> builder));
        // .invalid 是保留 TLD，DNS 必然失败：验证单站失败只进结果不中断
        catalog.savePeer("ghost", null, null, null,
                "https://ghost.invalid/.well-known/mc-interop-v1.json", Boolean.TRUE);

        Map<String, Object> summary = catalog.refreshPeers();

        assertEquals(0L, summary.get("packs"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) summary.get("results");
        assertEquals(1, results.size());
        assertEquals(Boolean.FALSE, results.get(0).get("ok"));
        assertTrue(results.get(0).get("error") != null && !String.valueOf(results.get(0).get("error")).isBlank());
        // 失败站的缓存为空快照：出口不抛错，本站条目照常出
        Map<String, Object> document = catalog.siteDocument();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> packs = (List<Map<String, Object>>) document.get("packs");
        assertEquals(1, packs.size());
        assertEquals("own", packs.get(0).get("id"));
    }

    // ---- helpers ----

    private CatalogService catalog() {
        return new CatalogService(new InteropRepository(new InMemoryDocuments()));
    }

    private CatalogEntry entry(String id, java.util.function.UnaryOperator<CatalogEntryBuilder> customizer) {
        CatalogEntryBuilder builder = new CatalogEntryBuilder().id(id).name("条目 " + id)
                .gameVersion("1.21.10").loader("fabric").loaderVersion("0.16.9")
                .artifactFormat("mrpack").artifactUrl("https://cdn.example.com/" + id + ".mrpack")
                .artifactSize(1024L).sha1("b4a144477c6a995511a10c7ac5a9b00338b0b243")
                .serverAddress("play.example.com").published(true).revision(1L);
        return customizer.apply(builder).build();
    }

    private static final class CatalogEntryBuilder {
        private String id;
        private String name;
        private String summary;
        private Long revision;
        private String gameVersion;
        private String loader;
        private String loaderVersion;
        private String artifactFormat;
        private String artifactUrl;
        private Long artifactSize;
        private String sha1 = "d".repeat(64).substring(0, 40);
        private String sha512;
        private String distSystem;
        private String distPackId;
        private String distVersionId;
        private String serverAddress;
        private Integer serverPort;
        private boolean published = true;

        CatalogEntryBuilder id(String value) { this.id = value; return this; }
        CatalogEntryBuilder name(String value) { this.name = value; return this; }
        CatalogEntryBuilder revision(Long value) { this.revision = value; return this; }
        CatalogEntryBuilder gameVersion(String value) { this.gameVersion = value; return this; }
        CatalogEntryBuilder loader(String value) { this.loader = value; return this; }
        CatalogEntryBuilder loaderVersion(String value) { this.loaderVersion = value; return this; }
        CatalogEntryBuilder artifactFormat(String value) { this.artifactFormat = value; return this; }
        CatalogEntryBuilder artifactUrl(String value) { this.artifactUrl = value; return this; }
        CatalogEntryBuilder artifactSize(Long value) { this.artifactSize = value; return this; }
        CatalogEntryBuilder sha1(String value) { this.sha1 = value; return this; }
        CatalogEntryBuilder sha512(String value) { this.sha512 = value; return this; }
        CatalogEntryBuilder serverAddress(String value) { this.serverAddress = value; return this; }
        CatalogEntryBuilder published(boolean value) { this.published = value; return this; }

        CatalogEntry build() {
            return new CatalogEntry(id, name, summary, revision, gameVersion, loader, loaderVersion,
                    artifactFormat, artifactUrl, artifactSize, sha1, sha512, distSystem, distPackId,
                    distVersionId, serverAddress, serverPort, published, System.currentTimeMillis());
        }
    }

    /** 测试用内存文档库。 */
    static final class InMemoryDocuments implements PluginDocumentStore {
        private final Map<String, Map<String, Map<String, Object>>> collections = new ConcurrentHashMap<>();

        private Map<String, Map<String, Object>> collection(String name) {
            return collections.computeIfAbsent(name, key -> new LinkedHashMap<>());
        }

        @Override
        public Map<String, Object> save(String collection, String id, Map<String, Object> document) {
            Map<String, Object> copy = new LinkedHashMap<>(document);
            copy.put("id", id);
            collection(collection).put(id, copy);
            return copy;
        }

        @Override
        public Optional<Map<String, Object>> findById(String collection, String id) {
            Map<String, Object> document = collection(collection).get(id);
            return document == null ? Optional.empty() : Optional.of(new LinkedHashMap<>(document));
        }

        @Override
        public List<Map<String, Object>> findAll(String collection, int page, int size) {
            return new ArrayList<>(collection(collection).values()).stream()
                    .skip((long) (page - 1) * size).limit(size).toList();
        }

        @Override
        public List<Map<String, Object>> findByField(String collection, String field, Object value, int page, int size) {
            return new ArrayList<>(collection(collection).values()).stream()
                    .filter(document -> value.equals(document.get(field)))
                    .skip((long) (page - 1) * size).limit(size).toList();
        }

        @Override
        public long count(String collection) {
            return collection(collection).size();
        }

        @Override
        public boolean updateIfFieldAtMost(String collection, String id, String field, long maximum,
                                           Map<String, Object> document) {
            Map<String, Object> existing = collection(collection).get(id);
            if (existing == null || !(existing.get(field) instanceof Number number) || number.longValue() > maximum) {
                return false;
            }
            existing.putAll(document);
            return true;
        }

        @Override
        public void delete(String collection, String id) {
            collection(collection).remove(id);
        }
    }
}
