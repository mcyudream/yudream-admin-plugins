package online.yudream.base.plugin.questionbank.application;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import online.yudream.base.plugin.questionbank.domain.Question;
import online.yudream.base.plugin.questionbank.domain.QuizScore;
import online.yudream.base.plugin.questionbank.infrastructure.Ids;
import online.yudream.base.plugin.questionbank.infrastructure.QuizScoreRepository;
import online.yudream.base.plugin.spi.system.FrameworkServices;
import online.yudream.base.plugin.spi.system.user.PluginUserProfile;

/**
 * QQ 群抢答积分：答对记录按 QQ 号落库，排行榜按 QQ 聚合并实时反解绑定的系统账号。
 * 用户端排行榜只暴露聚合后的展示名与题数，不下发原始 QQ 号。
 */
public final class QuizScoreService {
    private static final int MAX_LEADERBOARD = 100;

    private final QuizScoreRepository scores;
    private final FrameworkServices framework;

    public QuizScoreService(QuizScoreRepository scores, FrameworkServices framework) {
        this.scores = scores;
        this.framework = framework;
    }

    /** 记录一次抢答答对，返回该 QQ 累计答对题数。 */
    public int recordWin(String qq, String connectionId, String channelId, Question question) {
        if (qq == null || qq.isBlank()) {
            return 0;
        }
        scores.save(new QuizScore(Ids.newId(), qq, connectionId, channelId,
                question.id(), question.type().name(), System.currentTimeMillis()));
        return scoreOf(qq);
    }

    /** 该 QQ 累计答对题数。 */
    public int scoreOf(String qq) {
        int total = 0;
        for (QuizScore score : scores.listAll()) {
            if (qq.equals(score.qq())) {
                total++;
            }
        }
        return total;
    }

    /** 全量排行榜（用户端页面，未绑定账号的 QQ 号遮蔽中段）。 */
    public List<Map<String, Object>> leaderboard() {
        return aggregate(scores.listAll(), true);
    }

    /** 指定群聊的排行榜（QQ 指令展示用，群成员互相可见 QQ 号，不遮蔽）。 */
    public List<Map<String, Object>> channelLeaderboard(String connectionId, String channelId) {
        List<QuizScore> scoped = scores.listAll().stream()
                .filter(score -> java.util.Objects.equals(connectionId, score.connectionId())
                        && java.util.Objects.equals(channelId, score.channelId()))
                .toList();
        return aggregate(scoped, false);
    }

    private List<Map<String, Object>> aggregate(List<QuizScore> scoped, boolean mask) {
        Map<String, long[]> byQq = new HashMap<>();
        for (QuizScore score : scoped) {
            if (score.qq() == null || score.qq().isBlank()) {
                continue;
            }
            long[] slot = byQq.computeIfAbsent(score.qq(), key -> new long[2]);
            slot[0]++;
            slot[1] = Math.max(slot[1], score.answeredAt());
        }
        List<Map.Entry<String, long[]>> ranked = new ArrayList<>(byQq.entrySet());
        ranked.sort((a, b) -> {
            int byCount = Long.compare(b.getValue()[0], a.getValue()[0]);
            return byCount != 0 ? byCount : Long.compare(b.getValue()[1], a.getValue()[1]);
        });
        List<Map<String, Object>> result = new ArrayList<>();
        int rank = 0;
        for (Map.Entry<String, long[]> entry : ranked) {
            if (rank >= MAX_LEADERBOARD) {
                break;
            }
            rank++;
            String qq = entry.getKey();
            Optional<PluginUserProfile> profile = findByQq(qq);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("rank", rank);
            item.put("name", profile.map(QuizScoreService::displayName).orElse(mask ? maskQq(qq) : qq));
            item.put("bound", profile.isPresent());
            item.put("score", entry.getValue()[0]);
            item.put("lastAt", entry.getValue()[1]);
            result.add(item);
        }
        return result;
    }

    /** 该 QQ 是否已绑定系统账号。 */
    public boolean isBound(String qq) {
        return qq != null && !qq.isBlank() && findByQq(qq).isPresent();
    }

    private Optional<PluginUserProfile> findByQq(String qq) {
        try {
            return framework.users().findByQq(qq);
        }
        catch (Throwable e) {
            return Optional.empty();
        }
    }

    private static String displayName(PluginUserProfile profile) {
        return profile.nickname() != null && !profile.nickname().isBlank()
                ? profile.nickname() : profile.username();
    }

    /** 未绑定账号的展示名：遮蔽 QQ 号中段，避免在网页端泄露完整号码。 */
    static String maskQq(String qq) {
        if (qq == null || qq.isBlank()) {
            return "未知";
        }
        if (qq.length() <= 4) {
            return qq.charAt(0) + "***";
        }
        return "QQ " + qq.substring(0, 3) + "****" + qq.substring(qq.length() - 2);
    }
}
