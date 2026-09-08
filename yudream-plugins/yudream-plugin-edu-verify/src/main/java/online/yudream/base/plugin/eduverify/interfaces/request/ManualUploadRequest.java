package online.yudream.base.plugin.eduverify.interfaces.request;

public record ManualUploadRequest(
        String email,
        String filename,
        String contentType,
        String content
) {
}
