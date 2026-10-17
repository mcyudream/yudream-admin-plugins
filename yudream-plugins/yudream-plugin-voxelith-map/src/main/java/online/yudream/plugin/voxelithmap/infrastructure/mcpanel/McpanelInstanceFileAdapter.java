package online.yudream.plugin.voxelithmap.infrastructure.mcpanel;

import online.yudream.base.plugin.spi.core.PluginContext;
import online.yudream.plugin.voxelithmap.domain.port.InstanceFilePort;
import online.yudream.plugin.voxelithmap.domain.value.InstanceDirEntry;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * {@link InstanceFilePort} 的面板实现：本插件内**唯一**接触 mcpanel 实例文件契约的地方。
 *
 * <p>当前面板插件（0.22.6）没有实例文件契约，因此本类不能把它当作编译期类型依赖——否则地图插件
 * 会依赖一个尚未发布的面板版本，连构建都过不去。这里改为在 {@code dependencyAvailable("mcpanel")}
 * 守卫之后，按稳定的契约类名反射查找服务：契约存在时在服务端执行实例导入；不存在时报告不可用，
 * 由上层自动退回浏览器搬运（面板既有 HTTP 端点），插件主体（渲染、查看、标注）完全不受影响。</p>
 *
 * <p>三条纪律保持不变：</p>
 * <ol>
 *   <li>契约只在本类出现，入口类没有任何 mcpanel 类型引用；</li>
 *   <li>API 对象**每次现取**，不跨 provider disable/reload 缓存；</li>
 *   <li>任何 {@link LinkageError} / {@link RuntimeException} 都收敛为「不可用」。</li>
 * </ol>
 */
public final class McpanelInstanceFileAdapter implements InstanceFilePort {

    /** 面板插件 code（与 mcpanel 的 plugin.yml name 一致）。 */
    public static final String PLUGIN_CODE = "mcpanel";

    /** 面板实例文件契约的稳定类名：只按名字反射查找，不形成编译期依赖。 */
    static final String CONTRACT_CLASS = "online.yudream.base.plugin.mcpanel.api.McpanelInstanceAccessApi";

    private final PluginContext context;
    private final String contractClassName;

    public McpanelInstanceFileAdapter(PluginContext context) {
        this(context, CONTRACT_CLASS);
    }

    /** 测试接缝：允许用本地假契约验证反射映射，不触碰真实面板类型。 */
    McpanelInstanceFileAdapter(PluginContext context, String contractClassName) {
        this.context = context;
        this.contractClassName = contractClassName;
    }

    /**
     * 面板实例文件访问是否可用。
     *
     * <p>面板插件在但**拿不到契约**（当前 0.22.6 没有这个 API，或契约类在本插件的类加载器里
     * 不可见）时，服务端导入同样做不成。这里把这种情况一并算作不可用，由上层直接走浏览器搬运的
     * 降级路径——而不是等作业跑起来再报一句需要升级面板的错误。</p>
     */
    @Override
    public boolean available() {
        return api().isPresent();
    }

    @Override
    public List<InstanceDirEntry> listDir(String instanceId, String path) {
        Object raw = invoke(requireApi(), "listDir",
                new Class<?>[] {String.class, String.class}, instanceId, path);
        if (!(raw instanceof Iterable<?> entries)) {
            return List.of();
        }
        List<InstanceDirEntry> result = new ArrayList<>();
        for (Object entry : entries) {
            if (entry == null) {
                continue;
            }
            Object name = invoke(entry, "name", new Class<?>[0]);
            Object size = invoke(entry, "size", new Class<?>[0]);
            Object directory = invoke(entry, "directory", new Class<?>[0]);
            result.add(new InstanceDirEntry(
                    name == null ? "" : String.valueOf(name),
                    size instanceof Number number ? number.longValue() : 0L,
                    Boolean.TRUE.equals(directory)));
        }
        return List.copyOf(result);
    }

    @Override
    public long packToZip(String instanceId, List<String> paths, String dest) {
        Object value = invoke(requireApi(), "packToZip",
                new Class<?>[] {String.class, List.class, String.class}, instanceId, paths, dest);
        return value instanceof Number number ? number.longValue() : 0L;
    }

    @Override
    public byte[] readArchiveRange(String instanceId, String path, long offset, int length) {
        Object value = invoke(requireApi(), "readArchiveRange",
                new Class<?>[] {String.class, String.class, long.class, int.class},
                instanceId, path, offset, length);
        return value instanceof byte[] bytes ? bytes : new byte[0];
    }

    @Override
    public void deleteFile(String instanceId, String path) {
        invoke(requireApi(), "deleteFile",
                new Class<?>[] {String.class, String.class}, instanceId, path);
    }

    /**
     * 取面板契约对象；拿不到就返回空（面板未装/未启用，或当前面板没有该契约）。
     *
     * <p>先用 {@code dependencyAvailable} 做一次不加载契约类的存在性检查，再按类名反射解析；
     * 因此面板插件缺失、当前 0.22.6 没有契约、或契约类不可见，都只是降级，不会把插件打崩。</p>
     */
    private Optional<Api> api() {
        if (context == null) {
            return Optional.empty();
        }
        try {
            if (!context.dependencyAvailable(PLUGIN_CODE)) {
                return Optional.empty();
            }
            Class<?> contract = Class.forName(contractClassName, false, getClass().getClassLoader());
            @SuppressWarnings({"unchecked", "rawtypes"})
            Optional<Object> service = (Optional) context.service(PLUGIN_CODE, (Class) contract);
            return service.map(value -> new Api(contract, value));
        }
        catch (ClassNotFoundException | RuntimeException | LinkageError e) {
            return Optional.empty();
        }
    }

    /** 需要契约的方法入口：不可用时抛出可读原因（上层已用 available() 决策，这里是最后一道防线）。 */
    private Api requireApi() {
        return api().orElseThrow(() -> new IllegalStateException(
                "MC 面板插件未提供实例文件访问契约，服务端导入不可用（将改用浏览器搬运方式）"));
    }

    private static Object invoke(Api api, String method, Class<?>[] types, Object... args) {
        return invoke(api.service(), api.contract(), method, types, args);
    }

    private static Object invoke(Object target, String method, Class<?>[] types, Object... args) {
        return invoke(target, target.getClass(), method, types, args);
    }

    private static Object invoke(Object target, Class<?> type, String method, Class<?>[] types, Object... args) {
        try {
            return type.getMethod(method, types).invoke(target, args);
        }
        catch (NoSuchMethodException | IllegalAccessException e) {
            throw new IllegalStateException("面板实例文件契约不可用：" + e.getMessage(), e);
        }
        catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("面板实例文件契约调用失败", cause);
        }
    }

    private record Api(Class<?> contract, Object service) {
    }
}
