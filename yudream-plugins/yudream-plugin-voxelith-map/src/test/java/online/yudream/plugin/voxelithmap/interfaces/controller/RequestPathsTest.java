package online.yudream.plugin.voxelithmap.interfaces.controller;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 路径取参与解码测试。
 *
 * <p>回归点：宿主交给插件的 path 来自 {@code HttpServletRequest.getRequestURI()}，
 * <b>不做百分号解码</b>。所以「中文名地图」的 mapId 在插件里必须被解回原样，
 * 否则 {@code MapProjectAppService.require} 会拿 {@code %E4%B8%87...} 去查库，
 * 报「地图不存在: %E4%B8%87...」——这正是线上点击中文地图详情时的故障。</p>
 */
class RequestPathsTest {

    /** 走一遍真实的编码路径形态（前端 encodeURIComponent 的输出）。 */
    private static final String ENCODED_ID = "%E4%B8%87%E4%BD%9B%E5%B1%B1%E5%9C%B0%E5%BD%A2-27c4c";
    private static final String RAW_ID = "万佛山地形-27c4c";

    @Test
    void 管理端路径里的中文地图id被解码() {
        assertEquals(RAW_ID, RequestPaths.segment("/admin/maps/" + ENCODED_ID, 2));
    }

    @Test
    void 公开瓦片路径的每段都被解码() {
        String[] segments = RequestPaths.segments("/maps/" + ENCODED_ID + "/tiles/hires/0/-1");
        assertArrayEquals(new String[]{"", "maps", RAW_ID, "tiles", "hires", "0", "-1"}, segments);
    }

    @Test
    void 代内产物相对路径由解码后的段拼出() {
        assertEquals("tiles/hires/0/1.glb",
                RequestPaths.join("/maps/" + ENCODED_ID + "/generations/gen01/tiles/hires/0/1.glb", 5));
        // 与未编码形态等价（浏览器也可能直接发原始 UTF-8）
        assertEquals("tiles/hires/0/1.glb",
                RequestPaths.join("/maps/" + RAW_ID + "/generations/gen01/tiles/hires/0/1.glb", 5));
    }

    @Test
    void 加号按字面量保留而不是变成空格() {
        assertEquals("a+b", RequestPaths.decode("a+b"));
        assertEquals("a+b", RequestPaths.segment("/admin/maps/a%2Bb", 2));
    }

    @Test
    void 未编码段与非法转义都原样返回() {
        assertEquals("plain-ascii-01", RequestPaths.decode("plain-ascii-01"));
        // %zz 不是合法转义：原样返回，交给后续按「查不到这个 id」处理，不在这里抛 500
        assertEquals("%zz", RequestPaths.decode("%zz"));
    }

    @Test
    void 段缺失或为空时抛错() {
        assertThrows(IllegalArgumentException.class, () -> RequestPaths.segment("/admin/maps", 2));
        assertThrows(IllegalArgumentException.class, () -> RequestPaths.segment("/admin/maps/", 2));
    }

    @Test
    void 尾部拼接越界时返回空串() {
        assertEquals("", RequestPaths.join("/maps/m1", 5));
    }

    /**
     * 查询串不得混进路径段。
     *
     * <p>前端给图集页/瓦片 URL 带 {@code ?sha=…} 破缓存；{@code getRequestURI()} 本不该带查询串，
     * 但只要经过一次带查询的转发/重写，它就会并进最后一段——图集端点按段拼存储 key，
     * {@code lod-atlas.png?sha=xxx} 按字面量永远查不到（2D 地图「加载失败」的候选根因）。</p>
     */
    @Test
    void 查询串被剪掉而不是混进最后一段() {
        assertEquals("lod-atlas.png",
                RequestPaths.segment("/maps/m1/tiles/lod/1/lod-atlas.png?sha=abc123", 5));
        assertEquals("tiles/lod/1/lod-atlas.png",
                RequestPaths.join("/maps/m1/tiles/lod/1/lod-atlas.png?sha=abc123", 3));
        assertEquals("m1", RequestPaths.segment("/maps/m1/manifest.json?x=1", 1));
    }
}
