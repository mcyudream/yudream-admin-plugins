package online.yudream.ysm.model;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

/** Converts a data-only YSM source ZIP. No filesystem extraction and no executable expressions. */
public final class ModelCompiler {
    public static final int MAX_UPLOAD = 16 * 1024 * 1024;
    private static final int MAX_EXPANDED = 32 * 1024 * 1024;
    public ModelAsset compile(byte[] zip, String requestedName) {
        if (zip.length == 0 || zip.length > MAX_UPLOAD) throw new IllegalArgumentException("模型 ZIP 必须小于 16 MiB");
        Map<String, byte[]> files = unzip(zip);
        List<String> configs = files.keySet().stream().filter(p -> p.equals("ysm.json") || p.endsWith("/ysm.json")).toList();
        if (configs.size() > 1) throw new IllegalArgumentException("ZIP 包含多份模型，请分别上传");
        String configPath = configs.isEmpty() ? "" : configs.getFirst();
        String prefix = configPath.isEmpty() ? "" : configPath.substring(0, configPath.length()-8);
        JsonObject config = configPath.isEmpty() ? new JsonObject() : object(files, configPath);
        String modelPath = "models/main.json", animationPath = "animations/main.animation.json";
        if (config.has("files")) {
            JsonObject player = config.getAsJsonObject("files").getAsJsonObject("player");
            modelPath = player.getAsJsonObject("model").get("main").getAsString();
            animationPath = player.getAsJsonObject("animation").get("main").getAsString();
        }
        JsonArray geometries = object(files, prefix+modelPath).getAsJsonArray("minecraft:geometry");
        if (geometries == null || geometries.size() != 1) throw new IllegalArgumentException("首版需要且仅支持一份主模型几何");
        JsonObject geometry = geometries.get(0).getAsJsonObject();
        JsonObject description = geometry.getAsJsonObject("description");
        int width = positiveInt(description, "texture_width", 2048), height = positiveInt(description, "texture_height", 2048);
        JsonArray sourceBones = geometry.getAsJsonArray("bones");
        if (sourceBones == null || sourceBones.isEmpty() || sourceBones.size() > 128) throw new IllegalArgumentException("骨骼数量需要在 1–128 之间");
        JsonArray bones = new JsonArray(), parts = new JsonArray(); Set<String> names = new HashSet<>(); int cubeCount = 0;
        for (JsonElement e : sourceBones) {
            JsonObject b = e.getAsJsonObject(); String name = b.get("name").getAsString();
            if (name.isBlank() || name.length() > 120 || !names.add(name)) throw new IllegalArgumentException("骨骼名称为空、重复或过长");
            JsonObject bone = b.deepCopy(); bone.remove("cubes"); bones.add(bone);
            if (b.has("cubes") && !b.getAsJsonArray("cubes").isEmpty()) {
                JsonObject part = new JsonObject(); part.addProperty("bone", name); part.add("cubes", b.get("cubes").deepCopy());
                for (JsonElement ce : b.getAsJsonArray("cubes")) {
                    JsonObject c = ce.getAsJsonObject();
                    for (String k : List.of("origin", "size")) vector(c.get(k), k);
                    for (JsonElement size : c.getAsJsonArray("size")) if (size.getAsDouble() < 0 || size.getAsDouble() > 512) throw new IllegalArgumentException("方块尺寸需要在 0–512 之间");
                    if (!c.has("uv") || !c.get("uv").isJsonArray() || c.getAsJsonArray("uv").size()!=2) throw new IllegalArgumentException("首版支持 box UV；逐面 UV 模型暂不支持");
                    numeric(c.get("uv"));
                    if (c.has("inflate")) numeric(c.get("inflate"));
                    if (c.has("rotation")) vector(c.get("rotation"), "rotation");
                    if (c.has("pivot")) vector(c.get("pivot"), "pivot");
                    if (++cubeCount > 2048) throw new IllegalArgumentException("模型方块数量不能超过 2048");
                }
                parts.add(part);
            }
        }
        for (JsonElement e : bones) {
            JsonObject b=e.getAsJsonObject();
            if (b.has("parent") && !names.contains(b.get("parent").getAsString())) throw new IllegalArgumentException("模型包含不存在的父骨骼");
            if (b.has("pivot")) vector(b.get("pivot"), "pivot");
            if (b.has("rotation")) vector(b.get("rotation"), "rotation");
        }
        JsonObject animations = files.containsKey(prefix+animationPath) ? object(files, prefix+animationPath).getAsJsonObject("animations") : new JsonObject();
        if (animations == null || animations.size()>64) throw new IllegalArgumentException("动画数据无效或数量超过 64");
        for (var entry : animations.entrySet()) {
            JsonObject a=entry.getValue().getAsJsonObject();
            if (!a.has("animation_length")) a.addProperty("animation_length", 1);
            double length=a.get("animation_length").getAsDouble();
            if (!Double.isFinite(length) || length<=0 || length>600) throw new IllegalArgumentException("动画时长需要在 0–600 秒之间");
            if (a.has("loop") && !a.get("loop").getAsJsonPrimitive().isBoolean() && !a.get("loop").getAsString().equals("hold_on_last_frame")) throw new IllegalArgumentException("动画循环模式暂不支持");
            if (a.has("bones")) validateChannels(a.getAsJsonObject("bones"), names, length);
        }
        JsonArray textures=new JsonArray();
        List<String> texturePaths=files.keySet().stream().filter(p -> p.startsWith(prefix+"textures/") && p.endsWith(".png")).sorted().toList();
        if (texturePaths.isEmpty() || texturePaths.size()>16) throw new IllegalArgumentException("需要 1–16 张 PNG 贴图");
        long pixels = 0;
        for (String path : texturePaths) {
            byte[] bytes=files.get(path); int[] dim=pngDimensions(bytes);
            if (dim[0]!=width || dim[1]!=height) throw new IllegalArgumentException("贴图尺寸需要与主模型声明一致");
            pixels += (long) dim[0] * dim[1];
            if (pixels > 16 * 1024 * 1024) throw new IllegalArgumentException("贴图总像素超过 16 Mi");
            try { if (javax.imageio.ImageIO.read(new ByteArrayInputStream(bytes)) == null) throw new IOException(); }
            catch (IOException e) { throw new IllegalArgumentException("PNG 贴图损坏", e); }
            JsonObject t=new JsonObject(); t.addProperty("name", path.substring((prefix+"textures/").length(),path.length()-4));
            t.addProperty("png", Base64.getEncoder().encodeToString(bytes)); t.addProperty("width", width); t.addProperty("height", height); textures.add(t);
        }
        String name=requestedName == null || requestedName.isBlank() ? "YSM 模型" : requestedName.trim();
        if (name.length()>100) throw new IllegalArgumentException("模型名称不能超过 100 字符");
        JsonObject out=new JsonObject(); out.addProperty("schema",1); out.addProperty("name",name); out.add("bones",bones);out.add("parts",parts);out.add("animations",animations);out.add("textures",textures);
        if (config.has("metadata")) out.add("sourceMetadata",config.get("metadata").deepCopy());
        boundedDepth(out,0);ModelAsset asset=ModelAsset.of(out); new Rig(asset.data()); return asset;
    }
    private static Map<String,byte[]> unzip(byte[] input) {
        Map<String,byte[]> files=new TreeMap<>(); int count=0,total=0;
        try (ZipInputStream in=new ZipInputStream(new ByteArrayInputStream(input))) {
            ZipEntry e; byte[] buffer=new byte[8192];
            while ((e=in.getNextEntry())!=null) {
                if (++count>512) throw new IllegalArgumentException("ZIP 文件数量超过 512");
                String p=e.getName();
                if (p.startsWith("/") || p.contains("\\") || Arrays.asList(p.split("/")).contains("..") || p.contains(":")) throw new IllegalArgumentException("ZIP 包含不安全路径");
                if(e.isDirectory())continue;
                ByteArrayOutputStream bytes=new ByteArrayOutputStream(); int n;
                while((n=in.read(buffer))!=-1) {total+=n;if(total>MAX_EXPANDED)throw new IllegalArgumentException("解压内容超过 32 MiB");bytes.write(buffer,0,n);}
                if(files.put(p,bytes.toByteArray())!=null)throw new IllegalArgumentException("ZIP 存在重复路径");
            }
        }catch(IOException e){throw new IllegalArgumentException("无法读取模型 ZIP",e);}
        return files;
    }
    /** Downloaded bundles cross the same trust boundary as uploads, even when their hash matches. */
    public static void validateBundle(JsonObject data) {
        boundedDepth(data,0);
        if(data.get("schema").getAsInt()!=1)throw new IllegalArgumentException("不支持的模型格式版本");
        if(data.get("name").getAsString().length()>100)throw new IllegalArgumentException("模型名称过长");
        JsonArray bones=data.getAsJsonArray("bones"),parts=data.getAsJsonArray("parts"),textures=data.getAsJsonArray("textures");
        if(bones.isEmpty()||bones.size()>128||parts.size()>128||textures.isEmpty()||textures.size()>16)throw new IllegalArgumentException("模型数量超出限制");
        Set<String> names=new HashSet<>();
        for(var be:bones){
            var b=be.getAsJsonObject();String name=b.get("name").getAsString();
            if(name.isBlank()||name.length()>120||!names.add(name))throw new IllegalArgumentException("骨骼名称无效");
            if(b.has("pivot"))vector(b.get("pivot"),"pivot");if(b.has("rotation"))vector(b.get("rotation"),"rotation");
        }
        for(var be:bones){var b=be.getAsJsonObject();if(b.has("parent")&&!names.contains(b.get("parent").getAsString()))throw new IllegalArgumentException("父骨骼不存在");}
        int cubes=0;Set<String> partNames=new HashSet<>();
        for(var pe:parts){
            var p=pe.getAsJsonObject();String bone=p.get("bone").getAsString();
            if(!names.contains(bone)||!partNames.add(bone))throw new IllegalArgumentException("模型部件骨骼无效");
            for(var ce:p.getAsJsonArray("cubes")){
                var c=ce.getAsJsonObject();if(++cubes>2048)throw new IllegalArgumentException("方块数量超出限制");
                vector(c.get("origin"),"origin");vector(c.get("size"),"size");
                for(var size:c.getAsJsonArray("size"))if(size.getAsDouble()<0||size.getAsDouble()>512)throw new IllegalArgumentException("方块尺寸超出限制");
                if(!c.get("uv").isJsonArray()||c.getAsJsonArray("uv").size()!=2)throw new IllegalArgumentException("仅支持 box UV");numeric(c.get("uv"));
                if(c.has("pivot"))vector(c.get("pivot"),"pivot");if(c.has("rotation"))vector(c.get("rotation"),"rotation");if(c.has("inflate"))numeric(c.get("inflate"));
            }
        }
        JsonObject animations=data.getAsJsonObject("animations");if(animations.size()>64)throw new IllegalArgumentException("动画数量超出限制");
        for(var ae:animations.entrySet()){
            var a=ae.getValue().getAsJsonObject();double length=a.get("animation_length").getAsDouble();
            if(!Double.isFinite(length)||length<=0||length>600)throw new IllegalArgumentException("动画时长无效");
            if(a.has("loop")&&!a.get("loop").getAsJsonPrimitive().isBoolean()&&!a.get("loop").getAsString().equals("hold_on_last_frame"))throw new IllegalArgumentException("循环模式无效");
            if(a.has("bones"))validateChannels(a.getAsJsonObject("bones"),names,length);
        }
        long pixels=0;int width=0,height=0;Set<String> textureNames=new HashSet<>();
        for(var te:textures){
            var t=te.getAsJsonObject();String name=t.get("name").getAsString();if(name.length()>240||!textureNames.add(name))throw new IllegalArgumentException("贴图名称无效");
            byte[] png=Base64.getDecoder().decode(t.get("png").getAsString());int[] dimensions=pngDimensions(png);
            if(width==0){width=dimensions[0];height=dimensions[1];}
            if(dimensions[0]!=width||dimensions[1]!=height||t.get("width").getAsInt()!=width||t.get("height").getAsInt()!=height)throw new IllegalArgumentException("贴图尺寸不一致");
            pixels+=(long)width*height;if(pixels>16*1024*1024)throw new IllegalArgumentException("贴图像素超出限制");
            try{if(javax.imageio.ImageIO.read(new ByteArrayInputStream(png))==null)throw new IOException();}
            catch(IOException e){throw new IllegalArgumentException("PNG 贴图损坏",e);}
        }
        new Rig(data);
    }
    private static void boundedDepth(JsonElement value,int depth){
        if(depth>32)throw new IllegalArgumentException("模型数据嵌套过深");
        if(value.isJsonObject())value.getAsJsonObject().entrySet().forEach(e->boundedDepth(e.getValue(),depth+1));
        else if(value.isJsonArray())value.getAsJsonArray().forEach(e->boundedDepth(e,depth+1));
    }
    private static JsonObject object(Map<String,byte[]> files,String path) {
        byte[] bytes=files.get(path);if(bytes==null)throw new IllegalArgumentException("模型缺少文件："+path);
        try{return JsonParser.parseString(new String(bytes,StandardCharsets.UTF_8)).getAsJsonObject();}
        catch(RuntimeException e){throw new IllegalArgumentException("JSON 无效："+path,e);}
    }
    private static void validateChannels(JsonObject bones, Set<String> names, double length) {
        for (var b:bones.entrySet()) {
          if (!names.contains(b.getKey())) continue; // YSM commonly shares animation files with alternate geometry.
          for(var c:b.getValue().getAsJsonObject().entrySet()) {
            if(!List.of("position","rotation","scale").contains(c.getKey()))throw new IllegalArgumentException("暂不支持动画通道："+c.getKey());
            JsonElement value = c.getValue();
            if (!value.isJsonObject()) { channelVector(value); continue; }
            if (value.getAsJsonObject().isEmpty() || value.getAsJsonObject().size() > 4096) throw new IllegalArgumentException("动画关键帧数量无效");
            Set<Double> times = new HashSet<>();
            for (var f : value.getAsJsonObject().entrySet()) {
                try { double time=Double.parseDouble(f.getKey()); if(!Double.isFinite(time)||time<0||time>600||!times.add(time)) throw new NumberFormatException(); }
                catch(NumberFormatException e){throw new IllegalArgumentException("动画关键帧时间无效");}
                JsonElement frame = f.getValue();
                if (!frame.isJsonObject()) { channelVector(frame); continue; }
                JsonObject fields=frame.getAsJsonObject();
                if (fields.isEmpty() || !Set.of("pre","post","vector","lerp_mode").containsAll(fields.keySet())) throw new IllegalArgumentException("暂不支持此关键帧插值");
                for (var v : fields.entrySet()) if (!v.getKey().equals("lerp_mode")) channelVector(v.getValue());
                if (fields.has("lerp_mode") && !Set.of("linear","step","catmullrom").contains(fields.get("lerp_mode").getAsString())) throw new IllegalArgumentException("暂不支持此关键帧插值");
                if (!fields.has("vector") && !fields.has("post")) throw new IllegalArgumentException("关键帧需要 post 或 vector");
            }
          }
        }
    }
    private static void channelVector(JsonElement e){if(e.isJsonPrimitive())numeric(e);else vector(e,"动画向量");}
    private static void numeric(JsonElement e){
        if(e.isJsonPrimitive()){if(!e.getAsJsonPrimitive().isNumber() || !Double.isFinite(e.getAsDouble()) || Math.abs(e.getAsDouble())>1e6)throw new IllegalArgumentException("首版只支持数值动画，不支持 Molang 或特殊插值");}
        else if(e.isJsonArray())e.getAsJsonArray().forEach(ModelCompiler::numeric);
        else if(e.isJsonObject())e.getAsJsonObject().entrySet().forEach(x->numeric(x.getValue()));
        else throw new IllegalArgumentException("动画数据为空");
    }
    private static void vector(JsonElement e,String name){if(e==null || !e.isJsonArray() || e.getAsJsonArray().size()!=3)throw new IllegalArgumentException(name+" 必须为三维数值");numeric(e);}
    private static int positiveInt(JsonObject o,String k,int max){int n=o.get(k).getAsInt();if(n<=0||n>max)throw new IllegalArgumentException(k+" 超出范围");return n;}
    public static int[] pngDimensions(byte[] bytes){
        byte[] sig={(byte)137,80,78,71,13,10,26,10};
        if(bytes.length<33 || !Arrays.equals(Arrays.copyOf(bytes,8),sig))throw new IllegalArgumentException("贴图不是 PNG");
        java.nio.ByteBuffer b=java.nio.ByteBuffer.wrap(bytes);int w=b.getInt(16),h=b.getInt(20);
        if(w<=0||h<=0||w>2048||h>2048)throw new IllegalArgumentException("贴图最大为 2048×2048");return new int[]{w,h};
    }
}
