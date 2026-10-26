package online.yudream.ysm.folia.mc26_3;

import online.yudream.ysm.folia.PacketPort;
import io.netty.channel.*;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.inventory.ItemStack;
import org.joml.Matrix4f;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;

/** Version-local reflection boundary. Virtual entities are never registered with a server world. */
public final class Packets26_3 implements PacketPort {
    private final Class<?> entity=type("net.minecraft.world.entity.Entity"),data=type("net.minecraft.network.syncher.SynchedEntityData"),value=type("net.minecraft.network.syncher.SynchedEntityData$DataValue"),serializer=type("net.minecraft.network.syncher.EntityDataSerializer");
    private final Class<?> metadata=type("net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket"),equipment=type("net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket");
    private final Object byteSerializer=field(type("net.minecraft.network.syncher.EntityDataSerializers"),"BYTE",null);
    private final Constructor<?> valueCtor=constructor(value,int.class,serializer,Object.class),metaCtor=constructor(metadata,int.class,List.class),equipmentCtor=constructor(equipment,int.class,List.class);
    private final Constructor<?> removeCtor=constructor(type("net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket"),int[].class);
    private final Class<?> entityType=type("net.minecraft.world.entity.EntityType"),vec=type("net.minecraft.world.phys.Vec3");
    private final Object displayType=field(type("net.minecraft.world.entity.EntityTypes"),"ITEM_DISPLAY",null),zero=field(vec,"ZERO",null);
    private final Constructor<?> displayCtor=constructor(type("net.minecraft.world.entity.Display$ItemDisplay"),entityType,type("net.minecraft.world.level.Level"));
    private final Constructor<?> spawnCtor=constructor(type("net.minecraft.network.protocol.game.ClientboundAddEntityPacket"),int.class,UUID.class,double.class,double.class,double.class,float.class,float.class,entityType,int.class,vec,double.class);
    private final Class<?> positionSync=type("net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket");
    private final Method positionOf=method(positionSync,"of",entity),entityData=method(entity,"getEntityData"),packAll=method(data,"packAll"),packDirty=method(data,"packDirty");
    private final Method packetId=method(metadata,"id"),packed=method(metadata,"packedItems"),valueId=method(value,"id"),valueSerializer=method(value,"serializer"),valueValue=method(value,"value");
    private final Method equipmentId=method(equipment,"getEntity"),slots=method(equipment,"getSlots");
    private final Class<?> pair=type("com.mojang.datafixers.util.Pair");
    private final Method first=method(pair,"getFirst"),pairOf=method(pair,"of",Object.class,Object.class);
    private final Object empty=field(type("net.minecraft.world.item.ItemStack"),"EMPTY",null);
    public Packets26_3(){if(!Bukkit.getMinecraftVersion().equals("26.3"))throw new IllegalStateException("本运行端适配 Minecraft 26.3，请安装对应游戏版本的适配包");}
    @Override public Connection bind(Player player){
        Object handle=call(method(player.getClass(),"getHandle"),player),listener=field(handle.getClass(),"connection",handle),network=field(listener.getClass(),"connection",listener);
        Channel channel=(Channel)field(network.getClass(),"channel",network);return new ViewerConnection(channel);
    }
    @Override public Part part(Player target,ItemStack item,Location at,Matrix4f matrix){
        Object world=call(method(at.getWorld().getClass(),"getHandle"),at.getWorld()),ghost=create(displayCtor,displayType,world);
        ItemDisplay bukkit=(ItemDisplay)call(method(entity,"getBukkitEntity"),ghost);
        bukkit.setItemStack(item);bukkit.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
        bukkit.setInterpolationDuration(2);bukkit.setTeleportDuration(2);bukkit.setDisplayWidth(4);bukkit.setDisplayHeight(4);bukkit.setViewRange(2);
        Object synced=call(entityData,ghost);int id=bukkit.getEntityId();UUID uuid=bukkit.getUniqueId();
        return new Part(){
            Location location;
            public int id(){return id;}
            {transform(at,matrix);}
            public void transform(Location next,Matrix4f transform){
                location=next.clone();call(method(entity,"setPos",double.class,double.class,double.class),ghost,next.getX(),next.getY(),next.getZ());
                bukkit.setTransformationMatrix(transform);bukkit.setInterpolationDelay(0);
            }
            public List<Object> spawn(){return List.of(create(spawnCtor,id,uuid,location.getX(),location.getY(),location.getZ(),0f,0f,displayType,0,zero,0d),create(metaCtor,id,call(packAll,synced)));}
            public List<Object> update(){List<Object> out=new ArrayList<>();Object dirty=call(packDirty,synced);if(dirty!=null)out.add(create(metaCtor,id,dirty));out.add(call(positionOf,null,ghost));return List.copyOf(out);}
        };
    }
    public Object remove(int[] ids){return create(removeCtor,(Object)ids);}
    public Object payload(byte[] bytes){
        Class<?> identifier=type("net.minecraft.resources.Identifier");
        Object id=call(method(identifier,"parse",String.class),null,online.yudream.ysm.protocol.Protocol.CHANNEL);
        Object payload=create(constructor(type("net.minecraft.network.protocol.common.custom.DiscardedPayload"),identifier,byte[].class),id,bytes);
        return create(constructor(type("net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket"),type("net.minecraft.network.protocol.common.custom.CustomPacketPayload")),payload);
    }

