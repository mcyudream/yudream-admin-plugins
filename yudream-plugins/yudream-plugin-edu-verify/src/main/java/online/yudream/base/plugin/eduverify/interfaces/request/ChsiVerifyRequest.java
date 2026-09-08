package online.yudream.base.plugin.eduverify.interfaces.request;

public record ChsiVerifyRequest(String email, String vcode, String realName, String schoolName) {
}
