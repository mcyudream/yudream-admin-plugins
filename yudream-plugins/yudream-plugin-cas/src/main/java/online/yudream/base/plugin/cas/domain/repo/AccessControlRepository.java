package online.yudream.base.plugin.cas.domain.repo;

import online.yudream.base.plugin.cas.domain.aggregate.AccessControl;

public interface AccessControlRepository {

    AccessControl get();

    AccessControl save(AccessControl control);
}
