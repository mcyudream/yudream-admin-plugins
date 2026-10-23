package online.yudream.base.plugin.cas.infrastructure.archive;

import online.yudream.base.plugin.cas.domain.aggregate.StudentArchive;
import online.yudream.base.plugin.cas.domain.service.StudentArchiveQuery;

import java.util.Optional;

/** 学生档案插件未安装/未启用时的降级实现：始终「不可用」，不抛异常。 */
public final class UnavailableStudentArchiveQuery implements StudentArchiveQuery {

    public static final UnavailableStudentArchiveQuery INSTANCE = new UnavailableStudentArchiveQuery();

    private UnavailableStudentArchiveQuery() {
    }

    @Override
    public boolean available() {
        return false;
    }

    @Override
    public Optional<StudentArchive> findByStudentNo(String studentNo) {
        return Optional.empty();
    }
}
