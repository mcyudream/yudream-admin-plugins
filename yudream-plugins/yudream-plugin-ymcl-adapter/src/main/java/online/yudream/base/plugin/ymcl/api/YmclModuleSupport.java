package online.yudream.base.plugin.ymcl.api;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 提供方 module 页面包打包工具：把 jar 内资源（自包含 ESM 入口）包装成
 * 确定性 zip（固定时间戳，内容不变则字节不变），并生成页面描述所需的
 * bundle 块（sha256 + 下载 url + 宿主能力 permissions）。
 *
 * 版本号取内容哈希前缀（{@code v<sha12>}）：资源内容变化自动产生新版本，
 * 启动器按 {bundleId}/{version} 缓存自然失效，无需手工管理版本号。
 */
public final class YmclModuleSupport {

    /** module 页面可申请的宿主能力（与启动器 ModuleFrame 门控一致）。 */
    public static final String PERMISSION_DATA_FETCH = "data.fetch";
    public static final String PERMISSION_ACTION_EXECUTE = "action.execute";
    public static final String PERMISSION_OPEN_URL = "open-url";
    public static final String PERMISSION_THEME_READ = "theme.read";

    private static final long FIXED_ZIP_TIME = 0L;

    private YmclModuleSupport() {
    }

    /** 读取 jar 内资源字节；资源缺失返回 null（调用方按降级处理）。 */
    public static byte[] resourceBytes(Class<?> anchor, String resourcePath) {
        try (InputStream input = anchor.getResourceAsStream(resourcePath)) {
            if (input == null) {
                return null;
            }
            return input.readAllBytes();
        } catch (Exception error) {
            return null;
        }
    }

    /** 单入口确定性 zip：固定时间戳与 UTF-8 名称，同内容必同字节。 */
    public static byte[] zipSingle(String entryName, byte[] content) {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
                ZipEntry entry = new ZipEntry(entryName);
                entry.setTime(FIXED_ZIP_TIME);
                zip.putNextEntry(entry);
                zip.write(content);
                zip.closeEntry();
            }
            return buffer.toByteArray();
        } catch (Exception error) {
            throw new IllegalStateException("zip bundle entry failed", error);
        }
    }

    public static String sha256Hex(byte[] bytes) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                hex.append(String.format("%02x", item));
            }
            return hex.toString();
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    /** 内容派生版本号：{@code v<sha256 前 12 位>}，内容变即版本变。 */
    public static String contentVersion(byte[] zipBytes) {
        return "v" + sha256Hex(zipBytes).substring(0, 12);
    }

    /**
     * 页面描述的 bundle 块：id/version/entry/sha256/url/permissions，
     * url 指向适配器统一下载端点（提供方包与上传包同路径下发）。
     */
    public static Map<String, Object> bundleDescriptor(
            String bundleId, String version, String entry, byte[] zipBytes, List<String> permissions) {
        Map<String, Object> bundle = new LinkedHashMap<>();
        bundle.put("id", bundleId);
        bundle.put("version", version);
        bundle.put("entry", entry);
        bundle.put("sha256", sha256Hex(zipBytes));
        bundle.put("url", "/api/plugins/ymcl-adapter/v1/bundles/" + bundleId + "/" + version + "/package.zip");
        if (permissions != null && !permissions.isEmpty()) {
            bundle.put("permissions", permissions);
        }
        return bundle;
    }

    /**
     * 一步完成「资源 → 贡献 + 描述」：读取 jar 资源、打 zip、算版本与
     * sha256。资源缺失返回 null（提供方降级为不声明页面）。
     */
    public static YmclModuleBundle fromResource(
            Class<?> anchor, String resourcePath, String bundleId, String entry, List<String> permissions) {
        byte[] source = resourceBytes(anchor, resourcePath);
        if (source == null) {
            return null;
        }
        byte[] zip = zipSingle(entry, source);
        String version = contentVersion(zip);
        return new YmclModuleBundle(
                new YmclBundleContribution(bundleId, version, entry, zip),
                bundleDescriptor(bundleId, version, entry, zip, permissions));
    }

    /** 打包结果：贡献记录（bundles() 返回）与页面 bundle 块（pages() 引用）。 */
    public record YmclModuleBundle(YmclBundleContribution contribution, Map<String, Object> descriptor) {
    }
}
