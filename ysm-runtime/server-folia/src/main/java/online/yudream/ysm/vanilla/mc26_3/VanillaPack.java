package online.yudream.ysm.vanilla.mc26_3;

import online.yudream.ysm.model.*;
import com.google.gson.*;
import org.joml.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

/** 26.3-only export adapter. The dynamic client never applies this ZIP for live updates. */
public final class VanillaPack {
    public byte[] build(Collection<ModelAsset> assets) {
        TreeMap<String,byte[]> files=new TreeMap<>();
        put(files,"pack.mcmeta",Map.of("pack",Map.of("description","YuDream 玩家模型","min_format",List.of(97,1),"max_format",List.of(97,1))));
        assets.stream().sorted(Comparator.comparing(ModelAsset::hash)).distinct().forEach(a->add(files,a));
        try(ByteArrayOutputStream bytes=new ByteArrayOutputStream();ZipOutputStream zip=new ZipOutputStream(bytes)){
            for(var file:files.entrySet()){ZipEntry e=new ZipEntry(file.getKey());e.setTime(1767225600000L);zip.putNextEntry(e);zip.write(file.getValue());zip.closeEntry();}
            zip.finish();return bytes.toByteArray();
        }catch(IOException e){throw new UncheckedIOException(e);}
    }
    private void add(Map<String,byte[]> files,ModelAsset a) {
        String root="assets/ysm/",prefix=a.hash()+"/";Map<String,JsonObject> bones=new HashMap<>();
        a.data().getAsJsonArray("bones").forEach(b->bones.put(b.getAsJsonObject().get("name").getAsString(),b.getAsJsonObject()));
        for(JsonElement te:a.textures()) {JsonObject t=te.getAsJsonObject();files.put(root+"textures/item/"+prefix+textureId(t)+".png",Base64.getDecoder().decode(t.get("png").getAsString()));}
        int partIndex=0,cubeIndex=0;
        for(JsonElement pe:a.data().getAsJsonArray("parts")) {
            JsonObject p=pe.getAsJsonObject();List<List<Object>> layers=new ArrayList<>();for(int t=0;t<a.textures().size();t++)layers.add(new ArrayList<>());
            for(JsonElement ce:p.getAsJsonArray("cubes")) {
                JsonObject c=ce.getAsJsonObject();float inflate=c.has("inflate")?c.get("inflate").getAsFloat():0;Vector3f s=Rig.vector(c.get("size"),0).add(2*inflate,2*inflate,2*inflate);
                Matrix4f m=new Matrix4f().translation(.5f,.5f,.5f).mul(Geometry.local(bones.get(p.get("bone").getAsString()),c)).translate(-.5f,-.5f,-.5f);
                List<Float> matrix=new ArrayList<>();for(int row=0;row<4;row++)for(int col=0;col<4;col++)matrix.add(m.get(col,row));
                for(int ti=0;ti<a.textures().size();ti++) {
                    JsonObject t=a.textures().get(ti).getAsJsonObject();String id=prefix+"cube_"+cubeIndex+"_"+ti;Map<String,Object> faces=new LinkedHashMap<>();
                    Geometry.uv(c,t.get("width").getAsInt(),t.get("height").getAsInt()).forEach((name,uv)->faces.put(name,Map.of("texture","#skin","uv",List.of(uv[0]*16,uv[1]*16,uv[2]*16,uv[3]*16))));
                    put(files,root+"models/"+id+".json",Map.of("ambientocclusion",false,"textures",Map.of("skin","ysm:item/"+prefix+textureId(t),"particle","#skin"),"elements",List.of(Map.of("from",List.of(8-s.x/2,8-s.y/2,8-s.z/2),"to",List.of(8+s.x/2,8+s.y/2,8+s.z/2),"faces",faces))));
                    layers.get(ti).add(Map.of("type","minecraft:model","model","ysm:"+id,"transformation",matrix));
                }cubeIndex++;
            }
            List<Object> cases=new ArrayList<>();for(int ti=0;ti<layers.size();ti++)cases.add(Map.of("when",a.textures().get(ti).getAsJsonObject().get("name").getAsString(),"model",Map.of("type","minecraft:composite","models",layers.get(ti))));
            put(files,root+"items/"+prefix+"bone_"+partIndex+".json",Map.of("model",Map.of("type","minecraft:select","property","minecraft:custom_model_data","index",0,"cases",cases,"fallback",Map.of("type","minecraft:composite","models",layers.get(0)))));
            partIndex++;
        }
        files.put(root+"ysm/"+a.hash()+".json",a.bytes());
    }
    private static String textureId(JsonObject t){return ModelAsset.digest("SHA-256",t.get("name").getAsString().getBytes(StandardCharsets.UTF_8)).substring(0,16);}
    private static void put(Map<String,byte[]> files,String path,Object value){files.put(path,ModelAsset.canonicalBytes(value));}
}
