package online.yudream.base.plugin.mcpanel.domain.repo;

import online.yudream.base.plugin.mcpanel.domain.valobj.EnrollToken;

import java.util.List;
import java.util.Optional;

public interface EnrollTokenRepository {

    EnrollToken save(EnrollToken token);

    Optional<EnrollToken> findById(String tokenId);

    Optional<EnrollToken> findByDigest(String tokenDigest);

    /**
     * 一次性原子兑换：仅当 redeemedAtMs 仍为 0 时写入兑换时间。
     * 并发兑换只有一方成功，其余得到 false。
     */
    boolean claimOnce(EnrollToken token, long nowMs);

    /**
     * 一次性原子吊销（重签时使旧 token 立即过期）：仅当 redeemedAtMs 仍为 0
     * 时把 expiresAtMs 置为 now。并发 claim 已成功则本 CAS 失败返回 false，
     * 不破坏已兑换结果。
     */
    boolean expireOnce(EnrollToken token, long nowMs);

    List<EnrollToken> findByNodeId(String nodeId);

    void delete(String tokenId);
}
