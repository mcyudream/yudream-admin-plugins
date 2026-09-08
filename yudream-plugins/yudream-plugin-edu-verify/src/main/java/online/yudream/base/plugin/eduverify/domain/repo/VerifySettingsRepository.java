package online.yudream.base.plugin.eduverify.domain.repo;

import online.yudream.base.plugin.eduverify.domain.aggregate.VerifySettings;

public interface VerifySettingsRepository {

    VerifySettings get();

    VerifySettings save(VerifySettings settings);
}
