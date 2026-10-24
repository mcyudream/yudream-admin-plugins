package online.yudream.base.plugin.aioffice;

import online.yudream.base.plugin.aioffice.domain.aggregate.OfficeDocType;
import online.yudream.base.plugin.aioffice.domain.service.ImageMappingSupport;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageMappingSupportTest {

    @Test
    void detectsTopLevelAndRowImageVars() {
        List<OfficeDocType.Mapping> mappings = List.of(
                new OfficeDocType.Mapping("cover", "ctx:target.coverKey", "image:480", Map.of()),
                new OfficeDocType.Mapping("plain", "ctx:target.title", "", Map.of()),
                new OfficeDocType.Mapping("effectImages", "list:target.attachments", "",
                        Map.of("caption", "caption", "img", "image:item.fileKey")));
        assertEquals(List.of("cover"), ImageMappingSupport.topLevelImageVars(mappings));
        Map<String, List<String>> rows = ImageMappingSupport.rowImageVars(mappings);
        assertEquals(List.of("img"), rows.get("effectImages"));
    }

    @Test
    void widthParsing() {
        assertEquals(480 * 9525, ImageMappingSupport.widthEmu("image:480"));
        assertNull(ImageMappingSupport.widthEmu("image"));
        assertNull(ImageMappingSupport.widthEmu("image:abc"));
        assertNull(ImageMappingSupport.widthEmu("image:0"));
    }

    @Test
    void resolvesFormatFromContentTypeAndFilename() {
        assertEquals("jpeg", ImageMappingSupport.resolveFormat("image/jpeg", null));
        assertEquals("png", ImageMappingSupport.resolveFormat("image/png", "a.PNG"));
        assertEquals("jpeg", ImageMappingSupport.resolveFormat(null, "photo.jpg"));
        assertEquals("gif", ImageMappingSupport.resolveFormat("", "动画.gif"));
        assertNull(ImageMappingSupport.resolveFormat("video/mp4", "clip.mp4"));
        assertNull(ImageMappingSupport.resolveFormat("image/webp", null));
    }

    @Test
    void toImageReadsBytesFromMapOrKey() {
        // 真 PNG 魔数头，裸文件键路径靠嗅探识别格式
        byte[] png = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2};
        Map<String, Object> attachment = Map.of("fileKey", "effects/a/1.png", "contentType", "image/png",
                "filename", "1.png");
        ImageMappingSupport.ImageBytesLoader loader = key ->
                "effects/a/1.png".equals(key) ? png : new byte[0];
        assertTrue(ImageMappingSupport.toImage(attachment, loader) != null);
        assertTrue(ImageMappingSupport.toImage("effects/a/1.png", loader) != null);
        assertEquals("png", ImageMappingSupport.sniffFormat(png));
        assertEquals("jpeg", ImageMappingSupport.sniffFormat(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0}));
        // 视频等不可嵌入的附件返回 null，调用方应置空串
        assertNull(ImageMappingSupport.toImage(Map.of("fileKey", "v", "contentType", "video/mp4"), loader));
        assertNull(ImageMappingSupport.toImage("", loader));
    }
}
