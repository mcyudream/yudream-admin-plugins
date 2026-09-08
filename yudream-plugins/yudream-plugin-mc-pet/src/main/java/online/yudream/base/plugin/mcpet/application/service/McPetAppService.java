package online.yudream.base.plugin.mcpet.application.service;

import online.yudream.base.plugin.mcpet.application.cmd.PetDefaultsSaveCmd;
import online.yudream.base.plugin.mcpet.application.cmd.PetPreferenceSaveCmd;
import online.yudream.base.plugin.mcpet.application.dto.MyPetDTO;
import online.yudream.base.plugin.mcpet.application.dto.PetAdminItemDTO;
import online.yudream.base.plugin.mcpet.application.dto.PetOptionsDTO;
import online.yudream.base.plugin.mcpet.application.port.PetSkinPort;
import online.yudream.base.plugin.mcpet.domain.aggregate.PetPreference;
import online.yudream.base.plugin.mcpet.domain.valobj.PetDefaults;
import online.yudream.base.plugin.mcpet.infrastructure.repository.McPetRepository;

import java.util.List;
import java.util.Optional;

public class McPetAppService {

    public static final String TEXTURE_URL_PREFIX = "/api/plugins/yudream-skin/textures/";

    private final McPetRepository repository;
    private final PetSkinPort skinPort;

    public McPetAppService(McPetRepository repository, PetSkinPort skinPort) {
        this.repository = repository;
        this.skinPort = skinPort;
    }

    public MyPetDTO myPet(String userId) {
        PetPreference preference = repository.findPreference(userId)
                .orElseGet(() -> syntheticDefaultPreference(userId));
        return resolveEffective(preference, repository.defaults());
    }

    public MyPetDTO saveMyPreference(String userId, PetPreferenceSaveCmd cmd) {
        PetPreference existing = repository.findPreference(userId)
                .orElseGet(() -> syntheticDefaultPreference(userId));
        boolean clearPosition = cmd.clearPosition() != null && cmd.clearPosition();
        PetPreference normalized = new PetPreference(
                userId,
                cmd.mode() != null ? cmd.mode() : existing.mode(),
                cmd.playerName() != null ? cmd.playerName() : existing.playerName(),
                cmd.closetItemId() != null ? cmd.closetItemId() : existing.closetItemId(),
                cmd.size() != null ? cmd.size() : existing.size(),
                cmd.corner() != null ? cmd.corner() : existing.corner(),
                clearPosition ? null : (cmd.positionX() != null ? cmd.positionX() : existing.positionX()),
                clearPosition ? null : (cmd.positionY() != null ? cmd.positionY() : existing.positionY()),
                cmd.hidden() != null ? cmd.hidden() : existing.hidden(),
                System.currentTimeMillis()
        ).normalized();
        validateOwnership(userId, normalized);
        repository.savePreference(normalized);
        return myPet(userId);
    }

    public PetOptionsDTO myOptions(String userId) {
        boolean available = skinPort.available();
        List<PetOptionsDTO.PlayerOption> players = available
                ? skinPort.playersOf(userId).stream()
                .map(player -> new PetOptionsDTO.PlayerOption(player.name(), player.uuid(), player.textureHash(), player.model()))
                .toList()
                : List.of();
        List<PetOptionsDTO.ClosetOption> closet = available
                ? skinPort.closetOf(userId).stream()
                .map(item -> new PetOptionsDTO.ClosetOption(item.id(), item.textureHash(), item.itemName(), item.createdAt()))
                .toList()
                : List.of();
        return new PetOptionsDTO(available, players, closet);
    }

    /** 上传皮肤 PNG 到当前用户皮肤库并加入衣柜，返回新衣柜条目供直接选用。 */
    public PetOptionsDTO.ClosetOption uploadMySkin(String userId, String name, String model, String base64) {
        if (base64 == null || base64.isBlank()) {
            throw new IllegalArgumentException("请选择皮肤 PNG 文件");
        }
        String payload = base64.trim();
        int comma = payload.indexOf(',');
        if (payload.startsWith("data:") && comma > 0) {
            payload = payload.substring(comma + 1);
        }
        if (payload.length() > 1_500_000) {
            throw new IllegalArgumentException("皮肤文件过大（上限约 1MB）");
        }
        PetSkinPort.ClosetEntry entry = skinPort.uploadSkin(userId, name, model, payload);
        return new PetOptionsDTO.ClosetOption(entry.id(), entry.textureHash(), entry.itemName(), entry.createdAt());
    }

    public PetDefaults defaults() {
        return repository.defaults();
    }

