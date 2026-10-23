package online.yudream.base.plugin.cas.domain.repo;

import online.yudream.base.plugin.cas.domain.aggregate.SsoSettings;

public interface SsoSettingsRepository {

    SsoSettings get();

    SsoSettings save(SsoSettings settings);
}
