package online.yudream.base.plugin.mcguess.infrastructure;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import javax.imageio.ImageIO;
import online.yudream.base.plugin.mcwiki.api.McWikiApi;

/**
 * 图标按需从 mc-wiki 共享渲染资产库（getRender）拉取并缓存为模板使用的 data URI；
 * 渲染资产全版本共用，不按版本搬运。provider 引用每次经 supplier 重新解析，mc-wiki 重载/禁用期间降级为无图标
 * 而不是持有过期 API 对象；生效数据版本（与目录同一来源）变化时清空缓存。
 * 未命中不做负缓存：管理端一键更新渲染资产后无需重启即可自愈出图。
 * 模板内嵌前缩到 48px PNG，避免宾果 25 格把 HTML 撑过渲染服务体积上限。
 */
public final class IconSupport {
    static final int TEMPLATE_ICON_PX = 48;

    private final Supplier<Optional<McWikiApi>> wikiApi;
    private final Supplier<Optional<String>> effectiveVersion;
    private final Map<String, String> cache = new ConcurrentHashMap<>();
    private volatile String version;

    public IconSupport(Supplier<Optional<McWikiApi>> wikiApi, Supplier<Optional<String>> effectiveVersion) {
        this.wikiApi = wikiApi;
        this.effectiveVersion = effectiveVersion;
    }

    public String dataUri(String itemId) {
        if (itemId == null || itemId.isBlank()) return null;
        Optional<McWikiApi> api = wikiApi.get();
        if (api.isEmpty()) return null;
        String current = effectiveVersion.get().orElse(null);
        if (current == null) return null;
        if (!current.equals(version)) reset(current);
        String cached = cache.get(itemId);
        if (cached != null) return cached;
        return api.get().getRender(itemId, "item")
                .map(bytes -> {
                    String uri = "data:image/png;base64," + Base64.getEncoder().encodeToString(compactPng(bytes));
                    cache.putIfAbsent(itemId, uri);
                    return uri;
                })
                .orElse(null);
    }

    static byte[] compactPng(byte[] source) {
        if (source == null || source.length == 0) {
            return source;
        }
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(source));
            if (image == null) {
                return source;
            }
            BufferedImage scaled = new BufferedImage(TEMPLATE_ICON_PX, TEMPLATE_ICON_PX, BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = scaled.createGraphics();
            try {
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                graphics.drawImage(image, 0, 0, TEMPLATE_ICON_PX, TEMPLATE_ICON_PX, null);
            } finally {
                graphics.dispose();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (!ImageIO.write(scaled, "png", out)) {
                return source;
            }
            byte[] compacted = out.toByteArray();
            return compacted.length > 0 && compacted.length < source.length ? compacted : source;
        } catch (IOException ignored) {
            return source;
        }
    }

    private synchronized void reset(String current) {
        if (current.equals(version)) return;
        version = current;
        cache.clear();
    }
}
