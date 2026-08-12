package online.yudream.plugin.webcard.application;

import online.yudream.base.plugin.spi.system.messaging.PluginEvent;
import online.yudream.base.plugin.spi.system.messaging.PluginMessageContent;
import online.yudream.base.plugin.spi.system.messaging.PluginMessageRequest;
import online.yudream.base.plugin.spi.system.messaging.PluginMessagingService;
import online.yudream.plugin.webcard.domain.WebCardModels;
import online.yudream.plugin.webcard.domain.WebCardModels.*;
import online.yudream.plugin.webcard.domain.WebCardRepository;
import online.yudream.plugin.webcard.infrastructure.CardRenderer;
import online.yudream.plugin.webcard.infrastructure.ContentParser;
import online.yudream.plugin.webcard.infrastructure.SecretHeaderStore;
import online.yudream.plugin.webcard.infrastructure.SecureWebFetcher;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class WebCardApplicationService implements AutoCloseable {
    public static final String SITES = "sites", RULES = "parse-rules", ROUTE_RULES = "site-route-rules", TEMPLATES = "templates", VERSIONS = "template-versions", BINDINGS = "group-bindings", JOBS = "crawl-jobs", CONTENTS = "content-records", DELIVERIES = "deliveries", SESSIONS = "agent-sessions", PROPOSALS = "agent-proposals";
    private static final Pattern URL = Pattern.compile("https?://[^\\s<>\\[\\]{}\"']+");

    private final WebCardRepository repository;
    private final SecretHeaderStore headers;
    private final FetchOperation fetcher;
    private final ContentParser parser;
    private final RenderOperation renderer;
    private final PluginMessagingService messaging;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();

    public WebCardApplicationService(WebCardRepository repository, SecretHeaderStore headers,
                                     SecureWebFetcher fetcher, ContentParser parser,
                                     CardRenderer renderer, PluginMessagingService messaging) {
        this(repository, headers, fetcher == null ? null : new FetchOperation() {
                    @Override public SecureWebFetcher.Fetched fetch(Site site, String url, Map<String, String> headers) {
                        return fetcher.fetch(site, url, headers);
                    }
                    @Override public SecureWebFetcher.Fetched fetchResource(Site site, String url, Map<String, String> headers) {
                        return fetcher.fetchResource(site, url, headers);
                    }
                }, parser,
                renderer == null ? null : renderer::renderBase64, messaging);
    }

    private WebCardApplicationService(WebCardRepository repository, SecretHeaderStore headers,
                                      FetchOperation fetcher, ContentParser parser,
                                      RenderOperation renderer, PluginMessagingService messaging) {
        this.repository = repository; this.headers = headers; this.fetcher = fetcher; this.parser = parser; this.renderer = renderer; this.messaging = messaging;
    }

    static WebCardApplicationService forTesting(WebCardRepository repository, SecretHeaderStore headers,
                                                FetchOperation fetcher, ContentParser parser,
                                                RenderOperation renderer) {
        return new WebCardApplicationService(repository, headers, fetcher, parser, renderer, null);
    }

    interface FetchOperation {
        SecureWebFetcher.Fetched fetch(Site site, String url, Map<String, String> headers);
        default SecureWebFetcher.Fetched fetchResource(Site site, String url, Map<String, String> headers) {
            return fetch(site, url, headers);
        }
    }

    @FunctionalInterface
    interface RenderOperation {
        String renderBase64(TemplateVersion template, Map<String, Object> fields);
    }

    public Site saveSite(Site input, Map<String, String> headerValues) {
        long now = System.currentTimeMillis();
        Site existing = repository.find(SITES, input.id(), Site.class).orElse(null);
        String secretRef = existing == null ? input.secretRef() : existing.secretRef();
        if (input.accessMode() == AccessMode.CUSTOM_HEADERS && headerValues != null) secretRef = headers.save(secretRef, headerValues);
        if (input.accessMode() == AccessMode.PUBLIC_HTTP && secretRef != null) { headers.delete(secretRef); secretRef = null; }
        Site value = new Site(input.id(), input.name(), input.enabled(), input.hosts(), input.accessMode(),
                headerValues == null ? input.headerNames() : List.copyOf(headers.read(secretRef).keySet()), secretRef,
                input.responseType(), input.redirectHosts(), input.defaultTemplateId(), existing == null ? now : existing.createdAt(), now);
        return repository.save(SITES, value.id(), value);
    }
    public Optional<Site> site(String id) { return repository.find(SITES, id, Site.class); }
    public Page<Site> sites(int page, int size) { return new Page<>(repository.page(SITES, page, size, Site.class), repository.count(SITES)); }
    public Map<String, String> maskedHeaders(String siteId) { return site(siteId).map(Site::secretRef).map(headers::read).map(SecretHeaderStore::masked).orElse(Map.of()); }
    public void deleteSite(String id) {
        Site value = requireSite(id);
        forEachBy(TEMPLATES, "siteId", id, Template.class, template -> deleteTemplate(template.id()));
        forEachBy(JOBS, "siteId", id, CrawlJob.class, job -> repository.delete(JOBS, job.id()));
        forEachBy(BINDINGS, "siteId", id, GroupBinding.class, binding -> {
            forEachBy(DELIVERIES, "bindingId", binding.id(), DeliveryRecord.class, delivery -> repository.delete(DELIVERIES, delivery.id()));
            repository.delete(BINDINGS, binding.id());
        });
        forEachBy(CONTENTS, "siteId", id, ContentRecord.class, this::deleteContent);
        forEachBy(ROUTE_RULES, "siteId", id, SiteRouteRule.class, rule -> repository.delete(ROUTE_RULES, rule.id()));
        headers.delete(value.secretRef());
        repository.delete(RULES, id);
        repository.delete(SITES, id);
    }

    public ParseRules saveRules(ParseRules rules) { requireSite(rules.siteId()); return repository.save(RULES, rules.siteId(), rules); }
    public Optional<ParseRules> rules(String siteId) { return repository.find(RULES, siteId, ParseRules.class); }
    public List<SiteRouteRule> routeRules(String siteId) {
        requireSite(siteId);
        return repository.findBy(ROUTE_RULES, "siteId", siteId, 1, 200, SiteRouteRule.class).stream()
                .sorted(Comparator.comparingLong(SiteRouteRule::createdAt).thenComparing(SiteRouteRule::id)).toList();
    }
    public SiteRouteRule saveRouteRule(SiteRouteRule input) {
        Site site = requireSite(input.siteId());
        if (!input.templateId().isBlank()) {
            Template template = requireTemplate(input.templateId());
            if (!site.id().equals(template.siteId())) throw new IllegalArgumentException("子链接规则模板不属于当前站点");
        }
        ParseRules normalized = new ParseRules(site.id(), input.rules().detailType(), input.rules().fields(),
                input.rules().listExpression(), input.rules().listLinkAttribute(), input.rules().jsonItemsPath(),
                input.rules().canonicalField(), input.rules().contentKeyField(), input.rules().detailUrlPattern());
        if (normalized.detailUrlPattern().isBlank()) throw new IllegalArgumentException("子链接格式不能为空");
        long now = System.currentTimeMillis();
        SiteRouteRule existing = repository.find(ROUTE_RULES, input.id(), SiteRouteRule.class).orElse(null);
        SiteRouteRule value = new SiteRouteRule(input.id(), site.id(), input.name(), input.enabled(), input.templateId(),
                normalized, existing == null ? now : existing.createdAt(), now);
        return repository.save(ROUTE_RULES, value.id(), value);
    }
    public void deleteRouteRule(String id) { repository.delete(ROUTE_RULES, id); }

    public Template saveTemplate(Template input) {
        long now = System.currentTimeMillis(); requireSite(input.siteId());
        Template existing = repository.find(TEMPLATES, input.id(), Template.class).orElse(null);
        Template value = new Template(input.id(), existing == null ? input.siteId() : existing.siteId(), input.name(), input.mode(),
                existing == null ? null : existing.draftVersionId(), existing == null ? null : existing.publishedVersionId(),
                existing == null ? now : existing.createdAt(), now);
        return repository.save(TEMPLATES, value.id(), value);
    }
    public Page<Template> templates(int page, int size) { return new Page<>(repository.page(TEMPLATES, page, size, Template.class), repository.count(TEMPLATES)); }
    public Optional<Template> template(String id) { return repository.find(TEMPLATES, id, Template.class); }
    public void deleteTemplate(String id) {
        Template template = requireTemplate(id);
        forEachBy(VERSIONS, "templateId", id, TemplateVersion.class, this::deleteTemplateVersion);
        site(template.siteId()).filter(value -> id.equals(value.defaultTemplateId())).ifPresent(value -> repository.save(SITES, value.id(), new Site(
                value.id(), value.name(), value.enabled(), value.hosts(), value.accessMode(), value.headerNames(), value.secretRef(), value.responseType(), value.redirectHosts(), null, value.createdAt(), System.currentTimeMillis())));
        forEachBy(ROUTE_RULES, "templateId", id, SiteRouteRule.class, rule -> repository.save(ROUTE_RULES, rule.id(), new SiteRouteRule(
                rule.id(), rule.siteId(), rule.name(), rule.enabled(), "", rule.rules(), rule.createdAt(), System.currentTimeMillis())));
        repository.delete(TEMPLATES, id);
    }

    private void deleteTemplateVersion(TemplateVersion version) {
        forEachBy(DELIVERIES, "templateVersionId", version.id(), DeliveryRecord.class, delivery -> repository.delete(DELIVERIES, delivery.id()));
        forEachBy(CONTENTS, "templateVersionId", version.id(), ContentRecord.class, this::deleteContent);
        forEachBy(BINDINGS, "templateVersionId", version.id(), GroupBinding.class, binding -> repository.save(BINDINGS, binding.id(), new GroupBinding(
                binding.id(), binding.siteId(), binding.connectionId(), binding.platform(), binding.selfId(), binding.channelId(), binding.enabled(), null,
                binding.quietStart(), binding.quietEnd(), binding.cooldownSeconds(), binding.hourlyLimit(), binding.lastDeliveryAt(), binding.createdAt(), System.currentTimeMillis())));
        repository.delete(VERSIONS, version.id());
    }

    private void deleteContent(ContentRecord content) {
        forEachBy(DELIVERIES, "contentId", content.id(), DeliveryRecord.class, delivery -> repository.delete(DELIVERIES, delivery.id()));
        repository.delete(CONTENTS, content.id());
    }

    private <T> void forEachBy(String collection, String field, Object value, Class<T> type, java.util.function.Consumer<T> action) {
        while (true) {
            List<T> records = repository.findBy(collection, field, value, 1, 200, type);
            if (records.isEmpty()) return;
            records.forEach(action);
        }
    }
    public synchronized TemplateVersion saveVersion(TemplateVersion input) {
        Template template = requireTemplate(input.templateId()); long now = System.currentTimeMillis();
        if (input.parseRules() != null && !template.siteId().equals(input.parseRules().siteId())) throw new IllegalArgumentException("模板版本解析规则不属于模板站点");
        int version = maxVersion(template.id()) + 1;
        String id = UUID.randomUUID().toString();
        TemplateVersion value = new TemplateVersion(id, template.id(), version, input.parseRules(), input.mode(), input.structuredLayout(), input.html(), input.css(), input.fixture(), input.origin(), input.summary(), false, now);
        repository.save(VERSIONS, id, value);
        repository.save(TEMPLATES, template.id(), new Template(template.id(), template.siteId(), template.name(), value.mode(), id, template.publishedVersionId(), template.createdAt(), now));
        if (value.parseRules() != null) saveRules(value.parseRules());
        return value;
    }
    public Optional<TemplateVersion> version(String id) { return repository.find(VERSIONS, id, TemplateVersion.class); }
    public Page<TemplateVersion> versions(String templateId, int page, int size) { return new Page<>(repository.findBy(VERSIONS, "templateId", templateId, page, size, TemplateVersion.class), countBy(VERSIONS, "templateId", templateId, TemplateVersion.class)); }

    private int maxVersion(String templateId) {
        int page = 1;
        int maximum = 0;
        while (true) {
            List<TemplateVersion> values = repository.findBy(VERSIONS, "templateId", templateId, page++, 200, TemplateVersion.class);
            for (TemplateVersion value : values) maximum = Math.max(maximum, value.version());
            if (values.size() < 200) return maximum;
        }
    }

    private <T> long countBy(String collection, String field, Object value, Class<T> type) {
        int page = 1;
        long total = 0;
        while (true) {
            List<T> values = repository.findBy(collection, field, value, page++, 200, type);
            total += values.size();
            if (values.size() < 200) return total;
        }
    }
    public Template publish(String templateId, String versionId) {
        Template template = requireTemplate(templateId); TemplateVersion version = requireVersion(versionId);
        if (!version.templateId().equals(template.id()) || !version.previewPassed()) throw new IllegalArgumentException("仅能发布已成功预览的当前模板版本");
        Template published = new Template(template.id(), template.siteId(), template.name(), version.mode(), template.draftVersionId(), version.id(), template.createdAt(), System.currentTimeMillis());
        repository.save(TEMPLATES, template.id(), published);
        Site site = requireSite(template.siteId());
        repository.save(SITES, site.id(), new Site(site.id(), site.name(), site.enabled(), site.hosts(), site.accessMode(), site.headerNames(), site.secretRef(), site.responseType(), site.redirectHosts(), template.id(), site.createdAt(), System.currentTimeMillis()));
        return published;
    }
    public TemplateVersion rollback(String templateId, String versionId) { TemplateVersion source = requireVersion(versionId); if (!source.templateId().equals(templateId)) throw new IllegalArgumentException("版本不属于当前模板"); return saveVersion(new TemplateVersion(null, templateId, 0, source.parseRules(), source.mode(), source.structuredLayout(), source.html(), source.css(), source.fixture(), "ROLLBACK", "回滚自版本 " + source.version(), source.previewPassed(), 0)); }

    public GroupBinding saveBinding(GroupBinding input) {
        requireSite(input.siteId()); requireConnectionGroup(input.connectionId(), input.channelId()); long now = System.currentTimeMillis();
        GroupBinding existing = repository.find(BINDINGS, input.id(), GroupBinding.class).orElse(null);
        GroupBinding value = new GroupBinding(input.id(), input.siteId(), input.connectionId(), input.platform(), input.selfId(), input.channelId(), input.enabled(), input.templateVersionId(), input.quietStart(), input.quietEnd(), Math.max(0, input.cooldownSeconds()), Math.max(0, input.hourlyLimit()), existing == null ? 0 : existing.lastDeliveryAt(), existing == null ? now : existing.createdAt(), now);
        return repository.save(BINDINGS, value.id(), value);
    }
    public Page<GroupBinding> bindings(int page, int size) { return new Page<>(repository.page(BINDINGS, page, size, GroupBinding.class), repository.count(BINDINGS)); }
    public void deleteBinding(String id) { repository.delete(BINDINGS, id); }

    public CrawlJob saveJob(CrawlJob input) {
        requireSite(input.siteId()); long now = System.currentTimeMillis(); CrawlJob existing = repository.find(JOBS, input.id(), CrawlJob.class).orElse(null);
        CrawlJob value = new CrawlJob(input.id(), input.siteId(), input.sourceUrl(), input.sourceType(), input.enabled(), input.intervalMinutes(), input.initialItemCount(), input.nextRunAt() <= 0 ? now : input.nextRunAt(), null, 0, existing != null && existing.initialized(), existing == null ? now : existing.createdAt(), now);
        return repository.save(JOBS, value.id(), value);
    }
    public Page<CrawlJob> jobs(int page, int size) { return new Page<>(repository.page(JOBS, page, size, CrawlJob.class), repository.count(JOBS)); }
    public void deleteJob(String id) { repository.delete(JOBS, id); }
    public Page<DeliveryRecord> deliveries(int page, int size) { return new Page<>(repository.page(DELIVERIES, page, size, DeliveryRecord.class), repository.count(DELIVERIES)); }
    public Page<ContentRecord> contents(int page, int size) { return new Page<>(repository.page(CONTENTS, page, size, ContentRecord.class), repository.count(CONTENTS)); }

    public Map<String, Object> testFetch(String siteId, String url) { Site site = requireSite(siteId); var fetched = fetcher.fetch(site, url, headerValues(site)); return Map.of("status", fetched.status(), "finalUrl", fetched.finalUri().toString(), "contentType", fetched.contentType(), "size", fetched.body().length, "preview", fetched.text().substring(0, Math.min(2000, fetched.text().length()))); }
    public Map<String, Object> testParse(String siteId, String url) { Site site = requireSite(siteId); ParseRules rules = requireRules(siteId); return parser.detail(rules, fetcher.fetch(site, url, headerValues(site))); }
    public Map<String, Object> testRouteRule(String siteId, String url, SiteRouteRule input) {
        Site site = requireSite(siteId);
        if (input == null || input.rules() == null) throw new IllegalArgumentException("请选择需要测试的子链接规则");
        URI target = URI.create(url);
        if (target.getHost() == null || !site.matches(target.getHost())) throw new IllegalArgumentException("测试链接不属于当前站点");
        ParseRules rules = new ParseRules(site.id(), input.rules().detailType(), input.rules().fields(),
                input.rules().listExpression(), input.rules().listLinkAttribute(), input.rules().jsonItemsPath(),
                input.rules().canonicalField(), input.rules().contentKeyField(), input.rules().detailUrlPattern());
        if (!matchesDetailPath(rules.detailUrlPattern(), target.getPath())) throw new IllegalArgumentException("测试链接不符合当前子链接格式");
        return parser.detail(rules, fetcher.fetch(site, url, headerValues(site)));
    }
    public Map<String, Object> inspectForAgent(String url) {
        URI target = URI.create(url);
        Site site = findSite(target.getHost()).orElseGet(() -> new Site(
                "agent-inspection", target.getHost(), true, List.of(target.getHost()), AccessMode.PUBLIC_HTTP,
                List.of(), null, SourceType.HTML, List.of(), null, 0, 0
        ));
        return parser.inspect(fetcher.fetch(site, url, headerValues(site)));
    }
    public Map<String, Object> previewUrl(String url) {
        if (url == null || url.isBlank()) throw new IllegalArgumentException("请输入网页链接");
        RouteMatch match = findRouteForUrl(url).orElseThrow(() -> new IllegalArgumentException("没有匹配的站点和子链接规则"));
        Site site = match.site();
        SecureWebFetcher.Fetched fetched = fetcher.fetch(site, url, headerValues(site));
        Map<String, Object> fields = new LinkedHashMap<>(parser.detail(match.rules(), fetched));
        proxyImage(site, fields);
        fields.put("url", canonical(String.valueOf(fields.getOrDefault("url", fetched.finalUri().toString()))));
        TemplateVersion version = previewVersion(site, match.templateId());
        return Map.of("contentType", "image/png", "base64", renderer.renderBase64(version, fields), "fields", fields,
                "site", site.name(), "templateVersionId", version.id(), "finalUrl", fetched.finalUri().toString());
    }
    public Map<String, Object> previewDraftUrl(String siteId, String url, TemplateVersion draft) {
        return previewDraftUrl(siteId, url, draft, null, null);
    }

    public Map<String, Object> previewDraftUrl(String siteId, String url, TemplateVersion draft, Site draftSite, ParseRules draftRules) {
        if (draftSite != null && draftRules != null) return previewTransientPlan(siteId, url, draft, draftSite, draftRules);
        if (siteId == null || siteId.isBlank()) throw new IllegalArgumentException("请选择站点");
        if (url == null || url.isBlank()) throw new IllegalArgumentException("请输入网页链接");
        if (draft == null || draft.templateId() == null || draft.templateId().isBlank()) throw new IllegalArgumentException("请选择卡片模板");

        Site site = requireSite(siteId);
        Template template = requireTemplate(draft.templateId());
        if (!site.id().equals(template.siteId())) throw new IllegalArgumentException("模板不属于所选站点");

        URI target;
        try { target = URI.create(url); }
        catch (IllegalArgumentException error) { throw new IllegalArgumentException("网页链接格式无效", error); }
        if (target.getHost() == null || !site.matches(target.getHost())) throw new IllegalArgumentException("网页链接不属于所选站点");

        ParseRules persistedRules = requireRules(site.id());
        if (!matchesDetailPath(persistedRules.detailUrlPattern(), target.getPath())) throw new IllegalArgumentException("网页链接不符合站点子链接规则");

        SecureWebFetcher.Fetched fetched = fetcher.fetch(site, url, headerValues(site));
        Map<String, Object> fields = new LinkedHashMap<>(parser.detail(persistedRules, fetched));
        proxyImage(site, fields);
        fields.put("url", canonical(String.valueOf(fields.getOrDefault("url", fetched.finalUri().toString()))));
        TemplateVersion transientVersion = new TemplateVersion(null, template.id(), 0, persistedRules,
                draft.mode(), draft.structuredLayout(), draft.html(), draft.css(), draft.fixture(),
                "PREVIEW", draft.summary(), false, 0);
        return Map.of("contentType", "image/png", "base64", renderer.renderBase64(transientVersion, fields),
                "fields", fields, "site", site.name(), "finalUrl", fetched.finalUri().toString());
    }

    private Map<String, Object> previewTransientPlan(String siteId, String url, TemplateVersion draft, Site draftSite, ParseRules draftRules) {
        if (url == null || url.isBlank()) throw new IllegalArgumentException("请输入网页链接");
        if (draft == null || draft.templateId() == null || draft.templateId().isBlank()) throw new IllegalArgumentException("Agent 方案缺少模板");
        Site persisted = siteId == null || siteId.isBlank() ? null : site(siteId).orElse(null);
        boolean sameHosts = persisted != null && persisted.hosts().stream().anyMatch(draftSite.hosts()::contains);
        String transientId = draftSite.id() == null || draftSite.id().isBlank() ? "agent-preview" : draftSite.id();
        Site effectiveSite = sameHosts ? persisted : new Site(transientId, draftSite.name(), true, draftSite.hosts(),
                AccessMode.PUBLIC_HTTP, List.of(), null, draftSite.responseType(), draftSite.redirectHosts(), null, 0, 0);
        ParseRules rules = new ParseRules(effectiveSite.id(), draftRules.detailType(), draftRules.fields(), draftRules.listExpression(),
                draftRules.listLinkAttribute(), draftRules.jsonItemsPath(), draftRules.canonicalField(), draftRules.contentKeyField(), draftRules.detailUrlPattern());
        URI target;
        try { target = URI.create(url); }
        catch (IllegalArgumentException error) { throw new IllegalArgumentException("网页链接格式无效", error); }
        if (target.getHost() == null || !effectiveSite.matches(target.getHost())) throw new IllegalArgumentException("网页链接不属于 Agent 方案站点");
        if (!matchesDetailPath(rules.detailUrlPattern(), target.getPath())) throw new IllegalArgumentException("网页链接不符合 Agent 方案子链接规则");
        SecureWebFetcher.Fetched fetched = fetcher.fetch(effectiveSite, url, headerValues(effectiveSite));
        Map<String, Object> fields = new LinkedHashMap<>(parser.detail(rules, fetched));
        proxyImage(effectiveSite, fields);
        fields.put("url", canonical(String.valueOf(fields.getOrDefault("url", fetched.finalUri().toString()))));
        TemplateVersion transientVersion = new TemplateVersion(null, draft.templateId(), 0, rules, draft.mode(),
                draft.structuredLayout(), draft.html(), draft.css(), draft.fixture(), "AGENT_PREVIEW", draft.summary(), false, 0);
        return Map.of("contentType", "image/png", "base64", renderer.renderBase64(transientVersion, fields),
                "fields", fields, "site", effectiveSite.name(), "finalUrl", fetched.finalUri().toString());
    }
    public synchronized Map<String, Object> preview(String versionId, Map<String, Object> fixture) {
        TemplateVersion source = requireVersion(versionId);
        Map<String, Object> values = new LinkedHashMap<>(fixture == null || fixture.isEmpty() ? source.fixture() : fixture);
        Map<String, Object> renderValues = new LinkedHashMap<>(values);
        Template sourceTemplate = requireTemplate(source.templateId());
        proxyImage(requireSite(sourceTemplate.siteId()), renderValues);
        String rendered = renderer.renderBase64(source, renderValues);
        String verifiedId = UUID.randomUUID().toString();
        TemplateVersion verified = new TemplateVersion(verifiedId, source.templateId(), maxVersion(source.templateId()) + 1,
                source.parseRules(), source.mode(), source.structuredLayout(), source.html(), source.css(), values,
                source.origin(), source.summary(), true, System.currentTimeMillis());
        repository.save(VERSIONS, verified.id(), verified);
        Template template = sourceTemplate;
        repository.save(TEMPLATES, template.id(), new Template(template.id(), template.siteId(), template.name(), template.mode(),
                verified.id(), template.publishedVersionId(), template.createdAt(), System.currentTimeMillis()));
        return Map.of("contentType", "image/png", "base64", rendered, "version", verified);
    }

    public void onMessage(PluginEvent event) {
        if (event == null || event.channelId() == null || event.content() == null || event.content().isBlank() || event.userId() != null && event.userId().equals(event.selfId())) return;
        List<String> urls = extractUrls(event.content()); if (urls.isEmpty()) return;
        CompletableFuture.runAsync(() -> urls.forEach(url -> processMessageUrl(event, url)), workers);
    }
    private void processMessageUrl(PluginEvent event, String url) {
        String key = hash(event.connectionId() + ":" + event.channelId() + ":" + event.messageId() + ":" + url);
        try {
            RouteMatch match = findRouteForUrl(url).orElse(null); if (match == null) return;
            Site site = match.site();
            ContentRecord content = fetchContent(site, url, match.rules(), match.templateId());
            TemplateVersion defaultVersion;
            try { defaultVersion = content.templateVersionId() == null || content.templateVersionId().isBlank() ? runtimeVersionForTemplate(site, match.templateId()) : requireVersion(content.templateVersionId()); }
            catch (Exception ignored) { defaultVersion = null; }
            reply(new ParsedRuntime(content, defaultVersion), event, key);
        } catch (Exception error) { saveFailed(key, null, null, null, error); }
    }

    public void runDueJobs(String nodeId) {
        long now = System.currentTimeMillis();
        for (CrawlJob job : repository.page(JOBS, 1, 200, CrawlJob.class)) if (job.enabled() && job.nextRunAt() <= now && acquireLease(job, nodeId, now)) runJob(job, nodeId, now);
        retryDueDeliveries(now);
    }
    private void runJob(CrawlJob job, String nodeId, long now) {
        try {
            Site site = requireSite(job.siteId()); ParseRules rules = requireRules(site.id());
            List<String> discovered = parser.discover(job.sourceType(), rules, fetcher.fetch(site, job.sourceUrl(), headerValues(site)));
            if (!job.initialized()) discovered = discovered.stream().limit(job.initialItemCount()).toList();
            for (String url : discovered) {
                String contentId = hash(site.id() + ":" + canonical(url));
                if (repository.find(CONTENTS, contentId, ContentRecord.class).isPresent()) continue;
                try {
                    ParsedRuntime parsed = fetchAndParse(site, url);
                    for (GroupBinding binding : repository.findBy(BINDINGS, "siteId", site.id(), 1, 200, GroupBinding.class)) if (binding.enabled()) deliver(parsed, binding, hash(contentId + ":" + binding.id() + ":" + parsed.version().id()), null);
                } catch (Exception error) { saveFailed(contentId, null, null, error); }
            }
            repository.save(JOBS, job.id(), new CrawlJob(job.id(), job.siteId(), job.sourceUrl(), job.sourceType(), job.enabled(), job.intervalMinutes(), job.initialItemCount(), now + job.intervalMinutes() * 60_000L, null, 0, true, job.createdAt(), System.currentTimeMillis()));
        } catch (Exception error) {
            repository.save(JOBS, job.id(), new CrawlJob(job.id(), job.siteId(), job.sourceUrl(), job.sourceType(), job.enabled(), job.intervalMinutes(), job.initialItemCount(), now + Math.min(job.intervalMinutes(), 5) * 60_000L, null, 0, job.initialized(), job.createdAt(), System.currentTimeMillis()));
        }
    }

    private boolean acquireLease(CrawlJob job, String nodeId, long now) {
        CrawlJob leased = new CrawlJob(job.id(), job.siteId(), job.sourceUrl(), job.sourceType(), job.enabled(), job.intervalMinutes(), job.initialItemCount(), job.nextRunAt(), nodeId, now + 120_000, job.initialized(), job.createdAt(), now);
        return repository.updateIfFieldAtMost(JOBS, job.id(), "leaseUntil", now, leased);
    }

    private ParsedRuntime fetchAndParse(Site site, String url) {
        ContentRecord content = fetchContent(site, url);
        TemplateVersion version = content.templateVersionId() == null || content.templateVersionId().isBlank() ? null : requireVersion(content.templateVersionId());
        return new ParsedRuntime(content, version);
    }
    private ContentRecord fetchContent(Site site, String url) {
        return fetchContent(site, url, requireRules(site.id()), "");
    }
    private ContentRecord fetchContent(Site site, String url, ParseRules rules, String templateId) {
        SecureWebFetcher.Fetched fetched = fetcher.fetch(site, url, headerValues(site)); Map<String, Object> fields = new LinkedHashMap<>(parser.detail(rules, fetched));
        String normalized = canonical(String.valueOf(fields.getOrDefault("url", fetched.finalUri().toString()))); fields.put("url", normalized);
        String contentId = hash(site.id() + ":" + String.valueOf(fields.getOrDefault(rules.contentKeyField() == null ? "url" : rules.contentKeyField(), normalized)));
        String versionId = null; try { versionId = runtimeVersionForTemplate(site, templateId).id(); } catch (Exception ignored) { }
        ContentRecord content = new ContentRecord(contentId, site.id(), normalized, contentId, fields, versionId, System.currentTimeMillis(), System.currentTimeMillis()); repository.save(CONTENTS, contentId, content);
        return content;
    }
    private void deliver(ParsedRuntime parsed, GroupBinding binding, String deliveryId, String replyMessageId) {
        if (repository.find(DELIVERIES, deliveryId, DeliveryRecord.class).isPresent()) return;
        TemplateVersion version = binding.templateVersionId() == null || binding.templateVersionId().isBlank() ? parsed.version() : requireVersion(binding.templateVersionId());
        if (version == null) { saveFailed(deliveryId, parsed.content().id(), binding.id(), null, new IllegalArgumentException("站点尚未发布卡片模板")); return; }
        long now = System.currentTimeMillis();
        if (!allowedNow(binding)) { repository.save(DELIVERIES, deliveryId, new DeliveryRecord(deliveryId, parsed.content().id(), binding.id(), version.id(), DeliveryStage.DELAYED, null, 0, null, now + 60_000, now, now)); return; }
        try {
            String image = renderer.renderBase64(version, renderFields(parsed.content()));
            Map<String, Object> referrer = replyMessageId == null || replyMessageId.isBlank() ? Map.of() : Map.of("message_id", replyMessageId);
            messaging.send(new PluginMessageRequest(binding.connectionId(), binding.platform(), binding.selfId(), binding.channelId(), new PluginMessageContent(PluginMessageContent.Type.IMAGE, "base64://" + image, null, referrer))).toCompletableFuture().join();
            repository.save(DELIVERIES, deliveryId, new DeliveryRecord(deliveryId, parsed.content().id(), binding.id(), version.id(), DeliveryStage.DELIVERED, image, 1, null, 0, now, System.currentTimeMillis()));
            repository.save(BINDINGS, binding.id(), new GroupBinding(binding.id(), binding.siteId(), binding.connectionId(), binding.platform(), binding.selfId(), binding.channelId(), binding.enabled(), binding.templateVersionId(), binding.quietStart(), binding.quietEnd(), binding.cooldownSeconds(), binding.hourlyLimit(), System.currentTimeMillis(), binding.createdAt(), System.currentTimeMillis()));
        } catch (Exception error) { saveFailed(deliveryId, parsed.content().id(), binding.id(), version.id(), error); }
    }
    private void reply(ParsedRuntime parsed, PluginEvent event, String deliveryId) {
        if (repository.find(DELIVERIES, deliveryId, DeliveryRecord.class).isPresent()) return;
        TemplateVersion version = parsed.version();
        if (version == null) { saveFailed(deliveryId, parsed.content().id(), null, null, new IllegalArgumentException("站点尚未发布卡片模板")); return; }
        long now = System.currentTimeMillis();
        try {
            String image = renderer.renderBase64(version, renderFields(parsed.content()));
            Map<String, Object> referrer = event.messageId() == null || event.messageId().isBlank() ? Map.of() : Map.of("message_id", event.messageId());
            messaging.send(new PluginMessageRequest(event.connectionId(), event.platform(), event.selfId(), event.channelId(), new PluginMessageContent(PluginMessageContent.Type.IMAGE, "base64://" + image, null, referrer))).toCompletableFuture().join();
            repository.save(DELIVERIES, deliveryId, new DeliveryRecord(deliveryId, parsed.content().id(), null, version.id(), DeliveryStage.DELIVERED, image, 1, null, 0, now, System.currentTimeMillis()));
        } catch (Exception error) { saveFailed(deliveryId, parsed.content().id(), null, version.id(), error); }
    }
    public DeliveryRecord retry(String id) {
        DeliveryRecord record = repository.find(DELIVERIES, id, DeliveryRecord.class).orElseThrow(() -> new IllegalArgumentException("投递记录不存在"));
        ContentRecord content = repository.find(CONTENTS, record.contentId(), ContentRecord.class).orElseThrow(() -> new IllegalArgumentException("内容记录不存在"));
        GroupBinding binding = repository.find(BINDINGS, record.bindingId(), GroupBinding.class).orElseThrow(() -> new IllegalArgumentException("群绑定不存在"));
        TemplateVersion version = record.templateVersionId() == null || record.templateVersionId().isBlank() ? runtimeVersion(requireSite(content.siteId()), null) : requireVersion(record.templateVersionId());
        repository.delete(DELIVERIES, id); deliver(new ParsedRuntime(content, version), binding, id, null);
        return repository.find(DELIVERIES, id, DeliveryRecord.class).orElseThrow();
    }
    private void retryDueDeliveries(long now) { for (DeliveryRecord record : repository.page(DELIVERIES, 1, 200, DeliveryRecord.class)) if ((record.stage() == DeliveryStage.DELAYED || record.stage() == DeliveryStage.FAILED) && record.nextAttemptAt() > 0 && record.nextAttemptAt() <= now && record.attempts() < 4) try { retry(record.id()); } catch (Exception ignored) { } }
    private void saveFailed(String id, String contentId, String bindingId, Exception error) { saveFailed(id, contentId, bindingId, null, error); }
    private void saveFailed(String id, String contentId, String bindingId, String templateVersionId, Exception error) { long now = System.currentTimeMillis(); DeliveryRecord previous = repository.find(DELIVERIES, id, DeliveryRecord.class).orElse(null); int attempts = previous == null ? 1 : previous.attempts() + 1; repository.save(DELIVERIES, id, new DeliveryRecord(id, contentId, bindingId, previous == null ? templateVersionId : previous.templateVersionId(), DeliveryStage.FAILED, previous == null ? null : previous.renderedBase64(), attempts, safe(error), now + (long) Math.pow(2, attempts) * 30_000, previous == null ? now : previous.createdAt(), now)); }

    private TemplateVersion runtimeVersion(Site site, String override) {
        if (override != null && !override.isBlank()) return requireVersion(override);
        Template template = requireTemplate(site.defaultTemplateId()); if (template.publishedVersionId() == null || template.publishedVersionId().isBlank()) throw new IllegalArgumentException("站点模板尚未发布"); return requireVersion(template.publishedVersionId());
    }
    private TemplateVersion runtimeVersionForTemplate(Site site, String templateId) {
        Template template = requireTemplate(templateId == null || templateId.isBlank() ? site.defaultTemplateId() : templateId);
        if (!site.id().equals(template.siteId())) throw new IllegalArgumentException("子链接规则模板不属于当前站点");
        if (template.publishedVersionId() == null || template.publishedVersionId().isBlank()) throw new IllegalArgumentException("子链接规则模板尚未发布");
        return requireVersion(template.publishedVersionId());
    }
    private TemplateVersion previewVersion(Site site) {
        return previewVersion(site, "");
    }
    private TemplateVersion previewVersion(Site site, String templateId) {
        Template template = templateId == null || templateId.isBlank() ? site.defaultTemplateId() == null || site.defaultTemplateId().isBlank()
                ? repository.findBy(TEMPLATES, "siteId", site.id(), 1, 1, Template.class).stream().findFirst().orElseThrow(() -> new IllegalArgumentException("站点尚未配置卡片模板"))
                : requireTemplate(site.defaultTemplateId()) : requireTemplate(templateId);
        if (!site.id().equals(template.siteId())) throw new IllegalArgumentException("子链接规则模板不属于当前站点");
        String versionId = template.publishedVersionId();
        if (versionId == null || versionId.isBlank()) versionId = template.draftVersionId();
        return requireVersion(versionId);
    }
    private Map<String, String> headerValues(Site site) { return site.accessMode() == AccessMode.CUSTOM_HEADERS ? headers.read(site.secretRef()) : Map.of(); }
    Map<String, Object> renderFields(ContentRecord content) {
        Map<String, Object> fields = new LinkedHashMap<>(content.fields());
        proxyImage(requireSite(content.siteId()), fields);
        return fields;
    }
    private void proxyImage(Site site, Map<String, Object> fields) {
        Object value = fields.get("image");
        if (value == null || String.valueOf(value).isBlank()) return;
        if (String.valueOf(value).startsWith("data:image/")) return;
        try {
            URI uri = URI.create(String.valueOf(value));
            if (!site.matches(uri.getHost()) && !site.allowsRedirect(uri.getHost())) return;
            SecureWebFetcher.Fetched image = fetcher.fetchResource(site, uri.toString(), headerValues(site));
            if (image.contentType() == null || !image.contentType().toLowerCase().startsWith("image/")) { fields.remove("image"); return; }
            fields.put("image", "data:" + image.contentType().split(";")[0] + ";base64," + Base64.getEncoder().encodeToString(image.body()));
        } catch (Exception ignored) { fields.remove("image"); }
    }
    private Optional<Site> findSite(String host) { return repository.page(SITES, 1, 200, Site.class).stream().filter(Site::enabled).filter(site -> site.matches(host)).findFirst(); }
    private Optional<RouteMatch> findRouteForUrl(String url) {
        URI uri = URI.create(url);
        for (Site site : repository.page(SITES, 1, 200, Site.class).stream().filter(Site::enabled).filter(value -> value.matches(uri.getHost())).toList()) {
            Optional<SiteRouteRule> matched = routeRules(site.id()).stream().filter(SiteRouteRule::enabled)
                    .filter(rule -> matchesDetailPath(rule.rules().detailUrlPattern(), uri.getPath()))
                    .max(Comparator.comparingInt(rule -> rule.rules().detailUrlPattern().length()));
            if (matched.isPresent()) return Optional.of(new RouteMatch(site, matched.orElseThrow().rules(), matched.orElseThrow().templateId()));
            Optional<ParseRules> legacy = rules(site.id()).filter(value -> matchesDetailPath(value.detailUrlPattern(), uri.getPath()));
            if (legacy.isPresent()) return Optional.of(new RouteMatch(site, legacy.orElseThrow(), ""));
        }
        return Optional.empty();
    }
    private boolean matchesDetailPath(String pattern, String path) {
        if (pattern == null || pattern.isBlank()) return true;
        StringBuilder regex = new StringBuilder("^");
        for (int index = 0; index < pattern.length();) {
            char current = pattern.charAt(index);
            if (current == '{') {
                int end = pattern.indexOf('}', index + 1);
                if (end < 0) throw new IllegalArgumentException("子链接规则缺少右花括号");
                regex.append("[^/]+");
                index = end + 1;
            } else if (current == '*') {
                regex.append(".*");
                index++;
            } else {
                regex.append(Pattern.quote(String.valueOf(current)));
                index++;
            }
        }
        return (path == null ? "/" : path).matches(regex.append('$').toString());
    }
    private Site requireSite(String id) { return repository.find(SITES, id, Site.class).orElseThrow(() -> new IllegalArgumentException("站点不存在")); }
    private ParseRules requireRules(String siteId) { return rules(siteId).orElseThrow(() -> new IllegalArgumentException("站点解析规则未配置")); }
    private Template requireTemplate(String id) { if (id == null || id.isBlank()) throw new IllegalArgumentException("模板未配置"); return repository.find(TEMPLATES, id, Template.class).orElseThrow(() -> new IllegalArgumentException("模板不存在")); }
    private TemplateVersion requireVersion(String id) { return repository.find(VERSIONS, id, TemplateVersion.class).orElseThrow(() -> new IllegalArgumentException("模板版本不存在")); }
    private void requireConnectionGroup(String connectionId, String channelId) { boolean connection = messaging.connections().stream().anyMatch(item -> item.id().equals(connectionId)); boolean group = connection && messaging.groups(connectionId).stream().anyMatch(item -> item.id().equals(channelId)); if (!group) throw new IllegalArgumentException("请选择有效的连接和群"); }
    private boolean allowedNow(GroupBinding binding) {
        long now = System.currentTimeMillis(); if (binding.cooldownSeconds() > 0 && now - binding.lastDeliveryAt() < binding.cooldownSeconds() * 1000L) return false;
        if (binding.hourlyLimit() > 0) {
            long recent = repository.findBy(DELIVERIES, "bindingId", binding.id(), 1, 200, DeliveryRecord.class).stream()
                    .filter(record -> record.stage() == DeliveryStage.DELIVERED && record.updatedAt() >= now - 3_600_000L).count();
            if (recent >= binding.hourlyLimit()) return false;
        }
        if (binding.quietStart() == null || binding.quietStart().isBlank() || binding.quietEnd() == null || binding.quietEnd().isBlank()) return true;
        try { LocalTime current = LocalTime.now(ZoneId.systemDefault()), start = LocalTime.parse(binding.quietStart()), end = LocalTime.parse(binding.quietEnd()); boolean quiet = start.isBefore(end) ? !current.isBefore(start) && current.isBefore(end) : !current.isBefore(start) || current.isBefore(end); return !quiet; } catch (Exception ignored) { return true; }
    }
    private List<String> extractUrls(String text) { Matcher matcher = URL.matcher(text); List<String> values = new ArrayList<>(); while (matcher.find()) values.add(matcher.group().replaceAll("[),.;!?，。；！？]+$", "")); return values.stream().distinct().toList(); }
    private String canonical(String url) { URI uri = URI.create(url).normalize(); String scheme = uri.getScheme().toLowerCase(); String host = uri.getHost().toLowerCase(); int port = uri.getPort(); String authority = port == -1 || "http".equals(scheme) && port == 80 || "https".equals(scheme) && port == 443 ? host : host + ":" + port; String path = uri.getPath() == null || uri.getPath().isBlank() ? "/" : uri.getPath(); return URI.create(scheme + "://" + authority + path + (uri.getQuery() == null ? "" : "?" + uri.getQuery())).toString(); }
    private String hash(String value) { try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch (Exception e) { throw new IllegalStateException(e); } }
    private String safe(Throwable error) { Throwable value = error; while (value.getCause() != null) value = value.getCause(); String text = value.getMessage(); if (text == null || text.isBlank()) text = value.getClass().getSimpleName(); String sanitized = text.replaceAll("(?i)(authorization|cookie|api[-_ ]?key)\\s*[:=][^,;\\s]+", "$1=********"); return sanitized.substring(0, Math.min(500, sanitized.length())); }
    @Override public void close() { workers.close(); }
    private record ParsedRuntime(ContentRecord content, TemplateVersion version) { }
    private record RouteMatch(Site site, ParseRules rules, String templateId) { }
}
