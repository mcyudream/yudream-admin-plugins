package online.yudream.base.plugin.mcpanel.application.service;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** YAML(BungeeCord config.yml) / TOML(Velocity velocity.toml) 结构化读写。 */
class StructuredConfigCodecTest {

    private static final String VELOCITY_TOML = """
            config-version = "2.7"
            bind = "0.0.0.0:25577"
            motd = "Proxy"
            show-max-players = 500
            online-mode = true
            player-info-forwarding = "modern"

            [servers]
            survival = "localhost:25566"
            creative = { address = "localhost:25567" }

            try = [
              "survival",
              "creative"
            ]
            """;

    private static final String BUNGEE_YAML = String.join("\n",
            "ip_forward: false",
            "priorities:",
            "- lobby",
            "listeners:",
            "- motd: '&1Bungee'",
            "  query_port: 25577",
            "servers:",
            "  lobby:",
            "    motd: 'lobby'",
            "    address: localhost:25566",
            "  survival:",
            "    address: 127.0.0.1:25567",
            "");

    @Test
    void flattenVelocityTomlToDottedKeys() throws IOException {
        Map<String, Object> tree = StructuredConfigCodec.parseTree(VELOCITY_TOML, StructuredConfigCodec.Format.TOML);
        Map<String, String> flat = StructuredConfigCodec.flatten(tree);
        assertEquals("2.7", flat.get("config-version"));
        assertEquals("0.0.0.0:25577", flat.get("bind"));
        assertEquals("500", flat.get("show-max-players"));
        assertEquals("true", flat.get("online-mode"));
        assertEquals("localhost:25566", flat.get("servers.survival"));
        assertEquals("localhost:25567", flat.get("servers.creative.address"));
        // try 在 [servers] 表内（velocity.toml 实际结构）。
        assertEquals("survival, creative", flat.get("servers.try"));
        assertFalse(flat.containsKey("try"));
        assertFalse(flat.containsKey("missing"));
    }

    @Test
    void velocityTomlEditRoundTripKeepsTypes() throws IOException {
        Map<String, Object> tree = StructuredConfigCodec.parseTree(VELOCITY_TOML, StructuredConfigCodec.Format.TOML);
        Map<String, String> edits = new LinkedHashMap<>();
        edits.put("servers.survival", "127.0.0.1:30001");
        edits.put("show-max-players", "100");
        edits.put("player-info-forwarding", "legacy");
        StructuredConfigCodec.applyEdits(tree, edits);
        String written = StructuredConfigCodec.writeTree(tree, StructuredConfigCodec.Format.TOML);

        Map<String, Object> reparsed = StructuredConfigCodec.parseTree(written, StructuredConfigCodec.Format.TOML);
        assertEquals("127.0.0.1:30001", ((Map<?, ?>) reparsed.get("servers")).get("survival"));
        assertEquals("legacy", reparsed.get("player-info-forwarding"));
        assertInstanceOf(Number.class, reparsed.get("show-max-players"));
        assertEquals(100, ((Number) reparsed.get("show-max-players")).intValue());
        assertEquals(Boolean.TRUE, reparsed.get("online-mode"));
        assertEquals("0.0.0.0:25577", reparsed.get("bind"));
        @SuppressWarnings("unchecked")
        List<Object> tryList = (List<Object>) ((Map<?, ?>) reparsed.get("servers")).get("try");
        assertEquals(List.of("survival", "creative"), tryList);
    }

    /** TOML 规范要求表内标量先于子表：乱序树（servers 在前、标量在后）写出后必须可再解析。 */
    @Test
    void tomlWriteReordersScalarsBeforeTables() throws IOException {
        Map<String, Object> nested = new LinkedHashMap<>();
        @SuppressWarnings("unchecked")
        Map<String, Object> servers = new LinkedHashMap<>();
        servers.put("survival", "localhost:25566");
        // 故意把子表放在标量之前。
        nested.put("servers", servers);
        nested.put("bind", "0.0.0.0:25577");
        nested.put("show-max-players", 500);
        String written = StructuredConfigCodec.writeTree(nested, StructuredConfigCodec.Format.TOML);
        Map<String, Object> reparsed = StructuredConfigCodec.parseTree(written, StructuredConfigCodec.Format.TOML);
        assertEquals("0.0.0.0:25577", reparsed.get("bind"));
        assertEquals(500, ((Number) reparsed.get("show-max-players")).intValue());
        assertEquals("localhost:25566", ((Map<?, ?>) reparsed.get("servers")).get("survival"));
    }