    public PetDefaults saveDefaults(PetDefaultsSaveCmd cmd) {
        PetDefaults current = repository.defaults();
        PetDefaults next = new PetDefaults(
                cmd.mode() != null ? cmd.mode() : current.mode(),
                cmd.playerName() != null ? cmd.playerName() : current.playerName(),
                cmd.textureHash() != null ? cmd.textureHash() : current.textureHash(),
                cmd.model() != null ? cmd.model() : current.model(),
                cmd.animation() != null ? cmd.animation() : current.animation(),
                cmd.clickAction() != null ? cmd.clickAction() : current.clickAction(),
                cmd.size() != null ? cmd.size() : current.size(),
                cmd.corner() != null ? cmd.corner() : current.corner()
        ).normalized();
        if (PetDefaults.MODE_TEXTURE.equals(next.mode())
                && (next.textureHash() == null || next.textureHash().isBlank())) {
            throw new IllegalArgumentException("请先上传或选择默认皮肤");
        }
        return repository.saveDefaults(next);
    }

    public List<PetAdminItemDTO> adminList(int page, int size) {
        PetDefaults defaults = repository.defaults();
        return repository.listPreferences(page, size).stream()
                .map(preference -> new PetAdminItemDTO(preference.userId(), preference, resolveEffective(preference, defaults)))
                .toList();
    }

    public long preferenceCount() {
        return repository.preferenceCount();
    }

    public PetAdminItemDTO adminDetail(String userId) {
        PetPreference preference = repository.findPreference(userId)
                .orElseGet(() -> syntheticDefaultPreference(userId));
        return new PetAdminItemDTO(userId, preference, resolveEffective(preference, repository.defaults()));
    }

    public void resetPreference(String userId) {
        repository.deletePreference(userId);
    }

    private MyPetDTO resolveEffective(PetPreference preference, PetDefaults defaults) {
        Optional<PetSkinPort.ResolvedSkin> resolved = switch (preference.mode()) {
            case PetPreference.MODE_PLAYER -> skinPort.skinByPlayerName(preference.playerName());
            case PetPreference.MODE_CLOSET -> skinPort.closetOf(preference.userId()).stream()
                    .filter(item -> item.id() != null && item.id().equals(preference.closetItemId()))
                    .findFirst()
                    .map(item -> new PetSkinPort.ResolvedSkin(item.textureHash(), "classic"));
            default -> switch (defaults.mode()) {
                case PetDefaults.MODE_PLAYER -> skinPort.skinByPlayerName(defaults.playerName());
                case PetDefaults.MODE_TEXTURE -> defaults.textureHash() != null && !defaults.textureHash().isBlank()
                        ? Optional.of(new PetSkinPort.ResolvedSkin(defaults.textureHash(),
                                defaults.model() != null ? defaults.model() : "classic"))
                        : Optional.empty();
                default -> Optional.empty();
            };
        };
        boolean texture = resolved.isPresent() && resolved.get().textureHash() != null && !resolved.get().textureHash().isBlank();
        return new MyPetDTO(
                texture ? "texture" : "builtin",
                texture ? TEXTURE_URL_PREFIX + resolved.get().textureHash() : null,
                texture ? resolved.get().model() : "classic",
                preference.size() != null ? preference.size() : defaults.size(),
                preference.corner() != null ? preference.corner() : defaults.corner(),
                preference.positionX(),
                preference.positionY(),
                preference.hidden(),
                defaults.animation(),
                defaults.clickAction(),
                skinPort.available(),
                preference.mode(),
                preference.playerName(),
                preference.closetItemId()
        );
    }

    private void validateOwnership(String userId, PetPreference preference) {
        if (PetPreference.MODE_PLAYER.equals(preference.mode())) {
            if (preference.playerName() == null || preference.playerName().isBlank()) {
                throw new IllegalArgumentException("请选择角色");
            }
            return;
        }
        if (PetPreference.MODE_CLOSET.equals(preference.mode())) {
            if (preference.closetItemId() == null) {
                throw new IllegalArgumentException("请选择衣柜皮肤");
            }
            boolean owned = skinPort.closetOf(userId).stream()
                    .anyMatch(item -> preference.closetItemId().equals(item.id()));
            if (!owned) {
                throw new IllegalArgumentException("衣柜皮肤不存在或不属于当前用户");
            }
        }
    }

    private PetPreference syntheticDefaultPreference(String userId) {
        return new PetPreference(userId, PetPreference.MODE_DEFAULT, null, null, null, null, null, null, false, null);
    }
}
