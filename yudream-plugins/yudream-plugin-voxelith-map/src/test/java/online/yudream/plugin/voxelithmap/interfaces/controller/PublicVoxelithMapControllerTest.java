package online.yudream.plugin.voxelithmap.interfaces.controller;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 公开地图控制器的 URL 布局回归测试。
 *
 * <p>背景（2026-09-25 实测）：上游 4200402 的 {@code tileUrl} 模板给瓦片地址加了
 * {@code .glb} 后缀，而控制器再补一次后缀，拼出 {@code {z}.glb.glb} → 全部瓦片 404；
 * 新引擎清单又把 LOD 图集页地址从 {@code lod-atlas/{lvl}.png} 挪到了
 * {@code tiles/lod/{lvl}/lod-atlas.png}，老端点段数对不上 → 宿主 400「插件接口不存在」。
 * 这里用真实请求路径（经 {@link RequestPaths#segments}）锁住三种推导。</p>
 */
class PublicVoxelithMapControllerTest {

    /** 新引擎（4200402+）：尾段自带 .glb。 */
    @Test
    void 新引擎瓦片地址带glb后缀时不再重复拼接() {
        assertEquals("tiles/hires/1/-4.glb",
                PublicVoxelithMapController.hiresRelative(
                        RequestPaths.segments("/maps/test-beb8a/tiles/hires/1/-4.glb")));
        assertEquals("tiles/lod/2/0/-1.glb",
                PublicVoxelithMapController.lodRelative(
                        RequestPaths.segments("/maps/test-beb8a/tiles/lod/2/0/-1.glb")));
    }

    /** 旧引擎：尾段是纯坐标，仍要补 .glb。 */
    @Test
    void 旧引擎瓦片地址不带后缀时补上glb() {
        assertEquals("tiles/hires/0/1.glb",
                PublicVoxelithMapController.hiresRelative(
                        RequestPaths.segments("/maps/m1/tiles/hires/0/1")));
        assertEquals("tiles/lod/1/0/-2.glb",
                PublicVoxelithMapController.lodRelative(
                        RequestPaths.segments("/maps/m1/tiles/lod/1/0/-2")));
    }

    /** 新引擎清单：LOD 图集页按 tiles/lod/{lvl}/ 下的文件名原样落 key。 */
    @Test
    void 新引擎图集页地址按清单布局推导() {
        assertEquals("tiles/lod/1/lod-atlas.png",
                PublicVoxelithMapController.lodAtlasNewLayoutRelative(
                        RequestPaths.segments("/maps/test-beb8a/tiles/lod/1/lod-atlas.png")));
    }

    /**
     * 2D 地图取图通道的挑页规则：level 最小（分辨率最高）的一页，url 为空的页跳过。
     *
     * <p>挑错高 level 只是模糊，挑到空 url 必然 404——规则简单但必须钉住。</p>
     */
    @Test
    void 图集数据端点挑分辨率最高的一页() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var manifest = mapper.readTree("""
                {"lodAtlases": [
                  {"level": 2, "url": "tiles/lod/2/lod-atlas.png", "sha1": "b"},
                  {"level": 1, "url": "tiles/lod/1/lod-atlas.png", "sha1": "a"},
                  {"level": 3, "url": "tiles/lod/3/lod-atlas.png", "sha1": "c"}
                ]}
                """);
        var page = PublicVoxelithMapController.preferredAtlasPage(manifest).orElseThrow();
        assertEquals("tiles/lod/1/lod-atlas.png", page.url());
        assertEquals("a", page.sha1());
        assertEquals(1, page.level());

        // 空 url 页跳过；全部不可用或没有 lodAtlases 时为空
        var blankUrls = mapper.readTree("{\"lodAtlases\": [{\"level\": 1, \"url\": \"\"}]}");
        assertTrue(PublicVoxelithMapController.preferredAtlasPage(blankUrls).isEmpty());
        var noPages = mapper.readTree("{}");
        assertTrue(PublicVoxelithMapController.preferredAtlasPage(noPages).isEmpty());
    }
}
