package online.yudream.base.plugin.mcguess.infrastructure;

import java.io.IOException;
import java.io.InputStream;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 物品图标：classpath 资源 mcguess/icons/&lt;id&gt;.png 转 base64 data URI，供棋盘模板内联渲染。
 */
public final class IconSupport {

    private static final String ICON_PREFIX = "mcguess/icons/";

    private final ClassLoader classLoader;
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    public IconSupport(ClassLoader classLoader) {
        this.classLoader = classLoader;
    }

    /** 物品图标的 data URI；无图标时返回 null（模板显示占位符）。 */
    public String dataUri(String itemId) {
        if (itemId == null || itemId.isBlank()) {
            return null;
        }
        return cache.computeIfAbsent(itemId, this::loadDataUri);
    }

    private String loadDataUri(String itemId) {
        String resource = ICON_PREFIX + itemId + ".png";
        try (InputStream input = classLoader.getResourceAsStream(resource)) {
            if (input == null) {
                return null;
            }
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(input.readAllBytes());
        } catch (IOException e) {
            return null;
        }
    }
}
