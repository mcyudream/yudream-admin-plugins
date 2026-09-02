package online.yudream.base.plugin.mcguess.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import online.yudream.base.plugin.mcguess.domain.McMobCatalog;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 加载 mcguess 自有的手工精选生物业务数据集；物品与配方由 mc-wiki provider 提供。
 */
public final class McDataLoader {

    private static final String MOB_RESOURCE = "mcguess/mcmobs.json";

    private McDataLoader() {
    }

    /** @deprecated 物品目录已由 mc-wiki provider 快照装配，测试与运行时不得读取本地 JSON。 */
    @Deprecated
    public static online.yudream.base.plugin.mcguess.domain.McCatalog load(ClassLoader classLoader) {
        throw new UnsupportedOperationException("mcguess 不再加载自有 mcdata.json，请使用 McAssetsSnapshot");
    }

    public static McMobCatalog loadMobs(ClassLoader classLoader) {
        JsonNode root;
        try (InputStream input = classLoader.getResourceAsStream(MOB_RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("缺少数据资源 " + MOB_RESOURCE + "（手工维护的生物数据集）");
            }
            root = new ObjectMapper().readTree(input);
        } catch (IOException e) {
            throw new IllegalStateException("解析 " + MOB_RESOURCE + " 失败：" + e.getMessage(), e);
        }
        String version = root.hasNonNull("mcVersion") ? root.get("mcVersion").asText() : "";
        List<McMobCatalog.McCondition> conditions = new ArrayList<>();
        for (JsonNode node : root.withArray("conditions")) {
            conditions.add(new McMobCatalog.McCondition(node.get("code").asText(), node.get("zh").asText()));
        }
        List<McMobCatalog.McMob> mobs = new ArrayList<>();
        for (JsonNode node : root.withArray("mobs")) {
            List<String> cond = new ArrayList<>();
            for (JsonNode code : node.withArray("cond")) {
                cond.add(code.asText());
            }
            mobs.add(new McMobCatalog.McMob(node.get("id").asText(), node.get("zh").asText(), cond));
        }
        return new McMobCatalog(mobs, conditions, version);
    }
}
