package online.yudream.base.plugin.interop.domain.aggregate;

/**
 * 站点身份（mc-interop 出口 site 块的事实源）。
 *
 * <p>优先来自 yudream-union（站点身份单一事实源）；union 未安装或未配置时
 * 回退本插件设置。</p>
 */
public record SiteIdentityRecord(String code, String name, String origin) {
}
