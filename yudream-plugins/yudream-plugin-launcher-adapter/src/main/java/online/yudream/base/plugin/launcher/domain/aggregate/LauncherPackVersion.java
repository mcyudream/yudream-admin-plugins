package online.yudream.base.plugin.launcher.domain.aggregate;

import online.yudream.base.plugin.launcher.domain.valobj.LauncherEntry;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 整合包单个版本（协议 v2 §5）。
 * <p>
 * 状态机：DRAFT（草稿，预留）→ PUBLISHED（已发布，对启动器可见）→ ARCHIVED（保留但下线，回滚用）。
 * MVP 实现只暴露 PUBLISHED；状态字段为后续审核流预留。
 * <p>
 * channel：MRPACK（默认，indexJson/overrides 齐全，entries 由发布管线物化）；
 * VANILLA（无 indexJson，客户端只按 gameVersion/loader/loaderVersion 走标准安装，entries 通常为空或仅 overrides）。
 *
 * @param packId          归属 packId
 * @param versionId       SemVer 版本号，如 1.4.0
 * @param indexJson       modrinth.index.json 的原文（MRPACK 必填；VANILLA 为空串）
 * @param indexHash       indexJson 的 SHA-256 hex（小写），启动器据此做增量对比（VANILLA 为空串）
 * @param overridesObjectKey overrides zip 在 context.files() 里的 objectKey（可为空）
 * @param downloads       index 里 downloads[] 的白名单域（启动器按域校验）
 * @param changelog       面向玩家/管理员的变更说明（UTF-8 中文）
 * @param publishedAt     发布时间戳
 * @param publisherUserId 发布人 userId（审计）
 * @param status          当前状态
 * @param channel         MRPACK | VANILLA
 * @param gameVersion     MC 版本，如 1.21.1（MRPACK 由 index.dependencies.minecraft 解析）
 * @param loader          vanilla | fabric | forge | neoforge | quilt
 * @param loaderVersion   loader 版本（vanilla 时为空串）
 * @param revision        对账基准，每次发布/overrides 变更取 pack 级单调计数器的最新值
 * @param entries         物化后的对账条目；VANILLA 可为空或仅 overrides
 * @param ignorePatterns  保护玩家本地文件的 glob 清单（options.txt、saves/** 等）
 */
