package online.yudream.base.plugin.mcguess.application;

import online.yudream.base.plugin.mcguess.domain.McguessPlayer;
import online.yudream.base.plugin.mcguess.domain.McguessPlayerRepository;
import online.yudream.base.plugin.spi.system.FrameworkServices;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 三种模式共用的支撑：群渠道锁、参与者战绩记录（绑定账号）、昵称解析。
 */
public class McguessSupport {

    private final McguessPlayerRepository players;
    private final FrameworkServices framework;
    private final Map<String, Object> channelLocks = new ConcurrentHashMap<>();

    public McguessSupport(McguessPlayerRepository players, FrameworkServices framework) {
        this.players = players;
        this.framework = framework;
    }

    /** 每个群（connectionId:channelId）一把锁，三种模式共用即可（锁粒度不影响并行玩法）。 */
    public Object lockFor(String connectionId, String channelId) {
        return channelLocks.computeIfAbsent(connectionId + ":" + channelId, key -> new Object());
    }

    /** 记录一次有效猜测。 */
    public void recordGuess(String userId, String qq) {
        withPlayer(userId, qq, player -> player.recordGuess(System.currentTimeMillis()));
    }

    /**
     * 对局结束时结算战绩：全部绑定账号的参与者记一次参与，终结者额外记一次胜场。
     *
     * @param winnerUserId 胜利时的终结者；失败（投降 / 心耗尽）传 null
     */
    public void recordGameEnd(String mode, List<Participant> participants, String winnerUserId) {
        Map<String, Participant> distinct = new LinkedHashMap<>();
        for (Participant participant : participants) {
            if (participant.userId() != null && !participant.userId().isBlank()) {
                distinct.putIfAbsent(participant.userId(), participant);
            }
        }
        long now = System.currentTimeMillis();
        for (Participant participant : distinct.values()) {
            withPlayer(participant.userId(), participant.qq(), player -> {
                player.recordPlayed(mode, now);
                if (participant.userId().equals(winnerUserId)) {
                    player.recordWin(mode, now);
                }
            });
        }
    }

    private void withPlayer(String userId, String qq, java.util.function.Consumer<McguessPlayer> action) {
        withPlayerResult(userId, qq, player -> {
            action.accept(player);
            return null;
        });
    }

    /**
     * 查找或创建玩家（回填 QQ 与昵称），执行动作后保存，返回动作结果。
     * userId 为空时不执行动作并返回 null。
     */
    public <R> R withPlayerResult(String userId, String qq, java.util.function.Function<McguessPlayer, R> action) {
        if (userId == null || userId.isBlank()) {
            return null;
        }
        McguessPlayer player = players.findByUserId(userId).orElseGet(() -> new McguessPlayer(userId));
        if (qq != null && !qq.isBlank()) {
            player.setQq(qq);
        }
        resolveNickname(player);
        R result = action.apply(player);
        players.save(player);
        return result;
    }

    /**
     * 图鉴收集：把物品加入玩家图鉴，返回本次新增的物品 id（首次收集的）。
     * userId 为空或物品列表为空时返回空列表。
     */
    public List<String> collect(String userId, String qq, List<String> itemIds) {
        if (itemIds == null || itemIds.isEmpty()) {
            return List.of();
        }
        List<String> added = withPlayerResult(userId, qq, player -> {
            List<String> newlyAdded = new java.util.ArrayList<>();
            for (String itemId : itemIds) {
                if (player.collect(itemId)) {
                    newlyAdded.add(itemId);
                }
            }
            return newlyAdded;
        });
        return added == null ? List.of() : added;
    }

    private void resolveNickname(McguessPlayer player) {
        if (player.getNickname() != null && !player.getNickname().isBlank()) {
            return;
        }
        try {
            framework.users().findById(Long.valueOf(player.getUserId())).ifPresent(profile -> {
                String nickname = profile.nickname() == null || profile.nickname().isBlank()
                        ? profile.username() : profile.nickname();
                player.setNickname(nickname);
            });
        } catch (RuntimeException ignored) {
            // 昵称解析失败不影响战绩记录
        }
    }

    /** 战绩结算用的参与者（绑定账号 + QQ）。 */
    public record Participant(String userId, String qq) {
    }
}
