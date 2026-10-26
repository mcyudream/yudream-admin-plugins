package online.yudream.base.plugin.interop.infrastructure.launcher;

import online.yudream.base.plugin.ymcl.api.YmclBundleContribution;
import online.yudream.base.plugin.ymcl.api.YmclContributionProvider;
import online.yudream.base.plugin.ymcl.api.YmclDataSourceDescriptor;
import online.yudream.base.plugin.ymcl.api.YmclModuleSupport;
import online.yudream.base.plugin.ymcl.api.YmclPageDescriptor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 向 yda 域的 YAP manifest 贡献「友站服务器」页面：优先 module 自绘页
 * （friends-page.js，视觉与 mc-server 服务器页同款），资源缺失时降级为
 * 内置 server-list 渲染器。数据源 = mc-interop 合并目录（本站已发布条目
 * + peers 互采友站条目），条目动作 client:install-and-join / client:copy
 * / client:launch-server 由启动器通用执行。
 */
public class InteropYmclContributionProvider implements YmclContributionProvider {

    public static final String PROVIDER_CODE = "mc-interop";
    public static final String SOURCE_CATALOG = "interop-catalog";
    private static final int SCHEMA_VERSION = 1;
    /** 适配器侧数据缓存 TTL：60s——启动器「刷新」要能较快见到互采更新（300s 时手动拉取形同虚设）。 */
    private static final int CACHE_TTL_SECONDS = 60;

    /** 友站页 module 资源（YAP §6.8）：jar 内 ESM，经适配器 bundle 通道下发。 */
    private static final String MODULE_RESOURCE = "/ymcl-pages/friends-page.js";
    private static final String MODULE_BUNDLE_ID = "ymcl-mc-interop-friends";
    private static final String MODULE_ENTRY = "friends-page.js";

    /** 目录文档回调（由 bootstrap 注入 CatalogService::siteDocument）。 */
    public interface CatalogDocumentSupplier {
        Map<String, Object> document();
    }

    private final CatalogDocumentSupplier catalogDocument;

    public InteropYmclContributionProvider(CatalogDocumentSupplier catalogDocument) {
        this.catalogDocument = catalogDocument;
    }

    private static YmclModuleSupport.YmclModuleBundle moduleBundle() {
        return YmclModuleSupport.fromResource(
                InteropYmclContributionProvider.class,
                MODULE_RESOURCE,
                MODULE_BUNDLE_ID,
                MODULE_ENTRY,
                List.of(YmclModuleSupport.PERMISSION_DATA_FETCH, YmclModuleSupport.PERMISSION_ACTION_EXECUTE));
    }

    @Override
    public String providerCode() {
        return PROVIDER_CODE;
    }

    @Override
    public List<YmclPageDescriptor> pages() {
        YmclModuleSupport.YmclModuleBundle module = moduleBundle();
        if (module != null) {
            return List.of(new YmclPageDescriptor(
                    PROVIDER_CODE + "-catalog",
                    "module",
                    "友站服务器",
                    "i-ri:earth-line",
                    PROVIDER_CODE + "." + SOURCE_CATALOG,
                    15,
                    "plugin:mc-interop:view",
                    Map.of(),
                    module.descriptor()));
        }
        // 资源缺失时降级为内置 server-list 渲染器，页面不失联。
        return List.of(new YmclPageDescriptor(
                PROVIDER_CODE + "-catalog",
                "server-list",
                "友站服务器",
                "i-ri:earth-line",
                PROVIDER_CODE + "." + SOURCE_CATALOG,
                15,
                "plugin:mc-interop:view",
                Map.of(),
                null));
    }

    @Override
    public List<YmclBundleContribution> bundles() {
        YmclModuleSupport.YmclModuleBundle module = moduleBundle();
        return module == null ? List.of() : List.of(module.contribution());
    }

    @Override
    public List<YmclDataSourceDescriptor> dataSources() {
        return List.of(new YmclDataSourceDescriptor(
                SOURCE_CATALOG, "跨站目录", SCHEMA_VERSION, CACHE_TTL_SECONDS));
    }

    @Override
    public Object fetchData(String sourceCode, online.yudream.base.plugin.ymcl.api.YmclDataContext context) {
        Map<String, Object> document = castMap(catalogDocument.document());
        Map<String, Object> site = cast(document.get("site"));
        String catalogOrigin = site == null ? null : text(site.get("origin"));
        List<Map<String, Object>> packs = castPacks(document.get("packs"));
        // server-list 渲染器契约：id/title/summary/address/icon，与 minecraft-server
        // 贡献的服务器列表同风格；声明式动作（一键安装并进服/复制地址）由启动器通用执行。
        List<Map<String, Object>> records = new ArrayList<>();
        for (Map<String, Object> pack : packs) {
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("id", text(pack.get("id")));
            record.put("title", text(pack.get("name")));
            record.put("summary", buildSummary(pack));
            record.put("address", address(pack));
            record.put("origin", catalogOrigin);
            // module 页面按 hasPack 区分「一键安装并进服」与「加入游戏」。
            record.put("hasPack", pack.get("artifact") instanceof Map);
            Object source = pack.get("source");
            if (source instanceof Map<?, ?> sourceMap) {
                record.put("friendSite", text(sourceMap.get("siteId")));
            }
            records.add(record);
        }
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("schemaVersion", SCHEMA_VERSION);
        envelope.put("records", records);
        envelope.put("total", (long) records.size());
        envelope.put("itemActions", List.of(
                action("install-and-join", "一键安装并进服", "client:install-and-join", true,
                        Map.of(
                                "origin", "{{item.origin}}",
                                "entryId", "{{item.id}}",
                                "address", "{{item.address}}",
                                "title", "{{item.title}}")),
                action("copy-address", "复制地址", "client:copy", false,
                        Map.of("text", "{{item.address}}"))));
        return envelope;
    }

    private static Map<String, Object> action(
            String code, String title, String kind, boolean primary, Map<String, Object> params) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("code", code);
        view.put("title", title);
        view.put("kind", kind);
        view.put("primary", primary);
        view.put("params", params);
        return view;
    }

    @SuppressWarnings("unchecked")
    private static String castText(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> castPacks(Object value) {
        return value instanceof List ? (List<Map<String, Object>>) value : List.of();
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String address(Map<String, Object> pack) {
        Object server = pack.get("server");
        if (!(server instanceof Map<?, ?> serverMap)) {
            return null;
        }
        Object host = serverMap.get("address");
        if (host == null) {
            return null;
        }
        Object port = serverMap.get("port");
        return port == null ? String.valueOf(host) : host + ":" + port;
    }

    private static String buildSummary(Map<String, Object> pack) {
        StringBuilder builder = new StringBuilder();
        Object summary = pack.get("summary");
        if (summary != null && !String.valueOf(summary).isBlank()) {
            builder.append(summary);
        }
        Object gameVersion = pack.get("gameVersion");
        if (gameVersion != null) {
            if (!builder.isEmpty()) {
                builder.append(" · ");
            }
            builder.append("MC ").append(gameVersion);
        }
        Object loader = pack.get("loader");
        if (loader != null && !"vanilla".equals(loader)) {
            builder.append(" · ").append(loader);
        }
        return builder.toString();
    }
}
