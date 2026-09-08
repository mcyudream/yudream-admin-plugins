package online.yudream.base.plugin.eduverify.application.chsi;

import online.yudream.base.plugin.eduverify.application.chsi.ChsiVerifier.ChsiParseConfig;
import online.yudream.base.plugin.eduverify.application.chsi.ChsiVerifier.ChsiVerifyResult;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** 解析学信网《学籍在线验证报告》/《学历证书电子注册备案表》公开页 HTML。 */
public final class ChsiReportParser {

    public static final String DEFAULT_URL_TEMPLATE = "https://www.chsi.com.cn/xlcx/bg.do?vcode={code}&srcid=bgcx";
    public static final String FIELD_REAL_NAME = "realName";
    public static final String FIELD_SCHOOL_NAME = "schoolName";
    public static final String FIELD_STUDENT_STATUS = "studentStatus";
    public static final String FIELD_VCODE = "vcode";

    private static final Pattern ERROR = Pattern.compile("验证码有误|验证码无效|不存在|已过期|超过有效期|访问受限|频繁|412|报告不存在|无法显示");
    private static final Pattern TITLE_OK = Pattern.compile("教育部学籍在线验证报告|学历证书电子注册备案表");

    private ChsiReportParser() {
    }

    public static Map<String, String> defaultSelectors() {
        Map<String, String> selectors = new LinkedHashMap<>();
        selectors.put(FIELD_REAL_NAME, "td:matchesOwn(^\\s*姓名\\s*$) + td");
        selectors.put(FIELD_SCHOOL_NAME, "td:matchesOwn(^\\s*学校名称\\s*$) + td");
        selectors.put(FIELD_STUDENT_STATUS, "td:matchesOwn(^\\s*学籍状态\\s*$) + td");
        selectors.put(FIELD_VCODE, "td:matchesOwn(^\\s*在线验证码\\s*$) + td");
        return Map.copyOf(selectors);
    }

    public static ChsiVerifyResult parse(String html, String expectedVcode, ChsiParseConfig config) {
        if (html == null || html.isBlank()) {
            return ChsiVerifyResult.failed("学信网报告页为空");
        }
        String compact = html.replace('\u00a0', ' ');
        if (ERROR.matcher(compact).find() && !TITLE_OK.matcher(compact).find()) {
            return ChsiVerifyResult.failed("学信网报告无效或已过期，请核对在线验证码");
        }
        Document document = Jsoup.parse(compact);
        String title = firstNonBlank(document.title(), text(document.selectFirst("h1, h2, .reportTit, .report-title")));
        if (title != null && ERROR.matcher(title).find()) {
            return ChsiVerifyResult.failed("学信网报告无效或已过期，请核对在线验证码");
        }
        Map<String, String> selectors = config == null ? defaultSelectors() : config.selectors();
        String realName = extract(document, selectors.get(FIELD_REAL_NAME), "姓名");
        String schoolName = extract(document, selectors.get(FIELD_SCHOOL_NAME), "学校名称");
        String studentStatus = extract(document, selectors.get(FIELD_STUDENT_STATUS), "学籍状态");
        String pageVcode = extract(document, selectors.get(FIELD_VCODE), "在线验证码");
        if (isBlank(realName) || isBlank(schoolName)) {
            return ChsiVerifyResult.failed("未能从学信网报告页解析出姓名或学校，请改走人工审核或调整选择器");
        }
        if (!isBlank(expectedVcode) && !isBlank(pageVcode)
                && !expectedVcode.trim().equalsIgnoreCase(pageVcode.replace(" ", ""))) {
            return ChsiVerifyResult.failed("报告页在线验证码与提交的验证码不一致");
        }
        if (!TITLE_OK.matcher(compact).find() && isBlank(studentStatus) && isBlank(pageVcode)) {
            return ChsiVerifyResult.failed("页面不是有效的学信网在线验证报告");
        }
        String detail = "realName=" + realName
                + "; schoolName=" + schoolName
                + (isBlank(studentStatus) ? "" : "; studentStatus=" + studentStatus)
                + (isBlank(pageVcode) ? "" : "; vcode=" + pageVcode);
        return ChsiVerifyResult.passed(realName, schoolName, detail);
    }

    private static String extract(Document document, String selector, String label) {
        if (selector != null && !selector.isBlank()) {
            try {
                Element matched = document.selectFirst(selector.trim());
                String text = text(matched);
                if (!isBlank(text) && !text.equals(label)) {
                    return text;
                }
            } catch (RuntimeException ignored) {
                // 管理员配置的选择器非法时回退到标签配对。
            }
        }
        return siblingAfterLabel(document, label);
    }

    private static String siblingAfterLabel(Document document, String label) {
        Elements candidates = document.select("td, th, div, span, li, dt, p");
        for (int index = 0; index < candidates.size(); index++) {
            String current = normalized(candidates.get(index).ownText());
            if (!label.equals(current)) {
                continue;
            }
            Element node = candidates.get(index);
            Element next = node.nextElementSibling();
            String value = text(next);
            if (isBlank(value) && index + 1 < candidates.size()) {
                value = text(candidates.get(index + 1));
            }
            if (!isBlank(value) && !label.equals(normalized(value))) {
                return value;
            }
        }
        return null;
    }

    private static String text(Element element) {
        if (element == null) {
            return null;
        }
        String value = element.text();
        return value == null || value.isBlank() ? null : value.replace('\u00a0', ' ').trim();
    }

    private static String normalized(String value) {
        return value == null ? "" : value.replace('\u00a0', ' ').trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    public static String buildUrl(String template, String vcode) {
        String pattern = template == null || template.isBlank() ? DEFAULT_URL_TEMPLATE : template.trim();
        return pattern.replace("{code}", vcode == null ? "" : vcode.trim().toUpperCase(Locale.ROOT));
    }
}
