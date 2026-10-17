package online.yudream.plugin.voxelithmap.infrastructure.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 渲染超时按窗口规模自适应。
 *
 * <p>起因：大型地图渲染超时不是跑挂，而是固定上限套不住真实耗时——实测烘焙约 0.017–0.034 秒/区块，
 * 五万多区块光烘焙就 900 秒，再叠加多遍/采集/瓦片/LOD，十几万区块撞穿默认 180 分钟很正常。
 * 堆早就按区块预配，超时也必须同源自适应，且只放宽不缩小（尊重管理员显式配置）。</p>
 */
class RenderBudgetTest {

    @Test
    void smallWindowsKeepTheConfiguredTimeout() {
        // 几千区块的小图远用不到放宽：配置值就是上限（也不该被程序改小）
        assertEquals(180, RenderBudget.timeoutMinutes(180, 2_771));
        assertEquals(600, RenderBudget.timeoutMinutes(600, 2_771));
        assertEquals(180, RenderBudget.timeoutMinutes(180, 0));
    }

    @Test
    void largeWindowsScaleUpWithTheChunkCount() {
        // 54528 区块（线上那次）按时长预算应显著高于默认 180 分钟
        int minutes = RenderBudget.timeoutMinutes(180, 54_528);
        assertTrue(minutes > 180, "大型窗口必须放宽：" + minutes);
        assertEquals(546, minutes);

        // 十几万区块继续放大，但封顶 24 小时
        assertEquals(RenderBudget.MAX_ADAPTIVE_MINUTES, RenderBudget.timeoutMinutes(180, 5_000_000));
    }

    @Test
    void explicitLargerTimeoutIsNeverShrunk() {
        assertEquals(1200, RenderBudget.timeoutMinutes(1200, 2_771));
        assertEquals(1200, RenderBudget.timeoutMinutes(1200, 10_000));
    }

    @Test
    void unknownSizeFallsBackToTheConfiguredValue() {
        // 整世界渲染估不出区块数：不凭空放大，交回配置（日志会提示可调高该设置）
        assertEquals(180, RenderBudget.timeoutMinutes(180, -1));
        // 配置值非法（<=0）时回退到默认 180 分钟（与 VoxelithRuntimeConfig 的口径一致）
        assertEquals(180, RenderBudget.timeoutMinutes(0, -1));
    }
}
