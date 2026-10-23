package online.yudream.base.plugin.cas.infrastructure.archive;

import online.yudream.base.plugin.studentinfo.api.PluginStudentInfoProfile;
import online.yudream.base.plugin.studentinfo.api.PluginStudentInfoService;
import online.yudream.base.plugin.cas.domain.aggregate.StudentArchive;
import online.yudream.base.plugin.cas.domain.service.StudentArchiveQuery;

import java.util.Optional;

/**
 * yudream-student-info 插件的学生档案查询适配器（只读）。
 *
 * <p>本类直接引用 provider 的 {@code *.api} 契约，因此<b>只能在确认 provider 已加载时实例化</b>
 * （见 {@code CasSsoPlugin#studentArchiveQuery}），避免 provider 缺失导致 NoClassDefFoundError。</p>
 */
public final class StudentInfoArchiveAdapter implements StudentArchiveQuery {

    private final PluginStudentInfoService service;

    public StudentInfoArchiveAdapter(PluginStudentInfoService service) {
        this.service = service;
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public Optional<StudentArchive> findByStudentNo(String studentNo) {
        if (studentNo == null || studentNo.isBlank()) {
            return Optional.empty();
        }
        try {
            return service.findStudentInfoByStudentNo(studentNo.trim()).map(StudentInfoArchiveAdapter::toArchive);
        } catch (RuntimeException e) {
            // 学生档案插件查询异常不应影响 CAS 管理页其余内容
            System.err.println("[cas] 学生档案查询失败，学号 " + studentNo + ": " + e.getMessage());
            return Optional.empty();
        }
    }

    private static StudentArchive toArchive(PluginStudentInfoProfile profile) {
        return new StudentArchive(
                text(profile.studentNo()),
                text(profile.studentName()),
                text(profile.className()),
                text(profile.college())
        );
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }
}
