package online.yudream.ysm.folia;

import online.yudream.ysm.folia.mc26_3.Packets26_3;
import online.yudream.ysm.model.*;
import online.yudream.ysm.protocol.*;
import io.papermc.paper.command.brigadier.*;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public final class ModelsPlugin extends JavaPlugin implements Listener,PluginMessageListener {
    private final Map<UUID,Viewer> viewers=new ConcurrentHashMap<>();private final Map<String,ModelAsset> assets=new ConcurrentHashMap<>();
    private final Map<UUID,Protocol.Outfit> outfits=new ConcurrentHashMap<>();
    private final ExecutorService worker=Executors.newFixedThreadPool(2,r->{Thread t=new Thread(r,"ysm-bridge");t.setDaemon(true);return t;});
    private final ScheduledExecutorService poller=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"ysm-poll");t.setDaemon(true);return t;});
    private AssetCache cache;private BridgeClient bridge;private PackHost packs;private PacketPort packets;private Actors actors;private ModelsHandbook handbook;
    private final AtomicBoolean polling=new AtomicBoolean();private volatile boolean stopping;private long lastWarning,lastStateRefresh;
    @Override public void onEnable(){
        saveDefaultConfig();
        try{
            packets=new Packets26_3();bridge=new BridgeClient(getConfig().getString("website-url",""),getConfig().getString("bridge-token",""));
            cache=new AssetCache(getDataFolder().toPath().resolve("assets"));
            packs=new PackHost(getDataFolder().toPath().resolve("packs"),getConfig().getString("pack-host.bind","0.0.0.0"),getConfig().getInt("pack-host.port",8767),getConfig().getString("pack-host.public-url",""));
            actors=new Actors(this,packets,(float)getConfig().getDouble("model-scale",.75));
            getServer().getPluginManager().registerEvents(this,this);
            getServer().getMessenger().registerIncomingPluginChannel(this,Protocol.CHANNEL,this);getServer().getMessenger().registerOutgoingPluginChannel(this,Protocol.CHANNEL);
            getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS,event->event.registrar().register("model","玩家模型指南、状态与手动刷新",new BasicCommand(){
                public void execute(CommandSourceStack source,String[] args){if(source.getExecutor() instanceof Player player)player.getScheduler().run(ModelsPlugin.this,t->command(player,args),null);}
                public String permission(){return "ysm.use";}
                public Collection<String> suggest(CommandSourceStack source,String[] args){return List.of("guide","status","refresh","self");}
            }));
            handbook=new ModelsHandbook(this);handbook.enable();
            poller.scheduleWithFixedDelay(this::poll,1,Math.max(1,getConfig().getInt("poll-seconds",5)),TimeUnit.SECONDS);
            for(Player player:getServer().getOnlinePlayers())player.getScheduler().run(this,t->join(player),null);
            getLogger().info("模型桥接已启动；YDA 提供 schema=2 通用模型（兼容 schema 1），Minecraft 26.3 适配在本插件完成。");
        }catch(Exception e){getLogger().severe("模型插件启动失败："+e.getMessage());getServer().getPluginManager().disablePlugin(this);}
    }
    @EventHandler public void onJoin(PlayerJoinEvent e){join(e.getPlayer());}
    private void join(Player player){
        if(stopping||viewers.containsKey(player.getUniqueId()))return;
        Viewer viewer=new Viewer(player,packets.bind(player));viewers.put(player.getUniqueId(),viewer);actors.join(player);
        player.getScheduler().runAtFixedRate(this,t->viewer.tick(),()->viewers.remove(player.getUniqueId(),viewer),1,2);
        player.getScheduler().runDelayed(this,t->refresh(viewer,true),null,40);
        poller.execute(this::poll);
    }
    @EventHandler public void quit(PlayerQuitEvent e){Viewer v=viewers.remove(e.getPlayer().getUniqueId());if(v!=null)v.close();if(actors!=null)actors.quit(e.getPlayer().getUniqueId());outfits.remove(e.getPlayer().getUniqueId());for(Viewer other:viewers.values()){other.ready.remove(e.getPlayer().getUniqueId());if(other.enhanced)sendState(other,List.of(new Protocol.Outfit(e.getPlayer().getUniqueId().toString(),"","","","")));}}
    @EventHandler public void resourceStatus(PlayerResourcePackStatusEvent e){
        Viewer v=viewers.get(e.getPlayer().getUniqueId());if(v==null||v.packId==null||!v.packId.equals(e.getID()))return;
        var pending=v.session.pending();if(pending==null)return;
        String status=e.getStatus().name();
        if(status.equals("SUCCESSFULLY_LOADED")){v.session.complete(pending.revision(),true);UUID previous=v.loadedPackId;v.loadedPackId=v.packId;if(previous!=null)v.player.removeResourcePack(previous);v.lastResult="资源已加载";getLogger().info("MODEL_PACK_LOADED "+e.getPlayer().getUniqueId()+" "+pending.sha1());}
        else if(Set.of("DECLINED","FAILED_DOWNLOAD","INVALID_URL","FAILED_RELOAD","DISCARDED").contains(status)){v.session.complete(pending.revision(),false);v.player.removeResourcePack(v.packId);v.lastResult="刷新失败："+status;}
    }
    private void command(Player p,String[] args){
        if(args.length>0&&args[0].equalsIgnoreCase("guide")){handbook.open(p);return;}
        Viewer v=viewers.get(p.getUniqueId());if(v==null)return;
        if(args.length>0&&args[0].equalsIgnoreCase("refresh")){if(!p.hasPermission("ysm.refresh")){p.sendMessage("你没有刷新模型资源的权限。");return;}refresh(v,false);return;}
        if(args.length>0&&args[0].equalsIgnoreCase("self")){v.selfPreview=args.length>1?args[1].equalsIgnoreCase("on"):!v.selfPreview;p.sendMessage("自身模型预览："+(v.selfPreview?"开启，请切换第三人称查看；/model self off 可关闭":"关闭"));return;}
        p.sendMessage("模型模式："+(v.enhanced?"Fabric 动态更新":"原版资源快照")+"；"+v.lastResult+"；已加载模型 "+(v.session.active()==null?0:v.session.active().models().size())+"；资源包发送 "+v.packRequests+" 次；可使用 /model refresh 手动刷新。");
        p.sendMessage("玩家模型指南：/model guide。");
    }
    private void refresh(Viewer v,boolean initial){
        if(!v.refreshing.compareAndSet(false,true))return;
        if(!initial)v.player.sendMessage("正在检查模型资源更新……");
        CompletableFuture.runAsync(()->{
            try{
                Protocol.Manifest manifest=bridge.manifest(v.player.getUniqueId());
                if(manifest.models().size()>getConfig().getInt("max-snapshot-models",128))throw new IllegalArgumentException("本次快照模型数量超过服务器限制");
                List<ModelAsset> models=new ArrayList<>();for(var a:manifest.models().values()){var asset=cache.get(a.hash(),a.url()).join();assets.put(a.hash(),asset);models.add(asset);}
                var snapshot=packs.build(manifest,models);
                if(!stopping)v.player.getScheduler().run(this,t->{
                    try{
                        if(!viewers.containsKey(v.player.getUniqueId()))return;
                        if(!v.session.begin(snapshot,System.currentTimeMillis(),getConfig().getLong("refresh-cooldown-seconds",60)*1000,initial)){if(!initial)v.player.sendMessage("资源没有变化、正在加载或仍在刷新冷却中。");return;}
                        v.packId=UUID.randomUUID();v.sentAt=System.currentTimeMillis();v.packRequests++;v.lastResult="等待资源包加载";
                        v.player.addResourcePack(v.packId,snapshot.url(),HexFormat.of().parseHex(snapshot.sha1()),"加载服务器玩家模型",false);
                        getLogger().info("MODEL_PACK_SENT "+v.player.getUniqueId()+" "+snapshot.sha1()+" initial="+initial);
                    }finally{v.refreshing.set(false);}
                },()->v.refreshing.set(false));
            }catch(Exception e){v.refreshing.set(false);if(!stopping)v.player.getScheduler().run(this,t->{v.lastResult="资源获取失败，保留当前外观";if(!initial)v.player.sendMessage(v.lastResult);},null);warn("模型资源获取失败");}
        },worker);
    }
    private void poll(){
        if(stopping||bridge==null||viewers.isEmpty()||!polling.compareAndSet(false,true))return;
        try{
            List<UUID> players=List.copyOf(viewers.keySet());Map<UUID,Protocol.Outfit> fresh=new HashMap<>();
            for(int i=0;i<players.size();i+=128){var state=bridge.state(players.subList(i,Math.min(i+128,players.size())));if(state.protocol()!=Protocol.MAJOR)throw new IllegalStateException("桥接协议不兼容");for(var outfit:state.outfits())fresh.put(UUID.fromString(outfit.playerUuid()),outfit);}
            List<Protocol.Outfit> changed=new ArrayList<>();fresh.forEach((id,o)->{var old=outfits.put(id,o);if(old==null||!old.hash().equals(o.hash())||!old.modelId().equals(o.modelId())||!old.texture().equals(o.texture()))changed.add(o);});
            for(UUID old:new HashSet<>(outfits.keySet()))if(!fresh.containsKey(old)){outfits.remove(old);changed.add(new Protocol.Outfit(old.toString(),"","","",""));}
            boolean renew=System.currentTimeMillis()-lastStateRefresh>60000;if(renew)lastStateRefresh=System.currentTimeMillis();
            if(renew||!changed.isEmpty())for(Viewer v:viewers.values())if(v.enhanced)sendState(v,renew?List.copyOf(outfits.values()):changed);
        }catch(Exception e){warn("网站模型状态暂不可用，继续使用最近一次外观");}finally{polling.set(false);}
    }
    private void sendState(Viewer viewer,List<Protocol.Outfit> values){
        for(int i=0;i<values.size();i+=16){var state=new Protocol.State(Protocol.MAJOR,List.copyOf(values.subList(i,Math.min(i+16,values.size()))));viewer.player.getScheduler().run(this,t->viewer.player.sendPluginMessage(this,Protocol.CHANNEL,Wire.encode("state",state)),null);}
    }
    @Override public void onPluginMessageReceived(String channel,Player player,byte[] bytes){
        Viewer v=viewers.get(player.getUniqueId());if(v==null)return;
        try{
            var m=Wire.decode(bytes);
            if(m.type().equals("hello")){
                var hello=ModelAsset.JSON.fromJson(m.data(),Protocol.Hello.class);if(hello.major()!=Protocol.MAJOR||hello.capabilities()==null||!hello.capabilities().contains("hot-assets"))return;
                if(!v.enhanced){v.enhanced=true;sendState(v,List.copyOf(outfits.values()));getLogger().info("MODEL_FABRIC_CONNECTED "+player.getUniqueId());}
            }else if(m.type().equals("ready")&&v.enhanced){
                var ready=ModelAsset.JSON.fromJson(m.data(),Protocol.Ready.class);UUID target=UUID.fromString(ready.playerUuid());var current=outfits.get(target);
                if(current!=null&&!current.hash().isEmpty()&&current.hash().equals(ready.hash())){
                    v.ready.put(target,ready.hash());v.hide(target);player.sendPluginMessage(this,Protocol.CHANNEL,Wire.encode("activate",ready));
                }
            }
        }catch(RuntimeException ignored){/* Invalid or incompatible clients keep the vanilla fallback. */}
    }
    private synchronized void warn(String message){if(System.currentTimeMillis()-lastWarning>30000){lastWarning=System.currentTimeMillis();getLogger().warning(message);}}
    @Override public void onDisable(){
        if(handbook!=null)handbook.close();
        stopping=true;poller.shutdownNow();worker.shutdownNow();viewers.values().forEach(Viewer::close);viewers.clear();outfits.clear();assets.clear();
        if(actors!=null)actors.clear();if(cache!=null)cache.close();if(bridge!=null)bridge.close();if(packs!=null)packs.close();
    }
    private final class Viewer {
        final Player player;final PacketPort.Connection connection;final ViewerSession session=new ViewerSession();final AtomicBoolean refreshing=new AtomicBoolean();
        final Map<UUID,String> ready=new ConcurrentHashMap<>();final Map<UUID,Actors.Shown> shown=new ConcurrentHashMap<>();
        volatile boolean enhanced;boolean selfPreview;UUID packId,loadedPackId;long sentAt;int packRequests;String lastResult="等待初次资源加载";
        Viewer(Player player,PacketPort.Connection connection){this.player=player;this.connection=connection;}
        void tick(){
            var pending=session.pending();if(pending!=null&&System.currentTimeMillis()-sentAt>getConfig().getLong("resource-pack-timeout-seconds",120)*1000){session.complete(pending.revision(),false);player.removeResourcePack(packId);lastResult="资源加载超时，可手动重试";}
            Location at=player.getLocation();Set<UUID> visible=new HashSet<>();
            for(var entry:actors.targets.entrySet()){
                UUID id=entry.getKey();var presence=entry.getValue().presence;var outfit=outfits.get(id);
                if(id.equals(player.getUniqueId())&&!selfPreview)continue;
                if(presence==null||outfit==null||outfit.modelId().isEmpty()||!presence.visible()||presence.at().getWorld()!=at.getWorld()||presence.at().distanceSquared(at)>Math.pow(getConfig().getDouble("view-distance",64),2)||!player.canSee(presence.player()))continue;
                if(enhanced&&ready.containsKey(id))continue;
                var resource=session.model(outfit.modelId()).orElse(null);if(resource==null)continue;
                var asset=assets.get(resource.hash());if(asset==null)continue;
                String texture=asset.textures().asList().stream().anyMatch(t->t.getAsJsonObject().get("name").getAsString().equals(outfit.texture()))?outfit.texture():asset.textures().get(0).getAsJsonObject().get("name").getAsString();
                var variant=actors.variant(id,asset,texture);if(variant==null||variant.frame==null)continue;visible.add(id);var frame=variant.frame;var old=shown.get(id);
                if(old==null||old.variant()!=variant||!Arrays.equals(old.frame().ids(),frame.ids())){
                    hide(id);connection.mask(presence.entityId(),true,presence.flags(),presence.equipment());connection.send(frame.spawn());
                }else if(old.frame().sequence()!=frame.sequence())connection.send(frame.update());
                shown.put(id,new Actors.Shown(variant,frame,presence));
            }
            for(UUID id:new HashSet<>(shown.keySet()))if(!visible.contains(id))hide(id);
        }
        void hide(UUID id){
            var old=shown.remove(id);if(old==null)return;connection.send(List.of(packets.remove(old.frame().ids())));
            var target=actors.targets.get(id);var p=target==null?old.presence():target.presence;if(p==null)p=old.presence();
            connection.mask(old.presence().entityId(),false,p.flags(),p.equipment());
        }
        void close(){if(enhanced)connection.send(List.of(packets.payload(Wire.encode("reset",Map.of()))));for(var old:shown.values())connection.send(List.of(packets.remove(old.frame().ids())));shown.clear();connection.close();}
    }
}
