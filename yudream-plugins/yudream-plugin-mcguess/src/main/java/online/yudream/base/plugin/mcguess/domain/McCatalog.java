package online.yudream.base.plugin.mcguess.domain;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

/**
 * 物品目录：全物品查询、中文名智能匹配、代表配方与合成树（距离 / 出现次数）计算。
 * 数据集由 tools/build_assets.py 生成，classpath 资源 mcguess/mcdata.json。
 */
public class McCatalog {

    /** 智能匹配忽略的颜色词（zh_cn 用词，如 粉红色羊毛）。 */
    private static final List<String> COLOR_TOKENS = List.of(
            "淡灰色", "粉红色", "品红色", "淡蓝色", "黄绿色",
            "白色", "橙色", "黄色", "粉色", "灰色", "青色", "紫色", "蓝色", "棕色", "绿色", "红色", "黑色");
    /** 智能匹配忽略的主世界木质词（下界的 绯红 / 诡异 除外）。 */
    private static final List<String> WOOD_TOKENS = List.of(
            "深色橡木", "橡木", "云杉", "白桦", "丛林", "金合欢", "红树", "樱花", "竹");
    /** 智能匹配忽略的材质词。 */
    private static final List<String> MATERIAL_TOKENS = List.of("染色", "磨制", "切制");

    private final List<McItem> items;
    private final Map<String, McItem> byId;
    private final Map<String, McItem> byZh;
    private final Map<String, List<McItem>> byNormalizedZh;
    private final Map<String, McRecipe> recipes;
    private final List<McItem> guessTargets;
    /** 带图标的物品池（迷雾 / 宾果出题用）。 */
    private final List<McItem> iconItems;
    private final Map<String, TreeInfo> treeCache = new HashMap<>();
    /** 全局出现分数（懒计算）：物品作为原料出现在全部合成树配方格中的总次数。 */
    private volatile Map<String, Integer> occurrenceScores;

    public McCatalog(List<McItem> items, Map<String, McRecipe> recipes) {
        this.items = List.copyOf(items);
        this.recipes = Map.copyOf(recipes);
        this.byId = new HashMap<>();
        this.byZh = new HashMap<>();
        this.byNormalizedZh = new HashMap<>();
        for (McItem item : items) {
            byId.put(item.id(), item);
            byZh.putIfAbsent(item.zh(), item);
            String normalized = normalizeZh(item.zh());
            if (!normalized.isEmpty()) {
                byNormalizedZh.computeIfAbsent(normalized, key -> new ArrayList<>()).add(item);
            }
        }
        this.guessTargets = items.stream()
                .filter(item -> item.craftable() && treeOf(item.id()).nodeCount() >= 3)
                .toList();
        this.iconItems = items.stream().filter(McItem::icon).toList();
    }

    public List<McItem> items() {
        return items;
    }

    public Map<String, McRecipe> recipes() {
        return recipes;
    }

