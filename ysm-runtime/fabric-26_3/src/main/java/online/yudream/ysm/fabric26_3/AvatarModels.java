package online.yudream.ysm.fabric26_3;

import online.yudream.ysm.model.*;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import org.joml.*;
import java.lang.Math;
import java.util.*;

/** Render adapter: standard Blaze3D submission also works with the game's Vulkan backend. */
public final class AvatarModels {
    private static final Map<UUID,Cursor> cursors=new HashMap<>();
    private static final class Cursor {ClientModel model;String clip="";float changed,last;double elapsed;Rig.LocalPose[] pose,from;}
    static void clear(){cursors.clear();}
    public static boolean submit(AvatarRenderState state,PoseStack stack,SubmitNodeCollector collector){
        var mc=Minecraft.getInstance();if(mc.level==null||ModelsClient.INSTANCE==null)return false;
        var entity=mc.level.getEntity(state.id);if(entity==null)return false;
        var active=ModelsClient.INSTANCE.active(entity.getUUID()).orElse(null);if(active==null)return false;
        if(state.isInvisible||state.isSpectator||state.deathTime>0)return false;
        ClientModel model=active.resource();cursors.keySet().removeIf(id->mc.level.getPlayerByUUID(id)==null);
        Cursor c=cursors.computeIfAbsent(entity.getUUID(),id->new Cursor());
        if(c.model!=model){c.model=model;c.clip="";c.pose=null;c.from=null;c.last=state.ageInTicks;}
        String motion=state.isVisuallySwimming?"swim":state.isFallFlying?"fly":state.isCrouching?"sneak":state.walkAnimationSpeed>.01f?(entity.isSprinting()?"run":"walk"):"idle";
        String clip=model.rig.select(motion);
        if(!clip.equals(c.clip)){c.clip=clip;c.elapsed=0;c.from=c.pose;c.changed=state.ageInTicks;}
        c.elapsed+=Math.max(0,Math.min((state.ageInTicks-c.last)/20, .25f));c.last=state.ageInTicks;
        var animation=model.rig.clips.get(clip);double time=animation==null?0:animation.at(c.elapsed,false);
        float blend=c.from==null?1:Math.clamp((state.ageInTicks-c.changed)/6,0,1);blend=blend*blend*(3-2*blend);
        var evaluated=model.rig.evaluate(clip,time,new Matrix4f().rotateY((float)Math.toRadians(180-state.bodyRot)).scale(.75f),c.from,blend);c.pose=evaluated.local();if(blend>=1)c.from=null;
        Matrix4f[] matrices=evaluated.world();int light=state.lightCoords;int overlay=OverlayTexture.pack(0, state.hasRedOverlay);
        collector.submitCustomGeometry(stack,RenderTypes.entityTranslucent(model.texture(active.outfit().texture())),(pose,vertices)->{
            Vector3f p=new Vector3f(),n=new Vector3f();Matrix3f normal=new Matrix3f();
            for(var cube:model.cubes){
                Matrix4f transform=matrices[model.rig.partBones[cube.part()]];transform.normal(normal);
                for(var v:cube.vertices()){
                    transform.transformPosition(p.set(v.x(),v.y(),v.z()));normal.transform(n.set(v.nx(),v.ny(),v.nz())).normalize();
                    vertices.addVertex(pose,p.x,p.y,p.z).setColor(255,255,255,255).setUv(v.u(),v.v()).setOverlay(overlay).setLight(light).setNormal(pose,n.x,n.y,n.z);
                }
            }
        });return true;
    }
}
