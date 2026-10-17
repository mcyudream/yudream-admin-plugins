package online.yudream.plugin.voxelithmap.interfaces.controller;

import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;
import online.yudream.plugin.voxelithmap.application.service.MapProjectAppService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 端点路径布局回归：**路径没注册上/挂错位置**时前端只能看到「接口不存在」，
 * 而这类错误编译期完全看不出来（注解里写的是字符串）。
 *
 * <p>两条不变量：同一个方法不能有两条完全相同的路径（后注册者会覆盖前者）；维度端点的路径模板段数
 * 必须与控制器里取路径参数的下标对得上（取错段等于拿到别的 id）。</p>
 */
class VoxelithEndpointLayoutTest {

    /** 收集一个控制器里所有 {@code @PluginHttpEndpoint} 的「方法 + 路径」。 */
    private static List<String> endpointsOf(Class<?> controller) {
        List<String> endpoints = new ArrayList<>();
        for (Method method : controller.getDeclaredMethods()) {
            PluginHttpEndpoint annotation = method.getAnnotation(PluginHttpEndpoint.class);
            if (annotation != null) {
                endpoints.add(annotation.method().toUpperCase() + " " + annotation.path());
            }
        }
        return endpoints;
    }

    @Test
    void noDuplicateEndpointPaths() {
        for (Class<?> controller : List.of(AdminVoxelithMapController.class, PublicVoxelithMapController.class)) {
            List<String> endpoints = endpointsOf(controller);
            assertTrue(endpoints.contains("GET " + dimensionPath(controller)),
                    controller.getSimpleName() + " 缺少维度端点，路径写错时前端只会看到「接口不存在」");
            assertEquals(endpoints.size(), endpoints.stream().distinct().count(),
                    controller.getSimpleName() + " 存在重复路径（方法 + 路径），实际是 " + endpoints);
        }
    }

    private static String dimensionPath(Class<?> controller) {
        return controller == AdminVoxelithMapController.class
                ? "/admin/maps/{mapId}/dimensions"
                : "/maps/{mapId}/dimensions";
    }

    /**
     * 维度端点必须从正确的段取出地图 id。
     *
     * <p>用真实请求路径调用，锁住「模板段数 ↔ 取值下标」这一对：下标写错时取到的是
     * {@code dimensions} 或 {@code maps}，对外表现就是「接口不存在」或「查不到地图」。
     * 空仓储下必然抛「地图不存在」，断言报错里带的就是它取到的那个 id。</p>
     */
    @Test
    void dimensionEndpointsReadTheMapIdFromTheRightSegment() {
        // 只走到「查分组」这一步：编排器/预览/侧车都不参与，传 null 即可
        MapProjectAppService appService =
                new MapProjectAppService(new EmptyProjectRepo(), null, null, null, null, null, null, null);
        AdminVoxelithMapController admin = new AdminVoxelithMapController(appService, null, null, null,
                new com.fasterxml.jackson.databind.ObjectMapper(), null, null, null);
        PublicVoxelithMapController publicController = new PublicVoxelithMapController(appService, null);

        assertMapIdResolved("admin", () -> admin.dimensions(request("GET", "/admin/maps/m-1/dimensions")));
        assertMapIdResolved("public", () -> publicController.dimensions(request("GET", "/maps/m-1/dimensions")));
        // 2D 地图的取图通道（base64 JSON 直出）：路径无图片扩展名，绕开网关对图片路径的静态规则
        assertMapIdResolved("atlas-data", () -> publicController.lodAtlasData(request("GET", "/maps/m-1/lod-atlas-data")));
        // 分片取图通道：meta 与按 index 取片，段位取错同样会表现为「查不到地图」
        assertMapIdResolved("atlas-meta", () -> publicController.lodAtlasDataMeta(request("GET", "/maps/m-1/lod-atlas-data/meta")));
        // 稳定航拍图（2D 地图首选通道）
        assertMapIdResolved("atlas-image", () -> publicController.lodAtlasImage(request("GET", "/maps/m-1/lod-atlas.png")));
        assertMapIdResolved("atlas-chunk", () -> publicController.lodAtlasDataChunk(request("GET", "/maps/m-1/lod-atlas-chunks/0")));
    }

    /** 断言处理器确实从路径里读到了预期的地图 id。 */
    private static void assertMapIdResolved(String who, Runnable call) {
        try {
            call.run();
            org.junit.jupiter.api.Assertions.fail(who + "：空仓储里不该查出维度");
        }
        catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("m-1"),
                    who + " 端点的路径段位取错了：报错里应当是取到的地图 id，实际是 " + e.getMessage());
        }
    }

    /** 最小请求：{@link PluginHttpRequest} 是 record，直接按 (method, path, headers, query, body, principal) 构造。 */
    private static PluginHttpRequest request(String method, String path) {
        return new PluginHttpRequest(method, path, java.util.Map.of(), java.util.Map.of(), null, null);
    }

    /** 空仓储：只为验证「按段取 id」这一件事，不需要任何地图数据。 */
    private static final class EmptyProjectRepo
            implements online.yudream.plugin.voxelithmap.domain.repo.MapProjectRepo {

        @Override
        public online.yudream.plugin.voxelithmap.domain.aggregate.MapProject save(
                online.yudream.plugin.voxelithmap.domain.aggregate.MapProject project) {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.util.Optional<online.yudream.plugin.voxelithmap.domain.aggregate.MapProject> findById(
                String mapId) {
            return java.util.Optional.empty();
        }

        @Override
        public List<online.yudream.plugin.voxelithmap.domain.aggregate.MapProject> findAll() {
            return List.of();
        }

        @Override
        public void delete(String mapId) {
        }

        @Override
        public long count() {
            return 0;
        }
    }
}
