package online.yudream.base.plugin.cas.infrastructure.archive;

import online.yudream.base.plugin.spi.core.PluginContext;
import online.yudream.base.plugin.studentinfo.api.PluginStudentInfoService;
import online.yudream.base.plugin.cas.domain.service.StudentArchiveQuery;

/**
 * 学生档案查询端口的装配入口。
 *
 * <p>provider（yudream-student-info）是软依赖：只有确认依赖可用后才引用其 {@code *.api} 类型，
 * 未安装/未启用/未暴露服务时返回降级实现，避免 provider 缺失导致 {@code NoClassDefFoundError}。
 * 因此插件入口只依赖本工厂，不直接引用 provider 类型。</p>
 */
public final class StudentArchiveQueryFactory {

    private StudentArchiveQueryFactory() {
    }

    public static StudentArchiveQuery create(PluginContext context, String providerCode) {
        if (context == null || providerCode == null || providerCode.isBlank()) {
            return UnavailableStudentArchiveQuery.INSTANCE;
        }
        try {
            if (!context.dependencyAvailable(providerCode)) {
                return UnavailableStudentArchiveQuery.INSTANCE;
            }
            return context.service(providerCode, PluginStudentInfoService.class)
                    .<StudentArchiveQuery>map(StudentInfoArchiveAdapter::new)
                    .orElse(UnavailableStudentArchiveQuery.INSTANCE);
        } catch (RuntimeException | LinkageError e) {
            System.err.println("[cas] 学生档案插件不可用，学院/班级列将显示为空: " + e);
            return UnavailableStudentArchiveQuery.INSTANCE;
        }
    }
}
