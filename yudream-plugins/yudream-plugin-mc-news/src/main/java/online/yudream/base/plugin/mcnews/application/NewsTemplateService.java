package online.yudream.base.plugin.mcnews.application;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import online.yudream.base.plugin.mcnews.domain.NewsArticle;

/**
 * 推送消息模板渲染：{{name}} 占位符替换，未提供变量替换为空串；
 * 渲染后压缩 3 个以上连续换行，保持消息整洁。变量清单向前端暴露供模板编辑。
 */
public final class NewsTemplateService {
    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    public static final List<Variable> VARIABLES = List.of(
            new Variable("title", "新闻标题"),
            new Variable("summary", "官方摘要 / 副标题（可能为空）"),
            new Variable("aiSummary", "AI 整合的推送正文：一句话概括 + 具体要点（修复项、变更点等；AI 关闭或失败时为空）"),
            new Variable("body", "推送正文：AI 整合优先，自动回退官方摘要"),
            new Variable("sourceName", "来源名称"),
            new Variable("sourceId", "来源标识"),
            new Variable("category", "分类（News / Updates / Beta 等，可能为空）"),
            new Variable("url", "文章链接"),
            new Variable("imageUrl", "封面图地址（可能为空）"),
            new Variable("publishedAt", "发布时间（官网新闻为发现时间）"),
            new Variable("discoveredAt", "插件发现时间")
    );

    public record Variable(String name, String description) {
    }

    /** 按模板渲染一条新闻的推送文本。 */
    public String render(String template, NewsArticle article) {
        String content = replaceVars(template, variablesOf(article));
        return content.replaceAll("\n{3,}", "\n\n").trim();
    }

    public Map<String, String> variablesOf(NewsArticle article) {
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("title", nullToEmpty(article.title()));
        vars.put("summary", nullToEmpty(article.summary()));
        vars.put("aiSummary", nullToEmpty(article.aiSummary()));
        vars.put("body", nullToEmpty(article.body()));
        vars.put("sourceName", nullToEmpty(article.sourceName()));
        vars.put("sourceId", nullToEmpty(article.sourceId()));
        vars.put("category", nullToEmpty(article.category()));
        vars.put("url", nullToEmpty(article.url()));
        vars.put("imageUrl", nullToEmpty(article.imageUrl()));
        vars.put("publishedAt", formatDate(article.publishedAt()));
        vars.put("discoveredAt", formatDate(article.discoveredAt()));
        return vars;
    }

    /** 用示例数据渲染预览。 */
    public String preview(String template) {
        NewsArticle sample = new NewsArticle("preview", "mcnet-news", "Minecraft 官网新闻",
                "Minecraft Java 版 1.21.9 正式发布",
                "A Minecraft Java Release",
                "Java 版 1.21.9 现已推出，本次更新包含新的生物群系与区块加载优化，建议服务器管理员关注兼容性说明。",
                "News", "https://www.minecraft.net/zh-hans/article/minecraft-java-1-21-9",
                "https://www.minecraft.net/content/dam/minecraftnet/article-asset/example/tile.jpg",
                System.currentTimeMillis() - 3_600_000L, System.currentTimeMillis(), 0, NewsArticle.STATE_PENDING);
        return render(template, sample);
    }

    static String replaceVars(String template, Map<String, String> vars) {
        StringBuilder result = new StringBuilder();
        int index = 0;
        String source = template == null ? "" : template;
        while (index < source.length()) {
            int start = source.indexOf("{{", index);
            if (start < 0) {
                result.append(source, index, source.length());
                break;
            }
            result.append(source, index, start);
            int end = source.indexOf("}}", start + 2);
            if (end < 0) {
                result.append(source, start, source.length());
                break;
            }
            String name = source.substring(start + 2, end).trim();
            result.append(vars.getOrDefault(name, ""));
            index = end + 2;
        }
        return result.toString();
    }

    private String formatDate(long epochMillis) {
        return epochMillis <= 0 ? "" : DATE_TIME.format(Instant.ofEpochMilli(epochMillis));
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
