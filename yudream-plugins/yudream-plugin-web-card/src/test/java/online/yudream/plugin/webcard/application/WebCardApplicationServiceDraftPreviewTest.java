package online.yudream.plugin.webcard.application;

import online.yudream.base.plugin.spi.system.secret.PluginSecretStore;
import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.plugin.webcard.bootstrap.WebCardPlugin;
import online.yudream.plugin.webcard.domain.WebCardModels.AccessMode;
import online.yudream.plugin.webcard.domain.WebCardModels.ContentRecord;
import online.yudream.plugin.webcard.domain.WebCardModels.FieldRule;
import online.yudream.plugin.webcard.domain.WebCardModels.ParseRules;
import online.yudream.plugin.webcard.domain.WebCardModels.Site;
import online.yudream.plugin.webcard.domain.WebCardModels.SiteRouteRule;
import online.yudream.plugin.webcard.domain.WebCardModels.SourceType;
import online.yudream.plugin.webcard.domain.WebCardModels.Template;
import online.yudream.plugin.webcard.domain.WebCardModels.TemplateMode;
import online.yudream.plugin.webcard.domain.WebCardModels.TemplateVersion;
import online.yudream.plugin.webcard.domain.WebCardRepository;
import online.yudream.plugin.webcard.infrastructure.ContentParser;
import online.yudream.plugin.webcard.infrastructure.SecretHeaderStore;
import online.yudream.plugin.webcard.infrastructure.SecureWebFetcher;
import online.yudream.plugin.webcard.interfaces.JsonSupport;
import online.yudream.plugin.webcard.interfaces.WebCardAdminController;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static online.yudream.plugin.webcard.application.WebCardApplicationService.RULES;
import static online.yudream.plugin.webcard.application.WebCardApplicationService.ROUTE_RULES;
import static online.yudream.plugin.webcard.application.WebCardApplicationService.SITES;
import static online.yudream.plugin.webcard.application.WebCardApplicationService.TEMPLATES;
import static online.yudream.plugin.webcard.application.WebCardApplicationService.VERSIONS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebCardApplicationServiceDraftPreviewTest {
    @Test
    void exposesDraftPreviewOnlyThroughTheProtectedAdminEndpoint() throws Exception {
        PluginHttpEndpoint endpoint = WebCardAdminController.class.getDeclaredMethod("previewDraftUrl",
                online.yudream.base.plugin.spi.http.PluginHttpRequest.class).getAnnotation(PluginHttpEndpoint.class);

        assertNotNull(endpoint);
        assertEquals("POST", endpoint.method());
        assertEquals("/admin/template-draft-preview", endpoint.path());
        assertEquals(WebCardPlugin.MANAGE_PERMISSION, endpoint.permission());
    }

    @Test
    void rendersUnsavedAdvancedDraftUsingPersistedRulesAndHeadersWithoutSavingVersion() {
        MemoryRepository repository = new MemoryRepository();
        long now = System.currentTimeMillis();
        repository.save(SITES, "site", new Site("site", "Example", true, List.of("example.com"), AccessMode.CUSTOM_HEADERS,
                List.of("Authorization"), "headers:site", SourceType.HTML, List.of(), null, now, now));
        ParseRules persistedRules = new ParseRules("site", SourceType.HTML,
                List.of(new FieldRule("headline", "h1", "text", "string", true)), null, null, null, null, null, "/article/{id}");
        repository.save(RULES, "site", persistedRules);
        repository.save(TEMPLATES, "template", new Template("template", "site", "Card", TemplateMode.STRUCTURED, null, null, now, now));

        AtomicReference<TemplateVersion> renderedDraft = new AtomicReference<>();
        AtomicReference<Map<String, Object>> renderedFields = new AtomicReference<>();
        List<Map<String, String>> requestHeaders = new ArrayList<>();
        WebCardApplicationService service = WebCardApplicationService.forTesting(repository, headers(Map.of("Authorization", "Bearer secret")),
                (site, url, headers) -> {
                    requestHeaders.add(headers);
                    if (url.endsWith("cover.png")) return fetched(url, "image/png", new byte[]{1, 2, 3});
                    String html = "<html><head><title>Fallback</title><meta property='og:image' content='/cover.png'></head><body><h1>Parsed headline</h1></body></html>";
                    return fetched(url, "text/html; charset=UTF-8", html.getBytes(StandardCharsets.UTF_8));
                }, new ContentParser(), (version, fields) -> {
                    renderedDraft.set(version);
                    renderedFields.set(Map.copyOf(fields));
                    return "rendered-base64";
                });
        ParseRules ignoredDraftRules = new ParseRules("site", SourceType.JSON, List.of(), null, null, null, null, null, "");
        TemplateVersion draft = new TemplateVersion("unsaved", "template", 99, ignoredDraftRules, TemplateMode.ADVANCED,
                "{}", "<article id='web-card'>{{headline}}</article>", ".card { color: red; }", Map.of(), "AGENT", "unsaved", true, now);

        Map<String, Object> result = service.previewDraftUrl("site", "https://example.com/article/42", draft);

        assertEquals("rendered-base64", result.get("base64"));
        assertEquals("Example", result.get("site"));
        assertEquals("https://example.com/article/42", result.get("finalUrl"));
        assertEquals("Parsed headline", renderedFields.get().get("headline"));
        assertTrue(String.valueOf(renderedFields.get().get("image")).startsWith("data:image/png;base64,"));
        assertEquals(TemplateMode.ADVANCED, renderedDraft.get().mode());
        assertEquals("<article id='web-card'>{{headline}}</article>", renderedDraft.get().html());
        assertEquals(".card { color: red; }", renderedDraft.get().css());
        assertEquals(persistedRules, renderedDraft.get().parseRules());
        assertEquals(List.of(Map.of("Authorization", "Bearer secret"), Map.of("Authorization", "Bearer secret")), requestHeaders);
        assertEquals(0, repository.count(VERSIONS));
    }

    @Test
    void rejectsTemplateFromAnotherSiteBeforeFetching() {
        MemoryRepository repository = new MemoryRepository();
        long now = System.currentTimeMillis();
        repository.save(SITES, "selected", site("selected", "selected.example", now));
        repository.save(SITES, "other", site("other", "other.example", now));
        repository.save(RULES, "selected", new ParseRules("selected", SourceType.HTML, List.of(), null, null, null, null, null, ""));
        repository.save(TEMPLATES, "template", new Template("template", "other", "Other", TemplateMode.STRUCTURED, null, null, now, now));
        AtomicInteger fetches = new AtomicInteger();
        WebCardApplicationService service = WebCardApplicationService.forTesting(repository, headers(Map.of()),
                (site, url, headers) -> { fetches.incrementAndGet(); return fetched(url, "text/html", new byte[0]); },
                new ContentParser(), (version, fields) -> "unused");
        TemplateVersion draft = new TemplateVersion(null, "template", 0, null, TemplateMode.STRUCTURED,
                "{}", "", "", Map.of(), "MANUAL", "", false, 0);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.previewDraftUrl("selected", "https://selected.example/page", draft));

        assertEquals("模板不属于所选站点", error.getMessage());
        assertEquals(0, fetches.get());
        assertEquals(0, repository.count(VERSIONS));
    }

    @Test
    void rendersTransientAgentPlanBeforeSiteOrTemplateIsApplied() {
        MemoryRepository repository = new MemoryRepository();
        AtomicReference<TemplateVersion> rendered = new AtomicReference<>();
        WebCardApplicationService service = WebCardApplicationService.forTesting(repository, headers(Map.of()),
                (site, url, requestHeaders) -> fetched(url, "text/html", "<main><h1>Agent title</h1></main>".getBytes(StandardCharsets.UTF_8)),
                new ContentParser(), (version, fields) -> { rendered.set(version); return "agent-preview"; });
        Site transientSite = new Site("agent-site", "Agent site", true, List.of("new.example"), AccessMode.PUBLIC_HTTP,
                List.of(), null, SourceType.HTML, List.of(), null, 0, 0);
        ParseRules transientRules = new ParseRules("agent-site", SourceType.HTML,
                List.of(new FieldRule("title", "h1", "text", "string", true)), null, null, null, null, null, "/post/{id}");
        TemplateVersion draft = new TemplateVersion(null, "agent-template", 0, transientRules, TemplateMode.ADVANCED,
                "{}", "<article id=\"web-card\">{{title}}</article>", "#web-card{width:640px}", Map.of(), "AGENT", "", false, 0);

        Map<String, Object> result = service.previewDraftUrl("agent-site", "https://new.example/post/7", draft, transientSite, transientRules);

        assertEquals("agent-preview", result.get("base64"));
        assertEquals(TemplateMode.ADVANCED, rendered.get().mode());
        assertEquals(transientRules.fields(), rendered.get().parseRules().fields());
        assertEquals(0, repository.count(SITES));
        assertEquals(0, repository.count(TEMPLATES));
        assertEquals(0, repository.count(VERSIONS));
    }

    @Test
    void fixturePreviewProxiesAllowlistedAssetHostToDataUrl() {
        MemoryRepository repository = new MemoryRepository();
        long now = System.currentTimeMillis();
        repository.save(SITES, "site", new Site("site", "MC百科", true, List.of("www.mcmod.cn"),
                AccessMode.PUBLIC_HTTP, List.of(), null, SourceType.HTML, List.of("i.mcmod.cn"),
                "template", now, now));
        repository.save(TEMPLATES, "template", new Template(
                "template", "site", "Card", TemplateMode.STRUCTURED, "version", null, now, now
        ));
        repository.save(VERSIONS, "version", new TemplateVersion(
                "version", "template", 1, null, TemplateMode.STRUCTURED, "default", "", "",
                Map.of(), "MANUAL", "", false, now
        ));
        AtomicReference<Map<String, Object>> renderedFields = new AtomicReference<>();
        WebCardApplicationService service = WebCardApplicationService.forTesting(
                repository,
                headers(Map.of()),
                (site, url, requestHeaders) -> fetched(url, "image/jpeg", new byte[]{1, 2, 3}),
                new ContentParser(),
                (template, fields) -> {
                    renderedFields.set(Map.copyOf(fields));
                    return "rendered";
                }
        );

        service.preview("version", Map.of(
                "title", "More Advancements",
                "image", "https://i.mcmod.cn/class/cover/image.jpg"
        ));

        assertTrue(String.valueOf(renderedFields.get().get("image")).startsWith("data:image/jpeg;base64,"));
    }

    @Test
    void cachedContentImageIsProxiedAgainBeforeGroupRendering() {
        MemoryRepository repository = new MemoryRepository();
        long now = System.currentTimeMillis();
        repository.save(SITES, "site", new Site("site", "MC百科", true, List.of("www.mcmod.cn"),
                AccessMode.PUBLIC_HTTP, List.of(), null, SourceType.HTML, List.of("i.mcmod.cn"),
                "template", now, now));
        WebCardApplicationService service = WebCardApplicationService.forTesting(
                repository,
                headers(Map.of()),
                (site, url, requestHeaders) -> fetched(url, "image/jpeg", new byte[]{1, 2, 3}),
                new ContentParser(),
                (template, fields) -> "unused"
        );
        String imageUrl = "https://i.mcmod.cn/class/cover/image.jpg";
        ContentRecord cached = new ContentRecord(
                "content", "site", "https://www.mcmod.cn/class/17142.html", "content",
                Map.of("title", "More Advancements", "image", imageUrl), "version", now, now
        );

        Map<String, Object> renderFields = service.renderFields(cached);

        assertTrue(String.valueOf(renderFields.get("image")).startsWith("data:image/jpeg;base64,"));
        assertEquals(imageUrl, cached.fields().get("image"));
    }

    @Test
    void sameDomainUsesIndependentRulesAndTemplatesForDifferentChildPaths() {
        MemoryRepository repository = new MemoryRepository();
        long now = System.currentTimeMillis();
        repository.save(SITES, "site", new Site("site", "MC百科", true, List.of("www.mcmod.cn"),
                AccessMode.PUBLIC_HTTP, List.of(), null, SourceType.HTML, List.of(), "class-template", now, now));
        repository.save(TEMPLATES, "class-template", new Template("class-template", "site", "模组卡片", TemplateMode.STRUCTURED, null, "class-version", now, now));
        repository.save(TEMPLATES, "modpack-template", new Template("modpack-template", "site", "整合包卡片", TemplateMode.STRUCTURED, null, "modpack-version", now, now));
        repository.save(VERSIONS, "class-version", new TemplateVersion("class-version", "class-template", 1, null, TemplateMode.STRUCTURED, "default", "", "", Map.of(), "TEST", "", true, now));
        repository.save(VERSIONS, "modpack-version", new TemplateVersion("modpack-version", "modpack-template", 1, null, TemplateMode.STRUCTURED, "default", "", "", Map.of(), "TEST", "", true, now));
        ParseRules classRules = new ParseRules("site", SourceType.HTML, List.of(new FieldRule("title", "h1", "text", "TEXT", true)), "", "", "", "url", "url", "/class/{id}.html");
        ParseRules modpackRules = new ParseRules("site", SourceType.HTML, List.of(new FieldRule("title", "h2", "text", "TEXT", true)), "", "", "", "url", "url", "/modpack/{id}.html");
        repository.save(ROUTE_RULES, "class-rule", new SiteRouteRule("class-rule", "site", "模组详情", true, "class-template", classRules, now, now));
        repository.save(ROUTE_RULES, "modpack-rule", new SiteRouteRule("modpack-rule", "site", "整合包详情", true, "modpack-template", modpackRules, now, now));
        List<String> renderedTemplates = new ArrayList<>();
        List<String> renderedTitles = new ArrayList<>();
        WebCardApplicationService service = WebCardApplicationService.forTesting(repository, headers(Map.of()),
                (site, url, requestHeaders) -> fetched(url, "text/html", "<h1>Class page</h1><h2>Modpack page</h2>".getBytes(StandardCharsets.UTF_8)),
                new ContentParser(), (version, fields) -> { renderedTemplates.add(version.templateId()); renderedTitles.add(String.valueOf(fields.get("title"))); return "rendered"; });

        service.previewUrl("https://www.mcmod.cn/class/17142.html");
        service.previewUrl("https://www.mcmod.cn/modpack/100.html");

        assertEquals(List.of("class-template", "modpack-template"), renderedTemplates);
        assertEquals(List.of("Class page", "Modpack page"), renderedTitles);
    }

    private static Site site(String id, String host, long now) {
        return new Site(id, id, true, List.of(host), AccessMode.PUBLIC_HTTP, List.of(), null, SourceType.HTML, List.of(), null, now, now);
    }

    private static SecureWebFetcher.Fetched fetched(String url, String contentType, byte[] body) {
        return new SecureWebFetcher.Fetched(URI.create(url), 200, contentType, body);
    }

    private static SecretHeaderStore headers(Map<String, String> values) {
        PluginSecretStore store = (PluginSecretStore) Proxy.newProxyInstance(PluginSecretStore.class.getClassLoader(),
                new Class<?>[]{PluginSecretStore.class}, (proxy, method, args) -> {
                    if (method.getName().equals("get")) return Optional.of(JsonSupport.bytes(values));
                    if (method.getReturnType().equals(boolean.class)) return true;
                    return null;
                });
        return new SecretHeaderStore(store);
    }

    private static final class MemoryRepository implements WebCardRepository {
        private final Map<String, Map<String, Object>> collections = new LinkedHashMap<>();

        @Override public <T> T save(String collection, String id, T value) { collections.computeIfAbsent(collection, ignored -> new LinkedHashMap<>()).put(id, value); return value; }
        @Override public <T> Optional<T> find(String collection, String id, Class<T> type) { return Optional.ofNullable(type.cast(collection(collection).get(id))); }
        @Override public <T> List<T> page(String collection, int page, int size, Class<T> type) { return slice(collection(collection).values().stream().map(type::cast).toList(), page, size); }
        @Override public <T> List<T> findBy(String collection, String field, Object value, int page, int size, Class<T> type) {
            List<T> matched = collection(collection).values().stream().map(type::cast).filter(item -> {
                try { return java.util.Objects.equals(item.getClass().getMethod(field).invoke(item), value); }
                catch (Exception ignored) { return false; }
            }).toList();
            return slice(matched, page, size);
        }
        @Override public long count(String collection) { return collection(collection).size(); }
        @Override public <T> boolean updateIfFieldAtMost(String collection, String id, String field, long maximum, T value) { return false; }
        @Override public void delete(String collection, String id) { collection(collection).remove(id); }

        private Map<String, Object> collection(String name) { return collections.computeIfAbsent(name, ignored -> new LinkedHashMap<>()); }
        private static <T> List<T> slice(List<T> values, int page, int size) {
            int start = Math.min(values.size(), Math.max(0, page - 1) * size);
            return values.subList(start, Math.min(values.size(), start + size));
        }
    }
}
