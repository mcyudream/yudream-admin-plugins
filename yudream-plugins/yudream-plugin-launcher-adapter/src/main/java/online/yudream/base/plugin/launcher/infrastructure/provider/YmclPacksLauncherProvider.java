package online.yudream.base.plugin.launcher.infrastructure.provider;

import online.yudream.base.plugin.launcher.api.LauncherAction;
import online.yudream.base.plugin.launcher.api.LauncherContextView;
import online.yudream.base.plugin.launcher.api.LauncherDataEnvelope;
import online.yudream.base.plugin.launcher.api.LauncherDataSource;
import online.yudream.base.plugin.launcher.api.LauncherPage;
import online.yudream.base.plugin.launcher.api.LauncherProvider;
import online.yudream.base.plugin.launcher.application.service.PackAppService;
import online.yudream.base.plugin.launcher.bootstrap.LauncherAdapterPlugin;
import online.yudream.base.plugin.launcher.domain.aggregate.LauncherPack;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 向 YMCL 贡献站点整合包目录页。
 */
public class YmclPacksLauncherProvider implements LauncherProvider {

    public static final String PAGE_PACKS = "ymcl.packs";
    public static final String DATA_SOURCE_PACKS = "ymcl.packs.list";

    private final PackAppService packAppService;

    public YmclPacksLauncherProvider(PackAppService packAppService) {
        this.packAppService = packAppService;
    }

    @Override
    public String providerCode() {
        return LauncherAdapterPlugin.CODE;
    }

    @Override
    public List<LauncherPage> pages() {
        return List.of(new LauncherPage(
                PAGE_PACKS,
                "站点整合包",
                "i-ri:box-3-line",
                20,
                "pack-catalog",
                "/domain/packs",
                DATA_SOURCE_PACKS,
                null,
                LauncherAdapterPlugin.CODE,
                Map.of(),
                LauncherAdapterPlugin.VIEW_PERMISSION
        ));
    }

    @Override
    public List<LauncherDataSource> dataSources() {
        return List.of(new LauncherDataSource(
                DATA_SOURCE_PACKS,
                "站点整合包",
                2,
                PAGE_PACKS
        ));
    }

    @Override
    public Object fetchData(String dataSourceCode, LauncherContextView context) {
        if (!DATA_SOURCE_PACKS.equals(dataSourceCode)) {
            return LauncherDataEnvelope.paged(List.of(), 0);
        }
        List<LauncherPack> packs = packAppService.listPacks();
        List<Map<String, Object>> records = packs.stream().map(pack -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", pack.id());
            item.put("name", pack.name());
            item.put("description", pack.description() == null ? "" : pack.description());
            item.put("icon", pack.icon() == null ? "" : pack.icon());
            item.put("recommendedVersion", pack.recommendedVersionId() == null ? "" : pack.recommendedVersionId());
            item.put("updatedAt", String.valueOf(pack.updatedAt()));
            return item;
        }).toList();
        return new LauncherDataEnvelope(
                records,
                (long) records.size(),
                List.of(new LauncherAction("refresh", "刷新", "i-ri:refresh-line",
                        "secondary", "client:reload", Map.of(), "")),
                List.of(new LauncherAction("install", "安装 / 更新", "i-ri:download-line",
                        "primary", "client:install-pack",
                        Map.of("packId", "{item.id}", "versionId", "{item.recommendedVersion}"), "")));
    }
}
