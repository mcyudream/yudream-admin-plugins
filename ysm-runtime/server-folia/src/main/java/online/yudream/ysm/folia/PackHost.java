package online.yudream.ysm.folia;

import online.yudream.ysm.model.*;
import online.yudream.ysm.protocol.Protocol;
import online.yudream.ysm.vanilla.mc26_3.VanillaPack;
import org.bukkit.entity.Player;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Builds viewer-scoped snapshots independently of the selected HTTP transport. */
final class PackHost implements AutoCloseable {
    private final Path directory;
    private final PackPublisher publisher;
    private final long maxBytes;
    private long storedBytes;
    private volatile boolean closed;
    private final Map<String,Cached> snapshots = new ConcurrentHashMap<>();
    private record Cached(Protocol.Snapshot snapshot, PackPublisher.Download download) {}

    PackHost(Path directory, PackPublisher publisher, long maxBytes) throws Exception {
        this.directory = directory; this.publisher = publisher; this.maxBytes = maxBytes;
        Files.createDirectories(directory);
    }

    synchronized Protocol.Snapshot build(Protocol.Manifest manifest, Collection<ModelAsset> assets) throws Exception {
        if (this.closed) throw new IllegalStateException("资源快照已关闭");
        String revision = "mc26.3-export2-" + manifest.revision();
        var existing = this.snapshots.get(revision);
        if (existing != null) return existing.snapshot();
        if (this.snapshots.size() >= 256) throw new IllegalStateException("资源快照缓存已满，请重启清理后重试");
        byte[] zip = new VanillaPack().build(assets);
        if (zip.length > 128 * 1024 * 1024) throw new IllegalArgumentException("资源包超过 128 MiB");
        if (this.storedBytes + zip.length > this.maxBytes) throw new IllegalStateException("资源快照总大小超过 pack-host.max-cache-mib 限制");
        String sha1 = ModelAsset.digest("SHA-1", zip);
        Path file = this.directory.resolve(sha1 + ".zip");
        // Registered files stay immutable, including when revisions export identical bytes.
        if (!Files.exists(file)) {
            Path temporary = Files.createTempFile(this.directory, ".pack-", ".zip");
            try {
                Files.write(temporary, zip);
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            } finally { Files.deleteIfExists(temporary); }
        }
        var download = this.publisher.publish(file, sha1);
        // Single-use links are issued on the player's region immediately before sending.
        var snapshot = new Protocol.Snapshot(revision, sha1, "", Map.copyOf(manifest.models()));
        this.snapshots.put(revision, new Cached(snapshot, download)); this.storedBytes += zip.length;
        return snapshot;
    }

    String downloadUrl(Protocol.Snapshot snapshot, Player player) throws Exception {
        var cached = this.snapshots.get(snapshot.revision());
        if (this.closed || cached == null) throw new IllegalStateException("资源快照已关闭");
        return cached.download().url(player);
    }

    boolean ready() { return !this.closed && this.publisher.ready(); }

    @Override public synchronized void close() {
        this.closed = true; this.publisher.close(); this.snapshots.clear(); this.storedBytes = 0;
    }
}
