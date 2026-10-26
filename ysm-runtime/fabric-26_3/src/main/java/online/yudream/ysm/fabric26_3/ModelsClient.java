package online.yudream.ysm.fabric26_3;

import online.yudream.ysm.client.HotModels;
import online.yudream.ysm.model.*;
import online.yudream.ysm.protocol.*;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.*;
import net.fabricmc.fabric.api.client.networking.v1.*;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import org.slf4j.*;
import java.util.*;
import java.util.concurrent.*;

/** This module is the only client layer that imports Minecraft/Fabric classes. */
public final class ModelsClient implements ClientModInitializer {
    public static final Logger LOG=LoggerFactory.getLogger("yudream-models");
    public static ModelsClient INSTANCE;
    private final ExecutorService decode=Executors.newFixedThreadPool(2,r->{Thread t=new Thread(r,"ysm-decode");t.setDaemon(true);return t;});
    private AssetCache cache;private HotModels<ClientModel> models;private int ticks;
    public record Payload(byte[] bytes) implements CustomPacketPayload {
        static final Type<Payload> TYPE=new Type<>(Identifier.parse(Protocol.CHANNEL));
        static final StreamCodec<RegistryFriendlyByteBuf,Payload> CODEC=new StreamCodec<>(){
            public Payload decode(RegistryFriendlyByteBuf buffer){int n=buffer.readableBytes();if(n>32700)throw new IllegalArgumentException("Model message too large");byte[] bytes=new byte[n];buffer.readBytes(bytes);return new Payload(bytes);}
            public void encode(RegistryFriendlyByteBuf buffer,Payload value){if(value.bytes.length>32700)throw new IllegalArgumentException("Model message too large");buffer.writeBytes(value.bytes);}
        };
        public Type<Payload> type(){return TYPE;}
    }
    @Override public void onInitializeClient(){
        INSTANCE=this;cache=new AssetCache(FabricLoader.getInstance().getGameDir().resolve("ysm-cache"));
        PayloadTypeRegistry.clientboundPlay().register(Payload.TYPE,Payload.CODEC);PayloadTypeRegistry.serverboundPlay().register(Payload.TYPE,Payload.CODEC);
        ClientPlayNetworking.registerGlobalReceiver(Payload.TYPE,(payload,context)->context.client().execute(()->receive(payload.bytes)));
        ClientPlayConnectionEvents.JOIN.register((handler,sender,client)->{
            startSession(client);
            send("hello",new Protocol.Hello(Protocol.MAJOR,Protocol.MINOR,Set.of("hot-assets")));
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler,client)->closeSession());
        ClientTickEvents.END_CLIENT_TICK.register(client->{if(++ticks%100==0&&client.getConnection()!=null&&ClientPlayNetworking.canSend(Payload.TYPE)){if(models==null)startSession(client);models.retry();send("hello",new Protocol.Hello(Protocol.MAJOR,Protocol.MINOR,Set.of("hot-assets")));}});
        ClientLifecycleEvents.CLIENT_STOPPING.register(client->{closeSession();cache.close();decode.shutdownNow();});
    }
    private void startSession(Minecraft client){
        closeSession();models=new HotModels<>(a->cache.get(a.hash(),a.url()).thenApplyAsync(ClientModel::decode,decode).thenCompose(decoded->{
            var result=new CompletableFuture<ClientModel>();client.execute(()->{try{result.complete(decoded.upload(client));}catch(Throwable e){decoded.close();result.completeExceptionally(e);}});return result;
        }),client::execute,ready->send("ready",ready));
    }
    private void closeSession(){if(models!=null){models.close();models=null;}AvatarModels.clear();}
    private void receive(byte[] bytes){
        if(models==null)return;
        try{var message=Wire.decode(bytes);
            if(message.type().equals("state"))models.update(ModelAsset.JSON.fromJson(message.data(),Protocol.State.class));
            else if(message.type().equals("reset")){closeSession();LOG.info("YSM_RESET");}
            else if(message.type().equals("activate")){var ready=ModelAsset.JSON.fromJson(message.data(),Protocol.Ready.class);models.activate(ready);LOG.info("YSM_ACTIVATED {} {}",ready.playerUuid(),ready.hash());}
        }catch(RuntimeException e){LOG.warn("Invalid model state; retaining current appearance");}
    }
    private void send(String type,Object value){if(Minecraft.getInstance().getConnection()!=null)ClientPlayNetworking.send(new Payload(Wire.encode(type,value)));}
    public Optional<HotModels.Active<ClientModel>> active(UUID player){return models==null?Optional.empty():models.active(player.toString());}
}
