package online.yudream.plugin.worldmap.infrastructure.render;

import online.yudream.plugin.worldmap.infrastructure.resource.BakedQuad;
import online.yudream.plugin.worldmap.infrastructure.resource.BlockModelRegistry;
import online.yudream.plugin.worldmap.infrastructure.world.BlockState;

import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * 逐方块状态预计算六向"全封闭"遮挡掩码与半透明标记。
 *
 * <p>某方向封闭 = 存在一个非半透明面片：cullface 指向该方向、位于对应平面、
 * 且覆盖整个 16×16 面（原版全立方体方块满足）。面剔除时，
 * 面片 cullface 为 d 且相邻方块在 d.opposite() 方向封闭，则跳过该面片。</p>
 *
 * <p>半透明标记用于 AO：半透明方块（玻璃/水等）不参与遮挡。</p>
 */
final class StateOcclusion {

    /** 分析结果。 */
    record Info(int fullMask, boolean translucent) {
        static final Info EMPTY = new Info(0, false);

        /** 该状态在方向 d 上是否全封闭。 */
        boolean seals(FaceDirection d) {
            return (fullMask & d.bit) != 0;
        }
    }

    private static final float EPS = 0.01f;

    private final BlockModelRegistry registry;
    /** 渲染为单线程，普通 HashMap 即可。 */
    private final Map<String, Info> cache = new HashMap<>();

    StateOcclusion(BlockModelRegistry registry) {
        this.registry = registry;
    }

    Info of(BlockState state) {
        if (state.isAir()) {
            return Info.EMPTY;
        }
        return cache.computeIfAbsent(key(state), k -> analyze(state));
    }

    private Info analyze(BlockState state) {
        int mask = 0;
        boolean translucent = false;
        for (BakedQuad q : registry.quadsFor(state)) {
            if (q.translucent()) {
                translucent = true;
                continue; // 半透明面片不算封闭（玻璃不剔除相邻面）
            }
            FaceDirection d = FaceDirection.byName(q.cullface());
            if (d != null && coversFullFace(q, d)) {
                mask |= d.bit;
            }
        }
        return new Info(mask, translucent);
    }

    /** 面片是否位于 d 方向平面上且覆盖整个 16×16 面。 */
    private static boolean coversFullFace(BakedQuad q, FaceDirection d) {
        float[] p = q.positions();
        int a = d.axis;
        int b = (a + 1) % 3;
        int c = (a + 2) % 3;
        float plane = d.positive ? 16f : 0f;
        float minB = Float.MAX_VALUE, maxB = -Float.MAX_VALUE;
        float minC = Float.MAX_VALUE, maxC = -Float.MAX_VALUE;
        for (int i = 0; i < 4; i++) {
            if (Math.abs(p[i * 3 + a] - plane) > EPS) {
                return false;
            }
            minB = Math.min(minB, p[i * 3 + b]);
            maxB = Math.max(maxB, p[i * 3 + b]);
            minC = Math.min(minC, p[i * 3 + c]);
            maxC = Math.max(maxC, p[i * 3 + c]);
        }
        return minB <= EPS && maxB >= 16f - EPS && minC <= EPS && maxC >= 16f - EPS;
    }

    /** 缓存键：方块名 + 排序后的属性串（与 registry 缓存键风格一致）。 */
    static String key(BlockState state) {
        return state.name() + "|" + new TreeMap<>(state.properties());
    }
}
