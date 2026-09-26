package online.yudream.base.plugin.mcpanel.application.service;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 整合包解析：mrpack 索引/过滤/镜像、CurseForge minecraftinstance 直链构造、静态规则。 */
class ModpackServiceTest {

    private final ModpackService service = new ModpackService(null, "0.17.0");

    private static byte[] zip(Map<String, String> entries) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    @Test
    void parsesMrpackWithServerFilterAndOverrides() throws Exception {
        String index = """
                {
                  "formatVersion": 1,
                  "game": "minecraft",
                  "versionId": "1.0.0",
                  "name": "测试整合包",
                  "files": [
                    {"path": "mods/fabric-api.jar", "hashes": {"sha512": "abc"}, "size": 10,
                     "downloads": ["https://cdn.modrinth.com/data/aaaa/versions/x/fabric-api.jar"],
                     "env": {"client": "required", "server": "required"}},
                    {"path": "mods/client-only.jar", "hashes": {}, "size": 5,
                     "downloads": ["https://cdn.modrinth.com/data/bbbb/versions/y/c.jar"],
                     "env": {"client": "required", "server": "unsupported"}},
                    {"path": "shaderpacks/rethinking-voxels_r0.1-beta8.zip", "hashes": {}, "size": 9,
                     "downloads": ["https://cdn.modrinth.com/data/cccc/versions/z/shader.zip"],
                     "env": {"client": "required", "server": "optional"}},
                    {"path": "resourcepacks/fancy.zip", "hashes": {}, "size": 7,
                     "downloads": ["https://cdn.modrinth.com/data/dddd/versions/z/rp.zip"]},
                    {"path": "mods/client-leaning.jar", "hashes": {}, "size": 3,
                     "downloads": ["https://cdn.modrinth.com/data/eeee/versions/z/cl.jar"],
                     "env": {"client": "required", "server": "optional"}}
                  ],
                  "dependencies": {"minecraft": "1.21.1", "fabric-loader": "0.16.9"}
                }
                """;
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("modrinth.index.json", index);
        entries.put("overrides/config/server.toml", "a=1");
        entries.put("server-overrides/extra.txt", "b=2");
        entries.put("client-overrides/options.txt", "skip");
        ModpackService.ImportResult result = service.inspect(zip(entries));

        assertEquals("测试整合包", result.name());
        assertEquals("1.21.1", result.mcVersion());
        assertEquals("fabric", result.loader());
        assertEquals("0.16.9", result.loaderVersion());
        assertEquals(1, result.plan().size());
        assertEquals("mods/fabric-api.jar", result.plan().get(0).get("path"));
        assertEquals("abc", result.plan().get(0).get("sha512"));
        // 光影包/资源包/仅客户端倾向 mod 一律不进服务端计划。
        assertEquals(List.of("mods/client-only.jar",
                "shaderpacks/rethinking-voxels_r0.1-beta8.zip",
                "resourcepacks/fancy.zip",
                "mods/client-leaning.jar"), result.skippedClientOnly());
        assertEquals(2, result.overrides().size());
        // overrides 内容 base64 可还原。
        String decoded = new String(Base64.getDecoder().decode(
                String.valueOf(result.overrides().get(0).get("contentB64"))), StandardCharsets.UTF_8);
        assertTrue(decoded.equals("a=1") || decoded.equals("b=2"));
        assertTrue(result.coreChain().contains("fabric"));
    }

    @Test
    void rejectsUnknownArchive() throws Exception {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("readme.txt", "not a modpack");
        assertThrows(RuntimeException.class, () -> service.inspect(zip(entries)));
    }

    @Test
    void parsesCurseforgeInstanceWithoutNetwork() throws Exception {
        String instance = """
                {
                  "name": "CF 实例",
                  "minecraftVersion": "1.20.1",
                  "baseModLoader": {"name": "fabric-loader-0.15.11-1.20.1"},
                  "installedAddons": [
                    {"addonID": 306612, "installedFile": {"id": 5646573, "filename": "fabric-api-0.92.jar"}},
                    {"addonID": 999999, "installedFile": {"id": 1234567, "filename": "some-mod-1.0.jar"}}
                  ]
                }
                """;
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("minecraftinstance.json", instance);
        entries.put("overrides/config.toml", "x=1");
        ModpackService.ImportResult result = service.inspect(zip(entries));

        assertEquals("fabric", result.loader());
        assertEquals("0.15.11", result.loaderVersion());
        assertEquals(2, result.plan().size());
        assertEquals("mods/fabric-api-0.92.jar", result.plan().get(0).get("path"));
        // 默认源列表：MCIM 镜像为主 URL，forgecdn 官方直链进回退列表。
        assertEquals("https://mod.mcimirror.top/files/5646/573/fabric-api-0.92.jar",
                result.plan().get(0).get("url"));
        assertEquals(List.of(
                "https://mod.mcimirror.top/files/5646/573/fabric-api-0.92.jar",
                ModpackService.forgeCdnUrl(5646573L, "fabric-api-0.92.jar")),
                result.plan().get(0).get("urls"));
        assertEquals(1, result.overrides().size());
    }

    @Test
    void forgeCdnUrlFollowsYmclRule() {
        assertEquals("https://edge.forgecdn.net/files/5646/573/fabric%20api.jar",
                ModpackService.forgeCdnUrl(5646573L, "fabric api.jar"));
        assertEquals("https://edge.forgecdn.net/files/0/1/a.jar",
                ModpackService.forgeCdnUrl(1L, "a.jar"));
    }

    @Test
    void modloaderStringsParse() {
        String[] forge = invokeParse("forge-1.19.2-43.2.0", "1.19.2");
        assertEquals("forge", forge[0]);
        assertEquals("43.2.0", forge[1]);
        String[] neo = invokeParse("neoforge-20.4.237", "1.20.4");
        assertEquals("neoforge", neo[0]);
        String[] empty = invokeParse("", "");
        assertEquals("", empty[0]);
        // fabric-loader 版本带 MC 后缀时剔除
        String[] fabric = invokeParse("fabric-loader-0.15.11-1.20.1", "1.20.1");
        assertEquals("fabric", fabric[0]);
        assertEquals("0.15.11", fabric[1]);
        assertFalse(fabric[1].contains("1.20.1"));
    }

    private String[] invokeParse(String modloader, String mcVersion) {
        try {
            var method = ModpackService.class.getDeclaredMethod("parseCfModloader", String.class, String.class);
            method.setAccessible(true);
            return (String[]) method.invoke(service, modloader, mcVersion);
        }
        catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }
}
