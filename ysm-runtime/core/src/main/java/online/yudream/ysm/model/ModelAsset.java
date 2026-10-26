package online.yudream.ysm.model;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Versioned, game-independent wire asset. Content hashes never contain URLs or timestamps. */
public record ModelAsset(String hash, JsonObject data) {
    public static final Gson JSON = new GsonBuilder().disableHtmlEscaping().create();
    public static ModelAsset of(JsonObject data) {
        if (!data.has("schema") || data.get("schema").getAsInt() != 1) throw new IllegalArgumentException("不支持的模型格式版本");
        JsonObject canonical = canonical(data).getAsJsonObject();
        return new ModelAsset(digest("SHA-256", JSON.toJson(canonical).getBytes(StandardCharsets.UTF_8)), canonical);
    }
    public byte[] bytes() { return JSON.toJson(data).getBytes(StandardCharsets.UTF_8); }
    public static byte[] canonicalBytes(Object value) { return JSON.toJson(canonical(JSON.toJsonTree(value))).getBytes(StandardCharsets.UTF_8); }
    public String name() { return data.get("name").getAsString(); }
    public JsonArray textures() { return data.getAsJsonArray("textures"); }
    public String item(int part) { return "ysm:" + hash + "/bone_" + part; }
    public static String digest(String algorithm, byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static JsonElement canonical(JsonElement value) {
        if (value.isJsonObject()) {
            JsonObject out = new JsonObject();
            new TreeSet<>(value.getAsJsonObject().keySet()).forEach(k -> out.add(k, canonical(value.getAsJsonObject().get(k))));
            return out;
        }
        if (value.isJsonArray()) { JsonArray out = new JsonArray(); value.getAsJsonArray().forEach(v -> out.add(canonical(v))); return out; }
        return value.deepCopy();
    }
}
