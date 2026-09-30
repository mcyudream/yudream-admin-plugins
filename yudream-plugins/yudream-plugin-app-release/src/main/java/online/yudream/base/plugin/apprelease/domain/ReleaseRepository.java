package online.yudream.base.plugin.apprelease.domain;

import java.util.List;
import java.util.Optional;

/**
 * 更新发布仓储：版本元数据 + 更新包二进制 + 通道设置。
 * 二进制经 savePackage 与元数据一起写入，保证"元数据存在则包可用"。
 */
public interface ReleaseRepository {

    List<AppRelease> listAll();

    Optional<AppRelease> find(String id);

    /** 保存元数据与更新包二进制（同 id 覆盖）。 */
    void savePackage(AppRelease release, byte[] content);

    /** 仅更新元数据（发布/下架等状态变化）。 */
    void saveMeta(AppRelease release);

    /** 删除元数据与二进制。 */
    void delete(String id);

    /** 读取更新包二进制；不存在返回 empty。 */
    Optional<byte[]> readPackage(String id);

    ReleaseSettings settings();

    void saveSettings(ReleaseSettings settings);
}
