package online.yudream.base.plugin.ysm.domain;

public record ModelRecord(String id,String ownerId,String name,String hash,boolean serverEnabled,boolean enabled,long createdAt,long updatedAt) {}
