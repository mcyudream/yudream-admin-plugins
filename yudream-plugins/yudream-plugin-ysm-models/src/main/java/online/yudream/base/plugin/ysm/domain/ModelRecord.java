package online.yudream.base.plugin.ysm.domain;

public record ModelRecord(String id,String ownerId,String name,String hash,boolean serverEnabled,boolean enabled,long createdAt,long updatedAt,ReviewStatus reviewStatus,String reviewNote) {
    public ModelRecord(String id,String ownerId,String name,String hash,boolean serverEnabled,boolean enabled,long createdAt,long updatedAt) {
        this(id,ownerId,name,hash,serverEnabled,enabled,createdAt,updatedAt,ReviewStatus.APPROVED,"");
    }
    public boolean usable() { return enabled && reviewStatus == ReviewStatus.APPROVED; }
}
