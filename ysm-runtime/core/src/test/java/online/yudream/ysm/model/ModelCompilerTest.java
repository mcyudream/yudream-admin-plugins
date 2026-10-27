package online.yudream.ysm.model;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

class ModelCompilerTest {
    private static final String GEOMETRY="""
      {"minecraft:geometry":[{"description":{"texture_width":16,"texture_height":16},"bones":[
      {"name":"Root","pivot":[0,0,0],"cubes":[{"origin":[-4,0,-2],"size":[8,12,4],"uv":[0,0]}]}]}]}
      """;
    private static byte[] zip(String geometry, String animation, String extraPath) throws Exception {
        var png=new ByteArrayOutputStream();javax.imageio.ImageIO.write(new BufferedImage(16,16,BufferedImage.TYPE_INT_ARGB),"png",png);
        Map<String,byte[]> entries=new LinkedHashMap<>();
        entries.put("models/main.json",geometry.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        entries.put("textures/blue.png",png.toByteArray());
        if(animation!=null)entries.put("animations/main.animation.json",animation.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if(extraPath!=null)entries.put(extraPath,new byte[]{0});
        var out=new ByteArrayOutputStream();try(var z=new ZipOutputStream(out)){for(var e:entries.entrySet()){z.putNextEntry(new ZipEntry(e.getKey()));z.write(e.getValue());z.closeEntry();}}return out.toByteArray();
    }
    @Test void oneBundleWorksWithoutGameVersionAndHasStableHash() throws Exception {
        var compiler=new ModelCompiler();var a=compiler.compile(zip(GEOMETRY,null,null),"模型");
        assertEquals(a.hash(),compiler.compile(zip(GEOMETRY,null,null),"模型").hash());
        assertEquals(2,a.data().get("schema").getAsInt());
        assertFalse(a.data().has("minecraftVersion"));assertFalse(a.data().has("packFormat"));
        assertEquals(24,Geometry.mesh(a).getFirst().vertices().size());
        var rig=new Rig(a.data());assertNotNull(rig.evaluate("",0,new org.joml.Matrix4f(),null,1));
        JsonObject reordered=new JsonObject();var keys=new ArrayList<>(a.data().keySet());Collections.reverse(keys);keys.forEach(k->reordered.add(k,a.data().get(k)));
        assertEquals(a.hash(),ModelAsset.of(reordered).hash());
    }
    @Test void rejectsTraversalAndCyclicBones() throws Exception {
        assertThrows(IllegalArgumentException.class,()->new ModelCompiler().compile(zip(GEOMETRY,null,"../escape"),"x"));
        assertThrows(IllegalArgumentException.class,()->new ModelCompiler().compile(zip(GEOMETRY.replace("\"pivot\"","\"parent\":\"Root\",\"pivot\""),null,null),"x"));
    }
    @Test void expressionsAnimateWithoutExecutingHostCode() throws Exception {
        String animation="""
        {"animations":{"idle":{"animation_length":2,"bones":{"Root":{"position":[0,"math.sin(query.anim_time*90)*16",0]}}}}}
        """;
        var asset=new ModelCompiler().compile(zip(GEOMETRY,animation,null),"x");
        assertEquals(1,new Rig(asset.data()).evaluate("idle",1,new org.joml.Matrix4f(),null,1).world()[0].m31(),1e-5);
        ModelCompiler.validateBundle(asset.data());
        assertThrows(IllegalArgumentException.class,()->new ModelCompiler().compile(zip(GEOMETRY,animation.replace("[0,\"math.sin(query.anim_time*90)*16\",0]","[0,0]"),null),"x"));
        var unsupported=new ModelCompiler().compile(zip(GEOMETRY,animation.replace("math.sin(query.anim_time*90)*16","java.lang.Runtime.exec('unsafe')"),null),"x");
        assertTrue(unsupported.data().getAsJsonArray("compatibility").toString().contains("无法解析"));
        assertEquals(0,new Rig(unsupported.data()).evaluate("idle",1,new org.joml.Matrix4f(),null,1).world()[0].m31());
    }
    @Test void perFaceUvPreservesNegativeSizesMissingFacesAndRotation() throws Exception {
        String geometry=GEOMETRY.replace("[0,0]","{\"north\":{\"uv\":[4,4],\"uv_size\":[-4,8],\"uv_rotation\":90}}");
        var asset=new ModelCompiler().compile(zip(geometry,null,null),"faces");
        assertEquals(4,Geometry.mesh(asset).getFirst().vertices().size());
        var uv=Geometry.uv(asset.data().getAsJsonArray("parts").get(0).getAsJsonObject().getAsJsonArray("cubes").get(0).getAsJsonObject(),16,16);
        assertEquals(1,uv.size());assertArrayEquals(new float[]{.25f,.25f,0,.75f,90},uv.get("north"));
        ModelCompiler.validateBundle(asset.data());
    }
    @Test void identifiesNestedZipAndDeclaredPlayerTextureWithoutProjectileTexture() throws Exception {
        byte[] original=zip(GEOMETRY,null,null);var files=new TreeMap<String,byte[]>();
        try(var in=new ZipInputStream(new ByteArrayInputStream(original))){ZipEntry e;while((e=in.getNextEntry())!=null)files.put("nested/"+e.getName(),in.readAllBytes());}
        files.put("nested/ysm.json","{\"files\":{\"player\":{\"model\":{\"main\":\"models/main.json\"},\"texture\":[{\"uv\":\"textures/blue.png\"}]}}}".getBytes());
        files.put("nested/textures/arrow.png",new byte[]{0});
        var asset=new ModelCompiler().compile(files,"nested");assertEquals(1,asset.textures().size());assertEquals("blue",asset.textures().get(0).getAsJsonObject().get("name").getAsString());
        files.remove("nested/ysm.json");files.remove("nested/textures/arrow.png");files.put("nested/custom.geo.json",files.remove("nested/models/main.json"));
        assertDoesNotThrow(()->new ModelCompiler().compile(files,"auto"));
    }
    @Test void interpolatesPrePostKeyframes() throws Exception {
        String animation="{\"animations\":{\"walk\":{\"animation_length\":1,\"loop\":true,\"bones\":{\"Root\":{\"position\":{\"0\":[0,0,0],\"1\":{\"pre\":[0,16,0],\"post\":[0,16,0]}}}}}}}";
        var a=new ModelCompiler().compile(zip(GEOMETRY,animation,null),"x");
        assertEquals(.5f,new Rig(a.data()).evaluate("walk",.5,new org.joml.Matrix4f(),null,1).world()[0].m31(),1e-5);
    }
    @Test void negativeSourceSizesAreNormalizedAndReported() throws Exception {
        var asset=new ModelCompiler().compile(zip(GEOMETRY.replace("[8,12,4]","[8,-12,4]"),null,null),"signed");
        var cube=asset.data().getAsJsonArray("parts").get(0).getAsJsonObject().getAsJsonArray("cubes").get(0).getAsJsonObject();
        assertEquals(-12,cube.getAsJsonArray("origin").get(1).getAsDouble());assertEquals(12,cube.getAsJsonArray("size").get(1).getAsDouble());
        assertTrue(asset.data().getAsJsonArray("compatibility").toString().contains("负尺寸"));ModelCompiler.validateBundle(asset.data());
    }
    @Test void downloadedBundlesMustValidateEvenWithARecomputedHash() throws Exception {
        var valid=new ModelCompiler().compile(zip(GEOMETRY,null,null),"x");assertDoesNotThrow(()->ModelCompiler.validateBundle(valid.data()));
        var invalid=valid.data().deepCopy();invalid.getAsJsonArray("textures").get(0).getAsJsonObject().addProperty("width",2048);
        ModelAsset.of(invalid);assertThrows(IllegalArgumentException.class,()->ModelCompiler.validateBundle(invalid));
        var duplicate=valid.data().deepCopy();duplicate.getAsJsonArray("bones").add(duplicate.getAsJsonArray("bones").get(0).deepCopy());
        assertThrows(IllegalArgumentException.class,()->ModelCompiler.validateBundle(duplicate));
        var reversed=new LinkedHashMap<String,Object>();reversed.put("z",Map.of("b",2,"a",1));reversed.put("a",3);
        assertEquals("{\"a\":3,\"z\":{\"a\":1,\"b\":2}}",new String(ModelAsset.canonicalBytes(reversed),java.nio.charset.StandardCharsets.UTF_8));
    }
}
