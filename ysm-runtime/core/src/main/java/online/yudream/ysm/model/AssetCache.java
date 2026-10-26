package online.yudream.ysm.model;

import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.*;

/** Content-addressed cache, shared by the server and client. No game API or resource reload. */
public final class AssetCache implements AutoCloseable {
    private static final int LIMIT=48*1024*1024;
    private final Path directory;
    private final ExecutorService worker=Executors.newFixedThreadPool(2,r->{Thread t=new Thread(r,"ysm-assets");t.setDaemon(true);return t;});
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ConcurrentMap<String,CompletableFuture<ModelAsset>> jobs=new ConcurrentHashMap<>();
    public AssetCache(Path directory){this.directory=directory;}
    public synchronized CompletableFuture<ModelAsset> get(String hash,String url){
        if(!hash.matches("[a-f0-9]{64}"))return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid asset hash"));
        if(jobs.size()>=256&&!jobs.containsKey(hash))return CompletableFuture.failedFuture(new IllegalStateException("Asset memory limit reached"));
        CompletableFuture<ModelAsset> job=jobs.computeIfAbsent(hash,k->CompletableFuture.supplyAsync(()->load(hash,url),worker));
        job.whenComplete((a,e)->{if(e!=null)jobs.remove(hash,job);});return job;
    }
    private ModelAsset load(String hash,String url){
        try{
            Files.createDirectories(directory);Path file=directory.resolve(hash+".json");
            if(Files.exists(file)&&Files.size(file)<=LIMIT){
                try{return parse(Files.readAllBytes(file),hash);}catch(RuntimeException e){Files.deleteIfExists(file);}
            }
            URI uri=URI.create(url);
            if(!("https".equals(uri.getScheme())||"http".equals(uri.getScheme()))||uri.getHost()==null||uri.getUserInfo()!=null)throw new IllegalArgumentException("Invalid asset URL");
            var response=http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(45)).GET().build(),HttpResponse.BodyHandlers.ofInputStream());
            byte[] bytes;
            try(var in=response.body()){
                if(response.statusCode()!=200||response.headers().firstValueAsLong("Content-Length").orElse(0)>LIMIT)throw new IllegalStateException("Asset download rejected");
                bytes=in.readNBytes(LIMIT+1);
            }
            if(bytes.length>LIMIT)throw new IllegalArgumentException("Asset exceeds size limit");
            ModelAsset asset=parse(bytes,hash);Path tmp=Files.createTempFile(directory,hash,".part");
            try{Files.write(tmp,asset.bytes());Files.move(tmp,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}finally{Files.deleteIfExists(tmp);}
            return asset;
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw new CompletionException(new IllegalStateException("Asset download interrupted"));}
        catch(Exception e){throw new CompletionException(new IllegalStateException("Asset unavailable or failed validation",e));}
    }
    private static ModelAsset parse(byte[] bytes,String hash){
        var data=JsonParser.parseString(new String(bytes,StandardCharsets.UTF_8)).getAsJsonObject();
        ModelCompiler.validateBundle(data);
        var asset=ModelAsset.of(data);
        if(!asset.hash().equals(hash))throw new IllegalArgumentException("Asset checksum mismatch");
        new Rig(asset.data());return asset;
    }
    @Override public void close(){worker.shutdownNow();http.shutdownNow();jobs.values().forEach(f->f.cancel(true));jobs.clear();}
}