    @Override public Object equipment(Player player,Map<org.bukkit.inventory.EquipmentSlot,ItemStack> items){
        Class<?> nmsSlot=type("net.minecraft.world.entity.EquipmentSlot");
        Class<?> craftItem=type(Bukkit.getServer().getClass().getPackageName()+".inventory.CraftItemStack");
        Method copy=method(craftItem,"asNMSCopy",ItemStack.class);List<Object> values=new ArrayList<>();
        items.forEach((slot,item)->{
            String name=switch(slot){case HAND->"MAINHAND";case OFF_HAND->"OFFHAND";default->slot.name();};
            Object nms=Arrays.stream(nmsSlot.getEnumConstants()).filter(v->((Enum<?>)v).name().equals(name)).findFirst().orElseThrow();
            values.add(call(pairOf,null,nms,call(copy,null,item)));
        });return create(equipmentCtor,player.getEntityId(),values);
    }
    private final class ViewerConnection extends ChannelDuplexHandler implements Connection {
        private final class Mask {byte flags;final Map<Object,Object> items=new LinkedHashMap<>();Mask(byte flags,Object packet){this.flags=flags;capture(packet);}void capture(Object packet){for(Object slot:(List<?>)call(slots,packet))items.put(call(first,slot),slot);}}
        private final Channel channel;private final Map<Integer,Mask> masks=new HashMap<>();private final String name="yudream_ysm_"+UUID.randomUUID();private volatile boolean closed;
        ViewerConnection(Channel channel){this.channel=channel;channel.eventLoop().execute(()->channel.pipeline().addBefore("packet_handler",name,this));}
        public void send(Collection<Object> packets){if(closed)return;channel.eventLoop().execute(()->{if(closed||!channel.isActive())return;for(Object packet:packets)channel.write(packet);channel.flush();});}
        private void direct(Object packet){var context=channel.pipeline().context(name);if(context!=null)context.write(packet);}
        public void mask(int id,boolean hidden,byte flags,Object equipmentPacket){
            channel.eventLoop().execute(()->{
                if(closed)return;
                if(hidden){
                    if(masks.containsKey(id))return;Mask state=new Mask(flags,equipmentPacket);masks.put(id,state);
                    direct(create(metaCtor,id,List.of(create(valueCtor,0,byteSerializer,(byte)(flags|32)))));
                    List<Object> hiddenItems=new ArrayList<>();for(Object slot:state.items.keySet())hiddenItems.add(call(pairOf,null,slot,empty));
                    direct(create(equipmentCtor,id,hiddenItems));
                }else{
                    Mask state=masks.remove(id);if(state==null)return;
                    direct(create(metaCtor,id,List.of(create(valueCtor,0,byteSerializer,flags))));direct(equipmentPacket);
                }channel.flush();
            });
        }
        @Override public void write(ChannelHandlerContext ctx,Object packet,ChannelPromise promise)throws Exception {
            if(metadata.isInstance(packet)){
                int id=(Integer)call(packetId,packet);Mask state=masks.get(id);
                if(state!=null){
                    List<Object> replaced=new ArrayList<>();
                    for(Object v:(List<?>)call(packed,packet)){
                        if((Integer)call(valueId,v)==0){state.flags=((Number)call(valueValue,v)).byteValue();replaced.add(create(valueCtor,0,call(valueSerializer,v),(byte)(state.flags|32)));}
                        else replaced.add(v);
                    }packet=create(metaCtor,id,replaced);
                }
            }else if(equipment.isInstance(packet)){
                int id=(Integer)call(equipmentId,packet);Mask state=masks.get(id);
                if(state!=null){
                    state.capture(packet);List<Object> hiddenItems=new ArrayList<>();for(Object slot:(List<?>)call(slots,packet))hiddenItems.add(call(pairOf,null,call(first,slot),empty));
                    packet=create(equipmentCtor,id,hiddenItems);
                }
            }
            super.write(ctx,packet,promise);
        }
        public void close(){channel.eventLoop().execute(()->{if(closed)return;masks.forEach((id,state)->{direct(create(metaCtor,id,List.of(create(valueCtor,0,byteSerializer,state.flags))));direct(create(equipmentCtor,id,List.copyOf(state.items.values())));});masks.clear();channel.flush();if(channel.pipeline().get(name)!=null)channel.pipeline().remove(name);closed=true;});}
    }
    private static Class<?> type(String name){try{return Class.forName(name);}catch(Exception e){throw new IllegalStateException("Unsupported server adapter: "+name,e);}}
    private static Constructor<?> constructor(Class<?> c,Class<?>... args){try{return c.getConstructor(args);}catch(Exception e){throw new IllegalStateException("Unsupported adapter constructor: "+c.getName(),e);}}
    private static Method method(Class<?> c,String name,Class<?>... args){try{return c.getMethod(name,args);}catch(Exception e){throw new IllegalStateException("Unsupported adapter method: "+c.getName()+"."+name,e);}}
    private static Object call(Method method,Object target,Object... args){try{return method.invoke(target,args);}catch(Exception e){throw new IllegalStateException("Model packet adapter failed",e);}}
    private static Object create(Constructor<?> constructor,Object... args){try{return constructor.newInstance(args);}catch(Exception e){throw new IllegalStateException("Model packet creation failed",e);}}
    private static Object field(Class<?> c,String name,Object target){try{return c.getField(name).get(target);}catch(Exception e){throw new IllegalStateException("Unsupported adapter field: "+c.getName()+"."+name,e);}}
}
