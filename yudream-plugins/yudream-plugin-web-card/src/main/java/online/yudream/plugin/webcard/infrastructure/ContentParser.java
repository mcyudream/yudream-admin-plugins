package online.yudream.plugin.webcard.infrastructure;

import com.jayway.jsonpath.JsonPath;
import online.yudream.plugin.webcard.domain.WebCardModels.FieldRule;
import online.yudream.plugin.webcard.domain.WebCardModels.ParseRules;
import online.yudream.plugin.webcard.domain.WebCardModels.SourceType;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ContentParser {
    public Map<String, Object> detail(ParseRules rules, SecureWebFetcher.Fetched fetched) {
        return switch (rules.detailType()) {
            case HTML -> html(rules, fetched.text(), fetched.finalUri());
            case JSON -> json(rules, fetched.text());
            default -> throw new IllegalArgumentException("详情解析仅支持 HTML 或 JSON");
        };
    }

    public List<String> discover(SourceType type, ParseRules rules, SecureWebFetcher.Fetched fetched) {
        return switch (type) {
            case HTML -> htmlLinks(rules, fetched.text(), fetched.finalUri());
            case JSON -> jsonLinks(rules, fetched.text(), fetched.finalUri());
            case RSS -> xmlLinks(fetched.body(), "item/link", "entry/link", fetched.finalUri());
            case SITEMAP -> xmlLinks(fetched.body(), "url/loc", null, fetched.finalUri());
        };
    }

    public Map<String, Object> inspect(SecureWebFetcher.Fetched fetched) {
        if (fetched == null || fetched.contentType() == null
                || !fetched.contentType().toLowerCase(Locale.ROOT).startsWith("text/html")) {
            return Map.of("type", fetched == null ? "unknown" : fetched.contentType());
        }
        Document document = Jsoup.parse(fetched.text(), fetched.finalUri().toString());
        List<Map<String, Object>> candidates = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (Element container : document.select("ul,ol,dl,table")) {
            Map<String, List<Element>> groups = new LinkedHashMap<>();
            for (Element child : container.children()) groups.computeIfAbsent(elementSignature(child), ignored -> new ArrayList<>()).add(child);
            for (Map.Entry<String, List<Element>> group : groups.entrySet()) {
                if (group.getValue().size() < 2 || candidates.size() >= 16) continue;
                String expression = selector(container) + " > " + group.getKey();
                if (!seen.add(expression)) continue;
                List<String> samples = group.getValue().stream().map(Element::text).map(String::trim)
                        .filter(value -> !value.isBlank()).limit(4).toList();
                long keyed = samples.stream().filter(value -> separator(value) > 0).count();
                candidates.add(Map.of(
                        "expression", expression,
                        "recommendedType", keyed >= Math.max(1, samples.size() / 2) ? "KEY_VALUE_LIST" : "TEXT_LIST",
                        "count", group.getValue().size(),
                        "samples", samples
                ));
            }
            List<Element> links = container.select("a[href]");
            if (links.size() >= 2 && candidates.size() < 16) {
                String expression = selector(container) + " a[href]";
                if (seen.add(expression)) candidates.add(Map.of(
                        "expression", expression,
                        "recommendedType", "LINK_LIST",
                        "count", links.size(),
                        "samples", links.stream().map(Element::text).map(String::trim)
                                .filter(value -> !value.isBlank()).limit(4).toList()
                ));
            }
        }
        List<Map<String, Object>> images = document.select("img[src]").stream().limit(12).map(image -> Map.<String, Object>of(
                "expression", selector(image),
                "url", image.absUrl("src").isBlank() ? fetched.finalUri().resolve(image.attr("src")).toString() : image.absUrl("src"),
                "alt", image.attr("alt")
        )).toList();
        List<String> assetHosts = images.stream().map(value -> String.valueOf(value.get("url"))).map(value -> {
            try { return URI.create(value).getHost(); } catch (Exception ignored) { return null; }
        }).filter(value -> value != null && !value.isBlank()).distinct().toList();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("title", document.title());
        metadata.put("description", first(meta(document, "meta[name=description]", "content"), meta(document, "meta[property=og:description]", "content")));
        metadata.put("image", image(document, fetched.finalUri()));
        return Map.of(
                "url", fetched.finalUri().toString(),
                "metadata", metadata,
                "candidates", candidates,
                "images", images,
                "assetHosts", assetHosts
        );
    }

    private Map<String, Object> html(ParseRules rules, String body, URI base) {
        Document document = Jsoup.parse(body, base.toString());
        Map<String, Object> result = new LinkedHashMap<>();
        for (FieldRule rule : rules.fields()) {
            Object value;
            try { value = htmlValue(document, rule, base); }
            catch (Exception e) { throw new IllegalArgumentException("CSS Selector 或字段类型无效：" + rule.name(), e); }
            if (empty(value)) value = semanticFallback(document, rule.name(), base);
            put(result, rule, value);
        }
        putMetaFallback(result, "title", document.title());
        putMetaFallback(result, "summary", meta(document, "meta[name=description]", "content"));
        String image = image(document, base);
        if (image != null && !image.isBlank()) result.putIfAbsent("image", base.resolve(image).toString());
        putMetaFallback(result, "source", base.getHost());
        result.putIfAbsent("url", base.toString());
        return result;
    }
    private void putMetaFallback(Map<String, Object> result, String key, String value) { if (value != null && !value.isBlank()) result.putIfAbsent(key, value.trim()); }
    private String meta(Document document, String selector, String attribute) { Element element = document.selectFirst(selector); return element == null ? null : element.attr(attribute); }
    private Object semanticFallback(Document document, String name, URI base) {
        if ("title".equalsIgnoreCase(name)) return document.title();
        if ("summary".equalsIgnoreCase(name) || "description".equalsIgnoreCase(name)) return meta(document, "meta[name=description]", "content");
        if ("image".equalsIgnoreCase(name) || "cover".equalsIgnoreCase(name) || "logo".equalsIgnoreCase(name)) return image(document, base);
        return null;
    }
    private String image(Document document, URI base) {
        String value = meta(document, "meta[property=og:image]", "content");
        if (value == null || value.isBlank()) value = meta(document, "meta[name=twitter:image]", "content");
        if (value == null || value.isBlank()) value = meta(document, "link[rel=image_src]", "href");
        if (value == null || value.isBlank()) {
            Element candidate = document.selectFirst("article img[src], main img[src], img[src]");
            value = candidate == null ? null : candidate.attr("src");
        }
        return value == null || value.isBlank() ? null : base.resolve(value).toString();
    }
    private Object htmlValue(Element element, String attribute) {
        if (attribute == null || attribute.isBlank() || "text".equalsIgnoreCase(attribute)) return element.text();
        if ("html".equalsIgnoreCase(attribute)) return element.html();
        return element.hasAttr(attribute) ? element.absUrl(attribute).isBlank() ? element.attr(attribute) : element.absUrl(attribute) : null;
    }
    private Object htmlValue(Document document, FieldRule rule, URI base) {
        List<Element> elements = document.select(rule.expression());
        if (elements.isEmpty()) return null;
        String type = rule.type() == null ? "TEXT" : rule.type().trim().toUpperCase(Locale.ROOT);
        return switch (type) {
            case "TEXT_LIST", "LIST" -> elements.stream()
                    .map(element -> htmlValue(element, rule.attribute()))
                    .filter(value -> value != null && !String.valueOf(value).isBlank())
                    .map(String::valueOf).map(String::trim).distinct().toList();
            case "KEY_VALUE_LIST", "KEY_VALUES" -> keyValues(elements);
            case "LINK_LIST", "LINKS" -> links(elements, base);
            case "TABLE" -> table(elements);
            default -> htmlValue(elements.getFirst(), rule.attribute());
        };
    }
    private Map<String, Object> keyValues(List<Element> elements) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (Element element : elements) {
            String text = element.text().trim();
            int separator = separator(text);
            if (separator <= 0) continue;
            String key = text.substring(0, separator).trim();
            String value = text.substring(separator + 1).trim();
            if (!key.isBlank() && !value.isBlank()) values.put(key, value);
        }
        return values;
    }
    private List<Map<String, String>> links(List<Element> elements, URI base) {
        List<Map<String, String>> values = new ArrayList<>();
        for (Element source : elements) {
            Element link = "a".equals(source.tagName()) ? source : source.selectFirst("a[href]");
            if (link == null) continue;
            String url = link.absUrl("href");
            if (url.isBlank()) url = base.resolve(link.attr("href")).toString();
            String label = link.text().trim();
            if (label.isBlank()) label = first(link.attr("title"), link.attr("data-original-title"), source.text());
            if (url.isBlank() || label.isBlank()) continue;
            Map<String, String> value = new LinkedHashMap<>();
            value.put("label", label);
            value.put("url", url);
            String title = first(link.attr("data-original-title"), link.attr("title"));
            if (!title.isBlank()) value.put("title", title);
            values.add(Map.copyOf(value));
        }
        return values.stream().distinct().toList();
    }
    private Map<String, Object> table(List<Element> selected) {
        List<Element> rows = selected.size() == 1 && "table".equals(selected.getFirst().tagName())
                ? selected.getFirst().select("tr") : selected;
        Map<String, Object> values = new LinkedHashMap<>();
        for (Element row : rows) {
            List<Element> cells = row.children().stream()
                    .filter(cell -> "th".equals(cell.tagName()) || "td".equals(cell.tagName())).toList();
            if (cells.size() < 2) continue;
            String key = cells.getFirst().text().trim();
            String value = cells.subList(1, cells.size()).stream().map(Element::text)
                    .map(String::trim).filter(text -> !text.isBlank()).reduce((left, right) -> left + " · " + right).orElse("");
            if (!key.isBlank() && !value.isBlank()) values.put(key, value);
        }
        return values;
    }
    private int separator(String value) {
        int ascii = value.indexOf(':');
        int chinese = value.indexOf('：');
        if (ascii < 0) return chinese;
        if (chinese < 0) return ascii;
        return Math.min(ascii, chinese);
    }
    private String first(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value.trim();
        return "";
    }
    private boolean empty(Object value) {
        return value == null || value instanceof String text && text.isBlank()
                || value instanceof Collection<?> collection && collection.isEmpty()
                || value instanceof Map<?, ?> map && map.isEmpty();
    }
    private String selector(Element element) {
        String id = element.id();
        if (!id.isBlank()) return element.tagName() + "#" + cssName(id);
        List<String> classes = element.classNames().stream().filter(value -> !value.isBlank()).limit(3).toList();
        return element.tagName() + classes.stream().map(value -> "." + cssName(value)).reduce("", String::concat);
    }
    private String elementSignature(Element element) {
        List<String> classes = element.classNames().stream().filter(value -> !value.isBlank()).limit(3).toList();
        return element.tagName() + classes.stream().map(value -> "." + cssName(value)).reduce("", String::concat);
    }
    private String cssName(String value) {
        return value.replaceAll("[^a-zA-Z0-9_-]", "");
    }
    private Map<String, Object> json(ParseRules rules, String body) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (FieldRule rule : rules.fields()) {
            Object value = null; try { value = JsonPath.read(body, rule.expression()); } catch (Exception ignored) { }
            put(result, rule, value);
        }
        return result;
    }
    private void put(Map<String, Object> result, FieldRule rule, Object value) {
        if (empty(value)) { if (rule.required()) throw new IllegalArgumentException("缺少必填字段：" + rule.name()); return; }
        result.put(rule.name(), value);
    }
    private List<String> htmlLinks(ParseRules rules, String body, URI base) {
        if (rules.listExpression() == null || rules.listExpression().isBlank()) throw new IllegalArgumentException("列表 CSS Selector 不能为空");
        String attr = rules.listLinkAttribute() == null || rules.listLinkAttribute().isBlank() ? "href" : rules.listLinkAttribute();
        return Jsoup.parse(body, base.toString()).select(rules.listExpression()).stream().map(element -> element.absUrl(attr)).filter(value -> !value.isBlank()).distinct().toList();
    }
    private List<String> jsonLinks(ParseRules rules, String body, URI base) {
        if (rules.jsonItemsPath() == null || rules.jsonItemsPath().isBlank()) throw new IllegalArgumentException("JSON 列表路径不能为空");
        Object value = JsonPath.read(body, rules.jsonItemsPath());
        if (!(value instanceof List<?> list)) throw new IllegalArgumentException("JSON 列表路径未返回数组");
        return list.stream().map(String::valueOf).map(base::resolve).map(URI::toString).distinct().toList();
    }
    private List<String> xmlLinks(byte[] body, String primaryPath, String secondaryPath, URI base) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, ""); factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            org.w3c.dom.Document xml = factory.newDocumentBuilder().parse(new ByteArrayInputStream(body));
            List<String> values = nodes(xml, primaryPath, base);
            if (values.isEmpty() && secondaryPath != null) values = nodes(xml, secondaryPath, base);
            return values;
        } catch (Exception e) { throw new IllegalArgumentException("XML 解析失败", e); }
    }
    private List<String> nodes(org.w3c.dom.Document xml, String path, URI base) {
        String tag = path.substring(path.lastIndexOf('/') + 1);
        org.w3c.dom.NodeList nodes = xml.getElementsByTagName(tag);
        List<String> values = new ArrayList<>();
        for (int i = 0; i < nodes.getLength(); i++) {
            org.w3c.dom.Node node = nodes.item(i); String value = node.getTextContent();
            if ((value == null || value.isBlank()) && node.getAttributes() != null && node.getAttributes().getNamedItem("href") != null) value = node.getAttributes().getNamedItem("href").getNodeValue();
            if (value != null && !value.isBlank()) values.add(base.resolve(value.trim()).toString());
        }
        return values.stream().distinct().toList();
    }
}
