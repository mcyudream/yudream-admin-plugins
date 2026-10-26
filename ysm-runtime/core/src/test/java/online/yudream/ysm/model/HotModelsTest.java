package online.yudream.ysm.model;

import online.yudream.ysm.client.HotModels;
import online.yudream.ysm.protocol.Protocol;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class HotModelsTest {
    static final class Resource implements AutoCloseable {boolean closed;public void close(){closed=true;}}
    private Protocol.Outfit outfit(String hash){return new Protocol.Outfit("player","model",hash,"blue","https://example.invalid/asset");}
    @Test void staleDownloadsAndReceiptsCannotReplaceTheCurrentAsset(){
        Map<String,CompletableFuture<Resource>> jobs=new HashMap<>();List<Protocol.Ready> ready=new ArrayList<>();
        var models=new HotModels<Resource>(a->jobs.computeIfAbsent(a.hash(),k->new CompletableFuture<>()),Runnable::run,ready::add);
        models.update(new Protocol.State(1,List.of(outfit("old"))));Resource old=new Resource();jobs.get("old").complete(old);
        assertTrue(models.active("player").isEmpty());models.activate(ready.getFirst());assertSame(old,models.active("player").orElseThrow().resource());
        models.update(new Protocol.State(1,List.of(outfit("middle"))));models.update(new Protocol.State(1,List.of(outfit("new"))));
        jobs.get("new").complete(new Resource());var activation=ready.getLast();models.activate(activation);
        jobs.get("middle").complete(new Resource());models.activate(new Protocol.Ready("player","middle"));
        assertEquals("new",models.active("player").orElseThrow().outfit().hash());
        models.close();assertTrue(old.closed);
    }
    @Test void failedRefreshKeepsThePreviousModelAndClearIsImmediate(){
        Map<String,CompletableFuture<Resource>> jobs=new HashMap<>();List<Protocol.Ready> ready=new ArrayList<>();
        var models=new HotModels<Resource>(a->jobs.computeIfAbsent(a.hash(),k->new CompletableFuture<>()),Runnable::run,ready::add);
        models.update(new Protocol.State(1,List.of(outfit("one"))));jobs.get("one").complete(new Resource());models.activate(ready.getFirst());
        models.update(new Protocol.State(1,List.of(outfit("broken"))));jobs.get("broken").completeExceptionally(new IllegalStateException());
        assertEquals("one",models.active("player").orElseThrow().outfit().hash());
        models.update(new Protocol.State(1,List.of(new Protocol.Outfit("player","","","",""))));assertTrue(models.active("player").isEmpty());models.close();
    }
    @Test void synchronousLoaderFailuresDoNotEscapeAndOutstandingLoadsShareTheBudget(){
        var broken=new HotModels<Resource>(a->{throw new IllegalStateException();},Runnable::run,r->{});
        assertDoesNotThrow(()->broken.update(new Protocol.State(1,List.of(outfit("bad")))));broken.close();
        List<CompletableFuture<Resource>> jobs=new ArrayList<>();
        var bounded=new HotModels<Resource>(a->{var future=new CompletableFuture<Resource>();jobs.add(future);return future;},Runnable::run,r->{});
        List<Protocol.Outfit> many=new ArrayList<>();for(int i=0;i<100;i++)many.add(new Protocol.Outfit("player"+i,"model","hash"+i,"blue","url"));
        bounded.update(new Protocol.State(1,many));assertEquals(64,jobs.size());bounded.close();
        Resource late=new Resource();jobs.getFirst().complete(late);assertTrue(late.closed);
    }
}
