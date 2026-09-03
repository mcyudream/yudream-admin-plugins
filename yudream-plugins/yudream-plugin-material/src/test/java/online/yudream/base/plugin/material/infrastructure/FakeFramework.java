package online.yudream.base.plugin.material.infrastructure;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import online.yudream.base.plugin.spi.system.FrameworkServices;
import online.yudream.base.plugin.spi.system.ai.PluginAiService;
import online.yudream.base.plugin.spi.system.command.PluginCommandService;
import online.yudream.base.plugin.spi.system.document.PluginWordTemplateService;
import online.yudream.base.plugin.spi.system.mail.PluginMailService;
import online.yudream.base.plugin.spi.system.messaging.PluginMessagingRawService;
import online.yudream.base.plugin.spi.system.messaging.PluginMessagingService;
import online.yudream.base.plugin.spi.system.preview.PluginFilePreviewService;
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

/** 宿主 FrameworkServices 假实现：仅 users() 与 platformFile() 有语义，其余未用端口返回 null。 */
public final class FakeFramework implements FrameworkServices {
    private final Map<String, byte[]> platformFiles = new ConcurrentHashMap<>();
    private final PluginUserService users = new FakeUsers();
    private PluginFilePreviewService filePreview = new PluginFilePreviewService() {};

    /** 测试注入平台文件预览能力桩。 */
    public void useFilePreview(PluginFilePreviewService service) {
        this.filePreview = service;
    }

    @Override
    public PluginFilePreviewService filePreview() {
        return filePreview;
    }

    public void addPlatformFile(String fileId, byte[] bytes, String contentType) {
        platformFiles.put(fileId, bytes);
    }

    public Optional<PluginStoredFile> platformFile(String fileId) {
        byte[] bytes = platformFiles.get(fileId);
        if (bytes == null) {
            return Optional.empty();
        }
        return Optional.of(new PluginStoredFile("platform/" + fileId, "application/octet-stream",
                (long) bytes.length, new ByteArrayInputStream(bytes)));
    }

    public PluginUserService users() {
        return users;
    }

    public Optional<String> setting(String key) {
        return Optional.empty();
    }

    public PluginQqBindingService qqBindings() {
        return null;
    }

    public PluginCommandService commands() {
        return null;
    }

    public PluginAiService ai() {
        return null;
    }

    public PluginSecurityService security() {
        return null;
    }

    public PluginMailService mail() {
        return null;
    }

    public PluginWordTemplateService wordTemplates() {
        return null;
    }

    public PluginDocumentStore documents(String pluginCode) {
        return null;
    }

    public PluginFileStore files(String pluginCode) {
        return null;
    }

    public PluginMessagingService messaging() {
        return null;
    }

    public PluginMessagingRawService messagingRaw() {
        return null;
    }

    public PluginRenderService render() {
        return null;
    }

    private static final class FakeUsers implements PluginUserService {
        public Optional<PluginUserProfile> authenticate(String usernameOrEmail, String password) {
            return Optional.empty();
        }

        public PluginUserProfile create(PluginUserCreate create) {
            return null;
        }

        public Optional<PluginUserProfile> findById(Long userId) {
            return Optional.of(new PluginUserProfile(userId, "user" + userId, "用户" + userId,
                    null, null, null, null, "ACTIVE"));
        }

        public Optional<PluginUserProfile> findByUsername(String username) {
            return Optional.empty();
        }

        public Optional<PluginUserProfile> findByEmail(String email) {
            return Optional.empty();
        }

        public Optional<PluginUserProfile> findByQq(String qq) {
            return Optional.empty();
        }

        public void bindQqOnce(Long userId, String qq) {
        }

        public List<PluginUserOption> searchUsers(String keyword, Long deptId, int page, int size) {
            return List.of();
        }

        public List<PluginDeptOption> listDepartments(String keyword) {
            return List.of();
        }

        public List<PluginUserRole> listRoles(Long userId) {
            return List.of();
        }

        public List<PluginUserDept> listDepartments(Long userId) {
            return List.of();
        }

        public void updateProfile(Long userId, PluginUserProfileUpdate update) {
        }
    }
}
