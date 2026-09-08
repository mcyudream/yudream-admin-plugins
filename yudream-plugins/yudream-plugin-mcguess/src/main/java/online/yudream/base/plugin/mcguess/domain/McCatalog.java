package online.yudream.base.plugin.mcguess.domain;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.function.Supplier;

/**
 * 物品目录：全物品查询、中文名智能匹配、代表配方与合成树（距离 / 出现次数）计算。
 * 默认 eager 构造；{@link #lazy(Supplier)} 模式下每次访问经 source 解析实际目录，
 * 使启用期零查询、mc-wiki 发布版本变化时可热切换，公开接口与行为不变。
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

    /** 懒加载模式非 null：所有访问委托给 source 解析出的实际目录。 */
    private final Supplier<McCatalog> source;
    /** 目录数据对应的 MC 版本（如 1.20.6），用于棋盘字幕等展示；懒加载模式委托给实际目录。 */
    private final String version;
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

    public McCatalog(List<McItem> items, Map<String, McRecipe> recipes, String version) {
        this.source = null;
        this.version = version;
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
                .filter(item -> item.craftable()
                        && !isSelfReferential(recipes.get(item.id()))
                        && treeOf(item.id()).nodeCount() >= 3)
                .toList();
        this.iconItems = items.stream().filter(McItem::icon).toList();
    }

    /**
     * 增殖配方（如锻造模板：产物自身也是原料之一）不适合出题——猜物里自身格只能靠「猜中目标」
     * 点亮而无法作为普通格揭示，提示还可能直接报出答案；猜合成/快答/找茬共用同一出题池。
     */
    private static boolean isSelfReferential(McRecipe recipe) {
        return recipe != null && recipe.grid().contains(recipe.result());
    }

    private McCatalog(Supplier<McCatalog> source) {
        this.source = source;
        this.version = null;
        this.items = null;
        this.byId = null;
        this.byZh = null;
        this.byNormalizedZh = null;
        this.recipes = null;
        this.guessTargets = null;
        this.iconItems = null;
    }

    /** 懒加载目录：构造时不触碰 source，首次方法调用才解析；source 须返回 eager 实例。 */
    public static McCatalog lazy(Supplier<McCatalog> source) {
        return new McCatalog(Objects.requireNonNull(source, "source"));
    }

    private McCatalog d() {
        return source == null ? this : source.get();
    }

    /** 目录数据对应的 MC 版本（如 1.20.6）；懒加载模式委托给 source 解析出的实际目录。 */
    public String version() {
        return d().version;
    }

    public List<McItem> items() {
        return d().items;
    }

    public Map<String, McRecipe> recipes() {
        return d().recipes;
    }

    public Optional<McItem> byId(String id) {
        return Optional.ofNullable(d().byId.get(id));
    }

    public Optional<McRecipe> recipeOf(String itemId) {
        return Optional.ofNullable(d().recipes.get(itemId));
    }

    public int craftableCount() {
        return d().recipes.size();
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
        McCatalog self = d();
        McItem exact = self.byZh.get(input);
        if (exact != null) {
            return List.of(exact);
        }
        String normalized = normalizeZh(input);
        if (normalized.isEmpty()) {
            return List.of();
        }
        return self.byNormalizedZh.getOrDefault(normalized, List.of());
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
        McCatalog self = d();
        if (self.guessTargets.isEmpty()) {
            throw new IllegalStateException("当前数据版本没有可出题物品，请检查 mc-wiki 是否已发布配方数据");
        }
        return self.guessTargets.get(random.nextInt(self.guessTargets.size()));
    }

    public int guessTargetCount() {
        return d().guessTargets.size();
    }

    public int iconItemCount() {
        return d().iconItems.size();
    }

    /** 带图标的物品池（迷雾猜图标 / 宾果棋盘出题用）。 */
    public List<McItem> iconItems() {
        return d().iconItems;
    }

    /**
     * 视觉玩法出题池：优先带渲染图标的物品。云端已发布 wiki 但尚未一键更新渲染资产时
     * {@link #iconItems()} 为空，回退到全量物品（图标可能空白，{@code IconSupport.dataUri} 会返回 null）。
     * 数量仍不足时抛出中文 IllegalStateException，避免 Random.nextInt(0) / subList 越界把 JVM 英文异常漏到 QQ。
     */
    public List<McItem> visualPool(int minSize) {
        if (minSize < 1) {
            throw new IllegalArgumentException("minSize 必须为正");
        }
        McCatalog self = d();
        if (self.iconItems.size() >= minSize) {
            return self.iconItems;
        }
        if (self.items.size() >= minSize) {
            return self.items;
        }
        throw new IllegalStateException("当前数据版本可用物品不足 " + minSize
                + " 个（带图标 " + self.iconItems.size() + " 个，全部 "
                + self.items.size() + " 个），请先在 mc-wiki 导入并发布资源版本");
    }

    /**
     * 同族物品：归一化中文名相同的其他物品（如红色羊毛 ↔ 蓝色羊毛），找茬换格用。
     * 不含物品自身；无同族时返回空列表。
     */
    public List<McItem> familyOf(String itemId) {
        McCatalog self = d();
        McItem item = self.byId.get(itemId);
        if (item == null) {
            return List.of();
        }
        return self.byNormalizedZh.getOrDefault(normalizeZh(item.zh()), List.of()).stream()
                .filter(candidate -> !candidate.id().equals(itemId))
                .toList();
    }

    /**
     * 全局出现分数：该物品作为原料出现在全部可合成物品的合成树配方格中的总次数（比大小玩法用）。
     * 首次调用时遍历全部配方懒计算并缓存。
     */
    public int occurrenceScore(String itemId) {
        return d().occurrenceScores().getOrDefault(itemId, 0);
    }

    private Map<String, Integer> occurrenceScores() {
        Map<String, Integer> result = occurrenceScores;
        if (result == null) {
            synchronized (this) {
                if (occurrenceScores == null) {
                    Map<String, Integer> scores = new HashMap<>();
                    for (String target : recipes.keySet()) {
                        computeTree(target).occurrences().forEach((item, count) -> scores.merge(item, count, Integer::sum));
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
    public TreeInfo treeOf(String targetId) {
        return d().computeTree(targetId);
    }

    private synchronized TreeInfo computeTree(String targetId) {
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
