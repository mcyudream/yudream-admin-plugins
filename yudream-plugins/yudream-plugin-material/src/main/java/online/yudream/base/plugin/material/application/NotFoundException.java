package online.yudream.base.plugin.material.application;

/** 业务实体不存在，接口层映射为 404。 */
public final class NotFoundException extends RuntimeException {
    public NotFoundException(String message) {
        super(message);
    }
}
