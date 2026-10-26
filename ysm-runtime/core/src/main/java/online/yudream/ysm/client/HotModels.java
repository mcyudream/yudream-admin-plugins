package online.yudream.ysm.client;

import online.yudream.ysm.protocol.Protocol;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Version-free hot asset lifecycle. A prepared resource becomes visible only after server activation. */
public final class HotModels<R extends AutoCloseable> implements AutoCloseable {
    public record Active<R>(Protocol.Outfit outfit,R resource) {}
    private final Function<Protocol.Asset,CompletableFuture<R>> loader;
    private final Consumer<Runnable> dispatch;private final Consumer<Protocol.Ready> ready;
    private final Map<String,Protocol.Outfit> desired=new HashMap<>();private final Map<String,Active<R>> active=new HashMap<>();
    private final Map<String,R> prepared=new LinkedHashMap<>();private final Set<String> loading=new HashSet<>();private final Map<String,Long> retryAt=new HashMap<>();
    private boolean closed;
    public HotModels(Function<Protocol.Asset,CompletableFuture<R>> loader,Consumer<Runnable> dispatch,Consumer<Protocol.Ready> ready){this.loader=loader;this.dispatch=dispatch;this.ready=ready;}
    public synchronized void update(Protocol.State state){
        if(closed||state.protocol()!=Protocol.MAJOR)return;
        for(var o:state.outfits()){
            if(o.modelId().isEmpty()){desired.remove(o.playerUuid());active.remove(o.playerUuid());continue;}
            desired.put(o.playerUuid(),o);var old=active.get(o.playerUuid());
            if(old!=null&&old.outfit().hash().equals(o.hash()))active.put(o.playerUuid(),new Active<>(o,old.resource()));
            else request(o);
        }
    }
    public synchronized void retry(){if(!closed)for(var o:List.copyOf(desired.values())){var a=active.get(o.playerUuid());if(a==null||!a.outfit().hash().equals(o.hash()))request(o);}}
    private void request(Protocol.Outfit o){
        if(prepared.containsKey(o.hash())){ready.accept(new Protocol.Ready(o.playerUuid(),o.hash()));return;}
        if(retryAt.getOrDefault(o.hash(),0L)>System.currentTimeMillis()||!loading.add(o.hash()))return;
        if(prepared.size()+loading.size()>64){
            Set<String> used=new HashSet<>();desired.values().forEach(d->used.add(d.hash()));active.values().forEach(a->used.add(a.outfit().hash()));
            var disposable=prepared.entrySet().stream().filter(e->!used.contains(e.getKey())).findFirst();
            if(disposable.isPresent()){var entry=disposable.get();prepared.remove(entry.getKey());release(entry.getValue());}
            else{loading.remove(o.hash());return;}
        }
        CompletableFuture<R> download;
        try{download=Objects.requireNonNull(loader.apply(new Protocol.Asset(o.modelId(),o.hash(),o.url())));}
        catch(RuntimeException error){loading.remove(o.hash());retryAt.put(o.hash(),System.currentTimeMillis()+5000);return;}
        download.whenComplete((resource,error)->dispatch.accept(()->{
            synchronized(this){
                loading.remove(o.hash());if(closed){if(resource!=null)release(resource);return;}
                if(error!=null||resource==null){if(resource!=null)release(resource);retryAt.put(o.hash(),System.currentTimeMillis()+5000);return;}
                prepared.put(o.hash(),resource);retryAt.remove(o.hash());
                for(var current:desired.values())if(current.hash().equals(o.hash()))ready.accept(new Protocol.Ready(current.playerUuid(),current.hash()));
            }
        }));
    }
    public synchronized void activate(Protocol.Ready activation){var current=desired.get(activation.playerUuid());var resource=prepared.get(activation.hash());if(!closed&&current!=null&&current.hash().equals(activation.hash())&&resource!=null)active.put(activation.playerUuid(),new Active<>(current,resource));}
    public synchronized Optional<Active<R>> active(String player){return Optional.ofNullable(active.get(player));}
    private static void release(AutoCloseable resource){try{resource.close();}catch(Exception ignored){}}
    public synchronized void close(){closed=true;prepared.values().forEach(HotModels::release);prepared.clear();active.clear();desired.clear();loading.clear();retryAt.clear();}
}
