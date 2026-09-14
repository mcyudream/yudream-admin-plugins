package online.yudream.base.plugin.launcher.application.service;

import online.yudream.base.plugin.launcher.domain.aggregate.LauncherPack;
import online.yudream.base.plugin.launcher.domain.aggregate.LauncherPackVersion;
import online.yudream.base.plugin.launcher.domain.repo.PackRepository;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PackQueryTest {

    @Test
    void filtersAndPaginatesPacks() {
        PackAppService service = new PackAppService(new InMemoryPackRepository(List.of(
                pack("survival", "生存", 30),
                pack("creative", "创造", 20),
                pack("minigame", "小游戏", 10)
        )), null);

        PackAppService.PackQueryResult page = service.queryPacks("生存", 1, 10);
        assertEquals(1, page.total());
        assertEquals("survival", page.records().getFirst().id());

        PackAppService.PackQueryResult sliced = service.queryPacks("", 2, 2);
        assertEquals(3, sliced.total());
        assertEquals(1, sliced.records().size());
        assertEquals("minigame", sliced.records().getFirst().id());
    }

    private static LauncherPack pack(String id, String name, long updatedAt) {
        return LauncherPack.builder()
                .id(id)
                .name(name)
                .description("")
                .icon("")
                .retainedVersionIds(List.of())
                .createdAt(updatedAt)
                .updatedAt(updatedAt)
                .build();
    }

    private static final class InMemoryPackRepository implements PackRepository {
        private final List<LauncherPack> packs;

        private InMemoryPackRepository(List<LauncherPack> packs) {
            this.packs = new ArrayList<>(packs);
        }

        @Override
        public List<LauncherPack> listPacks() {
            return List.copyOf(packs);
        }

        @Override
        public Optional<LauncherPack> findPack(String packId) {
            return packs.stream().filter(pack -> pack.id().equals(packId)).findFirst();
        }

        @Override
        public void savePack(LauncherPack pack) {
            packs.removeIf(existing -> existing.id().equals(pack.id()));
            packs.add(pack);
        }

        @Override
        public List<LauncherPackVersion> listVersions(String packId) {
            return List.of();
        }

        @Override
        public Optional<LauncherPackVersion> findVersion(String packId, String versionId) {
            return Optional.empty();
        }

        @Override
        public void saveVersion(LauncherPackVersion version) {
        }
    }
}
