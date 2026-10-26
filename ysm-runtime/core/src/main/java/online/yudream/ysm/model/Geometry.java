package online.yudream.ysm.model;

import com.google.gson.*;
import org.joml.*;
import java.lang.Math;
import java.util.*;

/** Shared cube transforms and UVs used by the vanilla exporter and dynamic renderer. */
public final class Geometry {
    private Geometry() {}
    public static Matrix4f local(JsonObject bone, JsonObject cube) {
        Vector3f bp=Rig.bedrock(Rig.vector(bone.get("pivot"),0));
        Vector3f cp=Rig.bedrock(Rig.vector(cube.has("pivot")?cube.get("pivot"):bone.get("pivot"),0));
        Vector3f center=Rig.vector(cube.get("origin"),0).add(Rig.vector(cube.get("size"),0).mul(.5f)); Rig.bedrock(center);
        return new Matrix4f().translation(new Vector3f(cp).sub(bp).div(16)).rotate(Rig.rotation(Rig.vector(cube.get("rotation"),0)))
                .translate(new Vector3f(center).sub(cp).div(16));
    }
    public static Map<String,float[]> uv(JsonObject c,int w,int h) {
        JsonArray uv=c.getAsJsonArray("uv");float u=uv.get(0).getAsFloat(),v=uv.get(1).getAsFloat();
        Vector3f s=Rig.vector(c.get("size"),0);float x=s.x,y=s.y,z=s.z;
        Map<String,float[]> m=new LinkedHashMap<>();
        m.put("north",new float[]{u+z,v+z,u+z+x,v+z+y}); m.put("south",new float[]{u+2*z+x,v+z,u+2*z+2*x,v+z+y});
        m.put("east",new float[]{u,v+z,u+z,v+z+y});m.put("west",new float[]{u+z+x,v+z,u+2*z+x,v+z+y});
        m.put("up",new float[]{u+z,v,u+z+x,v+z});m.put("down",new float[]{u+z+x,v+z,u+z+2*x,v});
        if(c.has("mirror")&&c.get("mirror").getAsBoolean()) {float[] a=m.get("east");m.put("east",m.get("west"));m.put("west",a);m.replaceAll((k,f)->new float[]{f[2],f[1],f[0],f[3]});}
        m.values().forEach(f->{f[0]/=w;f[2]/=w;f[1]/=h;f[3]/=h;});return m;
    }
    public record Vertex(float x,float y,float z,float u,float v,float nx,float ny,float nz) {}
    public record Cube(int part,List<Vertex> vertices) {}
    public static List<Cube> mesh(ModelAsset asset) {
        Map<String,JsonObject> bones=new HashMap<>();asset.data().getAsJsonArray("bones").forEach(b->bones.put(b.getAsJsonObject().get("name").getAsString(),b.getAsJsonObject()));
        int w=asset.textures().get(0).getAsJsonObject().get("width").getAsInt(),h=asset.textures().get(0).getAsJsonObject().get("height").getAsInt();
        List<Cube> result=new ArrayList<>();int part=0;
        for(JsonElement pe:asset.data().getAsJsonArray("parts")) {
            JsonObject p=pe.getAsJsonObject();
            for(JsonElement ce:p.getAsJsonArray("cubes")) {
                JsonObject c=ce.getAsJsonObject(); Matrix4f m=local(bones.get(p.get("bone").getAsString()),c);
                float inflate=c.has("inflate")?c.get("inflate").getAsFloat():0; Vector3f s=Rig.vector(c.get("size"),0).add(2*inflate,2*inflate,2*inflate).div(32);
                Map<String,float[]> uv=uv(c,w,h);List<Vertex> vertices=new ArrayList<>(24);
                face(vertices,m,uv.get("north"),new float[][]{{s.x,s.y,-s.z},{s.x,-s.y,-s.z},{-s.x,-s.y,-s.z},{-s.x,s.y,-s.z}},0,0,-1);
                face(vertices,m,uv.get("south"),new float[][]{{-s.x,s.y,s.z},{-s.x,-s.y,s.z},{s.x,-s.y,s.z},{s.x,s.y,s.z}},0,0,1);
                face(vertices,m,uv.get("east"),new float[][]{{s.x,s.y,s.z},{s.x,-s.y,s.z},{s.x,-s.y,-s.z},{s.x,s.y,-s.z}},1,0,0);
                face(vertices,m,uv.get("west"),new float[][]{{-s.x,s.y,-s.z},{-s.x,-s.y,-s.z},{-s.x,-s.y,s.z},{-s.x,s.y,s.z}},-1,0,0);
                face(vertices,m,uv.get("up"),new float[][]{{s.x,s.y,s.z},{s.x,s.y,-s.z},{-s.x,s.y,-s.z},{-s.x,s.y,s.z}},0,1,0);
                face(vertices,m,uv.get("down"),new float[][]{{s.x,-s.y,-s.z},{s.x,-s.y,s.z},{-s.x,-s.y,s.z},{-s.x,-s.y,-s.z}},0,-1,0);
                result.add(new Cube(part,List.copyOf(vertices)));
            }part++;
        }return List.copyOf(result);
    }
    private static void face(List<Vertex> out,Matrix4f m,float[] uv,float[][] xyz,float nx,float ny,float nz) {
        Vector3f n=m.transformDirection(new Vector3f(nx,ny,nz));
        for(int i=0;i<4;i++){Vector3f p=m.transformPosition(new Vector3f(xyz[i]));out.add(new Vertex(p.x,p.y,p.z,i<2?uv[0]:uv[2],i==0||i==3?uv[1]:uv[3],n.x,n.y,n.z));}
    }
}
