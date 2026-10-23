package online.yudream.base.plugin.cas.infrastructure.cas;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * CAS3 /p3/serviceValidate XML 解析。关闭外部实体与 DTD，避免 XXE。
 * 除登录身份字段外，还会把 attributes 节点下的全部子元素收进属性表，
 * 供学生信息映射使用（字段名由学校 CAS 端决定，全量保留便于核对）。
 */
public final class CasServiceResponseParser {

    private CasServiceResponseParser() {
    }

    public static CasIdentity parse(String xml) {
        if (xml == null || xml.isBlank()) {
            throw new IllegalStateException("CAS 校验响应为空");
        }
        Document document = parseDocument(xml);
        Element root = document.getDocumentElement();
        if (root == null) {
            throw new IllegalStateException("CAS 校验响应缺少根节点");
        }
        Element failure = firstChild(root, "authenticationFailure");
        if (failure != null) {
            String code = failure.getAttribute("code");
            String text = textContent(failure);
            throw new IllegalStateException("CAS 票据校验失败" + (code.isBlank() ? "" : "（" + code + "）") + (text.isBlank() ? "" : "：" + text));
        }
        Element success = firstChild(root, "authenticationSuccess");
        if (success == null) {
            throw new IllegalStateException("CAS 校验响应既无成功也无失败节点");
        }
        String user = textContent(firstChild(success, "user"));
        if (user.isBlank()) {
            throw new IllegalStateException("CAS 校验成功但未返回用户标识");
        }
        Element attributes = firstChild(success, "attributes");
        Map<String, String> attributeMap = allAttributes(attributes);
        String displayName = firstNonBlank(
                attributeMap.get("displayName"),
                attributeMap.get("cn"),
                attributeMap.get("name"),
                user
        );
        String avatar = firstNonBlank(attributeMap.get("avatar"), attributeMap.get("picture"));
        return new CasIdentity(user.trim(), displayName, avatar, attributeMap);
    }

    /** 收集 attributes 节点下全部叶子元素的文本值；同名重复时保留首个非空值。 */
    private static Map<String, String> allAttributes(Element attributes) {
        Map<String, String> values = new LinkedHashMap<>();
        if (attributes == null) {
            return values;
        }
        NodeList children = attributes.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (!(node instanceof Element element)) {
                continue;
            }
            String key = localName(element);
            String value = textContent(element);
            if (key.isBlank() || value.isBlank()) {
                continue;
            }
            values.putIfAbsent(key, value);
        }
        return values;
    }

    private static Document parseDocument(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setExpandEntityReferences(false);
            factory.setXIncludeAware(false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("CAS 校验响应解析失败：" + e.getMessage(), e);
        }
    }

    private static Element firstChild(Element parent, String localName) {
        if (parent == null) {
            return null;
        }
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node instanceof Element element && localName.equals(localName(element))) {
                return element;
            }
        }
        return null;
    }

    private static String localName(Element element) {
        String local = element.getLocalName();
        if (local != null && !local.isBlank()) {
            return local;
        }
        String tag = element.getTagName();
        int colon = tag.indexOf(':');
        return colon < 0 ? tag : tag.substring(colon + 1);
    }

    private static String textContent(Element element) {
        return element == null || element.getTextContent() == null ? "" : element.getTextContent().trim();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    public record CasIdentity(String user, String displayName, String avatarUrl, Map<String, String> attributes) {
    }
}
