package online.yudream.base.plugin.mcguess.application;

import online.yudream.base.plugin.mcguess.domain.McCatalog;
import online.yudream.base.plugin.mcguess.domain.McItem;
import online.yudream.base.plugin.mcguess.infrastructure.IconSupport;
import online.yudream.base.plugin.spi.system.messaging.PluginEvent;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 比大小（Higher or Lower）用例编排：个人连胜挑战，不做群对局文档。
 * 每轮展示物品 A 与其「在全部合成配方中的出现次数」，玩家判断物品 B 的出现次数更高还是更低；
 * 答对 B 晋级为新 A 并抽取新 B，连胜 +1；答错连胜清零并揭晓答案。
 * 状态持久化在玩家文档（holA/holB/holStreak/holBest），必须绑定系统账号。
 */
public class HolAppService {

    private final McCatalog catalog;
    private final IconSupport icons;
    private final McguessSupport support;
    private final Random random = new Random();
    /** 出题池：带图标且至少在一条合成链中出现过的物品，惰性构建。 */
    private volatile List<McItem> pool;

    public HolAppService(McCatalog catalog, IconSupport icons, McguessSupport support) {
        this.catalog = catalog;
        this.icons = icons;
        this.support = support;
    }

    // ---------------------------------------------------------------- 群聊指令用例

    /** /比大小：未开局时发牌开新局，进行中时重播当前局面。 */
    public String play(PluginEvent event, Long userId) {
        String userIdString = userIdString(userId);
        if (userIdString == null) {
            return bindPrompt();
        }
        synchronized (support.lockFor("hol", userIdString)) {
            String result = support.withPlayerResult(userIdString, event.userId(), player -> {
                if (!player.holInProgress()) {
                    String a = randomFromPool();
                    String b = dealNext(a, catalog.occurrenceScore(a));
                    player.startHol(a, b);
                    return "🃏 比大小开局！当前连胜 " + player.getHolStreak() + "，历史最佳 " + player.getHolBest()
                            + "。\nA「" + zhOf(a) + "」在全部合成配方中共出现 " + catalog.occurrenceScore(a)
                            + " 次，B「" + zhOf(b) + "」的出现次数比 A 更高还是更低？\n发送 /高 或 /低 作答。";
                }
                return "🃏 比大小进行中（连胜 " + player.getHolStreak() + "）：A「" + zhOf(player.getHolA())
                        + "」出现 " + catalog.occurrenceScore(player.getHolA()) + " 次，B「" + zhOf(player.getHolB())
                        + "」的出现次数比 A 更高还是更低？\n发送 /高 或 /低 作答。";
            });
            return result == null ? bindPrompt() : result;
        }
    }

    /** /高 /低：判断 B 的出现次数相对 A 更高（higher=true）或更低。 */
    public String answer(PluginEvent event, Long userId, boolean higher) {
        String userIdString = userIdString(userId);
        if (userIdString == null) {
            return bindPrompt();
        }
        synchronized (support.lockFor("hol", userIdString)) {
            String result = support.withPlayerResult(userIdString, event.userId(), player -> {
                if (!player.holInProgress()) {
                    return "你还没有进行中的比大小，发送 /比大小 先开一局！";
                }
                String a = player.getHolA();
                String b = player.getHolB();
                int scoreA = catalog.occurrenceScore(a);
                int scoreB = catalog.occurrenceScore(b);
                boolean correct = higher == (scoreB > scoreA);
                player.recordGuess(System.currentTimeMillis());
                if (correct) {
                    String newB = dealNext(b, scoreB);
                    player.advanceHol(newB);
                    return "✅ 回答正确！「" + zhOf(b) + "」出现 " + scoreB + " 次，连胜 " + player.getHolStreak()
                            + "（历史最佳 " + player.getHolBest() + "）。"
                            + "\n新一轮：A「" + zhOf(b) + "」出现 " + scoreB + " 次，B「" + zhOf(newB)
                            + "」比它更高还是更低？发送 /高 或 /低。";
                }
                int streak = player.getHolStreak();
                player.clearHol();
                return "❌ 答错了！「" + zhOf(b) + "」实际出现 " + scoreB + " 次（A「" + zhOf(a) + "」是 "
                        + scoreA + " 次）。"
                        + "\n本局连胜止步 " + streak + "，历史最佳 " + player.getHolBest() + "。发送 /比大小 再来一局！";
            });
            return result == null ? bindPrompt() : result;
        }
    }

    // ---------------------------------------------------------------- 棋盘图片渲染

    /** 当前玩家的比大小棋盘渲染变量；未绑定或未开局时返回 null（走纯文本回复）。 */
    public Map<String, Object> boardVariables(Long userId, String banner) {
        String userIdString = userIdString(userId);
        if (userIdString == null) {
            return null;
        }
        synchronized (support.lockFor("hol", userIdString)) {
            Map<String, Object> variables = support.withPlayerResult(userIdString, null, player -> {
                if (!player.holInProgress()) {
                    return null;
                }
                String a = player.getHolA();
                String b = player.getHolB();
                Map<String, Object> vars = new HashMap<>();
                vars.put("title", "MC 比大小");
                vars.put("subtitle", "全部合成配方中出现次数 · Higher or Lower · JE 1.20.5");
                vars.put("aZh", zhOf(a));
                vars.put("aIcon", icons.dataUri(a));
                vars.put("aScore", catalog.occurrenceScore(a));
                vars.put("bZh", zhOf(b));
                vars.put("bIcon", icons.dataUri(b));
                vars.put("streak", player.getHolStreak());
                vars.put("best", player.getHolBest());
                vars.put("banner", banner);
                return vars;
            });
            return variables;
        }
    }

    // ---------------------------------------------------------------- 内部支撑

    private List<McItem> pool() {
        List<McItem> cached = pool;
        if (cached == null) {
            synchronized (this) {
                cached = pool;
                if (cached == null) {
                    cached = catalog.iconItems().stream()
                            .filter(item -> catalog.occurrenceScore(item.id()) > 0)
                            .toList();
                    pool = cached;
                }
            }
        }
        return cached;
    }

    private String randomFromPool() {
        List<McItem> candidates = pool();
        return candidates.get(random.nextInt(candidates.size())).id();
    }

    /** 抽一张与 avoid 不同且出现次数不同的 B（次数相同无法判定高低）。 */
    private String dealNext(String avoidId, int avoidScore) {
        List<McItem> candidates = pool();
        for (int attempt = 0; attempt < 200; attempt++) {
            McItem candidate = candidates.get(random.nextInt(candidates.size()));
            if (!candidate.id().equals(avoidId) && catalog.occurrenceScore(candidate.id()) != avoidScore) {
                return candidate.id();
            }
        }
        for (McItem candidate : candidates) {
            if (!candidate.id().equals(avoidId) && catalog.occurrenceScore(candidate.id()) != avoidScore) {
                return candidate.id();
            }
        }
        throw new IllegalStateException("比大小出题池中没有可比较的物品");
    }

    private String bindPrompt() {
        return "比大小是连胜挑战，需要先绑定系统账号才能记录成绩。发送 /绑定 完成绑定后再来！";
    }

    private String zhOf(String itemId) {
        return catalog.byId(itemId).map(McItem::zh).orElse(itemId);
    }

    private String userIdString(Long userId) {
        return userId == null ? null : String.valueOf(userId);
    }
}
