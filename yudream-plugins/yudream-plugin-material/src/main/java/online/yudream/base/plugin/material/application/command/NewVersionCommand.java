package online.yudream.base.plugin.material.application.command;

/** 从平台上传追加新版本。 */
public record NewVersionCommand(String fileId, String filename, String note) {
}
