package online.yudream.plugin.webcard.infrastructure;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import online.yudream.base.plugin.spi.system.render.PluginRenderService;
import online.yudream.plugin.webcard.domain.WebCardModels.TemplateMode;
import online.yudream.plugin.webcard.domain.WebCardModels.TemplateVersion;
import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;

import java.net.URI;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CardRenderer {
    private static final Pattern UNSAFE_CSS = Pattern.compile("(?is)(@import|expression\\s*\\(|javascript:|behavior\\s*|-moz-binding|url\\s*\\(|calc\\s*\\(|transform\\s*:|\\d+(?:\\.\\d+)?(?:vw|vh|vmin|vmax))");
    private static final Pattern CSS_LENGTH = Pattern.compile("(?i)(\\d+(?:\\.\\d+)?)(px|em|rem|%|cm|mm|in|pt|pc)");
    private static final Pattern SAFE_COLOR = Pattern.compile("#[0-9a-fA-F]{6}");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_TEMPLATE_CHARS = 100_000;
    private static final int MAX_RENDER_DIMENSION = 4096;
    private final PluginRenderService render;

    public CardRenderer(PluginRenderService render) { this.render = render; }

    public String renderBase64(TemplateVersion template, Map<String, Object> fields) {
        String html = renderHtml(template, fields);
        try {
            var image = render.html(html, "#web-card").toCompletableFuture().get(25, TimeUnit.SECONDS);
            if (image == null || image.content().length == 0 || image.width() <= 0 || image.height() <= 0) {
                throw new IllegalArgumentException("渲染服务返回空图片");
            }
            if (image.width() > MAX_RENDER_DIMENSION || image.height() > MAX_RENDER_DIMENSION
                    || (long) image.width() * image.height() > 16_000_000L) {
                throw new IllegalArgumentException("卡片渲染尺寸超过安全限制");
            }
            return Base64.getEncoder().encodeToString(image.content());
        } catch (Exception error) {
            throw new IllegalArgumentException("卡片渲染失败：" + root(error), error);
        }
    }

    String renderHtml(TemplateVersion template, Map<String, Object> fields) {
        return template.mode() == TemplateMode.ADVANCED ? advanced(template, fields) : structured(template, fields);
    }

    private String structured(TemplateVersion template, Map<String, Object> fields) {
        StructuredLayout config = structuredLayout(template.structuredLayout());
        String title = escape(value(fields, "title", "网页内容"));
        String summary = escape(value(fields, "summary", value(fields, "description", "")));
        String url = escape(value(fields, "url", ""));
        String source = escape(value(fields, "source", source(url)));
        String image = value(fields, "image", "");
        boolean hasImage = config.showImage() && safeImage(image);
        String imageHtml = hasImage ? "<div class=\"media\"><img class=\"cover\" src=\"" + escape(image) + "\" alt=\"\"></div>" : "";
        StringBuilder rows = new StringBuilder();
        fields.forEach((key, item) -> {
            if (config.showsExtraField(key) && !Set.of("title", "summary", "description", "url", "image", "source").contains(key) && item != null) {
                rows.append("<div class=\"row\"><span>").append(escape(key)).append("</span><strong>")
                        .append(escape(String.valueOf(item))).append("</strong></div>");
            }
        });
        String classes = "card variant-" + config.variant() + (hasImage ? " has-media" : " no-media");
        String sourceHtml = config.showSource() ? "<div class=\"eyebrow\">" + source + "</div>" : "";
        String summaryHtml = config.showSummary() && !summary.isBlank() ? "<div class=\"summary\">" + summary + "</div>" : "";
        String rowsHtml = config.sections().isEmpty()
                ? rows.isEmpty() ? "" : "<div class=\"fields\">" + rows + "</div>"
                : sections(fields, config.sections());
        String urlHtml = config.showUrl() && !url.isBlank() ? "<div class=\"url\">" + url + "</div>" : "";
        return "<!doctype html><html><head><meta charset=\"UTF-8\"><style>"
                + ":root{--accent:" + config.accentColor() + "}body{margin:0;padding:18px;background:transparent;font-family:Inter,Arial,'Microsoft YaHei',sans-serif;color:#17212b}"
                + ".card{width:760px;overflow:hidden;border:1px solid #dce2e8;border-radius:8px;background:#fff;box-shadow:0 14px 34px rgba(27,39,51,.13)}"
                + ".variant-editorial.has-media,.variant-compact.has-media{display:grid;grid-template-columns:280px minmax(0,1fr);min-height:360px}.variant-compact{width:660px}.variant-compact.has-media{grid-template-columns:220px minmax(0,1fr);min-height:290px}.variant-poster .media{height:340px}"
                + ".media{min-height:100%;background:#e9edf1}.cover{display:block;width:100%;height:100%;min-height:290px;object-fit:cover}.body{display:flex;min-width:0;flex-direction:column;padding:30px 32px}.variant-compact .body{padding:24px 26px}"
                + ".eyebrow{display:flex;align-items:center;gap:8px;color:var(--accent);font-size:13px;font-weight:700}.eyebrow:before{width:8px;height:8px;border-radius:50%;background:var(--accent);content:''}.title{margin-top:16px;font-size:29px;line-height:1.3;font-weight:750}.variant-compact .title{font-size:25px}"
                + ".summary{margin-top:13px;color:#586674;font-size:16px;line-height:1.65}.fields{display:grid;gap:0;margin-top:20px;border-top:1px solid #e7ebef}.row{display:grid;grid-template-columns:104px minmax(0,1fr);gap:14px;padding:10px 0;border-bottom:1px solid #edf0f3;font-size:14px}.row span{color:#7a8692}.row strong{font-weight:650;overflow-wrap:anywhere}.url{margin-top:auto;padding-top:22px;color:var(--accent);font-size:12px;overflow-wrap:anywhere}"
                + ".sections{display:grid;gap:18px;margin-top:22px}.section{display:grid;gap:10px}.section-title{color:#344454;font-size:13px;font-weight:750}.section-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:8px}.section-item{min-width:0;border:1px solid #e5e9ed;background:#f8fafb;padding:10px 12px}.section-item>span{display:block;margin-bottom:5px;color:#7a8692;font-size:11px}.section-item>strong{display:block;font-size:13px;line-height:1.5;overflow-wrap:anywhere}.value-list{display:flex;flex-wrap:wrap;gap:6px}.value-list b{border:1px solid #dce4e8;background:#fff;padding:3px 7px;font-size:11px;font-weight:650}.value-map{display:grid;gap:5px}.value-map div{display:grid;grid-template-columns:minmax(72px,.7fr) minmax(0,1.3fr);gap:8px}.value-map em{color:#74818d;font-style:normal;font-weight:500}.section-list .section-grid{grid-template-columns:1fr}@media(max-width:620px){.section-grid{grid-template-columns:1fr}}"
                + "</style></head><body><article id=\"web-card\" class=\"" + classes + "\">" + imageHtml
                + "<div class=\"body\">" + sourceHtml + "<div class=\"title\">" + title + "</div>" + summaryHtml + rowsHtml + urlHtml
                + "</div></article></body></html>";
    }

    private StructuredLayout structuredLayout(String source) {
        if (source == null || source.isBlank() || "default".equalsIgnoreCase(source.trim())) return StructuredLayout.defaults();
        try {
            Map<String, Object> values = JSON.readValue(source, new TypeReference<>() { });
            String variant = String.valueOf(values.getOrDefault("variant", "editorial")).toLowerCase(Locale.ROOT);
            if (!Set.of("editorial", "compact", "poster").contains(variant)) {
                throw new IllegalArgumentException("结构化布局 variant 仅支持 editorial、compact 或 poster");
            }
            String color = String.valueOf(values.getOrDefault("accentColor", "#39725d"));
            if (!SAFE_COLOR.matcher(color).matches()) throw new IllegalArgumentException("accentColor 需要使用 #RRGGBB 格式");
            Object extra = values.getOrDefault("extraFields", "auto");
            List<String> selectedFields = extra instanceof List<?> list
                    ? list.stream().map(String::valueOf).filter(value -> !value.isBlank()).toList() : List.of();
            boolean showExtras = !(extra instanceof Boolean bool && !bool) && !"none".equalsIgnoreCase(String.valueOf(extra));
            List<LayoutSection> sections = sections(values.get("sections"));
            return new StructuredLayout(variant, color, bool(values, "showImage", true), bool(values, "showSource", true),
                    bool(values, "showSummary", true), bool(values, "showUrl", true), showExtras, selectedFields, sections);
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalArgumentException("结构化布局 JSON 格式无效", error);
        }
    }

    private String advanced(TemplateVersion template, Map<String, Object> fields) {
        String source = template.html() == null ? "" : template.html();
        String css = template.css() == null ? "" : template.css();
        if (source.length() > MAX_TEMPLATE_CHARS) throw new IllegalArgumentException("高级模板 HTML 超过 100 KB 限制");
        if (css.indexOf('<') >= 0 || css.indexOf('>') >= 0) throw new IllegalArgumentException("高级模板 CSS 包含非法标签字符");
        if (css.length() > 50_000 || UNSAFE_CSS.matcher(css).find()) throw new IllegalArgumentException("高级模板 CSS 包含不安全内容");
        if (css.indexOf('\\') >= 0) throw new IllegalArgumentException("高级模板 CSS 不允许转义序列");
        validateCssLengths(css);
        for (Map.Entry<String, Object> entry : fields.entrySet()) {
            source = source.replace("{{" + entry.getKey() + "}}", escape(String.valueOf(entry.getValue())));
        }
        source = source.replaceAll("\\{\\{[^}]+}}", "");
        if (source.length() > 200_000) throw new IllegalArgumentException("字段替换后的 HTML 超过 200 KB 限制");
        Safelist safelist = Safelist.relaxed().addTags("article", "section", "footer", "header")
                .addAttributes(":all", "class", "id")
                .removeProtocols("img", "src", "http", "https")
                .addProtocols("img", "src", "data");
        String clean = Jsoup.clean(source, safelist);
        if (Jsoup.parseBodyFragment(clean).select("*").size() > 500) throw new IllegalArgumentException("高级模板 DOM 节点超过 500 个限制");
        if (!clean.contains("id=\"web-card\"")) clean = "<article id=\"web-card\">" + clean + "</article>";
        return "<!doctype html><html><head><meta charset=\"UTF-8\"><style>body{margin:0;background:transparent}" + css + "</style></head><body>" + clean + "</body></html>";
    }

    private boolean bool(Map<String, Object> values, String key, boolean fallback) {
        Object value = values.get(key);
        return value instanceof Boolean bool ? bool : value == null ? fallback : Boolean.parseBoolean(String.valueOf(value));
    }

    private List<LayoutSection> sections(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().filter(Map.class::isInstance).map(Map.class::cast).map(section -> {
            String title = String.valueOf(section.getOrDefault("title", "信息"));
            String layout = String.valueOf(section.getOrDefault("layout", "grid")).toLowerCase(Locale.ROOT);
            if (!Set.of("grid", "list", "chips", "links").contains(layout)) layout = "grid";
            Object configured = section.get("fields");
            List<String> fields = configured instanceof Collection<?> collection
                    ? collection.stream().map(String::valueOf).filter(item -> !item.isBlank()).toList() : List.of();
            return new LayoutSection(title, layout, fields);
        }).filter(section -> !section.fields().isEmpty()).toList();
    }

    private String sections(Map<String, Object> fields, List<LayoutSection> sections) {
        StringBuilder html = new StringBuilder("<div class=\"sections\">");
        LinkedHashSet<String> rendered = new LinkedHashSet<>();
        for (LayoutSection section : sections) {
            StringBuilder items = new StringBuilder();
            for (String field : section.fields()) {
                Object value = fields.get(field);
                if (value == null || rendered.contains(field)) continue;
                rendered.add(field);
                items.append("<div class=\"section-item\"><span>").append(escape(field))
                        .append("</span><strong>").append(renderValue(value, section.layout())).append("</strong></div>");
            }
            if (!items.isEmpty()) html.append("<section class=\"section section-").append(section.layout())
                    .append("\"><div class=\"section-title\">").append(escape(section.title()))
                    .append("</div><div class=\"section-grid\">").append(items).append("</div></section>");
        }
        return html.append("</div>").toString();
    }

    private String renderValue(Object value, String layout) {
        if (value instanceof Map<?, ?> map) {
            StringBuilder html = new StringBuilder("<div class=\"value-map\">");
            map.forEach((key, item) -> html.append("<div><em>").append(escape(String.valueOf(key)))
                    .append("</em><b>").append(escape(displayValue(item))).append("</b></div>"));
            return html.append("</div>").toString();
        }
        if (value instanceof Iterable<?> iterable) {
            StringBuilder html = new StringBuilder("<div class=\"value-list\">");
            for (Object item : iterable) html.append("<b>").append(escape(displayValue(item))).append("</b>");
            return html.append("</div>").toString();
        }
        return escape(String.valueOf(value));
    }

    private String displayValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Object label = map.get("label");
            if (label == null) label = map.get("title");
            if (label != null) return String.valueOf(label);
        }
        return String.valueOf(value);
    }

    private void validateCssLengths(String css) {
        Matcher matcher = CSS_LENGTH.matcher(css);
        while (matcher.find()) {
            double value = Double.parseDouble(matcher.group(1));
            String unit = matcher.group(2).toLowerCase(Locale.ROOT);
            double maximum = "px".equals(unit) ? MAX_RENDER_DIMENSION : "%".equals(unit) ? 100 : 100;
            if (value > maximum) throw new IllegalArgumentException("高级模板 CSS 尺寸超过安全限制");
        }
    }

    private String source(String url) { try { return URI.create(url).getHost(); } catch (Exception ignored) { return "网页来源"; } }
    private String value(Map<String, Object> fields, String key, String fallback) { Object value = fields.get(key); return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value); }
    private boolean safeImage(String value) { return value != null && value.startsWith("data:image/"); }
    private String escape(String value) { return org.jsoup.nodes.Entities.escape(value == null ? "" : value); }
    private String root(Throwable error) { Throwable value = error; while (value.getCause() != null) value = value.getCause(); return value.getMessage() == null ? value.getClass().getSimpleName() : value.getMessage(); }

    private record StructuredLayout(String variant, String accentColor, boolean showImage, boolean showSource,
                                    boolean showSummary, boolean showUrl, boolean showExtraFields, List<String> extraFields,
                                    List<LayoutSection> sections) {
        static StructuredLayout defaults() { return new StructuredLayout("editorial", "#39725d", true, true, true, true, true, List.of(), List.of()); }
        boolean showsExtraField(String key) { return showExtraFields && (extraFields.isEmpty() || extraFields.contains(key)); }
    }
    private record LayoutSection(String title, String layout, List<String> fields) { }
}
