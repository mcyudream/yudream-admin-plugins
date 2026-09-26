package online.yudream.base.plugin.mcpanel.application.service;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 整合包解析结果暂存：inspect（上传解析）与 modpack-apply（创建实例后安装）之间以
 * token 传递计划（数百条 URL 不适合经前端往返）。内存态 + 30 分钟 TTL + 容量上限；
 * take 后即删（一次性令牌），面板重启丢失仅要求重新上传解析。
 */
public final class ModpackInspectStore {

    /** 单条目：解析结果 + 到期时间。 */
    private record Slot(ModpackService.ImportResult result, long expiresAt, String format) {
    }

    private static final long TTL_MS = 30 * 60_000L;
    private static final int MAX_ENTRIES = 32;

    private static final ConcurrentHashMap<String, Slot> STORE = new ConcurrentHashMap<>();

    private ModpackInspectStore() {
    }

    public static String put(String token, String format, ModpackService.ImportResult result) {
        long now = System.currentTimeMillis();
        if (STORE.size() >= MAX_ENTRIES) {
            sweep(now);
            if (STORE.size() >= MAX_ENTRIES) {
                // 仍然满：丢最旧一条（按到期时间）。
                STORE.entrySet().stream()
                        .min(java.util.Comparator.comparingLong(entry -> entry.getValue().expiresAt()))
                        .ifPresent(entry -> STORE.remove(entry.getKey()));
            }
        }
        STORE.put(token, new Slot(result, now + TTL_MS, format));
        return token;
    }

    /** 一次性取出：返回 null 表示 token 不存在/过期/已用。 */
    public static ModpackService.ImportResult take(String token) {
        Slot slot = token == null ? null : STORE.remove(token);
        if (slot == null || slot.expiresAt() < System.currentTimeMillis()) {
            return null;
        }
        return slot.result();
    }

    private static void sweep(long now) {
        Iterator<Map.Entry<String, Slot>> iterator = STORE.entrySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getValue().expiresAt() < now) {
                iterator.remove();
            }
        }
    }
}
