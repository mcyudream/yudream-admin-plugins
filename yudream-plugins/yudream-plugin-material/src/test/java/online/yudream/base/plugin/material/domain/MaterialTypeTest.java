package online.yudream.base.plugin.material.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MaterialTypeTest {

    @Test
    void extOfExtractsLowercasedExtension() {
        assertEquals("pptx", MaterialType.extOf("方案.PPTX"));
        assertEquals("psd", MaterialType.extOf("a/b/海报.psd"));
        assertEquals("", MaterialType.extOf("无扩展名"));
        assertEquals("", MaterialType.extOf("trailingdot."));
        assertEquals("", MaterialType.extOf(null));
    }

    @Test
    void classifiesCommonTypes() {
        assertEquals(MaterialType.IMAGE, MaterialType.fromFilename("a.png"));
        assertEquals(MaterialType.DESIGN, MaterialType.fromFilename("a.psd"));
        assertEquals(MaterialType.DOCUMENT, MaterialType.fromFilename("a.pdf"));
        assertEquals(MaterialType.DOCUMENT, MaterialType.fromFilename("a.docx"));
        assertEquals(MaterialType.SPREADSHEET, MaterialType.fromFilename("a.xlsx"));
        assertEquals(MaterialType.SPREADSHEET, MaterialType.fromFilename("a.csv"));
        assertEquals(MaterialType.PRESENTATION, MaterialType.fromFilename("a.pptx"));
        assertEquals(MaterialType.VIDEO, MaterialType.fromFilename("a.mp4"));
        assertEquals(MaterialType.AUDIO, MaterialType.fromFilename("a.mp3"));
        assertEquals(MaterialType.ARCHIVE, MaterialType.fromFilename("a.zip"));
        assertEquals(MaterialType.OTHER, MaterialType.fromFilename("a.bin"));
        assertEquals(MaterialType.OTHER, MaterialType.fromFilename("无扩展名"));
    }

    @Test
    void browserRenderableRules() {
        assertTrue(MaterialType.browserRenderable(MaterialType.IMAGE, "png"));
        assertTrue(MaterialType.browserRenderable(MaterialType.VIDEO, "mp4"));
        assertFalse(MaterialType.browserRenderable(MaterialType.VIDEO, "mkv"));
        assertTrue(MaterialType.browserRenderable(MaterialType.AUDIO, "mp3"));
        assertTrue(MaterialType.browserRenderable(MaterialType.DOCUMENT, "pdf"));
        assertTrue(MaterialType.browserRenderable(MaterialType.DOCUMENT, "md"));
        assertFalse(MaterialType.browserRenderable(MaterialType.DOCUMENT, "docx"));
        assertFalse(MaterialType.browserRenderable(MaterialType.DESIGN, "psd"));
        assertFalse(MaterialType.browserRenderable(MaterialType.ARCHIVE, "zip"));
    }

    @Test
    void mimeFallback() {
        assertEquals("application/pdf", MaterialType.mimeOf("pdf"));
        assertEquals("application/octet-stream", MaterialType.mimeOf("xyz"));
    }
}
