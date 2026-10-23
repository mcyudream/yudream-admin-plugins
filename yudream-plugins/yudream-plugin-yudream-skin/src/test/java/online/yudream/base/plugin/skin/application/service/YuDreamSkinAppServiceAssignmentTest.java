package online.yudream.base.plugin.skin.application.service;

import online.yudream.base.plugin.skin.application.cmd.AssignTextureCmd;
import online.yudream.base.plugin.skin.bootstrap.YuDreamSkinPlugin;
import online.yudream.base.plugin.skin.domain.aggregate.SkinPlayer;
import online.yudream.base.plugin.skin.domain.aggregate.SkinTexture;
import online.yudream.base.plugin.skin.infrastructure.repository.YuDreamSkinRepository;
import online.yudream.base.plugin.skin.interfaces.controller.YuDreamSkinUserController;
import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class YuDreamSkinAppServiceAssignmentTest {

    private static final String OWNER = "owner-1";
    private static final String OTHER_OWNER = "owner-2";
    private static final String PLAYER = "Alice";
    private static final String SKIN = "equipped-skin";
    private static final String CAPE = "equipped-cape";
    private static final String NEW_SKIN = "new-skin";
    private static final String NEW_CAPE = "new-cape";

    private YuDreamSkinRepository repository;
    private YuDreamSkinAppService service;

    @BeforeEach
    void setUp() {
        repository = new YuDreamSkinRepository(new InMemoryDocuments(), null);
        service = new YuDreamSkinAppService(repository, null);
        repository.savePlayer(new SkinPlayer("alice-uuid", OWNER, PLAYER, "alice", SKIN, CAPE, null, 1L,
                SkinPlayer.KIND_OFFLINE, null));
        saveTexture(SKIN, "skin");
        saveTexture(CAPE, "cape");
        saveTexture(NEW_SKIN, "skin");
        saveTexture(NEW_CAPE, "cape");
        repository.saveClosetItem(OWNER, SKIN, "皮肤收藏");
        repository.saveClosetItem(OWNER, CAPE, "披风收藏");
    }

    @Test
    void keepsEquippedSkinAfterRemovingItsCollectionWhenChangingCape() {
        service.deleteOwnClosetItem(OWNER + ":" + SKIN, OWNER);
        repository.saveClosetItem(OWNER, NEW_CAPE, "新披风");

        SkinPlayer updated = service.assignOwnTextures(PLAYER, OWNER, new AssignTextureCmd(SKIN, NEW_CAPE));

        assertEquals(SKIN, updated.skinHash());
        assertEquals(NEW_CAPE, updated.capeHash());
        assertEquals(updated, repository.findPlayerByName(PLAYER).orElseThrow());
        assertFalse(repository.findClosetByUserAndTexture(OWNER, SKIN).isPresent());
    }

    @Test
    void keepsEquippedCapeAfterRemovingItsCollectionWhenChangingSkin() {
        service.deleteOwnClosetItem(OWNER + ":" + CAPE, OWNER);
        repository.saveClosetItem(OWNER, NEW_SKIN, "新皮肤");

        SkinPlayer updated = service.assignOwnTextures(PLAYER, OWNER, new AssignTextureCmd(NEW_SKIN, CAPE));

        assertEquals(NEW_SKIN, updated.skinHash());
        assertEquals(CAPE, updated.capeHash());
        assertEquals(updated, repository.findPlayerByName(PLAYER).orElseThrow());
        assertFalse(repository.findClosetByUserAndTexture(OWNER, CAPE).isPresent());
    }

    @Test
    void rejectsNewSkinCollectedOnlyByAnotherUser() {
        repository.saveClosetItem(OTHER_OWNER, NEW_SKIN, "他人的皮肤");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.assignOwnTextures(PLAYER, OWNER, new AssignTextureCmd(NEW_SKIN, CAPE)));

        assertEquals("材质不在你的衣柜中", error.getMessage());
        assertOriginalOutfit();
    }

    @Test
    void rejectsNewCapeCollectedOnlyByAnotherUser() {
        repository.saveClosetItem(OTHER_OWNER, NEW_CAPE, "他人的披风");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.assignOwnTextures(PLAYER, OWNER, new AssignTextureCmd(SKIN, NEW_CAPE)));

        assertEquals("材质不在你的衣柜中", error.getMessage());
        assertOriginalOutfit();
    }

    @Test
    void rejectsAnotherUsersPlayerEvenWhenBothSlotsAreUnchanged() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.assignOwnTextures(PLAYER, OTHER_OWNER, new AssignTextureCmd(SKIN, CAPE)));

        assertEquals("只能操作自己的角色", error.getMessage());
        assertOriginalOutfit();
    }

    @Test
    void rejectsMissingOwnerEvenWhenBothSlotsAreUnchanged() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.assignOwnTextures(PLAYER, " ", new AssignTextureCmd(SKIN, CAPE)));

        assertEquals("用户不能为空", error.getMessage());
        assertOriginalOutfit();
    }

    @Test
    void stillChecksTextureTypeForChangedSlots() {
        repository.saveClosetItem(OWNER, NEW_CAPE, "新披风");
        repository.saveClosetItem(OWNER, NEW_SKIN, "新皮肤");

        assertThrows(IllegalArgumentException.class,
                () -> service.assignOwnTextures(PLAYER, OWNER, new AssignTextureCmd(NEW_CAPE, CAPE)));
        assertThrows(IllegalArgumentException.class,
                () -> service.assignOwnTextures(PLAYER, OWNER, new AssignTextureCmd(SKIN, NEW_SKIN)));
        assertOriginalOutfit();
    }

    @Test
    void canClearRemovedCollectionsWithoutIntroducingNewTextures() {
        service.deleteOwnClosetItem(OWNER + ":" + SKIN, OWNER);
        service.deleteOwnClosetItem(OWNER + ":" + CAPE, OWNER);

        SkinPlayer updated = service.assignOwnTextures(PLAYER, OWNER, new AssignTextureCmd(null, " "));

        assertNull(updated.skinHash());
        assertNull(updated.capeHash());
    }

    @Test
    void userAssignmentEndpointKeepsItsUserPermissionAndPersonalPath() throws NoSuchMethodException {
        PluginHttpEndpoint endpoint = YuDreamSkinUserController.class
                .getMethod("assignMyTextures", PluginHttpRequest.class)
                .getAnnotation(PluginHttpEndpoint.class);

        assertEquals(YuDreamSkinPlugin.USER_PERMISSION, endpoint.permission());
        assertEquals("/me/players/{name}/textures", endpoint.path());
        assertEquals("PUT", endpoint.method());
    }

    private void saveTexture(String hash, String type) {
        repository.saveTexture(new SkinTexture(hash, hash, type, "default", "image/png", 1L,
                OWNER, true, null, null, 1L));
    }

    private void assertOriginalOutfit() {
        SkinPlayer player = repository.findPlayerByName(PLAYER).orElseThrow();
        assertEquals(SKIN, player.skinHash());
        assertEquals(CAPE, player.capeHash());
    }

    private static final class InMemoryDocuments implements PluginDocumentStore {

        private final Map<String, Map<String, Map<String, Object>>> collections = new LinkedHashMap<>();

        @Override
        public Map<String, Object> save(String collection, String id, Map<String, Object> document) {
            Map<String, Object> saved = new LinkedHashMap<>(document);
            saved.put("id", id);
            collections.computeIfAbsent(collection, ignored -> new LinkedHashMap<>()).put(id, saved);
            return new LinkedHashMap<>(saved);
        }

        @Override
        public Optional<Map<String, Object>> findById(String collection, String id) {
            return Optional.ofNullable(collections.getOrDefault(collection, Map.of()).get(id))
                    .map(LinkedHashMap::new);
        }

        @Override
        public List<Map<String, Object>> findAll(String collection, int page, int size) {
            return collections.getOrDefault(collection, Map.of()).values().stream()
                    .skip((long) (page - 1) * size).limit(size).map(LinkedHashMap::new)
                    .map(document -> (Map<String, Object>) document).toList();
        }

        @Override
        public List<Map<String, Object>> findByField(String collection, String field, Object value, int page, int size) {
            return collections.getOrDefault(collection, Map.of()).values().stream()
                    .filter(document -> Objects.equals(document.get(field), value))
                    .skip((long) (page - 1) * size).limit(size).map(LinkedHashMap::new)
                    .map(document -> (Map<String, Object>) document).toList();
        }

        @Override
        public long count(String collection) {
            return collections.getOrDefault(collection, Map.of()).size();
        }

        @Override
        public void delete(String collection, String id) {
            Map<String, Map<String, Object>> records = collections.get(collection);
            if (records != null) {
                records.remove(id);
            }
        }
    }
}
