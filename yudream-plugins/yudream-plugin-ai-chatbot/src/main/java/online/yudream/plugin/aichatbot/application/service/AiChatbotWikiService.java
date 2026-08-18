package online.yudream.plugin.aichatbot.application.service;

import online.yudream.base.plugin.spi.system.FrameworkServices;
import online.yudream.base.plugin.spi.system.ai.PluginAiToolResult;
import online.yudream.base.plugin.spi.system.messaging.PluginMessageContent;
import online.yudream.base.plugin.spi.system.messaging.PluginMessageRequest;
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
 * Wiki 附图通道：Agent 工作流的 wiki.search 工具节点命中页面后，从工具结果中提取站内配图
 * （/api/files/{id}/content），经平台文件存储读出并以 QQ 图片消息发回群里，实现图文并茂的教程回复。
 * 检索与意图判定全部由宿主 Agent 应用的工作流编排承担，本类不再直接发起检索。
 */
public class AiChatbotWikiService {
    private static final Logger LOGGER = Logger.getLogger(AiChatbotWikiService.class.getName());
    private static final Pattern FILE_URL = Pattern.compile("/api/files/(\\d+)/content");
    private static final int MAX_IMAGE_BYTES = 10 * 1024 * 1024;

    private final FrameworkServices framework;

    public AiChatbotWikiService(FrameworkServices framework) {
        this.framework = Objects.requireNonNull(framework, "framework");
    }

    public record WikiImage(String url, String caption) { }

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
                    String caption = imageMap.get("caption") == null ? "" : imageMap.get("caption").toString().trim();
                    images.add(new WikiImage(url, caption));
                    if (images.size() >= Math.max(1, limit)) {
                        return List.copyOf(images);
                    }
                }
            }
        }
        return List.copyOf(images);
    }

    /**
     * 把站内图片读出来并以 QQ 图片消息发到群里；返回成功发送的张数。
     * 图片经 /api/files/{id}/content 地址解析出文件 ID，走平台文件存储读取，无需外网可达。
     */
    public int sendImages(String connectionId, String platform, String selfId, String channelId, String messageId,
                          List<WikiImage> images) {
        int sent = 0;
        for (WikiImage image : images) {
            String fileId = fileIdOf(image.url());
            if (fileId == null) {
                continue;
            }
            try {
                Optional<PluginStoredFile> stored = framework.platformFile(fileId);
                if (stored.isEmpty()) {
                    continue;
                }
                byte[] bytes = readBounded(stored.get().inputStream());
                if (bytes.length == 0) {
                    continue;
                }
                String uri = "base64://" + Base64.getEncoder().encodeToString(bytes);
                Map<String, Object> referrer = messageId == null || messageId.isBlank() ? Map.of() : Map.of("message_id", messageId);
                framework.messaging().send(new PluginMessageRequest(connectionId, platform, selfId, channelId,
                        new PluginMessageContent(PluginMessageContent.Type.IMAGE, uri, null, referrer)));
                sent++;
            }
            catch (Exception error) {
                LOGGER.log(Level.WARNING, "[YuDreamAdmin] [AI Chatbot] wiki image send failed: " + image.url() + " - " + error.getMessage(), error);
            }
        }
        return sent;
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
