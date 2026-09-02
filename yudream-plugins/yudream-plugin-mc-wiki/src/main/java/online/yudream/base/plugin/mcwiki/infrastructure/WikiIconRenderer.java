package online.yudream.base.plugin.mcwiki.infrastructure;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.imageio.ImageIO;

/** 背包图标渲染：按模型解析出的贴图图层自下而上合成，并用最近邻缩放到指定像素尺寸，保持像素画锐利不模糊。 */
public final class WikiIconRenderer {
    public static final int MIN_SIZE = 1;
    public static final int MAX_SIZE = 1024;
    public static final int DEFAULT_SIZE = 64;
    private final WikiAssetService assets;
    public WikiIconRenderer(WikiAssetService assets){this.assets=assets;}
    /** 尺寸钳制到 1..1024，缺省 64。 */
    public int clampSize(Integer size){return size==null?DEFAULT_SIZE:Math.min(MAX_SIZE,Math.max(MIN_SIZE,size));}
    /** 合成图层（相对 assets/minecraft/textures 的 .png 路径，先底后顶）并缩放；全部图层缺失时返回空。 */
    public Optional<byte[]> renderLayers(String version,List<String> layers,int size){
        if(layers==null||layers.isEmpty())return Optional.empty();
        List<BufferedImage> decoded=new ArrayList<>();
        for(String layer:layers)read(version,layer).ifPresent(decoded::add);
        if(decoded.isEmpty())return Optional.empty();
        int width=decoded.stream().mapToInt(BufferedImage::getWidth).max().orElse(16);
        int height=decoded.stream().mapToInt(BufferedImage::getHeight).max().orElse(16);
        BufferedImage composed=new BufferedImage(width,height,BufferedImage.TYPE_INT_ARGB);
        Graphics2D g=composed.createGraphics();
        try{for(BufferedImage layer:decoded)g.drawImage(layer,0,0,null);}finally{g.dispose();}
        return Optional.ofNullable(png(scale(composed,size)));
    }
    /** 单张贴图缩放输出。 */
    public Optional<byte[]> renderSingle(String version,String texturePath,int size){return read(version,texturePath).map(image->png(scale(image,size)));}
    /** 渲染图（非像素画）按双线性缩放输出，边缘平滑；解码失败返回空。 */
    public Optional<byte[]> scaleRender(byte[] source,int size){if(source==null||source.length==0)return Optional.empty();try{BufferedImage image=ImageIO.read(new ByteArrayInputStream(source));if(image==null)return Optional.empty();return Optional.ofNullable(png(scale(image,size,true)));}catch(Exception ex){return Optional.empty();}}
    private Optional<BufferedImage> read(String version,String texturePath){
        if(texturePath==null||texturePath.isBlank())return Optional.empty();
        int slash=texturePath.indexOf('/'); if(slash<=0)return Optional.empty();
        return assets.read(version,texturePath.substring(0,slash),texturePath.substring(slash+1)).flatMap(bytes->{try{return Optional.ofNullable(ImageIO.read(new ByteArrayInputStream(bytes)));}catch(Exception ex){return Optional.empty();}});
    }
    /** 最近邻缩放：像素画必须使用 NEAREST_NEIGHBOR，双线性会把图标糊掉。 */
    private BufferedImage scale(BufferedImage source,int size){return scale(source,size,false);}
    private BufferedImage scale(BufferedImage source,int size,boolean smooth){
        double ratio=Math.min((double)size/source.getWidth(),(double)size/source.getHeight());
        int width=Math.max(1,(int)Math.round(source.getWidth()*ratio));
        int height=Math.max(1,(int)Math.round(source.getHeight()*ratio));
        BufferedImage out=new BufferedImage(size,size,BufferedImage.TYPE_INT_ARGB);
        Graphics2D g=out.createGraphics();
        try{g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,smooth?RenderingHints.VALUE_INTERPOLATION_BILINEAR:RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);g.drawImage(source,(size-width)/2,(size-height)/2,width,height,null);}finally{g.dispose();}
        return out;
    }
    private byte[] png(BufferedImage image){try{ByteArrayOutputStream out=new ByteArrayOutputStream();ImageIO.write(image,"png",out);return out.toByteArray();}catch(Exception ex){return null;}}
}
