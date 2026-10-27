package online.yudream.ysm.folia;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

final class StandalonePackPublisher implements PackPublisher {
    private final HttpServer server;
    private final String publicUrl;
    private final ExecutorService worker;
    private final Map<String,Path> downloads = new ConcurrentHashMap<>();
    private volatile boolean closed;

    StandalonePackPublisher(String bind, int port, String publicUrl) throws Exception {
        this.publicUrl = publicUrl.replaceAll("/+$", "");
        URI uri = URI.create(this.publicUrl);
        if (!Set.of("https", "http").contains(uri.getScheme()) || uri.getHost() == null
                || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null)
            throw new IllegalArgumentException("pack-host.public-url 需要为 HTTP(S) 公网根地址");
        this.server = HttpServer.create(new InetSocketAddress(bind, port), 16);
        this.worker = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "ysm-pack-http"); t.setDaemon(true); return t;
        });
        this.server.setExecutor(this.worker);
        this.server.createContext("/", exchange -> {
            try {
                Path file = this.downloads.get(exchange.getRequestURI().getPath());
                if (file == null || !Set.of("GET", "HEAD").contains(exchange.getRequestMethod())) {
                    exchange.sendResponseHeaders(404, -1); return;
                }
                exchange.getResponseHeaders().set("Content-Type", "application/zip");
                exchange.getResponseHeaders().set("Cache-Control", "private, max-age=3600");
                long length = Files.size(file);
                if (exchange.getRequestMethod().equals("HEAD")) {
                    exchange.getResponseHeaders().set("Content-Length", "" + length);
                    exchange.sendResponseHeaders(200, -1); return;
                }
                exchange.sendResponseHeaders(200, length);
                try (var out = exchange.getResponseBody()) { Files.copy(file, out); }
            } finally { exchange.close(); }
        });
        this.server.start();
    }

    @Override public synchronized Download publish(Path file, String sha1) {
        if (this.closed) throw new IllegalStateException("资源包托管已关闭");
        String path = "/" + UUID.randomUUID().toString().replace("-", "") + "/" + sha1 + ".zip";
        this.downloads.put(path, file);
        return player -> this.publicUrl + path;
    }

    @Override public synchronized void close() {
        this.closed = true;
        this.server.stop(0); this.worker.shutdownNow(); this.downloads.clear();
    }
}