    @Test
    void flattenBungeeYamlSkipsListOfMaps() throws IOException {
        Map<String, Object> tree = StructuredConfigCodec.parseTree(BUNGEE_YAML, StructuredConfigCodec.Format.YAML);
        Map<String, String> flat = StructuredConfigCodec.flatten(tree);
        assertEquals("false", flat.get("ip_forward"));
        assertEquals("lobby", flat.get("priorities"));
        assertEquals("localhost:25566", flat.get("servers.lobby.address"));
        assertEquals("127.0.0.1:25567", flat.get("servers.survival.address"));
        assertEquals("lobby", flat.get("servers.lobby.motd"));
        assertFalse(flat.keySet().stream().anyMatch(key -> key.startsWith("listeners")),
                "listeners（列表内含表）不得进入结构化编辑");
    }

    @Test
    void bungeeYamlEditCoercesBoolean() throws IOException {
        Map<String, Object> tree = StructuredConfigCodec.parseTree(BUNGEE_YAML, StructuredConfigCodec.Format.YAML);
        Map<String, String> edits = new LinkedHashMap<>();
        edits.put("ip_forward", "true");
        edits.put("servers.lobby.address", "10.0.0.8:25566");
        StructuredConfigCodec.applyEdits(tree, edits);
        String written = StructuredConfigCodec.writeTree(tree, StructuredConfigCodec.Format.YAML);
        Map<String, Object> reparsed = StructuredConfigCodec.parseTree(written, StructuredConfigCodec.Format.YAML);
        assertEquals(Boolean.TRUE, reparsed.get("ip_forward"));
        assertEquals("10.0.0.8:25566",
                ((Map<?, ?>) ((Map<?, ?>) reparsed.get("servers")).get("lobby")).get("address"));
    }

    @Test
    void dottedKeyNamesAreSkippedNotMangled() throws IOException {
        Map<String, Object> tree = new LinkedHashMap<>();
        @SuppressWarnings("unchecked")
        Map<String, Object> servers = new LinkedHashMap<>();
        servers.put("a.b", "host:25566"); // 名称含点的子服：不参与结构化编辑
        tree.put("servers", servers);
        Map<String, String> flat = StructuredConfigCodec.flatten(tree);
        assertTrue(flat.isEmpty());
    }

    @Test
    void rawFormatHasNoTreeSupport() {
        assertEquals(StructuredConfigCodec.Format.RAW, StructuredConfigCodec.formatOf("ops.json"));
        assertEquals(StructuredConfigCodec.Format.PROPERTIES, StructuredConfigCodec.formatOf("server.properties"));
        assertEquals(StructuredConfigCodec.Format.YAML, StructuredConfigCodec.formatOf("config.yml"));
        assertEquals(StructuredConfigCodec.Format.TOML, StructuredConfigCodec.formatOf("velocity.toml"));
        assertThrows(IllegalArgumentException.class,
                () -> StructuredConfigCodec.parseTree("x", StructuredConfigCodec.Format.RAW));
    }

    @Test
    void newKeysFallBackToString() throws IOException {
        Map<String, Object> tree = new LinkedHashMap<>();
        Map<String, String> edits = new LinkedHashMap<>();
        edits.put("brand", "YuDream");
        StructuredConfigCodec.applyEdits(tree, edits);
        String written = StructuredConfigCodec.writeTree(tree, StructuredConfigCodec.Format.TOML);
        Map<String, Object> reparsed = StructuredConfigCodec.parseTree(written, StructuredConfigCodec.Format.TOML);
        assertEquals("YuDream", reparsed.get("brand"));
        assertNotEquals("", written);
        assertNull(reparsed.get("nope"));
    }
}
