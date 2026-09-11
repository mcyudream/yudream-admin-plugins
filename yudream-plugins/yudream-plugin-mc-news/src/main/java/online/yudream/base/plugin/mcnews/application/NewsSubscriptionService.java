package online.yudream.base.plugin.mcnews.application;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import online.yudream.base.plugin.mcnews.infrastructure.McNewsStore;

/**
 * 用户订阅状态：是否通过宿主消息绑定私信接收新新闻（mc_news_subs，每用户一份）。
 * 个人 Webhook 存放在推送目标集合中，由 NewsTargetService 按归属维护。
 */
public final class NewsSubscriptionService {
    private final McNewsStore store;

    public NewsSubscriptionService(McNewsStore store) {
        this.store = store;
    }

    public boolean directEnabled(String userId) {
        return store.find(McNewsStore.COL_SUBS, userId)
                .map(doc -> McNewsStore.bool(doc, "directEnabled", false))
                .orElse(false);
    }

    public void setDirectEnabled(String userId, boolean enabled) {
        Map<String, Object> doc = new HashMap<>();
        doc.put("userId", userId);
        doc.put("directEnabled", enabled);
        doc.put("updatedAt", System.currentTimeMillis());
        store.save(McNewsStore.COL_SUBS, userId, doc);
    }

    /** 开启私信推送的全部用户。 */
    public List<String> directSubscribers() {
        return store.all(McNewsStore.COL_SUBS).stream()
                .filter(doc -> McNewsStore.bool(doc, "directEnabled", false))
                .map(doc -> McNewsStore.str(doc, "userId"))
                .filter(userId -> !userId.isBlank())
                .toList();
    }
}
