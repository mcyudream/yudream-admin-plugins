package online.yudream.base.plugin.mcguess.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import online.yudream.base.plugin.mcguess.domain.McCatalog;
import online.yudream.base.plugin.mcguess.domain.McItem;
import online.yudream.base.plugin.mcguess.domain.McMobCatalog;
import online.yudream.base.plugin.mcguess.domain.McRecipe;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 加载插件数据集：mcguess/mcdata.json 物品与配方（tools/build_assets.py 生成）、
 * mcguess/mcmobs.json 手工精选生物数据集。
 */
public final class McDataLoader {

    private static final String DATA_RESOURCE = "mcguess/mcdata.json";
    private static final String MOB_RESOURCE = "mcguess/mcmobs.json";

    private McDataLoader() {
    }

    public static McCatalog load(ClassLoader classLoader) {
        JsonNode root;
        try (InputStream input = classLoader.getResourceAsStream(DATA_RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("缺少数据资源 " + DATA_RESOURCE + "，请先运行 tools/build_assets.py 生成");
            }
            root = new ObjectMapper().readTree(input);
        } catch (IOException e) {
            throw new IllegalStateException("解析 " + DATA_RESOURCE + " 失败：" + e.getMessage(), e);
        }
        List<McItem> items = new ArrayList<>();
        for (JsonNode node : root.withArray("items")) {
            items.add(new McItem(
                    node.get("id").asText(),
                    node.get("en").asText(),
                    node.get("zh").asText(),
                    node.get("craft").asBoolean(),
                    node.get("icon").asBoolean()));
        }
        Map<String, McRecipe> recipes = new LinkedHashMap<>();
        JsonNode recipesNode = root.get("recipes");
        recipesNode.properties().forEach(entry -> {
            List<String> grid = new ArrayList<>();
            for (JsonNode cell : entry.getValue().withArray("g")) {
                grid.add(cell.isNull() ? null : cell.asText());
            }
            recipes.put(entry.getKey(), new McRecipe(entry.getKey(), grid, entry.getValue().get("c").asInt(1)));
        });
        return new McCatalog(items, recipes);
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
        return new McMobCatalog(mobs, conditions);
    }
}