public record LauncherPackVersion(
        String packId,
        String versionId,
        String indexJson,
        String indexHash,
        String overridesObjectKey,
        List<String> downloads,
        String changelog,
        long publishedAt,
        Long publisherUserId,
        Status status,
        String channel,
        String gameVersion,
        String loader,
        String loaderVersion,
        long revision,
        List<LauncherEntry> entries,
        List<String> ignorePatterns
) {
    private static final Pattern SEMVER = Pattern.compile("^\\d+\\.\\d+\\.\\d+(-[0-9A-Za-z.-]+)?(\\+[0-9A-Za-z.-]+)?$");

    public static final String CHANNEL_MRPACK = "MRPACK";
    public static final String CHANNEL_VANILLA = "VANILLA";

    /** 缺省保护清单：玩家本地数据永远不在对账删除范围内。 */
    public static final List<String> DEFAULT_IGNORE_PATTERNS = List.of(
            "options.txt", "saves/**", "screenshots/**", "logs/**", "crash-reports/**",
            "resourcepacks/**", "shaderpacks/**"
    );

    public LauncherPackVersion {
        Objects.requireNonNull(packId, "packId");
        Objects.requireNonNull(versionId, "versionId");
        if (!SEMVER.matcher(versionId).matches()) {
            throw new IllegalArgumentException("versionId 必须是 SemVer: " + versionId);
        }
        channel = channel == null || channel.isBlank() ? CHANNEL_MRPACK : channel;
        if (!CHANNEL_MRPACK.equals(channel) && !CHANNEL_VANILLA.equals(channel)) {
            throw new IllegalArgumentException("未知 channel: " + channel);
        }
        indexJson = indexJson == null ? "" : indexJson;
        indexHash = indexHash == null ? "" : indexHash;
        if (CHANNEL_MRPACK.equals(channel)) {
            if (indexJson.isBlank()) {
                throw new IllegalArgumentException("MRPACK 版本 indexJson 不能为空");
            }
            if (indexHash.length() != 64) {
                throw new IllegalArgumentException("indexHash 必须是 64 位十六进制 SHA-256");
            }
        }
        overridesObjectKey = overridesObjectKey == null ? "" : overridesObjectKey;
        downloads = downloads == null ? List.of() : List.copyOf(downloads);
        gameVersion = gameVersion == null ? "" : gameVersion;
        loader = loader == null || loader.isBlank() ? "vanilla" : loader;
        loaderVersion = loaderVersion == null ? "" : loaderVersion;
        entries = entries == null ? List.of() : List.copyOf(entries);
        ignorePatterns = ignorePatterns == null ? DEFAULT_IGNORE_PATTERNS : List.copyOf(ignorePatterns);
    }

    /** 兼容 v1 调用方的 10 参构造：默认 MRPACK、revision 0、无物化 entries。 */
    public LauncherPackVersion(String packId, String versionId, String indexJson, String indexHash,
                               String overridesObjectKey, List<String> downloads, String changelog,
                               long publishedAt, Long publisherUserId, Status status) {
        this(packId, versionId, indexJson, indexHash, overridesObjectKey, downloads, changelog,
                publishedAt, publisherUserId, status, CHANNEL_MRPACK, "", "vanilla", "", 0,
                List.of(), DEFAULT_IGNORE_PATTERNS);
    }

    public enum Status { DRAFT, PUBLISHED, ARCHIVED }

    public boolean isPublished() { return status == Status.PUBLISHED; }

    public boolean isVanilla() { return CHANNEL_VANILLA.equals(channel); }

    public LauncherPackVersion archive() {
        return new LauncherPackVersion(packId, versionId, indexJson, indexHash, overridesObjectKey,
                downloads, changelog, publishedAt, publisherUserId, Status.ARCHIVED,
                channel, gameVersion, loader, loaderVersion, revision, entries, ignorePatterns);
    }

    /** overrides 上传后重物化（entries 与 revision 一并替换）。 */
    public LauncherPackVersion withOverrides(String newOverridesObjectKey, List<LauncherEntry> newEntries, long newRevision) {
        return new LauncherPackVersion(packId, versionId, indexJson, indexHash, newOverridesObjectKey,
                downloads, changelog, publishedAt, publisherUserId, status,
                channel, gameVersion, loader, loaderVersion, newRevision, newEntries, ignorePatterns);
    }

    public static LauncherPackVersion publishMrpack(String packId, String versionId, String indexJson, String indexHash,
                                                    String overridesObjectKey, List<String> downloads, String changelog,
                                                    Long publisherUserId, String gameVersion, String loader,
                                                    String loaderVersion, long revision, List<LauncherEntry> entries,
                                                    List<String> ignorePatterns) {
        return new LauncherPackVersion(packId, versionId, indexJson, indexHash, overridesObjectKey,
                downloads, changelog, Instant.now().toEpochMilli(), publisherUserId, Status.PUBLISHED,
                CHANNEL_MRPACK, gameVersion, loader, loaderVersion, revision, entries, ignorePatterns);
    }

    public static LauncherPackVersion publishVanilla(String packId, String versionId, String gameVersion,
                                                     String loader, String loaderVersion, String changelog,
                                                     Long publisherUserId, long revision,
                                                     List<LauncherEntry> entries, List<String> ignorePatterns) {
        if (gameVersion == null || gameVersion.isBlank()) {
            throw new IllegalArgumentException("VANILLA 版本必须声明 gameVersion");
        }
        return new LauncherPackVersion(packId, versionId, "", "", "", List.of(), changelog,
                Instant.now().toEpochMilli(), publisherUserId, Status.PUBLISHED,
                CHANNEL_VANILLA, gameVersion, loader, loaderVersion, revision, entries, ignorePatterns);
    }

    /** 兼容旧签名：MRPACK 发布，revision 0、无 entries（由应用服务补全）。 */
    public static LauncherPackVersion publish(String packId, String versionId, String indexJson, String indexHash,
                                              String overridesObjectKey, List<String> downloads, String changelog,
                                              Long publisherUserId) {
        return publishMrpack(packId, versionId, indexJson, indexHash, overridesObjectKey, downloads, changelog,
                publisherUserId, "", "vanilla", "", 0, List.of(), DEFAULT_IGNORE_PATTERNS);
    }
}
