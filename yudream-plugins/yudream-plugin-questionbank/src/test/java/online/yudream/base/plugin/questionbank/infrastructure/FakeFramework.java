package online.yudream.base.plugin.questionbank.infrastructure;

import java.util.List;
import java.util.Optional;
import online.yudream.base.plugin.spi.system.FrameworkServices;
import online.yudream.base.plugin.spi.system.ai.PluginAiService;
import online.yudream.base.plugin.spi.system.command.PluginCommandService;
import online.yudream.base.plugin.spi.system.document.PluginWordTemplateService;
import online.yudream.base.plugin.spi.system.mail.PluginMailService;
import online.yudream.base.plugin.spi.system.messaging.PluginMessagingRawService;
import online.yudream.base.plugin.spi.system.messaging.PluginMessagingService;
import online.yudream.base.plugin.spi.system.render.PluginRenderService;
import online.yudream.base.plugin.spi.system.security.PluginSecurityService;
import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;
import online.yudream.base.plugin.spi.system.storage.PluginFileStore;
import online.yudream.base.plugin.spi.system.storage.PluginStoredFile;
import online.yudream.base.plugin.spi.system.user.PluginDeptOption;
import online.yudream.base.plugin.spi.system.user.PluginQqBindingService;
import online.yudream.base.plugin.spi.system.user.PluginUserCreate;
import online.yudream.base.plugin.spi.system.user.PluginUserDept;
import online.yudream.base.plugin.spi.system.user.PluginUserOption;
import online.yudream.base.plugin.spi.system.user.PluginUserProfile;
import online.yudream.base.plugin.spi.system.user.PluginUserProfileUpdate;
import online.yudream.base.plugin.spi.system.user.PluginUserRole;
import online.yudream.base.plugin.spi.system.user.PluginUserService;

/** 宿主 FrameworkServices 假实现：仅 users() 有语义（返回 用户{id} 昵称），其余未用端口返回 null。 */
public final class FakeFramework implements FrameworkServices {
    private final PluginUserService users = new FakeUsers();
    private PluginAiService ai;

    /** 测试按需注入 AI 假实现；默认 null 模拟宿主 AI 能力不可用。 */
    public void setAi(PluginAiService ai) {
        this.ai = ai;
    }

    @Override
    public PluginUserService users() {
        return users;
    }

    @Override
    public Optional<String> setting(String key) {
        return Optional.empty();
    }

    @Override
    public PluginQqBindingService qqBindings() {
        return null;
    }

    @Override
    public PluginCommandService commands() {
        return null;
    }

    @Override
    public PluginAiService ai() {
        return ai;
    }

    @Override
    public PluginSecurityService security() {
        return null;
    }

    @Override
    public PluginMailService mail() {
        return null;
    }

    @Override
    public PluginWordTemplateService wordTemplates() {
        return null;
    }

    @Override
    public Optional<PluginStoredFile> platformFile(String fileId) {
        return Optional.empty();
    }

    @Override
    public PluginDocumentStore documents(String pluginCode) {
        return null;
    }

    @Override
    public PluginFileStore files(String pluginCode) {
        return null;
    }

    @Override
    public PluginMessagingService messaging() {
        return null;
    }

    @Override
    public PluginMessagingRawService messagingRaw() {
        return null;
    }

    @Override
    public PluginRenderService render() {
        return null;
    }

    private static final class FakeUsers implements PluginUserService {
        @Override
        public Optional<PluginUserProfile> authenticate(String usernameOrEmail, String password) {
            return Optional.empty();
        }

        @Override
        public PluginUserProfile create(PluginUserCreate create) {
            return null;
        }

        @Override
        public Optional<PluginUserProfile> findById(Long userId) {
            return Optional.of(new PluginUserProfile(userId, "user" + userId, "用户" + userId,
                    null, null, null, null, "ACTIVE"));
        }

        @Override
        public Optional<PluginUserProfile> findByUsername(String username) {
            return Optional.empty();
        }

        @Override
        public Optional<PluginUserProfile> findByEmail(String email) {
            return Optional.empty();
        }

        @Override
        public Optional<PluginUserProfile> findByQq(String qq) {
            return Optional.empty();
        }

        @Override
        public void bindQqOnce(Long userId, String qq) {
        }

        @Override
        public List<PluginUserOption> searchUsers(String keyword, Long deptId, int page, int size) {
            return List.of();
        }

        @Override
        public List<PluginDeptOption> listDepartments(String keyword) {
            return List.of();
        }

        @Override
        public List<PluginUserRole> listRoles(Long userId) {
            return List.of();
        }

        @Override
        public List<PluginUserDept> listDepartments(Long userId) {
            return List.of();
        }

        @Override
        public void updateProfile(Long userId, PluginUserProfileUpdate update) {
        }
    }
}
