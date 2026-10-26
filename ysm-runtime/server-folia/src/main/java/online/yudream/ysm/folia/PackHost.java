package online.yudream.ysm.folia;

import com.sun.net.httpserver.HttpServer;
import online.yudream.ysm.model.*;
import online.yudream.ysm.protocol.Protocol;
import online.yudream.ysm.vanilla.mc26_3.VanillaPack;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

final class PackHost implements AutoCloseable {
    private final HttpServer server;private final Path directory;private final String publicUrl;
    private final ExecutorService worker=Executors.newFixedThreadPool(2,r->{Thread t=new Thread(r,"ysm-pack-http");t.setDaemon(true);return t;});
    private final Map<String,Path> downloads=new ConcurrentHashMap<>();
    private final Map<String,Protocol.Snapshot> snapshots=new ConcurrentHashMap<>();
    PackHost(Path directory,String bind,int port,String publicUrl) throws Exception {
        this.directory=directory;this.publicUrl=publicUrl.replaceAll("/+$","");Files.createDirectories(directory);
        URI publicUri=URI.create(this.publicUrl);if(!Set.of("https","http").contains(publicUri.getScheme()))throw new IllegalArgumentException("pack-host.public-url 需要为 HTTP(S) 地址");
        server=HttpServer.create(new InetSocketAddress(bind,port),16);server.setExecutor(worker);
        server.createContext("/",exchange->{
            try{
                Path file=downloads.get(exchange.getRequestURI().getPath());
                if(file==null||!Set.of("GET","HEAD").contains(exchange.getRequestMethod())){exchange.sendResponseHeaders(404,-1);return;}
                exchange.getResponseHeaders().set("Content-Type","application/zip");exchange.getResponseHeaders().set("Cache-Control","private, max-age=3600");
                long length=Files.size(file);
                if(exchange.getRequestMethod().equals("HEAD")){exchange.getResponseHeaders().set("Content-Length",""+length);exchange.sendResponseHeaders(200,-1);return;}
                exchange.sendResponseHeaders(200,length);try(var out=exchange.getResponseBody()){Files.copy(file,out);}
            }finally{exchange.close();}
        });server.start();
    }
    synchronized Protocol.Snapshot build(Protocol.Manifest manifest,Collection<ModelAsset> assets) throws Exception {
        String revision="mc26.3-export1-"+manifest.revision();var existing=snapshots.get(revision);if(existing!=null)return existing;
        if(snapshots.size()>=256)throw new IllegalStateException("资源快照缓存已满，请重启清理后重试");
        byte[] zip=new VanillaPack().build(assets);if(zip.length>128*1024*1024)throw new IllegalArgumentException("资源包超过 128 MiB");
        String sha1=ModelAsset.digest("SHA-1",zip);Path file=directory.resolve(sha1+".zip");Files.write(file,zip);
        String path="/"+UUID.randomUUID().toString().replace("-","")+"/"+sha1+".zip";downloads.put(path,file);
        var snapshot=new Protocol.Snapshot(revision,sha1,publicUrl+path,manifest.models());snapshots.put(revision,snapshot);return snapshot;
    }
    public void close(){server.stop(0);worker.shutdownNow();downloads.clear();snapshots.clear();}
}
