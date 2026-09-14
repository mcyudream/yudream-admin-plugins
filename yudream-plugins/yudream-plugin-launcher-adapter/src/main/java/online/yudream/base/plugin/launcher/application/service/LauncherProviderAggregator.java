package online.yudream.base.plugin.launcher.application.service;

import online.yudream.base.plugin.launcher.api.LauncherDataSource;
import online.yudream.base.plugin.launcher.api.LauncherPage;
import online.yudream.base.plugin.launcher.api.LauncherProvider;
import online.yudream.base.plugin.spi.core.PluginContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 启动器侧内容贡献聚合器。
 * <p>
 * 启动时通过 {@link PluginContext#extensions(Class)} 拉取所有 {@link LauncherProvider} 实现，
 * 在每次 manifest 构建时按 {@link LauncherPage#navOrder()} 升序汇总所有页面与数据源。
 * 禁用/卸载的 provider 不会出现在结果中，启动器侧对应页面自然消失。
 */
public class LauncherProviderAggregator {

    private final PluginContext context;

    public LauncherProviderAggregator(PluginContext context) {
        this.context = context;
    }

    public List<LauncherPage> aggregatePages() {
        List<LauncherPage> all = new ArrayList<>();
        for (LauncherProvider provider : context.extensions(LauncherProvider.class)) {
            try {
                all.addAll(stamp(provider, provider.pages()));
            } catch (RuntimeException | LinkageError ignored) {
                // 单个 provider 失败或 ClassLoader 快照过期（NoSuchMethodError）不影响整体聚合
            }
        }
        return all.stream()
                .sorted(Comparator.comparingInt(LauncherPage::navOrder).thenComparing(LauncherPage::code))
                .toList();
    }

    public List<LauncherDataSource> aggregateDataSources() {
        List<LauncherDataSource> all = new ArrayList<>();
        for (LauncherProvider provider : context.extensions(LauncherProvider.class)) {
            try {
                all.addAll(provider.dataSources());
            } catch (RuntimeException | LinkageError ignored) {
            }
        }
        return all.stream()
                .sorted(Comparator.comparing(LauncherDataSource::code))
                .toList();
    }

    /**
     * 数据源 code → 贡献方 providerCode 映射（重复 code 先注册者胜），供 v2 下发端点与数据路由。
     */
    public java.util.Map<String, String> dataSourceProviders() {
        java.util.Map<String, String> map = new java.util.LinkedHashMap<>();
        for (LauncherProvider provider : context.extensions(LauncherProvider.class)) {
            try {
                for (LauncherDataSource source : provider.dataSources()) {
                    if (source != null && source.code() != null && !source.code().isBlank()) {
                        map.putIfAbsent(source.code(), provider.providerCode());
                    }
                }
            } catch (RuntimeException | LinkageError ignored) {
            }
        }
        return map;
    }

    /**
     * 按数据源 code 查声明（用于信封 schemaVersion），找不到返回 null。
     */
    public LauncherDataSource findDataSource(String dataSourceCode) {
        for (LauncherDataSource source : aggregateDataSources()) {
            if (source.code() != null && source.code().equals(dataSourceCode)) {
                return source;
            }
        }
        return null;
    }

    private static List<LauncherPage> stamp(LauncherProvider provider, List<LauncherPage> pages) {
        if (pages == null || pages.isEmpty()) {
            return List.of();
        }
        String providerCode = provider.providerCode();
        List<LauncherPage> stamped = new ArrayList<>(pages.size());
        for (LauncherPage page : pages) {
            if (page == null) {
                continue;
            }
            if (page.providerCode() != null && !page.providerCode().isBlank()) {
                stamped.add(page);
            } else {
                stamped.add(new LauncherPage(
                        page.code(), page.title(), page.icon(), page.navOrder(), page.type(),
                        page.path(), page.dataSourceCode(), page.extensionPackageId(), providerCode,
                        page.props(), page.requiresPermission()));
            }
        }
        return stamped;
    }
}