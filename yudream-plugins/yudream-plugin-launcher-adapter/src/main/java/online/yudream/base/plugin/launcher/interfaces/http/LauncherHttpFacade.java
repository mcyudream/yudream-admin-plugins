package online.yudream.base.plugin.launcher.interfaces.http;

import online.yudream.base.plugin.authlib.api.PluginAuthProfile;
import online.yudream.base.plugin.authlib.api.PluginAuthService;
import online.yudream.base.plugin.launcher.api.LauncherAction;
import online.yudream.base.plugin.launcher.api.LauncherActionContext;
import online.yudream.base.plugin.launcher.api.LauncherContextView;
import online.yudream.base.plugin.launcher.api.LauncherDataEnvelope;
import online.yudream.base.plugin.launcher.api.LauncherDataSource;
import online.yudream.base.plugin.launcher.api.LauncherProvider;
import online.yudream.base.plugin.launcher.application.service.LauncherProviderAggregator;
import online.yudream.base.plugin.launcher.application.service.ManifestAppService;
import online.yudream.base.plugin.launcher.application.service.PackAppService;
import online.yudream.base.plugin.launcher.application.service.YmclChromeAppService;
import online.yudream.base.plugin.launcher.domain.valobj.YmclLauncherChrome;
import online.yudream.base.plugin.launcher.domain.valobj.YmclNavNode;
import online.yudream.base.plugin.launcher.interfaces.assembler.LauncherWebAssembler;
import online.yudream.base.plugin.launcher.interfaces.request.PublishPackRequest;
import online.yudream.base.plugin.launcher.interfaces.request.PublishPackVersionRequest;
import online.yudream.base.plugin.launcher.interfaces.request.PublishVanillaVersionRequest;
import online.yudream.base.plugin.launcher.interfaces.request.RollbackRequest;
import online.yudream.base.plugin.launcher.interfaces.request.UploadOverridesRequest;
import online.yudream.base.plugin.launcher.interfaces.request.YggExchangeRequest;
import online.yudream.base.plugin.launcher.interfaces.request.YmclChromeSaveRequest;
import online.yudream.base.plugin.launcher.interfaces.res.YggSessionRes;
import online.yudream.base.plugin.launcher.interfaces.support.JsonSupport;
import online.yudream.base.plugin.spi.core.PluginContext;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class LauncherHttpFacade {

    private static final String AUTHLIB_PLUGIN_CODE = "authlib-injector";

    private final PluginContext context;
    private final ManifestAppService manifestAppService;
    private final PackAppService packAppService;
    private final YmclChromeAppService chromeAppService;
    private final LauncherProviderAggregator aggregator;
    private final LauncherWebAssembler assembler = new LauncherWebAssembler();

    public LauncherHttpFacade(
            PluginContext context,
            ManifestAppService manifestAppService,
            PackAppService packAppService,
            YmclChromeAppService chromeAppService,
            LauncherProviderAggregator aggregator
    ) {
        this.context = context;
        this.manifestAppService = manifestAppService;
        this.packAppService = packAppService;
        this.chromeAppService = chromeAppService;
        this.aggregator = aggregator;
    }

    public PluginHttpResponse getManifest(PluginHttpRequest request) {
        return PluginHttpResponse.rawJson(200, manifestAppService.buildManifest());
    }

    public PluginHttpResponse listPacks(PluginHttpRequest request) {
        return run(() -> PluginHttpResponse.rawJson(200,
                packAppService.listPacks().stream().map(assembler::toPackRes).toList()));
    }

    public PluginHttpResponse adminListPacks(PluginHttpRequest request) {
        return run(() -> {
            String keyword = firstQuery(request, "keyword");
            int page = parseInt(firstQuery(request, "page"), 1, 1, Integer.MAX_VALUE);
            int size = parseInt(firstQuery(request, "size"), 10, 1, 100);
            PackAppService.PackQueryResult result = packAppService.queryPacks(keyword, page, size);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("records", result.records().stream().map(assembler::toPackRes).toList());
            body.put("total", result.total());
            return PluginHttpResponse.ok(body);
        });
    }

    public PluginHttpResponse adminGetPack(PluginHttpRequest request) {
        return run(() -> {
            String packId = requireSegment(request.path(), "packs");
            return PluginHttpResponse.ok(assembler.toDetailRes(
                    packAppService.requirePack(packId), packAppService.listVersions(packId)));
        });
    }

    public PluginHttpResponse adminCreatePack(PluginHttpRequest request) {
        return run(() -> {
            PublishPackRequest body = JsonSupport.read(request.body(), PublishPackRequest.class);
            return PluginHttpResponse.ok(assembler.toPackRes(
                    packAppService.createPack(body.packId(), body.name(), body.description(), body.icon())));
        });
    }

    public PluginHttpResponse adminGetChrome(PluginHttpRequest request) {
        return run(() -> PluginHttpResponse.ok(chromeAppService.adminView(aggregator.aggregatePages())));
    }

    public PluginHttpResponse adminSaveChrome(PluginHttpRequest request) {
        return run(() -> {
            YmclChromeSaveRequest body = JsonSupport.read(request.body(), YmclChromeSaveRequest.class);
            chromeAppService.save(toChrome(body));
            return PluginHttpResponse.ok(chromeAppService.adminView(aggregator.aggregatePages()));
        });
    }

    public PluginHttpResponse adminRollback(PluginHttpRequest request) {
        return run(() -> {
            RollbackRequest body = JsonSupport.read(request.body(), RollbackRequest.class);
            return PluginHttpResponse.ok(assembler.toPackRes(
                    packAppService.rollback(requireSegment(request.path(), "packs"), body.targetVersionId())));
        });
    }

    public PluginHttpResponse getPack(PluginHttpRequest request) {
        return run(() -> {
            String packId = requireSegment(request.path(), "packs");
            return PluginHttpResponse.rawJson(200, assembler.toDetailRes(
                    packAppService.requirePack(packId), packAppService.listVersions(packId)));
        });
    }

    public PluginHttpResponse listVersions(PluginHttpRequest request) {
        return run(() -> PluginHttpResponse.rawJson(200,
                packAppService.listVersions(requireSegment(request.path(), "packs"))
                        .stream().map(assembler::toVersionRes).toList()));
    }

    public PluginHttpResponse downloadIndex(PluginHttpRequest request) {
        return run(() -> {
            String packId = requireSegment(request.path(), "packs");
            String versionId = requireSegment(request.path(), "versions");
            return PluginHttpResponse.rawJson(200, packAppService.downloadIndex(packId, versionId));
        });
    }

    public PluginHttpResponse downloadOverridesLocation(PluginHttpRequest request) {
        return run(() -> {
            String packId = requireSegment(request.path(), "packs");
            String versionId = requireSegment(request.path(), "versions");
            return PluginHttpResponse.rawJson(200, packAppService.downloadOverridesLocation(packId, versionId));
        });
    }

    public PluginHttpResponse downloadOverrides(PluginHttpRequest request) {
        try {
            String packId = requireSegment(request.path(), "packs");
            String versionId = requireSegment(request.path(), "versions");
            byte[] bytes = packAppService.readOverridesBytes(packId, versionId);
            String filename = packId + "-" + versionId + ".zip";
            return new PluginHttpResponse(
                    200,
                    Map.of("Content-Disposition", "attachment; filename=\"" + filename + "\""),
                    "application/zip",
                    bytes,
                    false
            );
        } catch (IllegalArgumentException | IllegalStateException e) {
            return PluginHttpResponse.rawJson(404, Map.of("message", e.getMessage()));
        } catch (RuntimeException e) {
            return PluginHttpResponse.rawJson(500, Map.of("message", "下载 overrides 失败: " + e.getMessage()));
        }
    }

    public PluginHttpResponse createPack(PluginHttpRequest request) {
        return run(() -> {
            PublishPackRequest body = JsonSupport.read(request.body(), PublishPackRequest.class);
            return PluginHttpResponse.rawJson(201, assembler.toPackRes(
                    packAppService.createPack(body.packId(), body.name(), body.description(), body.icon())));
        });
    }

    public PluginHttpResponse publishVersion(PluginHttpRequest request) {
        return run(() -> {
            PublishPackVersionRequest body = JsonSupport.read(request.body(), PublishPackVersionRequest.class);
            String packId = requireSegment(request.path(), "packs");
            return PluginHttpResponse.rawJson(201, assembler.toVersionRes(packAppService.publishVersion(
                    packId, body.versionId(), body.indexJson(), body.indexHash(),
                    body.overridesObjectKey(), body.downloads(), body.changelog(), principalUserId(request))));
        });
    }

    public PluginHttpResponse rollback(PluginHttpRequest request) {
        return run(() -> {
            RollbackRequest body = JsonSupport.read(request.body(), RollbackRequest.class);
            return PluginHttpResponse.rawJson(200, assembler.toPackRes(
                    packAppService.rollback(requireSegment(request.path(), "packs"), body.targetVersionId())));
        });
    }

    public PluginHttpResponse uploadOverrides(PluginHttpRequest request) {
        return run(() -> {
            UploadOverridesRequest body = JsonSupport.read(request.body(), UploadOverridesRequest.class);
            String packId = requireSegment(request.path(), "packs");
            String versionId = requireSegment(request.path(), "versions");
            return PluginHttpResponse.rawJson(200, assembler.toVersionRes(
                    packAppService.uploadOverrides(packId, versionId, body.overridesContentBase64(), body.expectedSha256())));
        });
    }

    public PluginHttpResponse fetchPageData(PluginHttpRequest request) {
        return run(() -> {
            String providerCode = requireSegment(request.path(), "pages");
            String dataSourceCode = afterSegment(request.path(), "pages", 1);
            if (dataSourceCode == null || dataSourceCode.isBlank()) {
                throw new IllegalArgumentException("缺少 dataSourceCode");
            }
            LauncherProvider provider = findProvider(providerCode);
            if (provider == null) {
                return PluginHttpResponse.rawJson(404, Map.of("message", "provider 不存在或未启用: " + providerCode));
            }
            int page = parseInt(firstQuery(request, "page"), 1, 1, Integer.MAX_VALUE);
            int pageSize = parseInt(firstQuery(request, "pageSize"), 20, 1, 200);
            LauncherContextView view = new LauncherContextView(
                    principalUserId(request), page, pageSize, flattenQuery(request.query()));
            Object data = provider.fetchData(dataSourceCode, view);
            if (data instanceof LauncherDataEnvelope envelope) {
                // v1 观测期兼容：信封拆回 v1 形状（records/total/page/pageSize），v2 端点直接按信封下发
                Map<String, Object> legacy = new LinkedHashMap<>();
                legacy.put("records", envelope.payload());
                if (envelope.total() != null) {
                    legacy.put("total", envelope.total());
                }
                legacy.put("page", page);
                legacy.put("pageSize", pageSize);
                data = legacy;
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("providerCode", providerCode);
            body.put("dataSourceCode", dataSourceCode);
            body.put("data", data);
            return PluginHttpResponse.rawJson(200, body);
        });
    }

    // ---------- 协议 v2 §5：pack 对账与分发端点 ----------

    public PluginHttpResponse packHeadV2(PluginHttpRequest request) {
        return run(() -> PluginHttpResponse.rawJson(200, packAppService.headView(
                requireSegment(request.path(), "packs"), requireSegment(request.path(), "versions"))));
    }

    public PluginHttpResponse packManifestV2(PluginHttpRequest request) {
        return run(() -> PluginHttpResponse.rawJson(200, packAppService.manifestView(
                requireSegment(request.path(), "packs"), requireSegment(request.path(), "versions"))));
    }

    public PluginHttpResponse packMrpackV2(PluginHttpRequest request) {
        try {
            String packId = requireSegment(request.path(), "packs");
            String versionId = requireSegment(request.path(), "versions");
            byte[] bytes = packAppService.buildMrpackSnapshot(packId, versionId);
            String filename = packId + "-" + versionId + ".mrpack";
            return new PluginHttpResponse(
                    200,
                    Map.of("Content-Disposition", "attachment; filename=\"" + filename + "\""),
                    "application/x-modrinth-modpack+zip",
                    bytes,
                    false
            );
        } catch (IllegalArgumentException | IllegalStateException e) {
            return PluginHttpResponse.rawJson(404, Map.of("message", e.getMessage()));
        } catch (RuntimeException e) {
            return PluginHttpResponse.rawJson(500, Map.of("message", "重组 mrpack 快照失败: " + e.getMessage()));
        }
    }

    public PluginHttpResponse downloadManagedFileV2(PluginHttpRequest request) {
        try {
            String packId = requireSegment(request.path(), "packs");
            String sha1 = requireSegment(request.path(), "files");
            byte[] bytes = packAppService.readManagedFile(packId, sha1);
            return new PluginHttpResponse(200, Map.of(), "application/octet-stream", bytes, false);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return PluginHttpResponse.rawJson(404, Map.of("message", e.getMessage()));
        } catch (RuntimeException e) {
            return PluginHttpResponse.rawJson(500, Map.of("message", "读取文件失败: " + e.getMessage()));
        }
    }

    public PluginHttpResponse publishVanillaVersionV2(PluginHttpRequest request) {
        return run(() -> {
            PublishVanillaVersionRequest body = JsonSupport.read(request.body(), PublishVanillaVersionRequest.class);
            String packId = requireSegment(request.path(), "packs");
            return PluginHttpResponse.rawJson(201, assembler.toVersionRes(packAppService.publishVanillaVersion(
                    packId, body.versionId(), body.gameVersion(), body.loader(), body.loaderVersion(),
                    body.changelog(), body.ignorePatterns(), body.overridesContentBase64(),
                    body.expectedSha256(), principalUserId(request))));
        });
    }

    /**
     * 协议 v2 §4.2 统一信封：provider 返回 {@link LauncherDataEnvelope} 时按信封下发（含动作），
     * 返回其它类型时包装为纯 payload。actions/itemActions 在后端按 requiresPermission 过滤，
     * remote 动作由 adapter 补 endpoint。
     */
    public PluginHttpResponse fetchDataV2(PluginHttpRequest request) {
        return run(() -> {
            String providerCode = requireSegment(request.path(), "data");
            String dataSourceCode = afterSegment(request.path(), "data", 1);
            if (dataSourceCode == null || dataSourceCode.isBlank()) {
                throw new IllegalArgumentException("缺少 dataSourceCode");
            }
            LauncherProvider provider = findProvider(providerCode);
            if (provider == null) {
                return PluginHttpResponse.rawJson(404, Map.of("message", "provider 不存在或未启用: " + providerCode));
            }
            List<String> permissions = principalPermissions(request);
            int page = parseInt(firstQuery(request, "page"), 1, 1, Integer.MAX_VALUE);
            int pageSize = parseInt(firstQuery(request, "pageSize"), 20, 1, 200);
            LauncherContextView view = new LauncherContextView(
                    principalUserId(request), page, pageSize, flattenQuery(request.query()), permissions);
            Object data = provider.fetchData(dataSourceCode, view);
            Object payload = data;
            Long total = null;
            List<LauncherAction> actions = List.of();
            List<LauncherAction> itemActions = List.of();
            if (data instanceof LauncherDataEnvelope envelope) {
                payload = envelope.payload();
                total = envelope.total();
                actions = envelope.actions();
                itemActions = envelope.itemActions();
            }
            LauncherDataSource source = aggregator.findDataSource(dataSourceCode);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("schemaVersion", source == null ? 1 : source.schemaVersion());
            body.put("dataSource", dataSourceCode);
            Map<String, Object> pageView = new LinkedHashMap<>();
            pageView.put("page", page);
            pageView.put("pageSize", pageSize);
            if (total != null) {
                pageView.put("total", total);
            }
            body.put("page", pageView);
            body.put("payload", payload);
            body.put("actions", actionViews(actions, permissions, providerCode));
            body.put("itemActions", actionViews(itemActions, permissions, providerCode));
            return PluginHttpResponse.rawJson(200, body);
        });
    }

    /**
     * 协议 v2 §4.4 remote 动作回传：路由到 provider 的 {@code executeAction}。
     * provider 未实现该动作时回 400；返回 null 视为静默成功（200 空对象）。
     */
    public PluginHttpResponse executeActionV2(PluginHttpRequest request) {
        if (request.principal() == null || request.principal().userId() == null) {
            return PluginHttpResponse.rawJson(401, Map.of("message", "未登录或令牌已失效"));
        }
        return run(() -> {
            String providerCode = requireSegment(request.path(), "action");
            String actionCode = afterSegment(request.path(), "action", 1);
            if (actionCode == null || actionCode.isBlank()) {
                throw new IllegalArgumentException("缺少 actionCode");
            }
            LauncherProvider provider = findProvider(providerCode);
            if (provider == null) {
                return PluginHttpResponse.rawJson(404, Map.of("message", "provider 不存在或未启用: " + providerCode));
            }
            Map<String, Object> bodyMap = JsonSupport.read(request.body(), Map.class);
            Object rawPayload = bodyMap.get("payload");
            Map<String, Object> payload = rawPayload instanceof Map<?, ?> map
                    ? (Map<String, Object>) map
                    : Map.of();
            Object result;
            try {
                result = provider.executeAction(actionCode, new LauncherActionContext(
                        String.valueOf(request.principal().userId()), principalPermissions(request), payload));
            } catch (UnsupportedOperationException e) {
                return PluginHttpResponse.rawJson(400, Map.of("message",
                        e.getMessage() == null ? "动作不支持: " + actionCode : e.getMessage()));
            }
            if (result == null) {
                return PluginHttpResponse.rawJson(200, Map.of());
            }
            if (result instanceof Map<?, ?> map) {
                return PluginHttpResponse.rawJson(200, map);
            }
            return PluginHttpResponse.rawJson(200, Map.of("result", result));
        });
    }

    private List<Map<String, Object>> actionViews(List<LauncherAction> actions, List<String> permissions, String providerCode) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (LauncherAction action : actions == null ? List.<LauncherAction>of() : actions) {
            if (action == null || !actionAllowed(action, permissions)) {
                continue;
            }
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("code", safe(action.code()));
            view.put("title", safe(action.title()));
            if (!safe(action.icon()).isBlank()) {
                view.put("icon", safe(action.icon()));
            }
            view.put("kind", safe(action.kind()));
            view.put("type", safe(action.type()));
            if ("remote".equals(action.type())) {
                view.put("endpoint", "/api/plugins/launcher-adapter/v2/action/" + providerCode + "/" + action.code());
            }
            if (action.payload() != null && !action.payload().isEmpty()) {
                view.put("payload", action.payload());
            }
            result.add(view);
        }
        return result;
    }

    private boolean actionAllowed(LauncherAction action, List<String> permissions) {
        String required = action.requiresPermission();
        if (required == null || required.isBlank()) {
            return true;
        }
        return permissions.contains("*") || permissions.contains(required);
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    public PluginHttpResponse exchangeYgg(PluginHttpRequest request) {
        if (request.principal() == null || request.principal().userId() == null) {
            return PluginHttpResponse.rawJson(401, Map.of("message", "未登录"));
        }
        Optional<PluginAuthService> service = authService();
        if (service.isEmpty()) {
            return PluginHttpResponse.rawJson(503, Map.of("message", "authlib-injector 插件未启用，无法签发 ygg 会话"));
        }
        YggExchangeRequest body = JsonSupport.read(request.body(), YggExchangeRequest.class);
        String userId = String.valueOf(request.principal().userId());
        try {
            PluginAuthService.IssuedSession issued = service.get().issueSession(userId, body.clientToken(), body.profileName());
            return PluginHttpResponse.rawJson(200, new YggSessionRes(
                    issued.userId(),
                    issued.username(),
                    issued.profileId(),
                    issued.accessToken(),
                    issued.clientToken(),
                    issued.availableProfiles(),
                    Map.of("id", issued.profileId(), "name", issued.username())
            ));
        } catch (IllegalArgumentException e) {
            return PluginHttpResponse.rawJson(400, Map.of("message", e.getMessage()));
        } catch (RuntimeException e) {
            return PluginHttpResponse.rawJson(500, Map.of("message", "签发失败: " + e.getMessage()));
        }
    }

    public PluginHttpResponse listYggProfiles(PluginHttpRequest request) {
        if (request.principal() == null || request.principal().userId() == null) {
            return PluginHttpResponse.rawJson(401, Map.of("message", "未登录"));
        }
        Optional<PluginAuthService> service = authService();
        if (service.isEmpty()) {
            return PluginHttpResponse.rawJson(503, Map.of("message", "authlib-injector 插件未启用"));
        }
        String userId = String.valueOf(request.principal().userId());
        List<PluginAuthProfile> profiles = service.get().listProfiles(userId);
        return PluginHttpResponse.rawJson(200, Map.of("userId", userId, "profiles", profiles));
    }

    private Optional<PluginAuthService> authService() {
        if (!context.dependencyAvailable(AUTHLIB_PLUGIN_CODE)) {
            return Optional.empty();
        }
        return context.service(AUTHLIB_PLUGIN_CODE, PluginAuthService.class);
    }

    private LauncherProvider findProvider(String providerCode) {
        for (LauncherProvider provider : context.extensions(LauncherProvider.class)) {
            if (providerCode.equals(provider.providerCode())) {
                return provider;
            }
        }
        return null;
    }

    private PluginHttpResponse run(Handler handler) {
        try {
            return handler.handle();
        } catch (IllegalArgumentException e) {
            return PluginHttpResponse.rawJson(400, Map.of("message", e.getMessage()));
        } catch (IllegalStateException e) {
            return PluginHttpResponse.rawJson(409, Map.of("message", e.getMessage()));
        } catch (LinkageError e) {
            // 贡献方类快照过期（reload 后未重载消费方）或依赖未导出：明确提示而非打成 400
            return PluginHttpResponse.rawJson(502, Map.of(
                    "message", "内容提供方类加载失败，请重载提供方插件后重试: " + e.getMessage()));
        } catch (RuntimeException e) {
            return PluginHttpResponse.rawJson(500, Map.of("message", e.getMessage()));
        }
    }

    private String principalUserId(PluginHttpRequest request) {
        if (request.principal() == null || request.principal().userId() == null) {
            return null;
        }
        return String.valueOf(request.principal().userId());
    }

    private List<String> principalPermissions(PluginHttpRequest request) {
        if (request.principal() == null || request.principal().userId() == null
                || request.principal().permissions() == null) {
            return List.of();
        }
        return request.principal().permissions();
    }

    private String requireSegment(String path, String marker) {
        String value = afterSegment(path, marker, 0);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("路径缺少 " + marker + " 标识");
        }
        return value;
    }

    private String afterSegment(String path, String marker, int offset) {
        String[] segments = trim(path).split("/");
        for (int i = 0; i + 1 + offset < segments.length; i++) {
            if (marker.equals(segments[i])) {
                return decode(segments[i + 1 + offset]);
            }
        }
        return null;
    }

    private String firstQuery(PluginHttpRequest request, String key) {
        List<String> values = request.query().get(key);
        return values == null || values.isEmpty() ? null : values.getFirst();
    }

    private Map<String, String> flattenQuery(Map<String, List<String>> query) {
        Map<String, String> flat = new LinkedHashMap<>();
        query.forEach((k, v) -> {
            if (v != null && !v.isEmpty()) {
                flat.put(k, v.getFirst());
            }
        });
        return flat;
    }

    private int parseInt(String value, int def, int min, int max) {
        if (value == null) {
            return def;
        }
        try {
            int parsed = Integer.parseInt(value);
            return Math.max(min, Math.min(max, parsed));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private String trim(String path) {
        String value = path == null ? "" : path.trim();
        while (value.startsWith("/")) {
            value = value.substring(1);
        }
        return value;
    }

    private String decode(String value) {
        return value == null ? null : URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private YmclLauncherChrome toChrome(YmclChromeSaveRequest body) {
        List<YmclNavNode> tree = toNavNodes(body.navTree(), true);
        if (tree.isEmpty()) {
            tree = List.of(YmclLauncherChrome.defaultSiteTab());
        }
        return new YmclLauncherChrome(body.displayName(), body.logoUrl(), body.backgroundUrl(), tree);
    }

    private List<YmclNavNode> toNavNodes(List<YmclChromeSaveRequest.YmclNavNodeRequest> requests, boolean root) {
        if (requests == null) {
            return List.of();
        }
        List<YmclNavNode> nodes = new java.util.ArrayList<>();
        int index = 0;
        for (YmclChromeSaveRequest.YmclNavNodeRequest request : requests) {
            if (request == null) {
                continue;
            }
            String title = request.title() == null ? "" : request.title().trim();
            String pageCode = request.pageCode() == null ? "" : request.pageCode().trim();
            List<YmclNavNode> children = toNavNodes(request.children(), false);
            if (title.isBlank() && pageCode.isBlank() && children.isEmpty()) {
                continue;
            }
            String kind = root ? YmclNavNode.KIND_TAB : YmclNavNode.KIND_MENU;
            String prefix = root ? "tab-" : "menu-";
            String code = request.code() == null || request.code().isBlank() ? prefix + index : request.code().trim();
            nodes.add(new YmclNavNode(
                    code,
                    kind,
                    title,
                    request.icon(),
                    pageCode,
                    request.visible() == null || request.visible(),
                    children
            ));
            index++;
        }
        return nodes;
    }

    @FunctionalInterface
    private interface Handler {
        PluginHttpResponse handle();
    }
}
