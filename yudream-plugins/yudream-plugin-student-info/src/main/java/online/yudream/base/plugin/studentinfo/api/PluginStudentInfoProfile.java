package online.yudream.base.plugin.studentinfo.api;

public record PluginStudentInfoProfile(String userId, String studentName, String studentNo, String className,
                                       String college, long createdAt, long updatedAt) {
}
