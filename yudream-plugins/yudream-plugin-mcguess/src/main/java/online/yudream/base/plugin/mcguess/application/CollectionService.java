package online.yudream.base.plugin.mcguess.application;

import online.yudream.base.plugin.mcguess.domain.McCatalog;
import online.yudream.base.plugin.mcguess.domain.McItem;
import online.yudream.base.plugin.mcguess.domain.McguessPlayer;
import online.yudream.base.plugin.mcguess.domain.McguessPlayerRepository;
import online.yudream.base.plugin.mcguess.infrastructure.IconSupport;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 物品图鉴收集：各模式获胜 / 宾果点亮 / 快答答对时收集对应物品，
 * /图鉴 查看个人收集进度（图片），/图鉴排行 查看收集榜。
 */
public class CollectionService {

    /** 图鉴图片中最多展示的最近收集物品数。 */
    private static final int RECENT_LIMIT = 54;
    /** 排行榜取前 N 名。 */
    private static final int LEADERBOARD_LIMIT = 10;
    /** 排行统计扫描的玩家上限。 */
    private static final int LEADERBOARD_SCAN = 500;

    private final McguessPlayerRepository players;
    private final McCatalog catalog;
    private final IconSupport icons;

    public CollectionService(McguessPlayerRepository players, McCatalog catalog, IconSupport icons) {
        this.players = players;
        this.catalog = catalog;
        this.icons = icons;
    }

    /** /图鉴：未绑定或未收集过时返回 null（走纯文本回复），否则返回渲染变量。 */
    public Map<String, Object> collectionVariables(Long userId, String banner) {
        if (userId == null) {
            return null;
        }
        McguessPlayer player = players.findByUserId(String.valueOf(userId)).orElse(null);
        if (player == null) {
            return null;
        }
        List<Map<String, Object>> recent = new ArrayList<>();
        List<String> items = player.collectionItems();
        int from = Math.max(0, items.size() - RECENT_LIMIT);
        for (int i = from; i < items.size(); i++) {
            String itemId = items.get(i);
            Map<String, Object> cell = new HashMap<>();
            cell.put("zh", catalog.byId(itemId).map(McItem::zh).orElse(itemId));
            cell.put("icon", icons.dataUri(itemId));
            recent.add(cell);
        }
        Map<String, Object> variables = new HashMap<>();
        variables.put("title", "MC 物品图鉴");
        variables.put("subtitle", "游玩各模式收集物品 · JE 1.20.5");
        variables.put("nickname", player.getNickname() == null || player.getNickname().isBlank()
                ? "QQ " + player.getQq() : player.getNickname());
        variables.put("collected", player.collectionSize());
        variables.put("total", catalog.iconItems().size());
        variables.put("recent", recent);
        variables.put("recentNote", items.size() > RECENT_LIMIT ? "仅展示最近收集的 " + RECENT_LIMIT + " 件" : null);
        variables.put("banner", banner);
        return variables;
    }

    /** /图鉴 的纯文本提示（未绑定 / 还没有收集任何物品时）。 */
    public String collectionText(Long userId) {
        if (userId == null) {
            return "图鉴收集需要绑定系统账号。发送 /绑定 完成绑定后再来！";
        }
        return "🎴 你还没有收集到任何图鉴物品。游玩 猜物 / 猜合成 / 迷雾 / 找茬 获胜，"
                + "或在宾果中点亮格子、快答中答对题目，即可收集对应物品！";
    }

    /** /图鉴排行：按收集数量排序的全服榜单（纯文本）。 */
    public String collectionLeaderboardText() {
        List<McguessPlayer> all = players.search(1, LEADERBOARD_SCAN);
        List<McguessPlayer> ranked = all.stream()
                .filter(player -> player.collectionSize() > 0)
                .sorted(Comparator.comparingInt(McguessPlayer::collectionSize).reversed())
                .limit(LEADERBOARD_LIMIT)
                .toList();
        if (ranked.isEmpty()) {
            return "🎴 图鉴排行暂无数据，快去游玩收集物品吧！";
        }
        StringBuilder text = new StringBuilder("🎴 MC 图鉴收集排行（共 " + catalog.iconItems().size() + " 件带图标物品）：");
        int rank = 1;
        for (McguessPlayer player : ranked) {
            String name = player.getNickname() == null || player.getNickname().isBlank()
                    ? "QQ " + player.getQq() : player.getNickname();
            text.append("\n").append(rank++).append(". ").append(name)
                    .append(" — 已收集 ").append(player.collectionSize()).append(" 件");
        }
        return text.toString();
    }
}
