package online.yudream.base.plugin.ysm.application;

/** Effective policy for the current authenticated uploader. */
public record ModelUploadPolicy(boolean uploadEnabled, boolean reviewRequired, boolean canUpload) {}
