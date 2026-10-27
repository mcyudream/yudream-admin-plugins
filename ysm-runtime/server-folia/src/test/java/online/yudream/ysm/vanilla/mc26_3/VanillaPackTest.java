package online.yudream.ysm.vanilla.mc26_3;

import com.google.gson.*;
import online.yudream.ysm.model.*;
import org.joml.*;
import org.junit.jupiter.api.Test;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

class VanillaPackTest {
    @Test void largeRotatedInflatedCubeKeepsItsGeometryWithinMinecraftJsonBounds() throws Exception {
        var png=new ByteArrayOutputStream();javax.imageio.ImageIO.write(new BufferedImage(16,16,BufferedImage.TYPE_INT_ARGB),"png",png);
        String geometry="""
            {"minecraft:geometry":[{"description":{"texture_width":16,"texture_height":16},"bones":[
              {"name":"body","pivot":[3,5,2],"cubes":[
                {"origin":[-32,-28,-8],"size":[64,56,16],"inflate":0.25,"rotation":[20,30,40],"pivot":[4,5,2],"uv":[0,0]},
                {"origin":[-4,0,-2],"size":[8,12,4],"uv":[0,0]}
              ]}
            ]}]}
            """;
        ModelAsset asset=new ModelCompiler().compile(Map.of("models/main.json",geometry.getBytes(java.nio.charset.StandardCharsets.UTF_8),"textures/skin.png",png.toByteArray()),"bounds");
        Map<String,JsonObject> files=new HashMap<>();
        try(var zip=new ZipInputStream(new ByteArrayInputStream(new VanillaPack().build(List.of(asset))))){
            ZipEntry entry;while((entry=zip.getNextEntry())!=null)if(entry.getName().endsWith(".json"))files.put(entry.getName(),JsonParser.parseString(new String(zip.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject());
        }
        var layers=files.get("assets/ysm/items/"+asset.hash()+"/bone_0.json").getAsJsonObject("model").getAsJsonArray("cases").get(0).getAsJsonObject().getAsJsonObject("model").getAsJsonArray("models");
        var mesh=Geometry.mesh(asset);
        for(int cube=0;cube<2;cube++){
            var element=files.get("assets/ysm/models/"+asset.hash()+"/cube_"+cube+"_0.json").getAsJsonArray("elements").get(0).getAsJsonObject();
            Vector3f from=Rig.vector(element.get("from"),0),to=Rig.vector(element.get("to"),0);
            for(int axis=0;axis<3;axis++){assertTrue(from.get(axis)>=-16&&from.get(axis)<=32);assertTrue(to.get(axis)>=-16&&to.get(axis)<=32);}
            if(cube==1){assertEquals(new Vector3f(4,2,6),from);assertEquals(new Vector3f(12,14,10),to);}
            JsonArray values=layers.get(cube).getAsJsonObject().getAsJsonArray("transformation");Matrix4f transform=new Matrix4f();
            for(int row=0;row<4;row++)for(int col=0;col<4;col++)transform.set(col,row,values.get(row*4+col).getAsFloat());
            List<Vector3f> corners=new ArrayList<>();
            for(int corner=0;corner<8;corner++)corners.add(transform.transformPosition(new Vector3f((corner&1)==0?from.x:to.x,(corner&2)==0?from.y:to.y,(corner&4)==0?from.z:to.z).div(16)));
            for(var vertex:mesh.get(cube).vertices()){
                var expected=new Vector3f(vertex.x()+.5f,vertex.y()+.5f,vertex.z()+.5f);
                assertTrue(corners.stream().anyMatch(v->v.distance(expected)<1e-5),"export must match dynamic-renderer vertices for cube "+cube);
            }
        }
    }
}
