package online.yudream.base.plugin.material.application.command;

/** 为子物料追加新版本，各子物料版本号独立递增。 */
public record NewItemVersionCommand(String fileId, String filename, String note) {
}
