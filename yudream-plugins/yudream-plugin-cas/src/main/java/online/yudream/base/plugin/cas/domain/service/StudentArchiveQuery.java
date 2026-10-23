package online.yudream.base.plugin.cas.domain.service;

import online.yudream.base.plugin.cas.domain.aggregate.StudentArchive;

import java.util.Optional;

/**
 * 学生档案（yudream-student-info 插件）只读查询端口。
 * <p>软依赖：插件未安装/未启用时用 {@code available()==false} 的实现降级，页面显示「未安装学生档案插件」，
 * 其余功能不受影响。</p>
 */
public interface StudentArchiveQuery {

    /** 学生档案插件是否可用——决定管理端「学院 / 班级」列能否取值。 */
    boolean available();

    /** 按学号读取学生档案；对方插件没有该学号的记录时返回空。 */
    Optional<StudentArchive> findByStudentNo(String studentNo);
}
