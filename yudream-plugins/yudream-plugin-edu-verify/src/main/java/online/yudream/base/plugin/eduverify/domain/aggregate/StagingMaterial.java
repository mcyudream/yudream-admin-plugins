package online.yudream.base.plugin.eduverify.domain.aggregate;

public record StagingMaterial(
        String id,
        String emailLower,
        String clientKey,
        String objectKey,
        String filename,
        String contentType,
        long size,
        String sha256,
        long createdAt,
        long expiresAt
) {
}
