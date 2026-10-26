package online.yudream.ysm.folia;

import online.yudream.ysm.model.*;
import online.yudream.ysm.protocol.Protocol;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.joml.Matrix4f;
import java.util.*;
import java.util.concurrent.*;

/** Target-owned animation tasks publish immutable packets; each viewer sends its selected version. */
final class Actors {
    record Presence(Player player,Location at,int entityId,byte flags,Object equipment,boolean visible) {}
    record Frame(long sequence,int[] ids,List<Object> spawn,List<Object> update) {}
    record Key(String hash,String texture) {}
    record Shown(Variant variant,Frame frame,Presence presence) {}
    private final JavaPlugin plugin;private final PacketPort port;private final float scale;
    final Map<UUID,Target> targets=new ConcurrentHashMap<>();
    Actors(JavaPlugin plugin,PacketPort port,float scale){this.plugin=plugin;this.port=port;this.scale=scale;}
    void join(Player player){Target target=new Target(player);targets.put(player.getUniqueId(),target);player.getScheduler().runAtFixedRate(plugin,t->target.tick(),()->targets.remove(player.getUniqueId(),target),1,1);}
    void quit(UUID player){targets.remove(player);}
    Variant variant(UUID player,ModelAsset asset,String texture){
        Target target=targets.get(player);if(target==null||target.variants.size()>=16&&!target.variants.containsKey(new Key(asset.hash(),texture)))return null;
        var variant=target.variants.computeIfAbsent(new Key(asset.hash(),texture),key->new Variant(target,asset,texture));variant.lastUsed=System.nanoTime();return variant;
    }
    void clear(){targets.clear();}
    private static Map<EquipmentSlot,ItemStack> equipment(Player p){Map<EquipmentSlot,ItemStack> out=new EnumMap<>(EquipmentSlot.class);for(EquipmentSlot slot:List.of(EquipmentSlot.HAND,EquipmentSlot.OFF_HAND,EquipmentSlot.HEAD,EquipmentSlot.CHEST,EquipmentSlot.LEGS,EquipmentSlot.FEET)){ItemStack item=p.getEquipment().getItem(slot);out.put(slot,item.clone());}return Map.copyOf(out);}
    static Map<EquipmentSlot,ItemStack> emptyEquipment(){Map<EquipmentSlot,ItemStack> out=new EnumMap<>(EquipmentSlot.class);for(EquipmentSlot slot:List.of(EquipmentSlot.HAND,EquipmentSlot.OFF_HAND,EquipmentSlot.HEAD,EquipmentSlot.CHEST,EquipmentSlot.LEGS,EquipmentSlot.FEET))out.put(slot,new ItemStack(Material.AIR));return out;}
    final class Target {
        final Player player;final Map<Key,Variant> variants=new ConcurrentHashMap<>();volatile Presence presence;long ticks;Location previous;String motion="idle";final MotionState moving=new MotionState();
        Target(Player player){this.player=player;}
        void tick(){
            if(!targets.containsKey(player.getUniqueId()))return;ticks++;
            Location at=player.getLocation();boolean reset=previous==null||previous.getWorld()!=at.getWorld()||previous.distanceSquared(at)>256;
            double delta=reset?0:Math.hypot(at.getX()-previous.getX(),at.getZ()-previous.getZ());
            motion=player.isSwimming()?"swim":player.isGliding()||player.isFlying()?"fly":player.isSneaking()?"sneak":moving.update(ticks,delta,player.isOnGround(),player.isFlying(),player.isSprinting(),reset);
            previous=at.clone();byte flags=(byte)((player.getFireTicks()>0?1:0)|(player.isSneaking()?2:0)|(player.isSprinting()?8:0)|(player.isSwimming()?16:0)|(player.isInvisible()?32:0)|(player.isGlowing()?64:0)|(player.isGliding()?128:0));
            presence=new Presence(player,at,player.getEntityId(),flags,port.equipment(player,equipment(player)),!player.isInvisible()&&player.getGameMode()!=GameMode.SPECTATOR);
            if(ticks%2!=0)return;
            for(var entry:variants.entrySet()){
                Variant v=entry.getValue();if(System.nanoTime()-v.lastUsed>30_000_000_000L){variants.remove(entry.getKey(),v);continue;}
                try{v.update(at,reset);}catch(RuntimeException e){variants.remove(entry.getKey(),v);plugin.getLogger().warning("模型动画适配失败："+e.getClass().getSimpleName());}
            }
        }
    }
    final class Variant {
        final Target target;final ModelAsset asset;final String texture;final Rig rig;final List<PacketPort.Part> parts=new ArrayList<>();volatile Frame frame;volatile long lastUsed=System.nanoTime();
        String clip="";double elapsed;Rig.LocalPose[] previousPose,blendFrom;long blendAt;World world;
        Variant(Target target,ModelAsset asset,String texture){this.target=target;this.asset=asset;this.texture=texture;this.rig=new Rig(asset.data());}
        void update(Location at,boolean reset){
            String next=rig.select(target.motion);if(!next.equals(clip)){clip=next;elapsed=0;blendFrom=previousPose;blendAt=target.ticks;}
            elapsed+=.1;Rig.Clip animation=rig.clips.get(clip);double time=animation==null?0:animation.at(elapsed,false);
            float blend=blendFrom==null?1:Math.min(1,(target.ticks-blendAt)/6f);blend=blend*blend*(3-2*blend);
            Rig.Evaluation evaluated=rig.evaluate(clip,time,new Matrix4f().rotateY((float)Math.toRadians(180-at.getYaw())).scale(scale),blendFrom,blend);
            previousPose=evaluated.local();if(blend>=1)blendFrom=null;
            boolean rebuild=world!=at.getWorld()||parts.isEmpty();if(rebuild){parts.clear();world=at.getWorld();}
            Location origin=at.clone();origin.setYaw(0);origin.setPitch(0);
            for(int i=0;i<rig.partBones.length;i++){
                Matrix4f matrix=new Matrix4f(evaluated.world()[rig.partBones[i]]).rotateY((float)Math.PI);
                if(rebuild){
                    int index=i;ItemStack item=new ItemStack(Material.PAPER);
                    item.editMeta(meta->{meta.setItemModel(Objects.requireNonNull(NamespacedKey.fromString(asset.item(index))));var custom=meta.getCustomModelDataComponent();custom.setStrings(List.of(texture));meta.setCustomModelDataComponent(custom);});
                    parts.add(port.part(target.player,item,origin,matrix));
                }else parts.get(i).transform(origin,matrix);
            }
            List<Object> spawn=new ArrayList<>(),update=new ArrayList<>();for(var part:parts){spawn.addAll(part.spawn());update.addAll(part.update());}
            frame=new Frame(target.ticks,parts.stream().mapToInt(PacketPort.Part::id).toArray(),List.copyOf(spawn),List.copyOf(update));
        }
    }
}