    public Optional<McItem> byId(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    public Optional<McRecipe> recipeOf(String itemId) {
        return Optional.ofNullable(recipes.get(itemId));
    }

    public int craftableCount() {
        return recipes.size();
    }

    /**
     * 智能匹配：先按中文全名精确匹配；否则忽略颜色词、木质词（下界木材除外）与材质词后匹配。
     * 返回候选列表（可能多个同族物品），空列表表示没有匹配。
     */
    public List<McItem> match(String rawInput) {
        String input = rawInput == null ? "" : rawInput.trim();
        if (input.isEmpty()) {
            return List.of();
        }
        McItem exact = byZh.get(input);
        if (exact != null) {
            return List.of(exact);
        }
        String normalized = normalizeZh(input);
        if (normalized.isEmpty()) {
            return List.of();
        }
        return byNormalizedZh.getOrDefault(normalized, List.of());
    }

    /** 归一化中文名：去除全部可忽略词，直到稳定。 */
    public static String normalizeZh(String name) {
        String value = name.replaceAll("[\\s·]", "");
        boolean changed = true;
        while (changed) {
            changed = false;
            for (List<String> group : List.of(COLOR_TOKENS, WOOD_TOKENS, MATERIAL_TOKENS)) {
                for (String token : group) {
                    if (value.contains(token)) {
                        value = value.replace(token, "");
                        changed = true;
                    }
                }
            }
        }
        return value;
    }

    /** 随机出题：从可合成且合成树非平凡的物品池中纯随机选取。 */
    public McItem randomTarget(Random random) {
        return guessTargets.get(random.nextInt(guessTargets.size()));
    }

    public int guessTargetCount() {
        return guessTargets.size();
    }

    /** 带图标的物品池（迷雾猜图标 / 宾果棋盘出题用）。 */
    public List<McItem> iconItems() {
        return iconItems;
    }

    /**
     * 同族物品：归一化中文名相同的其他物品（如红色羊毛 ↔ 蓝色羊毛），找茬换格用。
     * 不含物品自身；无同族时返回空列表。
     */
    public List<McItem> familyOf(String itemId) {
        McItem item = byId.get(itemId);
        if (item == null) {
            return List.of();
        }
        return byNormalizedZh.getOrDefault(normalizeZh(item.zh()), List.of()).stream()
                .filter(candidate -> !candidate.id().equals(itemId))
                .toList();
    }

    /**
     * 全局出现分数：该物品作为原料出现在全部可合成物品的合成树配方格中的总次数（比大小玩法用）。
     * 首次调用时遍历全部配方懒计算并缓存。
     */
    public int occurrenceScore(String itemId) {
        return occurrenceScores().getOrDefault(itemId, 0);
    }

    private Map<String, Integer> occurrenceScores() {
        Map<String, Integer> result = occurrenceScores;
        if (result == null) {
            synchronized (this) {
                if (occurrenceScores == null) {
                    Map<String, Integer> scores = new HashMap<>();
                    for (String target : recipes.keySet()) {
                        treeOf(target).occurrences().forEach((item, count) -> scores.merge(item, count, Integer::sum));
                    }
                    occurrenceScores = Map.copyOf(scores);
                }
                result = occurrenceScores;
            }
        }
        return result;
    }

    /**
     * 合成树：从目标物品出发沿代表配方展开。
     * distance = 该物品到答案所需的合成次数（原料为 1，原料的原料为 2，不可达为 null）；
     * occurrences = 该物品在合成树全部配方格子中出现的次数。
     */
    public synchronized TreeInfo treeOf(String targetId) {
        TreeInfo cached = treeCache.get(targetId);
        if (cached != null) {
            return cached;
        }
        Map<String, Integer> distance = new HashMap<>();
        Map<String, Integer> occurrences = new LinkedHashMap<>();
        Deque<String> queue = new ArrayDeque<>();
        distance.put(targetId, 0);
        queue.add(targetId);
        while (!queue.isEmpty()) {
            String node = queue.poll();
            McRecipe recipe = recipes.get(node);
            if (recipe == null) {
                continue;
            }
            int nodeDistance = distance.get(node);
            for (String ingredient : recipe.ingredients()) {
                occurrences.merge(ingredient, 1, Integer::sum);
                if (!distance.containsKey(ingredient)) {
                    distance.put(ingredient, nodeDistance + 1);
                    queue.add(ingredient);
                }
            }
        }
        TreeInfo tree = new TreeInfo(targetId, distance, occurrences);
        treeCache.put(targetId, tree);
        return tree;
    }

    /**
     * 某个目标的合成树信息。
     */
    public record TreeInfo(String targetId, Map<String, Integer> distance, Map<String, Integer> occurrences) {

        public int nodeCount() {
            return distance.size();
        }

        /** 距离；不可达返回 null。 */
        public Integer distanceOf(String itemId) {
            return distance.get(itemId);
        }

        /** 出现次数；不在树中返回 0。 */
        public int occurrencesOf(String itemId) {
            return occurrences.getOrDefault(itemId, 0);
        }

        public boolean contains(String itemId) {
            return distance.containsKey(itemId);
        }
    }
}
