package online.yudream.base.plugin.ymcl.interfaces.support;

import online.yudream.base.plugin.ymcl.api.YmclPageDescriptor;
import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 自定义页面存储：管理员不依赖其他业务插件、直接在适配器里注册的页面。
 * 渲染器限定 bundle 类（extension 沙箱 / module 可信模块），页面体来自
 * /v1/bundles 上传的不可变 zip 包；文档以页面 id 为主键存于
 * {@code ymcl_custom_pages} 集合。
 *
 * 自定义页面与 SPI 提供方页面在导航配置、页面注册表与 manifest 下发中
 * 完全同权（同一 YmclPageDescriptor 模型）。
 */
public final class YmclCustomPages {

    public static final String COLLECTION = "ymcl_custom_pages";

    /** 自定义页面允许的渲染器：都必须携带 bundle 描述。 */
    public static final Set<String> RENDERERS = Set.of("extension", "module");

    /** module 页面可申请的宿主能力（与启动器 ModuleFrame 门控一致）。 */
    public static final Set<String> BUNDLE_PERMISSIONS = Set.of(
            "data.fetch", "action.execute", "open-url", "theme.read");

    private YmclCustomPages() {
    }

    /** 全量读取（文档存储单页 200 上限，翻页聚合）。 */
    public static List<YmclPageDescriptor> list(PluginDocumentStore documents) {
        List<YmclPageDescriptor> pages = new ArrayList<>();
        int page = 1;
        while (true) {
            List<Map<String, Object>> batch = documents.findAll(COLLECTION, page, 200);
            if (batch.isEmpty()) {
                break;
            }
            for (Map<String, Object> doc : batch) {
                YmclPageDescriptor descriptor = toDescriptor(doc);
                if (descriptor != null) {
                    pages.add(descriptor);
                }
            }
            page++;
        }
        return pages;
    }

    /** 文档 → 页面描述；缺 id/renderer 的脏数据跳过。 */
    @SuppressWarnings("unchecked")
    public static YmclPageDescriptor toDescriptor(Map<String, Object> doc) {
        String id = str(doc.get("id"));
        String renderer = str(doc.get("renderer"));
        if (id == null || renderer == null) {
            return null;
        }
        Map<String, Object> params = doc.get("params") instanceof Map<?, ?> map
                ? (Map<String, Object>) map : null;
        Map<String, Object> bundle = doc.get("bundle") instanceof Map<?, ?> map
                ? (Map<String, Object>) map : null;
        return new YmclPageDescriptor(
                id,
                renderer,
                str(doc.get("title")),
                str(doc.get("icon")),
                null,
                0,
                str(doc.get("requiredPermission")),
                params,
                bundle);
    }

    /** id 规范：小写 slug，禁止与 native 前缀冲突。 */
    public static boolean isValidId(String id) {
        return id != null
                && id.matches("[a-z0-9][a-z0-9._-]{0,63}")
                && !id.startsWith(YmclNativePages.PREFIX);
    }

    /** bundle 描述规范化：补齐下载 url 并校验必填字段，非法返回 null。 */
    public static Map<String, Object> normalizeBundle(Map<?, ?> raw) {
        String bundleId = str(raw.get("id"));
        String version = str(raw.get("version"));
        String entry = str(raw.get("entry"));
        String sha256 = str(raw.get("sha256"));
        if (bundleId == null || version == null || entry == null || sha256 == null) {
            return null;
        }
        if (bundleId.contains("/") || bundleId.contains("..")
                || version.contains("/") || version.contains("..")) {
            return null;
        }
        Map<String, Object> bundle = new LinkedHashMap<>();
        bundle.put("id", bundleId);
        bundle.put("version", version);
        bundle.put("entry", entry);
        bundle.put("sha256", sha256.toLowerCase());
        String url = str(raw.get("url"));
        bundle.put("url", url == null
                ? "/api/plugins/ymcl-adapter/v1/bundles/" + bundleId + "/" + version + "/package.zip"
                : url);
        if (raw.get("permissions") instanceof List<?> list) {
            List<String> permissions = new ArrayList<>();
            for (Object item : list) {
                String permission = str(item);
                if (permission != null && BUNDLE_PERMISSIONS.contains(permission) && !permissions.contains(permission)) {
                    permissions.add(permission);
                }
            }
            if (!permissions.isEmpty()) {
                bundle.put("permissions", permissions);
            }
        }
        return bundle;
    }

    private static String str(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }
}
