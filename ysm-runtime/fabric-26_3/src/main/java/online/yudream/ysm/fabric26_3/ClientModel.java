package online.yudream.ysm.fabric26_3;

import online.yudream.ysm.model.*;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** CPU decode happens on a worker; upload/release happens on the render thread. */
public final class ClientModel implements AutoCloseable {
    private static final AtomicLong IDS=new AtomicLong();
    final Rig rig;final List<Geometry.Cube> cubes;private final Map<String,Identifier> textures;private final Minecraft client;
    private ClientModel(Rig rig,List<Geometry.Cube> cubes,Map<String,Identifier> textures,Minecraft client){this.rig=rig;this.cubes=cubes;this.textures=textures;this.client=client;}
    Identifier texture(String name){return textures.getOrDefault(name,textures.values().iterator().next());}
    static Decoded decode(ModelAsset asset){
        Map<String,NativeImage> images=new LinkedHashMap<>();
        try{
            for(var te:asset.textures()){var t=te.getAsJsonObject();images.put(t.get("name").getAsString(),NativeImage.read(Base64.getDecoder().decode(t.get("png").getAsString())));}
            return new Decoded(asset.hash(),new Rig(asset.data()),Geometry.mesh(asset),images);
        }catch(Exception e){images.values().forEach(NativeImage::close);throw new IllegalArgumentException("Texture decode failed",e);}
    }
    static final class Decoded implements AutoCloseable {
        final String hash;final Rig rig;final List<Geometry.Cube> cubes;final Map<String,NativeImage> images;
        Decoded(String hash,Rig rig,List<Geometry.Cube> cubes,Map<String,NativeImage> images){this.hash=hash;this.rig=rig;this.cubes=cubes;this.images=images;}
        ClientModel upload(Minecraft client){
            Map<String,Identifier> ids=new LinkedHashMap<>();
            try{
                for(var entry:images.entrySet()){
                    Identifier id=Identifier.fromNamespaceAndPath("yudream_models",hash+"/"+IDS.incrementAndGet());
                    DynamicTexture texture=new DynamicTexture(()->"YSM "+hash,entry.getValue());
                    try{client.getTextureManager().register(id,texture);ids.put(entry.getKey(),id);}catch(Throwable e){texture.close();throw e;}
                }
                images.clear();ModelsClient.LOG.info("YSM_PREPARED {} parts={} cubes={}",hash,rig.partBones.length,cubes.size());
                return new ClientModel(rig,cubes,ids,client);
            }catch(Throwable e){ids.values().forEach(client.getTextureManager()::release);throw e;}
        }
        public void close(){images.values().forEach(i->{if(!i.isClosed())i.close();});images.clear();}
    }
    @Override public void close(){textures.values().forEach(client.getTextureManager()::release);textures.clear();}
}
