package online.yudream.base.plugin.cas.application.service;

import online.yudream.base.plugin.cas.domain.aggregate.AccessControl;
import online.yudream.base.plugin.cas.domain.repo.AccessControlRepository;

/** 访问控制用例：读取/保存「未绑定则限制使用其他功能」开关。 */
public final class AccessControlService {

    private final AccessControlRepository repository;

    public AccessControlService(AccessControlRepository repository) {
        this.repository = repository;
    }

    public AccessControl current() {
        return repository.get();
    }

    public boolean requireBinding() {
        return repository.get().requireBinding();
    }

    public AccessControl save(AccessControl control) {
        if (control == null) {
            throw new IllegalArgumentException("访问控制配置不能为空");
        }
        return repository.save(control);
    }
}
