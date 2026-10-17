package online.yudream.plugin.voxelithmap.infrastructure.mcpanel;

import online.yudream.base.plugin.spi.core.PluginContext;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 与**当前面板插件**（0.22.6，没有实例文件契约）的兼容性。
 *
 * <p>这一类问题必须用「类加载器里根本没有契约类型」来验证，而不是用异常分支来模拟：当前面板在
 * 运行时就是这样——契约类不存在，任何在<b>类链接/验证期</b>触碰它的写法都会让插件启用失败或
 * 适配器加载失败（历史教训：适配器的构造签名带契约类型时，JVM 在类验证期就去加载该类型，
 * try/catch 拦不住，插件直接启用失败）。</p>
 *
 * <p>因此本测试把插件类装进一个**不含 mcpanel** 的隔离类加载器（父加载器为平台加载器，
 * 不继承应用类路径），再反射实例化适配器：只要有一处描述符/指令在链接期需要契约类型，
 * 这里就会抛 {@code NoClassDefFoundError}——测试随即失败。适配器现在只按类名反射查找契约，
 * 契约缺失时报告不可用并退回浏览器搬运。</p>
 */
class McpanelContractAbsentCompatibilityTest {

    private static final String ADAPTER =
            "online.yudream.plugin.voxelithmap.infrastructure.mcpanel.McpanelInstanceFileAdapter";

    /** 只含 SPI 与本插件类、**不含 mcpanel** 的隔离加载器（等价于当前面板 0.22.6 的运行时）。 */
    private static URLClassLoader isolatedLoader() {
        URL spi = codeSource(PluginContext.class);
        URL pluginClasses = codeSource(McpanelInstanceFileAdapter.class);
        assertNotNull(spi, "找不到 SPI 的类路径位置");
        assertNotNull(pluginClasses, "找不到插件编译产物位置");
        return new URLClassLoader(new URL[] {spi, pluginClasses}, ClassLoader.getPlatformClassLoader());
    }

    private static URL codeSource(Class<?> type) {
        var domain = type.getProtectionDomain();
        return domain == null || domain.getCodeSource() == null ? null : domain.getCodeSource().getLocation();
    }

    /** 面板插件「已启用但没有实例文件契约」：依赖检查通过，取服务时抛 NoClassDefFoundError。 */
    private static Object contextWithoutContract(ClassLoader loader, Class<?> contextType) {
        return Proxy.newProxyInstance(loader, new Class<?>[] {contextType}, (proxy, method, args) -> switch (method.getName()) {
            case "dependencyAvailable" -> true;
            case "service" -> throw new NoClassDefFoundError(
                    "online/yudream/base/plugin/mcpanel/api/McpanelInstanceAccessApi");
            case "toString" -> "fake-panel-context";
            case "hashCode" -> 1;
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException(method.getName());
        });
    }

    @Test
    void adapterLinksAndDegradesWhenProviderContractClassIsAbsent() throws Exception {
        try (URLClassLoader loader = isolatedLoader()) {
            Class<?> contextType = Class.forName(PluginContext.class.getName(), true, loader);
            Class<?> adapterType = Class.forName(ADAPTER, true, loader);
            Object context = contextWithoutContract(loader, contextType);
            Object adapter = adapterType.getConstructor(contextType).newInstance(context);

            // 加载/实例化/调用都不能因为缺少契约类型而抛 NoClassDefFoundError
            Method available = adapterType.getMethod("available");
            assertEquals(false, available.invoke(adapter), "契约不可用时必须报告不可用");

            Method listDir = adapterType.getMethod("listDir", String.class, String.class);
            InvocationTargetException failure = assertThrows(InvocationTargetException.class,
                    () -> listDir.invoke(adapter, "inst-1", "."));
            assertTrue(failure.getCause() instanceof IllegalStateException,
                    "不可用时应给出可读原因，实际：" + failure.getCause());
            assertFalse(failure.getCause() instanceof NoClassDefFoundError);
        }
    }

    /** 契约类确实不在隔离加载器里（否则本测试会失去意义）。 */
    @Test
    void isolatedLoaderReallyLacksTheProviderContract() {
        try (URLClassLoader loader = isolatedLoader()) {
            assertThrows(ClassNotFoundException.class,
                    () -> Class.forName("online.yudream.base.plugin.mcpanel.api.McpanelInstanceAccessApi", true, loader));
        }
        catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    /** 面板插件缺失时（dependencyAvailable=false）同样只降级，不抛链接错误。 */
    @Test
    void adapterDegradesWhenPanelPluginIsNotEnabled() throws Exception {
        try (URLClassLoader loader = isolatedLoader()) {
            Class<?> contextType = Class.forName(PluginContext.class.getName(), true, loader);
            Class<?> adapterType = Class.forName(ADAPTER, true, loader);
            Object context = Proxy.newProxyInstance(loader, new Class<?>[] {contextType},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "dependencyAvailable" -> false;
                        case "service" -> Optional.empty();
                        case "toString" -> "fake-context";
                        case "hashCode" -> 1;
                        case "equals" -> proxy == args[0];
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
            Object adapter = adapterType.getConstructor(contextType).newInstance(context);

            assertEquals(false, adapterType.getMethod("available").invoke(adapter));
            // 不可用的方法调用同样只给可读原因，不抛 NoClassDefFoundError
            Method packToZip = adapterType.getMethod("packToZip", String.class, java.util.List.class, String.class);
            InvocationTargetException failure = assertThrows(InvocationTargetException.class,
                    () -> packToZip.invoke(adapter, "inst-1", java.util.List.of("world"), "pkg.zip"));
            assertTrue(failure.getCause() instanceof IllegalStateException, String.valueOf(failure.getCause()));
        }
    }
}
