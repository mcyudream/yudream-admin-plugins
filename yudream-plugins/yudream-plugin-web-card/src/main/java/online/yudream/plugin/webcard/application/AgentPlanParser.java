package online.yudream.plugin.webcard.application;

import com.fasterxml.jackson.core.type.TypeReference;
import online.yudream.plugin.webcard.interfaces.JsonSupport;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

final class AgentPlanParser {
    private static final Pattern SCHEDULE = Pattern.compile("(?i)(定时|定期|周期|每隔|RSS|SITEMAP|监控更新|自动(?:采集|抓取|爬取))");
    private static final Pattern NEGATED_SCHEDULE = Pattern.compile("(?i)((不需要|不要|无需|关闭|禁用|别|不).{0,12}(定时|定期|周期|RSS|SITEMAP|监控更新|自动采集|自动抓取)|(定时|定期|周期|RSS|SITEMAP|监控更新|自动采集|自动抓取).{0,8}(不需要|不要|无需|关闭|禁用))");
    private static final Pattern BINDING = Pattern.compile("(?i)((投递|推送|发送).{0,16}(群|频道|连接)|(群|频道|连接).{0,16}(投递|推送|发送))");
    private static final Pattern NEGATED_BINDING = Pattern.compile("(?i)((不需要|不要|无需|禁止|关闭|别|不).{0,12}(投递|推送|发送)|(投递|推送|发送).{0,8}(不需要|不要|无需|禁止|关闭))");
    record Result(Optional<Map<String, Object>> plan, Optional<String> warning) { }

    private AgentPlanParser() { }

    static Result tryParse(String content, boolean crawlIntent) {
        return tryParse(content, crawlIntent, false);
    }

    static Result tryParse(String content, boolean crawlIntent, boolean bindingIntent) {
        try {
            return new Result(Optional.of(parse(content, crawlIntent, bindingIntent)), Optional.empty());
        } catch (RuntimeException error) {
            return new Result(Optional.empty(), Optional.of("Agent 未返回有效的结构化提案，本次回复已保留，请继续说明需要调整的内容。"));
        }
    }

    static Map<String, Object> parse(String content, boolean crawlIntent) {
        return parse(content, crawlIntent, false);
    }

    static Map<String, Object> parse(String content, boolean crawlIntent, boolean bindingIntent) {
        String json = extractObject(content);
        try {
            Map<String, Object> parsed = JsonSupport.MAPPER.readValue(json, new TypeReference<>() { });
            Map<String, Object> plan = new LinkedHashMap<>(parsed);
            validate(plan);
            if (!crawlIntent) plan.remove("job");
            if (!bindingIntent) plan.put("binding", null);
            plan.put("publish", false);
            return plan;
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalArgumentException("Agent 提案不是有效 JSON", error);
        }
    }

    static Map<String, Object> normalize(Map<String, Object> input, boolean crawlIntent) {
        return normalize(input, crawlIntent, false);
    }

    static Map<String, Object> normalize(Map<String, Object> input, boolean crawlIntent, boolean bindingIntent) {
        if (input == null) throw new IllegalArgumentException("提案不能为空");
        Map<String, Object> plan = new LinkedHashMap<>(input);
        validate(plan);
        if (!crawlIntent) plan.remove("job");
        if (!bindingIntent) plan.put("binding", null);
        plan.put("publish", false);
        return plan;
    }

    static boolean hasExplicitCrawlIntent(String message) {
        return message != null && SCHEDULE.matcher(message).find() && !NEGATED_SCHEDULE.matcher(message).find();
    }

    static boolean hasExplicitBindingIntent(String message) {
        return message != null && BINDING.matcher(message).find() && !NEGATED_BINDING.matcher(message).find();
    }

    private static void validate(Map<String, Object> plan) {
        if (!(plan.get("site") instanceof Map<?, ?> site)
                || !(plan.get("rules") instanceof Map<?, ?>)
                || !(plan.get("template") instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("Agent 方案缺少站点、解析或模板定义");
        }
        Object hosts = site.get("hosts");
        if (!(hosts instanceof java.util.List<?> values) || values.isEmpty()
                || values.stream().anyMatch(value -> value == null || String.valueOf(value).isBlank())) {
            throw new IllegalArgumentException("Agent 方案缺少有效域名");
        }
        try {
            if (site.get("accessMode") != null) online.yudream.plugin.webcard.domain.WebCardModels.AccessMode.valueOf(String.valueOf(site.get("accessMode")));
            if (site.get("responseType") != null) online.yudream.plugin.webcard.domain.WebCardModels.SourceType.valueOf(String.valueOf(site.get("responseType")));
            if (plan.get("template") instanceof Map<?, ?> template && template.get("mode") != null) online.yudream.plugin.webcard.domain.WebCardModels.TemplateMode.valueOf(String.valueOf(template.get("mode")));
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("Agent 方案枚举值无效", error);
        }
        Object job = plan.get("job");
        if (job != null && !(job instanceof Map<?, ?>)) throw new IllegalArgumentException("采集任务格式无效");
    }

    private static String extractObject(String content) {
        if (content == null || content.isBlank()) throw new IllegalArgumentException("Agent 提案为空");
        int start = content.indexOf('{');
        if (start < 0) throw new IllegalArgumentException("Agent 提案缺少 JSON 对象");
        int depth = 0;
        boolean quoted = false;
        boolean escaped = false;
        for (int i = start; i < content.length(); i++) {
            char current = content.charAt(i);
            if (quoted) {
                if (escaped) escaped = false;
                else if (current == '\\') escaped = true;
                else if (current == '"') quoted = false;
                continue;
            }
            if (current == '"') quoted = true;
            else if (current == '{') depth++;
            else if (current == '}' && --depth == 0) return content.substring(start, i + 1);
        }
        throw new IllegalArgumentException("Agent 提案 JSON 不完整");
    }
}
