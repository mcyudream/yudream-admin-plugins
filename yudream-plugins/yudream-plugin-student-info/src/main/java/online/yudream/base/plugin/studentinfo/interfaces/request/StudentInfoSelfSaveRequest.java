package online.yudream.base.plugin.studentinfo.interfaces.request;

public record StudentInfoSelfSaveRequest(
        String studentName,
        String studentNo,
        String className,
        String college
) {
}
