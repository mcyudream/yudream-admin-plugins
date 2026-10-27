package online.yudream.ysm.model;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

/** Validates decoded YSM source files without extracting files to the host filesystem. */
public final class ModelCompiler {
    public static final int MAX_UPLOAD = 16 * 1024 * 1024;
    private static final int MAX_EXPANDED = 32 * 1024 * 1024;
    public static boolean isZip(byte[] bytes) { return bytes.length>=4 && bytes[0]=='P' && bytes[1]=='K' && ((bytes[2]==3&&bytes[3]==4)||(bytes[2]==5&&bytes[3]==6)); }
    public ModelAsset compile(byte[] zip, String requestedName) {
        if (zip.length == 0 || zip.length > MAX_UPLOAD) throw new IllegalArgumentException("模型文件必须小于 16 MiB");
        if (!isZip(zip)) throw new IllegalArgumentException("这不是明文 ZIP；原始 .ysm 需要使用网站上传解析");
        return compile(unzip(zip), requestedName);
    }
    public ModelAsset compile(Map<String,byte[]> files, String requestedName) {
        if(files.isEmpty()||files.size()>512)throw new IllegalArgumentException("模型需要 1–512 个文件");
        long total=0;for(var entry:files.entrySet()){safePath(entry.getKey());total+=entry.getValue().length;if(total>MAX_EXPANDED)throw new IllegalArgumentException("模型展开内容超过 32 MiB");}
        List<String> configs = files.keySet().stream().filter(p -> p.equals("ysm.json") || p.endsWith("/ysm.json")).sorted().toList();
        if (configs.size() > 1) throw new IllegalArgumentException("文件包含多份模型，请分别上传");
        String configPath = configs.isEmpty() ? "" : configs.getFirst();
        String prefix = configPath.isEmpty() ? "" : configPath.substring(0, configPath.length()-8);
        JsonObject config = configPath.isEmpty() ? new JsonObject() : object(files, configPath);
        JsonObject player = config.has("files") && config.getAsJsonObject("files").has("player") ? config.getAsJsonObject("files").getAsJsonObject("player") : new JsonObject();
        String modelPath = mainPath(player.get("model"), "models/main.json"), animationPath = mainPath(player.get("animation"), "animations/main.animation.json");
        if(!player.has("model")&&!files.containsKey(prefix+modelPath)){
            List<String> candidates=files.keySet().stream().filter(path->path.endsWith(".json")&&!path.endsWith("ysm.json")).filter(path->{try{return object(files,path).has("minecraft:geometry");}catch(IllegalArgumentException e){return false;}}).toList();
            if(candidates.size()!=1)throw new IllegalArgumentException("找不到唯一主模型，请在 ysm.json 的 files.player.model.main 中指定路径");
            modelPath=candidates.getFirst();int directory=modelPath.lastIndexOf('/');prefix=directory<0?"":modelPath.substring(0,directory+1);if(prefix.endsWith("models/"))prefix=prefix.substring(0,prefix.length()-7);modelPath=modelPath.substring(prefix.length());
        }
        JsonArray geometries = object(files, prefix+modelPath).getAsJsonArray("minecraft:geometry");
        if (geometries == null || geometries.size() != 1) throw new IllegalArgumentException("需要且仅支持一份主模型几何");
        JsonObject geometry = geometries.get(0).getAsJsonObject();
        JsonObject description = geometry.getAsJsonObject("description");
        int width = positiveInt(description, "texture_width", 2048), height = positiveInt(description, "texture_height", 2048);
        JsonArray sourceBones = geometry.getAsJsonArray("bones");
        if (sourceBones == null || sourceBones.isEmpty() || sourceBones.size() > 512) throw new IllegalArgumentException("骨骼数量需要在 1–512 之间");
        Set<String> warnings=new LinkedHashSet<>();
        JsonArray bones = new JsonArray(), parts = new JsonArray(); Set<String> names = new HashSet<>(); int cubeCount = 0;
        for (JsonElement e : sourceBones) {
            JsonObject b = e.getAsJsonObject(); String name = b.get("name").getAsString();
            if (name.isBlank() || name.length() > 120 || !names.add(name)) throw new IllegalArgumentException("骨骼名称为空、重复或过长");
            JsonObject bone = b.deepCopy(); bone.remove("cubes"); bones.add(bone);
            if (b.has("cubes") && !b.getAsJsonArray("cubes").isEmpty()) {
                JsonObject part = new JsonObject(); part.addProperty("bone", name); part.add("cubes", b.get("cubes"));
                for (JsonElement ce : b.getAsJsonArray("cubes")) {
                    JsonObject c = ce.getAsJsonObject();
                    for (String k : List.of("origin", "size")) vector(c.get(k), k);
                    for(int axis=0;axis<3;axis++){
                        double size=c.getAsJsonArray("size").get(axis).getAsDouble();
                        if(Math.abs(size)>512)throw new IllegalArgumentException("方块尺寸绝对值不能超过 512");
                        if(size<0){c.getAsJsonArray("origin").set(axis,new JsonPrimitive(c.getAsJsonArray("origin").get(axis).getAsDouble()+size));c.getAsJsonArray("size").set(axis,new JsonPrimitive(-size));warnings.add("模型含负尺寸方块，已规范化为正尺寸；这些方块的 UV 朝向可能与源文件不同。");}
                    }
                    validateUv(c.get("uv"));
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
            if (!a.has("animation_length")) a.addProperty("animation_length", inferredLength(a));
            double length=a.get("animation_length").getAsDouble();
            if (!Double.isFinite(length) || length<=0 || length>600) throw new IllegalArgumentException("动画时长需要在 0–600 秒之间");
            if (a.has("loop") && !a.get("loop").getAsJsonPrimitive().isBoolean() && !a.get("loop").getAsString().equals("hold_on_last_frame")) throw new IllegalArgumentException("动画循环模式暂不支持");
            normalizeAnimation(a,names,warnings);
            if (a.has("bones")) validateChannels(a.getAsJsonObject("bones"), names, length);
        }
        final String texturePrefix=prefix;
        animationBudget(animations);
        JsonArray textures=new JsonArray();
        List<String> texturePaths=new ArrayList<>();
        if(player.has("texture")){
            JsonElement declared=player.get("texture");
            List<JsonElement> entries=declared.isJsonArray()?declared.getAsJsonArray().asList():List.of(declared);
            for(JsonElement entry:entries){String path=entry.isJsonPrimitive()?entry.getAsString():entry.getAsJsonObject().get("uv").getAsString();safePath(path);if(!texturePaths.contains(prefix+path))texturePaths.add(prefix+path);}
        }else texturePaths=files.keySet().stream().filter(p -> p.startsWith(texturePrefix+"textures/") && p.toLowerCase(Locale.ROOT).endsWith(".png")).sorted().toList();
        if (texturePaths.isEmpty() || texturePaths.size()>16) throw new IllegalArgumentException("需要 1–16 张 PNG 贴图");
        long pixels = 0;Set<String> textureNames=new HashSet<>();
        for (String path : texturePaths) {
            byte[] bytes=files.get(path);if(bytes==null)throw new IllegalArgumentException("模型缺少贴图："+path); int[] dim=pngDimensions(bytes);
            if (dim[0]!=width || dim[1]!=height) throw new IllegalArgumentException("贴图尺寸需要与主模型声明一致");
            pixels += (long) dim[0] * dim[1];
            if (pixels > 16 * 1024 * 1024) throw new IllegalArgumentException("贴图总像素超过 16 Mi");
            try { if (javax.imageio.ImageIO.read(new ByteArrayInputStream(bytes)) == null) throw new IOException(); }
            catch (IOException e) { throw new IllegalArgumentException("PNG 贴图损坏", e); }
            String textureName=textureName(path);if(textureName.length()>240||!textureNames.add(textureName))throw new IllegalArgumentException("主模型贴图名称重复或过长");
            JsonObject t=new JsonObject(); t.addProperty("name", textureName);
            t.addProperty("png", Base64.getEncoder().encodeToString(bytes)); t.addProperty("width", width); t.addProperty("height", height); textures.add(t);
        }
        String name=requestedName == null || requestedName.isBlank() ? "YSM 模型" : requestedName.trim();
        if (name.length()>100) throw new IllegalArgumentException("模型名称不能超过 100 字符");
        JsonObject out=new JsonObject(); out.addProperty("schema",2); out.addProperty("name",name); out.add("bones",bones);out.add("parts",parts);out.add("animations",animations);out.add("textures",textures);
        JsonArray layers=new JsonArray();animations.keySet().stream().filter(k->k.matches("(?:pre_)?parallel[0-7]")).sorted(Comparator.comparing((String k)->!k.startsWith("pre_")).thenComparing(k->k)).forEach(layers::add);out.add("layers",layers);
        if(config.has("properties")){
            JsonObject properties=config.getAsJsonObject("properties");
            double widthScale=properties.has("width_scale")?properties.get("width_scale").getAsDouble():1,heightScale=properties.has("height_scale")?properties.get("height_scale").getAsDouble():1;
            if(!Double.isFinite(widthScale)||!Double.isFinite(heightScale)||widthScale<=0||heightScale<=0||widthScale>4||heightScale>4)throw new IllegalArgumentException("模型宽高缩放需要在 0–4 之间");
            JsonArray modelScale=new JsonArray();modelScale.add(widthScale);modelScale.add(heightScale);modelScale.add(widthScale);out.add("modelScale",modelScale);
            if(properties.has("preview_animation")&&animations.has(properties.get("preview_animation").getAsString()))out.add("previewAnimation",properties.get("preview_animation").deepCopy());
            if(properties.has("default_texture")){String preferred=properties.get("default_texture").getAsString();for(int i=0;i<textures.size();i++)if(textures.get(i).getAsJsonObject().get("name").getAsString().equals(preferred)){var first=textures.get(0);textures.set(0,textures.get(i));textures.set(i,first);break;}}
        }
        if(!warnings.isEmpty()){var notes=new JsonArray();warnings.forEach(notes::add);out.add("compatibility",notes);}
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
                safePath(p);
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
        if(data.get("schema").getAsInt()!=1&&data.get("schema").getAsInt()!=2)throw new IllegalArgumentException("不支持的模型格式版本");
        if(data.get("name").getAsString().length()>100)throw new IllegalArgumentException("模型名称过长");
        JsonArray bones=data.getAsJsonArray("bones"),parts=data.getAsJsonArray("parts"),textures=data.getAsJsonArray("textures");
        if(bones.isEmpty()||bones.size()>512||parts.size()>512||textures.isEmpty()||textures.size()>16)throw new IllegalArgumentException("模型数量超出限制");
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
                validateUv(c.get("uv"));
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
        animationBudget(animations);
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
        if(data.has("layers")){if(!data.get("layers").isJsonArray()||data.getAsJsonArray("layers").size()>16)throw new IllegalArgumentException("并行动画数量无效");for(var layer:data.getAsJsonArray("layers"))if(!animations.has(layer.getAsString()))throw new IllegalArgumentException("并行动画不存在");}
        for(var entry:animations.entrySet())if(entry.getValue().getAsJsonObject().has("initializers")){var initial=entry.getValue().getAsJsonObject().getAsJsonArray("initializers");if(initial.size()>64)throw new IllegalArgumentException("初始化表达式数量超限");initial.forEach(Molang::compile);}
        if(data.has("modelScale")){vector(data.get("modelScale"),"模型缩放");for(var n:data.getAsJsonArray("modelScale"))if(n.getAsDouble()<=0||n.getAsDouble()>4)throw new IllegalArgumentException("模型缩放无效");}
        new Rig(data);
    }
    private static void animationBudget(JsonObject animations){int[] count={0,0};animationBudget(animations,count);}
    private static void animationBudget(JsonElement value,int[] count){
        if(++count[0]>500000)throw new IllegalArgumentException("动画数据总量超限");
        if(value.isJsonObject()){var o=value.getAsJsonObject();if(o.has("expr")&&++count[1]>16384)throw new IllegalArgumentException("动画表达式总量超限");for(var e:o.entrySet())animationBudget(e.getValue(),count);}
        else if(value.isJsonArray())for(var e:value.getAsJsonArray())animationBudget(e,count);
    }
    public static void safePath(String path){if(path.isBlank()||path.length()>1024||path.startsWith("/")||path.contains("\\")||path.contains(":")||path.indexOf('\0')>=0||Arrays.asList(path.split("/",-1)).contains(".."))throw new IllegalArgumentException("模型包含不安全路径");}
    private static String mainPath(JsonElement value,String fallback){if(value==null)return fallback;String path=value.isJsonPrimitive()?value.getAsString():value.getAsJsonObject().has("main")?value.getAsJsonObject().get("main").getAsString():fallback;safePath(path);return path;}
    private static String textureName(String path){String p=path.substring(path.lastIndexOf('/')+1);return p.substring(0,p.length()-4);}
    private static double inferredLength(JsonObject animation){double max=1;if(animation.has("bones"))for(var bone:animation.getAsJsonObject("bones").entrySet())for(var channel:bone.getValue().getAsJsonObject().entrySet())if(channel.getValue().isJsonObject())for(var time:channel.getValue().getAsJsonObject().keySet())try{max=Math.max(max,Double.parseDouble(time));}catch(NumberFormatException ignored){}return max;}
    private static void validateUv(JsonElement uv){
        if(uv==null)throw new IllegalArgumentException("方块缺少 UV");
        if(uv.isJsonArray()){pair(uv);return;}
        if(!uv.isJsonObject()||uv.getAsJsonObject().isEmpty()||!Set.of("north","south","east","west","up","down").containsAll(uv.getAsJsonObject().keySet()))throw new IllegalArgumentException("逐面 UV 无效");
        for(var entry:uv.getAsJsonObject().entrySet()){var f=entry.getValue().getAsJsonObject();pair(f.get("uv"));if(f.has("uv_size"))pair(f.get("uv_size"));if(f.has("uv_rotation")){numeric(f.get("uv_rotation"));double rotation=f.get("uv_rotation").getAsDouble();if(!Set.of(0d,90d,180d,270d).contains(rotation))throw new IllegalArgumentException("UV 旋转必须为 0、90、180 或 270 度");}}
    }
    private static void pair(JsonElement value){if(value==null||!value.isJsonArray()||value.getAsJsonArray().size()!=2)throw new IllegalArgumentException("UV 必须为二维数值");numeric(value);}
    private static void normalizeAnimation(JsonObject animation,Set<String> names,Set<String> warnings){
        if(animation.has("bones"))animation.getAsJsonObject("bones").entrySet().removeIf(b->!names.contains(b.getKey()));
        if(animation.has("bones"))for(var bone:animation.getAsJsonObject("bones").entrySet()){
            for(var channel:bone.getValue().getAsJsonObject().entrySet())channel.setValue(expressions(channel.getValue(),channel.getKey().equals("scale")?1:0,warnings));
        }
        JsonArray initial=new JsonArray();
        if(animation.has("timeline")){var timeline=animation.getAsJsonObject("timeline");for(var event:timeline.entrySet()){
            if(!event.getKey().matches("0(?:\\.0*)?")){warnings.add("动画时间线只应用零时刻的默认变量，声音及后续事件未转换。");continue;}
            List<JsonElement> values=event.getValue().isJsonArray()?event.getValue().getAsJsonArray().asList():List.of(event.getValue());
            for(var e:values){try{initial.add(Molang.parse(e.getAsString()));}catch(IllegalArgumentException ex){warnings.add("部分时间线表达式不受支持，已跳过。");}}
        }animation.remove("timeline");}
        if(!initial.isEmpty())animation.add("initializers",initial);
        if(animation.has("sound_effects")||animation.has("particle_effects"))warnings.add("声音、粒子和 YSM 专用控制器未转换。");
    }
    private static JsonElement expressions(JsonElement value,int fallback,Set<String> warnings){
        if(value.isJsonPrimitive()&&value.getAsJsonPrimitive().isString()){
            String expression=value.getAsString();warnings.add("Molang 使用默认外观变量和中立查询值；未复现 YSM 专用控制器及物理，second_order 按目标值近似。");
            try{return Molang.parse(expression);}catch(IllegalArgumentException e){if(warnings.size()<8)warnings.add("部分表达式无法解析，已按通道默认值处理："+expression.substring(0,Math.min(100,expression.length())));return new JsonPrimitive(fallback);}
        }
        if(value.isJsonArray()){JsonArray a=new JsonArray();for(var e:value.getAsJsonArray())a.add(expressions(e,fallback,warnings));return a;}
        if(value.isJsonObject()){JsonObject o=new JsonObject();for(var e:value.getAsJsonObject().entrySet())o.add(e.getKey(),e.getKey().equals("lerp_mode")?e.getValue():expressions(e.getValue(),fallback,warnings));return o;}
        return value;
    }
    private static void boundedDepth(JsonElement value,int depth){
        if(depth>64)throw new IllegalArgumentException("模型数据嵌套过深");
        if(value.isJsonObject())value.getAsJsonObject().entrySet().forEach(e->boundedDepth(e.getValue(),depth+1));
        else if(value.isJsonArray())value.getAsJsonArray().forEach(e->boundedDepth(e,depth+1));
    }
    private static JsonObject object(Map<String,byte[]> files,String path) {
        byte[] bytes=files.get(path);if(bytes==null)throw new IllegalArgumentException("模型缺少文件："+path);
        try{JsonObject value=JsonParser.parseString(new String(bytes,StandardCharsets.UTF_8)).getAsJsonObject();boundedDepth(value,0);return value;}
        catch(RuntimeException e){throw new IllegalArgumentException("JSON 无效："+path,e);}
    }
    private static void validateChannels(JsonObject bones, Set<String> names, double length) {
        for (var b:bones.entrySet()) {
          if (!names.contains(b.getKey())) continue; // YSM commonly shares animation files with alternate geometry.
          for(var c:b.getValue().getAsJsonObject().entrySet()) {
            if(!List.of("position","rotation","scale").contains(c.getKey()))throw new IllegalArgumentException("暂不支持动画通道："+c.getKey());
            JsonElement value = c.getValue();
            if (!value.isJsonObject() || value.getAsJsonObject().has("expr")) { channelVector(value); continue; }
            if (value.getAsJsonObject().isEmpty() || value.getAsJsonObject().size() > 4096) throw new IllegalArgumentException("动画关键帧数量无效");
            Set<Double> times = new HashSet<>();
            for (var f : value.getAsJsonObject().entrySet()) {
                try { double time=Double.parseDouble(f.getKey()); if(!Double.isFinite(time)||time<0||time>600||!times.add(time)) throw new NumberFormatException(); }
                catch(NumberFormatException e){throw new IllegalArgumentException("动画关键帧时间无效");}
                JsonElement frame = f.getValue();
                if (!frame.isJsonObject() || frame.getAsJsonObject().has("expr")) { channelVector(frame); continue; }
                JsonObject fields=frame.getAsJsonObject();
                if (fields.isEmpty() || !Set.of("pre","post","vector","lerp_mode").containsAll(fields.keySet())) throw new IllegalArgumentException("暂不支持此关键帧插值");
                for (var v : fields.entrySet()) if (!v.getKey().equals("lerp_mode")) channelVector(v.getValue());
                if (fields.has("lerp_mode") && !Set.of("linear","step","catmullrom").contains(fields.get("lerp_mode").getAsString())) throw new IllegalArgumentException("暂不支持此关键帧插值");
                if (!fields.has("vector") && !fields.has("post")) throw new IllegalArgumentException("关键帧需要 post 或 vector");
            }
          }
        }
    }
    private static void channelVector(JsonElement e){
        if(e.isJsonArray()){if(e.getAsJsonArray().size()!=3)throw new IllegalArgumentException("动画向量必须有三个分量");e.getAsJsonArray().forEach(Molang::compile);}else Molang.compile(e);
    }
    private static void numeric(JsonElement e){
        if(e.isJsonPrimitive()){if(!e.getAsJsonPrimitive().isNumber() || !Double.isFinite(e.getAsDouble()) || Math.abs(e.getAsDouble())>1e6)throw new IllegalArgumentException("模型包含无效数值");}
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
