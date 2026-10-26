package online.yudream.ysm.protocol;

import java.util.*;

/** Wire DTOs contain no game classes. Protocol and asset schema versions evolve independently. */
public final class Protocol {
    public static final int MAJOR=1, MINOR=0;
    public static final String CHANNEL="yudream:ysm";
    public record Hello(int major,int minor,Set<String> capabilities) {}
    public record Asset(String modelId,String hash,String url) {}
    public record Outfit(String playerUuid,String modelId,String hash,String texture,String url) {}
    public record State(int protocol,List<Outfit> outfits) {}
    public record Manifest(String revision,Map<String,Asset> models) {}
    public record Snapshot(String revision,String sha1,String url,Map<String,Asset> models) {}
    public record Ready(String playerUuid,String hash) {}
    private Protocol() {}
}
