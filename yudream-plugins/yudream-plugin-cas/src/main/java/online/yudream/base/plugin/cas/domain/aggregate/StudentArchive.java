package online.yudream.base.plugin.cas.domain.aggregate;

/**
 * 只读的「学生档案」快照：来自 yudream-student-info 插件按学号查到的记录。
 * <p>本插件只读取，不向对方插件写入任何信息。</p>
 */
public record StudentArchive(String studentNo, String name, String className, String college) {
}
