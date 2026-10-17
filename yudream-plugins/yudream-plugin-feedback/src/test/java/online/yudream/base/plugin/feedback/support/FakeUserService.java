package online.yudream.base.plugin.feedback.support;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import online.yudream.base.plugin.spi.system.user.PluginDeptOption;
import online.yudream.base.plugin.spi.system.user.PluginMessagingIdentity;
import online.yudream.base.plugin.spi.system.user.PluginUserCreate;
import online.yudream.base.plugin.spi.system.user.PluginUserDept;
import online.yudream.base.plugin.spi.system.user.PluginUserField;
import online.yudream.base.plugin.spi.system.user.PluginUserOption;
import online.yudream.base.plugin.spi.system.user.PluginUserProfile;
import online.yudream.base.plugin.spi.system.user.PluginUserProfileUpdate;
import online.yudream.base.plugin.spi.system.user.PluginUserRole;
import online.yudream.base.plugin.spi.system.user.PluginUserService;
import online.yudream.base.plugin.spi.system.user.PluginUserTag;

/**
 * 测试用账号目录：只实现反馈插件真正用到的方法（findById / searchUsers），
 * 其余方法一律抛错——一旦未来代码误用它们，测试会立刻失败而不是静默返回空值。
 */
public final class FakeUserService implements PluginUserService {

    private final List<PluginUserProfile> users = new ArrayList<>();

    public FakeUserService add(long id, String username, String nickname) {
        users.add(new PluginUserProfile(id, username, nickname, username + "@example.com",
                null, null, null, "enabled"));
        return this;
    }

    @Override
    public Optional<PluginUserProfile> findById(Long id) {
        return users.stream().filter(user -> user.id().equals(id)).findFirst();
    }

    @Override
    public List<PluginUserOption> searchUsers(String keyword, Long deptId, int page, int size) {
        String needle = keyword == null ? "" : keyword.toLowerCase();
        return users.stream()
                .filter(user -> needle.isEmpty()
                        || user.username().toLowerCase().contains(needle)
                        || (user.nickname() != null && user.nickname().toLowerCase().contains(needle)))
                .map(user -> new PluginUserOption(String.valueOf(user.id()), user.username(),
                        user.nickname(), user.email(), user.avatar(), user.status(), List.of(), List.of()))
                .toList();
    }

    // ---------------------------------------------------------------- 以下为插件未使用的 SPI，误用即失败

    @Override
    public Optional<PluginUserProfile> authenticate(String username, String password) {
        throw new UnsupportedOperationException("反馈插件不应调用 authenticate");
    }

    @Override
    public PluginUserProfile create(PluginUserCreate create) {
        throw new UnsupportedOperationException("反馈插件不应调用 create");
    }

    @Override
    public Optional<PluginUserProfile> findByUsername(String username) {
        throw new UnsupportedOperationException("反馈插件不应调用 findByUsername");
    }

    @Override
    public Optional<PluginUserProfile> findByEmail(String email) {
        throw new UnsupportedOperationException("反馈插件不应调用 findByEmail");
    }

    @Override
    public Optional<PluginUserProfile> findByQq(String qq) {
        throw new UnsupportedOperationException("反馈插件不应调用 findByQq");
    }

    @Override
    public void bindQqOnce(Long userId, String qq) {
        throw new UnsupportedOperationException("反馈插件不应调用 bindQqOnce");
    }

    @Override
    public Optional<PluginUserProfile> findByMessagingIdentity(PluginMessagingIdentity identity) {
        throw new UnsupportedOperationException("反馈插件不应调用 findByMessagingIdentity");
    }

    @Override
    public void bindMessagingIdentityOnce(Long userId, PluginMessagingIdentity identity) {
        throw new UnsupportedOperationException("反馈插件不应调用 bindMessagingIdentityOnce");
    }

    @Override
    public List<PluginMessagingIdentity> listMessagingIdentities(Long userId) {
        throw new UnsupportedOperationException("反馈插件不应调用 listMessagingIdentities");
    }

    @Override
    public List<PluginDeptOption> listDepartments(String keyword) {
        throw new UnsupportedOperationException("反馈插件不应调用 listDepartments");
    }

    @Override
    public List<PluginUserRole> listRoles(Long userId) {
        throw new UnsupportedOperationException("反馈插件不应调用 listRoles");
    }

    @Override
    public List<PluginUserDept> listDepartments(Long userId) {
        throw new UnsupportedOperationException("反馈插件不应调用 listDepartments");
    }

    @Override
    public void updateProfile(Long userId, PluginUserProfileUpdate update) {
        throw new UnsupportedOperationException("反馈插件不应调用 updateProfile");
    }

    @Override
    public List<PluginUserTag> listTags(Long userId) {
        throw new UnsupportedOperationException("反馈插件不应调用 listTags");
    }

    @Override
    public void replaceTags(Long userId, String scope, List<PluginUserTag> tags) {
        throw new UnsupportedOperationException("反馈插件不应调用 replaceTags");
    }

    @Override
    public void replaceFields(Long userId, String scope, List<PluginUserField> fields) {
        throw new UnsupportedOperationException("反馈插件不应调用 replaceFields");
    }
}
