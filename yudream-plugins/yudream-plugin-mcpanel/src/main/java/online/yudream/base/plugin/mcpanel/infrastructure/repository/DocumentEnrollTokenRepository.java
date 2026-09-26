package online.yudream.base.plugin.mcpanel.infrastructure.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import online.yudream.base.plugin.mcpanel.domain.repo.EnrollTokenRepository;
import online.yudream.base.plugin.mcpanel.domain.valobj.EnrollToken;
import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 注册令牌仓储。claimOnce 使用宿主 CAS（字段值 ≤ 0 才更新）保证全局一次性兑换。
 */
public class DocumentEnrollTokenRepository implements EnrollTokenRepository {

    static final String COLLECTION = "enroll-tokens";

    private final PluginDocumentStore documents;
    private final ObjectMapper mapper;

    public DocumentEnrollTokenRepository(PluginDocumentStore documents, ObjectMapper mapper) {
        this.documents = documents;
        this.mapper = mapper;
    }

    @Override
    public EnrollToken save(EnrollToken token) {
        documents.save(COLLECTION, token.id(), toDocument(token));
        return token;
    }

    @Override
    public Optional<EnrollToken> findById(String tokenId) {
        return documents.findById(COLLECTION, tokenId).map(this::toToken);
    }

    @Override
    public Optional<EnrollToken> findByDigest(String tokenDigest) {
        return documents.findByField(COLLECTION, "tokenDigest", tokenDigest, 1, 5).stream()
                .findFirst()
                .map(this::toToken);
    }

    @Override
    public boolean claimOnce(EnrollToken token, long nowMs) {
        EnrollToken redeemed = token.withRedeemed(nowMs);
        return documents.updateIfFieldAtMost(COLLECTION, token.id(), "redeemedAtMs", 0L,
                toDocument(redeemed));
    }

    @Override
    public boolean expireOnce(EnrollToken token, long nowMs) {
        EnrollToken expired = token.withExpiredAt(nowMs);
        return documents.updateIfFieldAtMost(COLLECTION, token.id(), "redeemedAtMs", 0L,
                toDocument(expired));
    }

    @Override
    public List<EnrollToken> findByNodeId(String nodeId) {
        return documents.findByField(COLLECTION, "nodeId", nodeId, 1, 200).stream()
                .map(this::toToken)
                .toList();
    }

    @Override
    public void delete(String tokenId) {
        documents.delete(COLLECTION, tokenId);
    }

    private Map<String, Object> toDocument(EnrollToken token) {
        Map<String, Object> raw = mapper.convertValue(token, new TypeReference<Map<String, Object>>() {
        });
        // 与 DocumentNodeRepository.toDocument 相同的 null 剔除语义（沙盒兼容）。
        Map<String, Object> document = new java.util.LinkedHashMap<>();
        raw.forEach((key, value) -> {
            if (value != null) {
                document.put(key, value);
            }
        });
        return document;
    }

    private EnrollToken toToken(Map<String, Object> document) {
        return mapper.convertValue(document, EnrollToken.class);
    }
}
