package online.yudream.base.plugin.projectprogress.support;

import online.yudream.base.plugin.spi.core.PluginContext;
import online.yudream.base.plugin.spi.system.storage.PluginFileStore;

import java.lang.reflect.Proxy;
import java.util.List;

/**
 * 测试用插件上下文：只实现本契约用到的行为（{@code extensions} 返回给定的监听者列表），
 * 其余 SPI 方法调用即报错，避免为测试造一个庞大的上下文实现。
 *
 * <p>扩展点列表是同一个可变列表的引用：调用方注册/清空监听者后，无需重建上下文。</p>
 */
public final class FakePluginContext {

    private FakePluginContext() {
    }

    public static PluginContext withExtensions(List<?> extensions) {
        return (PluginContext) Proxy.newProxyInstance(FakePluginContext.class.getClassLoader(),
                new Class<?>[]{PluginContext.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "extensions" -> extensions;
                    case "pluginCode" -> "project-progress-test";
                    case "toString" -> "FakePluginContext";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException("测试上下文未实现：" + method.getName());
                });
    }

    /** 测试用文件存储：验收链路上不会被调用，调用即报错。 */
    public static PluginFileStore fileStore() {
        return (PluginFileStore) Proxy.newProxyInstance(FakePluginContext.class.getClassLoader(),
                new Class<?>[]{PluginFileStore.class},
                (proxy, method, args) -> {
                    throw new UnsupportedOperationException("测试文件存储未实现：" + method.getName());
                });
    }
}
