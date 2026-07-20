package online.yudream.plugin.worldmap.infrastructure.resource;

import online.yudream.plugin.worldmap.infrastructure.world.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link BlockModelRegistry} 默认实现：blockstate 定义 + 模型解析 + 烘焙缓存。
 *
 * <p>{@link BlockState} 复用 infrastructure.world 包的同一类型（契约约定）。
 * 回退策略：blockstate 缺失、variants 无匹配、模型 JSON 缺失 → 品红棋盘全立方体；
 * 模型存在但无元素（空气等）→ 空数组；multipart 无命中部分 → 空数组（原版行为）。</p>
 */
final class DefaultBlockModelRegistry implements BlockModelRegistry {

    private static final BakedQuad[] EMPTY = new BakedQuad[0];

    private final Map<String, BlockStateDefinition> definitions;
    private final ModelResolver resolver;
    private final ModelBaker baker;
    private final TextureAtlas atlas;
    private final BiomeColors biomeColors;

    private final Map<String, BakedQuad[]> quadCache = new ConcurrentHashMap<>();
    private volatile BakedQuad[] missingQuads;

    DefaultBlockModelRegistry(Map<String, BlockStateDefinition> definitions,
                              ModelResolver resolver, ModelBaker baker,
                              TextureAtlas atlas, BiomeColors biomeColors) {
        this.definitions = definitions;
        this.resolver = resolver;
        this.baker = baker;
        this.atlas = atlas;
        this.biomeColors = biomeColors;
    }

    @Override
    public BakedQuad[] quadsFor(BlockState state) {
        // 缓存键：方块名 + 排序后的属性串
        String key = plainName(state) + "|" + new TreeMap<>(state.properties());
        return quadCache.computeIfAbsent(key, k -> bakeState(state));
    }

    @Override
    public TextureAtlas atlas() {
        return atlas;
    }

    /** 生物群系染色表（契约外附加能力，渲染层可取用）。 */
    public BiomeColors biomeColors() {
        return biomeColors;
    }

    private BakedQuad[] bakeState(BlockState state) {
        String name = plainName(state);
        BlockStateDefinition def = definitions.get(name);
        if (def == null) {
            return missingQuads();
        }
        List<BlockStateDefinition.ModelRef> refs = def.modelsFor(state.properties());
        if (refs.isEmpty()) {
            // variants 覆盖全部属性组合，无匹配即非法状态；multipart 无命中按原版渲染为空
            return def.hasVariants() ? missingQuads() : EMPTY;
        }
        List<BakedQuad> all = new ArrayList<>();
        for (BlockStateDefinition.ModelRef ref : refs) {
            ResolvedModel model = resolver.resolve(ref.model());
            if (!model.found()) {
                // 引用的模型缺失：该部分用品红立方体顶替，便于排查
                all.addAll(List.of(missingQuads()));
            } else if (model.elements().isEmpty() && isLiquid(name)) {
                // 原版液体无 JSON 几何（客户端硬编码），注入内置液体模型
                all.addAll(baker.bakeLiquid(liquidTexture(name), liquidHeight(state), liquidTint(name)));
            } else {
                all.addAll(baker.bake(model, ref.x(), ref.y(), ref.uvlock()));
            }
        }
        return applyHardcodedTint(name, all.toArray(BakedQuad[]::new));
    }

    /** 去掉命名空间前缀（"minecraft:stone" → "stone"），与 blockstate 文件名对应。 */
    private static String plainName(BlockState state) {
        String name = state.name();
        int idx = name.indexOf(':');
        return idx >= 0 ? name.substring(idx + 1) : name;
    }

    /** 无 JSON 几何的液体方块。 */
    static boolean isLiquid(String name) {
        return name.equals("water") || name.equals("lava") || name.equals("bubble_column");
    }

    private static String liquidTexture(String name) {
        return name.equals("lava") ? "block/lava_still" : "block/water_still";
    }

    private static TintType liquidTint(String name) {
        return name.equals("lava") ? TintType.NONE : TintType.WATER;
    }

    /** 液面高度：level 0 → 16*8/9；level 1..7 递减；level 8..15 / falling → 满格。 */
    private static float liquidHeight(BlockState state) {
        int level = 0;
        String levelText = state.properties().get("level");
        if (levelText != null) {
            try {
                level = Integer.parseInt(levelText);
            } catch (NumberFormatException ignored) {
                // 非数字按静水处理
            }
        }
        if (level >= 8 || "true".equals(state.properties().get("falling"))) {
            return 16f;
        }
        return (8 - level) * 16f / 9f;
    }

    /**
     * 原版客户端在 BlockColors 中按方块名硬编码注册的染色（模型面无 tintindex）：
     * 树叶（foliage colormap）、水、草/蕨/甘蔗/藤蔓（grass colormap）、红石线。
     * 仅重标 tint==NONE 的面，不覆盖模型自带 tintindex。
     */
    private static BakedQuad[] applyHardcodedTint(String name, BakedQuad[] quads) {
        TintType tint = hardcodedTint(name);
        if (tint == null || quads.length == 0) {
            return quads;
        }
        BakedQuad[] result = quads;
        for (int i = 0; i < quads.length; i++) {
            BakedQuad q = quads[i];
            if (q.tint() == TintType.NONE) {
                if (result == quads) {
                    result = quads.clone();
                }
                result[i] = new BakedQuad(q.positions(), q.uvs(), tint, q.shade(), q.cullface(), q.translucent());
            }
        }
        return result;
    }

    /** 方块名 → 硬编码染色类型；null 表示无硬编码染色。 */
    private static TintType hardcodedTint(String name) {
        if (name.equals("water") || name.equals("bubble_column")) {
            return TintType.WATER;
        }
        if (name.equals("redstone_wire")) {
            return TintType.REDSTONE;
        }
        // cherry_leaves 自带粉色不染色；birch/spruce 原版为固定 RGB，此处近似走 foliage colormap
        if (name.endsWith("_leaves") && !name.equals("cherry_leaves")) {
            return TintType.FOLIAGE;
        }
        return switch (name) {
            case "short_grass", "grass", "fern", "tall_grass", "large_fern",
                 "sugar_cane", "seagrass", "tall_seagrass", "vine" -> TintType.GRASS;
            default -> null;
        };
    }

    private BakedQuad[] missingQuads() {
        BakedQuad[] quads = missingQuads;
        if (quads == null) {
            synchronized (this) {
                if (missingQuads == null) {
                    missingQuads = baker.missingCube();
                }
                quads = missingQuads;
            }
        }
        return quads;
    }
}
