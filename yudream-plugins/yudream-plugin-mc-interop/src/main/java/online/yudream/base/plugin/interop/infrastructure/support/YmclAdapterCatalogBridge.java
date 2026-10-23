package online.yudream.base.plugin.interop.infrastructure.support;

import online.yudream.base.plugin.interop.domain.aggregate.AdapterCatalogEntry;
import online.yudream.base.plugin.spi.core.PluginContext;
import online.yudream.base.plugin.ymcl.api.YmclPackCatalogService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ymcl-adapter 目录桥：全部 ymcl api 类型引用隔离在本类，仅在
 * {@code context.dependencyAvailable("ymcl-adapter")} 时实例化/调用。
 */
public class YmclAdapterCatalogBridge {

    public static final String ADAPTER_PLUGIN_CODE = "ymcl-adapter";

    private final PluginContext context;

    public YmclAdapterCatalogBridge(PluginContext context) {
        this.context = context;
    }

    /** 静态入口：bootstrap 以 Supplier 引用，ymcl-adapter 在位时才实际加载本类。 */
    public static List<AdapterCatalogEntry> catalogOf(PluginContext context) {
        return new YmclAdapterCatalogBridge(context).catalog();
    }

    /** 适配器聚合目录：pack 最新版 × 绑定服务器。 */
    public List<AdapterCatalogEntry> catalog() {
        List<Map<String, Object>> rows = context
                .service(ADAPTER_PLUGIN_CODE, YmclPackCatalogService.class)
                .map(YmclPackCatalogService::catalog)
                .orElse(List.of());
        List<AdapterCatalogEntry> entries = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            entries.add(new AdapterCatalogEntry(
                    text(row.get("packId")),
                    text(row.get("packName")),
                    text(row.get("version")),
                    text(row.get("gameVersion")),
                    text(row.get("loaderType")),
                    text(row.get("loaderVersion")),
                    text(row.get("sha512")),
                    nullableLong(row.get("sizeBytes")),
                    text(row.get("mrpackPath")),
                    text(row.get("serverId")),
                    text(row.get("serverName")),
                    text(row.get("serverAddress"))));
        }
        return entries;
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Long nullableLong(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }
}
