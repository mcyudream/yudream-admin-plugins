package online.yudream.ysm.protocol;

import com.google.gson.*;
import online.yudream.ysm.model.ModelAsset;
import java.nio.charset.StandardCharsets;

public final class Wire {
    public record Message(int protocol, String type, JsonElement data) {}
    public static byte[] encode(String type,Object data) {
        byte[] bytes=ModelAsset.JSON.toJson(new Message(Protocol.MAJOR,type,ModelAsset.JSON.toJsonTree(data))).getBytes(StandardCharsets.UTF_8);
        if(bytes.length>32700) throw new IllegalArgumentException("Model message exceeds channel limit");
        return bytes;
    }
    public static Message decode(byte[] bytes) {
        if(bytes.length>32700)throw new IllegalArgumentException("Model message exceeds channel limit");
        var m=ModelAsset.JSON.fromJson(new String(bytes,StandardCharsets.UTF_8),Message.class);
        if(m==null||m.protocol()!=Protocol.MAJOR||m.type()==null||m.type().length()>32||m.data()==null)throw new IllegalArgumentException("Unsupported model protocol");
        return m;
    }
    private Wire() {}
}
