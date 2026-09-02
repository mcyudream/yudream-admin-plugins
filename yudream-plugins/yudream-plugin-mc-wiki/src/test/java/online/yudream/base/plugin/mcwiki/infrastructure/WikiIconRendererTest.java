package online.yudream.base.plugin.mcwiki.infrastructure;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import javax.imageio.ImageIO;
import online.yudream.base.plugin.spi.system.storage.PluginFileStore;
import online.yudream.base.plugin.spi.system.storage.PluginStoredFile;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WikiIconRendererTest {
    private final Map<String,byte[]> storage=new HashMap<>();
    private final PluginFileStore files=new PluginFileStore() {
        @Override public String put(String key,InputStream in,long length,String contentType){try{storage.put(key,in.readAllBytes());}catch(Exception ex){throw new RuntimeException(ex);}return key;}
        @Override public PluginStoredFile get(String key){byte[] data=storage.get(key);return data==null?null:new PluginStoredFile(key,"image/png",(long)data.length,new ByteArrayInputStream(data));}
        @Override public void delete(String key){storage.remove(key);}
    };
    private final WikiIconRenderer renderer=new WikiIconRenderer(new WikiAssetService(files));

    @Test void layersCompositeBottomToTopAndScaleNearestNeighbor() throws Exception {
        files.put("textures/1.20.2/item/base.png",new ByteArrayInputStream(png(solid(16,0xFFFF0000))),256,"image/png");
        BufferedImage overlay=solid(16,0x00000000); overlay.setRGB(0,0,0xFF0000FF);
        files.put("textures/1.20.2/item/overlay.png",new ByteArrayInputStream(png(overlay)),256,"image/png");
        byte[] out=renderer.renderLayers("1.20.2",java.util.List.of("item/base.png","item/overlay.png"),64).orElseThrow();
        BufferedImage image=ImageIO.read(new ByteArrayInputStream(out));
        assertEquals(64,image.getWidth()); assertEquals(64,image.getHeight());
        assertEquals(0xFF0000FF,image.getRGB(0,0),"顶图层的蓝点必须盖住底层");
        assertEquals(0xFFFF0000,image.getRGB(63,63),"其余像素保留底层红色");
    }

    @Test void missingLayersAreSkippedAndAllMissingYieldsEmpty() {
        files.put("textures/1.20.2/item/base.png",new ByteArrayInputStream(png(solid(16,0xFF00FF00))),256,"image/png");
        assertTrue(renderer.renderLayers("1.20.2",java.util.List.of("item/missing.png","item/base.png"),32).isPresent());
        assertTrue(renderer.renderLayers("1.20.2",java.util.List.of("item/missing.png"),32).isEmpty());
    }

    @Test void singleTextureScalesWithoutBlur() throws Exception {
        BufferedImage source=solid(16,0xFF000000); source.setRGB(8,8,0xFFFFFFFF);
        files.put("textures/1.20.2/block/stone.png",new ByteArrayInputStream(png(source)),256,"image/png");
        BufferedImage image=ImageIO.read(new ByteArrayInputStream(renderer.renderSingle("1.20.2","block/stone.png",32).orElseThrow()));
        assertEquals(32,image.getWidth());
        assertEquals(0xFFFFFFFF,image.getRGB(16,16),"最近邻缩放必须保持硬边像素");
        assertEquals(0xFFFFFFFF,image.getRGB(17,17),"最近邻缩放的单像素应扩展为 2x2 色块");
        assertEquals(0xFF000000,image.getRGB(15,15));
    }

    @Test void sizeIsClamped() {
        assertEquals(WikiIconRenderer.MIN_SIZE,renderer.clampSize(0));
        assertEquals(WikiIconRenderer.MAX_SIZE,renderer.clampSize(99999));
        assertEquals(WikiIconRenderer.DEFAULT_SIZE,renderer.clampSize(null));
        assertEquals(128,renderer.clampSize(128));
    }

    @Test void renderScalesSmoothlyWithBilinear() throws Exception {
        BufferedImage source=new BufferedImage(2,2,BufferedImage.TYPE_INT_ARGB);
        source.setRGB(0,0,0xFF000000); source.setRGB(1,0,0xFFFF0000); source.setRGB(0,1,0xFF00FF00); source.setRGB(1,1,0xFF0000FF);
        BufferedImage image=ImageIO.read(new ByteArrayInputStream(renderer.scaleRender(png(source),64).orElseThrow()));
        assertEquals(64,image.getWidth());
        int center=image.getRGB(32,32);
        assertNotEquals(0xFF000000,center); assertNotEquals(0xFFFF0000,center); assertNotEquals(0xFF00FF00,center); assertNotEquals(0xFF0000FF,center);
        assertEquals(0xFF,center>>>24,"渲染图缩放必须保持不透明");
        assertTrue(renderer.scaleRender(new byte[]{1,2,3},64).isEmpty(),"非 PNG 内容返回空");
    }

    private BufferedImage solid(int size,int argb){BufferedImage image=new BufferedImage(size,size,BufferedImage.TYPE_INT_ARGB);for(int x=0;x<size;x++)for(int y=0;y<size;y++)image.setRGB(x,y,argb);return image;}
    private byte[] png(BufferedImage image){try{ByteArrayOutputStream out=new ByteArrayOutputStream();ImageIO.write(image,"png",out);return out.toByteArray();}catch(Exception ex){throw new RuntimeException(ex);}}
}
