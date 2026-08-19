package online.yudream.plugin.aichatbot.application.service;

import online.yudream.base.plugin.spi.system.FrameworkServices;
import online.yudream.base.plugin.spi.system.ai.PluginAiToolResult;
import online.yudream.base.plugin.spi.system.storage.PluginStoredFile;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiChatbotWikiServiceTest {

    @Test
    void extractsMarkdownAltAndGeneratedCaptionFromToolResults() {
        AiChatbotWikiService service = new AiChatbotWikiService(framework(Optional.empty()));
        PluginAiToolResult result = new PluginAiToolResult("wiki.search", "ok", Map.of(
                "hits", List.of(Map.of("images", List.of(Map.of(
                        "url", "/api/files/123/content",
                        "index", 1,
                        "alt", "设置页截图",
                        "generatedCaption", "设置页右上角的保存按钮",
                        "caption", "设置页截图"))))));

        var images = service.imagesFromToolResults(List.of(result), 3);

        assertEquals(1, images.size());
        assertEquals(1, images.getFirst().index());
        assertEquals("设置页截图", images.getFirst().alt());
        assertEquals("设置页右上角的保存按钮", images.getFirst().generatedCaption());
    }

    @Test
    void richMessageOnlyIncludesImagesChosenByAiMarkers() {
        FrameworkServices framework = framework(Optional.of(new PluginStoredFile(
                "image.png", "image/png", 4L, new ByteArrayInputStream(new byte[]{1, 2, 3, 4}))));
        AiChatbotWikiService service = new AiChatbotWikiService(framework);
        List<AiChatbotWikiService.WikiImage> images = List.of(
                new AiChatbotWikiService.WikiImage(1, "/api/files/123/content", "设置页截图", "保存按钮", "设置页截图"),
                new AiChatbotWikiService.WikiImage(2, "/api/files/456/content", "无关截图", "无关内容", "无关截图"));

        var message = service.richMessage("第一步。\n[[wiki-image:1]]\n图注：点击保存。\n第二步。", images);

        assertEquals(1, message.imageCount());
        assertEquals(1, message.attachments().size());
        assertEquals("wiki-image:1", message.attachments().getFirst().title());
        assertTrue(message.attachments().getFirst().url().startsWith("base64://"));
        assertTrue(message.content().contains("[[wiki-image:1]]"));
        assertEquals("第一步。\n[配图]\n图注：点击保存。\n第二步。", service.plainText(message));
    }

    @Test
    void richMessageWithoutAiMarkerDoesNotSendSearchedImages() {
        AiChatbotWikiService service = new AiChatbotWikiService(framework(Optional.empty()));
        var message = service.richMessage("这是一段纯文本回答。", List.of(
                new AiChatbotWikiService.WikiImage(1, "/api/files/123/content", "截图", "描述", "截图")));

        assertEquals(0, message.imageCount());
        assertTrue(message.attachments().isEmpty());
        assertEquals("这是一段纯文本回答。", message.content());
    }

    private FrameworkServices framework(Optional<PluginStoredFile> file) {
        return (FrameworkServices) Proxy.newProxyInstance(
                FrameworkServices.class.getClassLoader(),
                new Class<?>[]{FrameworkServices.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "platformFile" -> file;
                    case "setting" -> Optional.empty();
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
