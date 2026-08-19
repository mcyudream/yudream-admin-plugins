package online.yudream.plugin.aichatbot.application.service;

import online.yudream.base.plugin.spi.system.FrameworkServices;
import online.yudream.base.plugin.spi.system.ai.PluginAiToolResult;
import online.yudream.base.plugin.spi.system.messaging.PluginMessageContent;
import online.yudream.base.plugin.spi.system.storage.PluginStoredFile;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Wiki 附图通道：Agent 工作流的 wiki.search 工具节点命中页面后，提取站内配图、Markdown 原文图注
 * 与视觉模型生成 caption，供回答模型以 [[wiki-image:N]] 决定相关性和插入位置；插件只把 AI 明确选中
 * 的图片转为同一条 QQ 消息的图片分段，未选中的检索图片不会外发。
 */
public class AiChatbotWikiService {
    private static final Logger LOGGER = Logger.getLogger(AiChatbotWikiService.class.getName());
    private static final Pattern FILE_URL = Pattern.compile("/api/files/(\\d+)/content");
    private static final int MAX_IMAGE_BYTES = 10 * 1024 * 1024;

    private final FrameworkServices framework;

    public AiChatbotWikiService(FrameworkServices framework) {
        this.framework = Objects.requireNonNull(framework, "framework");
    }

    public record WikiImage(int index, String url, String alt, String generatedCaption, String caption) { }

    public record WikiRichMessage(String content, List<PluginMessageContent.Attachment> attachments, int imageCount) { }

    private static final Pattern IMAGE_MARKER = Pattern.compile("\\[\\[(?:wiki-image|image):(\\d+)\\]\\]", Pattern.CASE_INSENSITIVE);
    /**
     * 从 Agent 工具结果中收集 wiki.search 命中的站内配图（按 URL 去重，保持命中顺序，最多 limit 张）。
     * 工具结果载荷结构：{ hits: [ { images: [ { url, caption } ] } ] }。
     */
    public List<WikiImage> imagesFromToolResults(List<PluginAiToolResult> results, int limit) {
        Set<String> seen = new LinkedHashSet<>();
        List<WikiImage> images = new ArrayList<>();
        if (results == null) {
            return List.of();
        }
        for (PluginAiToolResult result : results) {
            Object hits = result.payload().get("hits");
            if (!(hits instanceof List<?> hitList)) {
                continue;
            }
            for (Object hit : hitList) {
                if (!(hit instanceof Map<?, ?> hitMap)) {
                    continue;
                }
                Object hitImages = hitMap.get("images");
                if (!(hitImages instanceof List<?> imageList)) {
                    continue;
                }
                for (Object item : imageList) {
                    if (!(item instanceof Map<?, ?> imageMap)) {
                        continue;
                    }
                    String url = imageMap.get("url") == null ? "" : imageMap.get("url").toString().trim();
                    if (url.isBlank() || fileIdOf(url) == null || !seen.add(url)) {
                        continue;
                    }
                    String alt = text(imageMap.get("alt"));
                    String generatedCaption = text(imageMap.get("generatedCaption"));
                    String caption = text(imageMap.get("caption"));
                    if (caption.isBlank()) {
                        caption = alt.isBlank() ? generatedCaption : alt;
                    }
                    int index = integer(imageMap.get("index"), images.size() + 1);
                    images.add(new WikiImage(index, url, alt, generatedCaption, caption));
                    if (images.size() >= Math.max(1, limit)) {
                        return List.copyOf(images);
                    }
                }
            }
        }
        return List.copyOf(images);
    }

    /**
     * 把 AI 排版时选择的 [[wiki-image:N]] 标记转换为同一条 QQ 消息可使用的附件分段。
     * 未被 AI 标记引用的候选图片不会发送；无效或读取失败的标记会从文本中移除。
     */
    public WikiRichMessage richMessage(String content, List<WikiImage> images) {
        String text = content == null ? "" : content;
        if (images == null || images.isEmpty()) {
            return new WikiRichMessage(removeImageMarkers(text), List.of(), 0);
        }
        Map<Integer, WikiImage> byIndex = new java.util.LinkedHashMap<>();
        for (WikiImage image : images) {
            byIndex.putIfAbsent(image.index(), image);
        }
        Matcher matcher = IMAGE_MARKER.matcher(text);
        StringBuilder normalized = new StringBuilder();
        Map<String, PluginMessageContent.Attachment> attachments = new java.util.LinkedHashMap<>();
        Map<String, WikiImage> used = new java.util.LinkedHashMap<>();
        while (matcher.find()) {
            int index = Integer.parseInt(matcher.group(1));
            WikiImage image = byIndex.get(index);
            PluginMessageContent.Attachment attachment = image == null ? null : attachment(image);
            if (attachment == null) {
                matcher.appendReplacement(normalized, "");
                continue;
            }
            String token = "wiki-image:" + index;
            attachments.putIfAbsent(token, attachment);
            used.putIfAbsent(token, image);
            matcher.appendReplacement(normalized, Matcher.quoteReplacement("[[" + token + "]]"));
        }
        matcher.appendTail(normalized);
        return new WikiRichMessage(normalized.toString(), List.copyOf(attachments.values()), used.size());
    }

    /** 写入会话历史时去掉内部占位符，避免后续模型把标记当成用户可见文本。 */
    public String plainText(WikiRichMessage message) {
        if (message == null) {
            return "";
        }
        return IMAGE_MARKER.matcher(message.content()).replaceAll("[配图]");
    }

    private PluginMessageContent.Attachment attachment(WikiImage image) {
        String fileId = fileIdOf(image.url());
        if (fileId == null) {
            return null;
        }
        try {
            Optional<PluginStoredFile> stored = framework.platformFile(fileId);
            if (stored.isEmpty()) {
                return null;
            }
            byte[] bytes = readBounded(stored.get().inputStream());
            if (bytes.length == 0) {
                return null;
            }
            String uri = "base64://" + Base64.getEncoder().encodeToString(bytes);
            String contentType = stored.get().contentType() != null && stored.get().contentType().startsWith("image/")
                    ? stored.get().contentType() : "image/png";
            return new PluginMessageContent.Attachment(uri, "wiki-image:" + image.index(), contentType);
        }
        catch (Exception error) {
            LOGGER.log(Level.WARNING, "[YuDreamAdmin] [AI Chatbot] wiki image load failed: " + image.url() + " - " + error.getMessage(), error);
            return null;
        }
    }

    private String removeImageMarkers(String text) {
        return IMAGE_MARKER.matcher(text == null ? "" : text).replaceAll("");
    }

    private int integer(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? fallback : Integer.parseInt(value.toString().trim());
        }
        catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private String text(Object value) {
        return value == null ? "" : value.toString().trim();
    }

    private String fileIdOf(String url) {
        if (url == null) {
            return null;
        }
        Matcher matcher = FILE_URL.matcher(url);
        return matcher.find() ? matcher.group(1) : null;
    }

    private byte[] readBounded(InputStream input) throws Exception {
        try (input) {
            byte[] bytes = input.readNBytes(MAX_IMAGE_BYTES + 1);
            return bytes.length > MAX_IMAGE_BYTES ? new byte[0] : bytes;
        }
    }
}
