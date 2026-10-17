package online.yudream.plugin.voxelithmap.infrastructure.mcpanel;

import online.yudream.base.plugin.spi.core.PluginContext;
import online.yudream.plugin.voxelithmap.domain.value.InstanceDirEntry;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 面板契约拿不到时的降级语义，以及可用时的反射映射。
 *
 * <p>当前面板插件（0.22.6）没有实例文件契约，地图插件不能把契约类型当编译期依赖。这里用本地
 * 假契约验证：面板缺失、契约类不存在、服务未暴露、服务查询失败时都只报告不可用；契约可用时
 * 列目录 / 打包 / 分块读回 / 删除四个动作都能正确映射。</p>
 */
class McpanelInstanceFileAdapterTest {

    private static final String FAKE_CONTRACT = FakeMcpanelContract.class.getName();

    /** 只关心 dependencyAvailable / service 两个方法的假上下文。 */
    private static PluginContext context(boolean dependencyPresent, Object serviceValue, Throwable serviceError) {
        return (PluginContext) Proxy.newProxyInstance(
                McpanelInstanceFileAdapterTest.class.getClassLoader(),
                new Class<?>[] { PluginContext.class },
                (proxy, method, args) -> switch (method.getName()) {
                    case "dependencyAvailable" -> dependencyPresent;
                    case "service" -> {
                        if (serviceError != null) {
                            throw serviceError;
                        }
                        yield Optional.ofNullable(serviceValue);
                    }
                    case "toString" -> "fake-plugin-context";
                    case "hashCode" -> 1;
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static FakeMcpanelContract contract(List<String> deleted) {
        return new FakeMcpanelContract() {
            @Override
            public List<Entry> listDir(String instanceId, String path) {
                return List.of(new Entry("world", 0, true), new Entry("server-1.21.1.jar", 42, false));
            }

            @Override
            public long packToZip(String instanceId, List<String> paths, String dest) {
                return 123;
            }

            @Override
            public byte[] readArchiveRange(String instanceId, String path, long offset, int length) {
                return new byte[] {1, 2, 3};
            }

            @Override
            public void deleteFile(String instanceId, String path) {
                deleted.add(instanceId + ":" + path);
            }
        };
    }

    private static McpanelInstanceFileAdapter adapter(PluginContext context) {
        return new McpanelInstanceFileAdapter(context, FAKE_CONTRACT);
    }

    @Test
    void unavailableWhenPanelPluginIsNotEnabled() {
        McpanelInstanceFileAdapter adapter = adapter(context(false, contract(new ArrayList<>()), null));

        assertFalse(adapter.available());
        // 不可用时的调用必须给出可读原因，而不是 NoClassDefFoundError 或一句「请升级面板」
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> adapter.listDir("inst-1", "."));
        assertTrue(error.getMessage().contains("未提供实例文件访问契约"), error.getMessage());
    }

    @Test
    void unavailableWhenPanelHasNoContract() {
        // 当前面板 0.22.6：面板存在，但契约类根本不在类路径上
        McpanelInstanceFileAdapter adapter = new McpanelInstanceFileAdapter(
                context(true, contract(new ArrayList<>()), null),
                "online.yudream.base.plugin.mcpanel.api.MissingContract");

        assertFalse(adapter.available(), "契约缺失必须算作不可用，让上层降级而不是报错");
        assertThrows(IllegalStateException.class, () -> adapter.listDir("inst-1", "."));
    }

    @Test
    void unavailableWhenServiceIsNotExposed() {
        McpanelInstanceFileAdapter adapter = adapter(context(true, null, null));

        assertFalse(adapter.available());
    }

    @Test
    void unavailableWhenServiceLookupFails() {
        McpanelInstanceFileAdapter adapter = adapter(context(true, null,
                new NoClassDefFoundError("online/yudream/base/plugin/mcpanel/api/McpanelInstanceAccessApi")));

        assertFalse(adapter.available(), "服务查询失败必须收敛为不可用");
    }

    @Test
    void forwardsToContractWhenAvailable() {
        List<String> deleted = new ArrayList<>();
        McpanelInstanceFileAdapter adapter = adapter(context(true, contract(deleted), null));

        assertTrue(adapter.available());
        assertEquals(List.of(new InstanceDirEntry("world", 0, true),
                        new InstanceDirEntry("server-1.21.1.jar", 42, false)),
                adapter.listDir("inst-1", "."));
        assertEquals(123, adapter.packToZip("inst-1", List.of("world"), "pkg.zip"));
        assertEquals(3, adapter.readArchiveRange("inst-1", "pkg.zip", 0, 8).length);
        adapter.deleteFile("inst-1", "pkg.zip");
        assertEquals(List.of("inst-1:pkg.zip"), deleted);
    }
}
