package online.yudream.base.plugin.minecraft.application.assembler;

import online.yudream.base.plugin.minecraft.application.dto.MinecraftEndpointStatusDTO;
import online.yudream.base.plugin.minecraft.application.dto.MinecraftInheritanceRuleDTO;
import online.yudream.base.plugin.minecraft.application.dto.MinecraftSeasonAdjustmentDTO;
import online.yudream.base.plugin.minecraft.application.dto.MinecraftSeasonOperationDTO;
import online.yudream.base.plugin.minecraft.application.dto.MinecraftPlayerActivityDTO;
import online.yudream.base.plugin.minecraft.application.dto.MinecraftServerDTO;
import online.yudream.base.plugin.minecraft.application.dto.MinecraftServerMapDTO;
import online.yudream.base.plugin.minecraft.application.dto.MinecraftServerStatusDTO;
import online.yudream.base.plugin.minecraft.application.dto.MinecraftStatusSnapshotDTO;
import online.yudream.base.plugin.minecraft.application.dto.ModpackBindingDTO;
import online.yudream.base.plugin.minecraft.domain.aggregate.MinecraftSeasonOperation;
import online.yudream.base.plugin.minecraft.domain.aggregate.MinecraftServer;
import online.yudream.base.plugin.minecraft.domain.aggregate.MinecraftServerTopology;
import online.yudream.base.plugin.minecraft.domain.aggregate.MinecraftPlayerActivity;
import online.yudream.base.plugin.minecraft.domain.valobj.MinecraftEndpointStatus;
import online.yudream.base.plugin.minecraft.domain.valobj.MinecraftInheritanceRule;
import online.yudream.base.plugin.minecraft.domain.valobj.MinecraftSeasonAdjustment;
import online.yudream.base.plugin.minecraft.domain.valobj.MinecraftServerEndpoint;
import online.yudream.base.plugin.minecraft.domain.valobj.MinecraftServerSeason;
import online.yudream.base.plugin.minecraft.domain.valobj.MinecraftServerMap;
import online.yudream.base.plugin.minecraft.domain.valobj.MinecraftServerStatus;
import online.yudream.base.plugin.minecraft.domain.valobj.MinecraftStatusSnapshot;
import online.yudream.base.plugin.minecraft.domain.valobj.MinecraftSubServer;
import online.yudream.base.plugin.minecraft.domain.valobj.MinecraftSubServerActivity;
import online.yudream.base.plugin.minecraft.domain.valobj.ModpackBinding;

import java.math.BigDecimal;
import java.util.Map;

public class MinecraftServerAppAssembler {

    public MinecraftServerDTO toDTO(MinecraftServer server, MinecraftServerStatus status) {
        return toDTO(server, status, null);
    }

    /** @param topology the proxy's reported server list, or {@code null} for a non-proxy server. */
    public MinecraftServerDTO toDTO(MinecraftServer server, MinecraftServerStatus status, MinecraftServerTopology topology) {
        return new MinecraftServerDTO(
                server.id(),
                server.name(),
                server.descriptionMarkdown(),
                server.enabled(),
                server.sort(),
                server.endpoints().stream().map(this::toDTO).toList(),
                server.seasons().stream().map(this::toDTO).toList(),
                server.currentSeason() == null ? null : toDTO(server.currentSeason()),
                status == null ? null : toDTO(status),
                server.map() == null ? null : toDTO(server.map()),
                toDTO(topology),
                server.createdAt(),
                server.updatedAt()
        );
    }

    public MinecraftServerDTO.TopologyDTO toDTO(MinecraftServerTopology topology) {
        if (topology == null) {
            return null;
        }
        return new MinecraftServerDTO.TopologyDTO(
                topology.proxy(),
                topology.proxyVersion(),
                topology.reportedAt(),
                topology.servers().stream().map(this::toDTO).toList()
        );
    }

    public MinecraftServerDTO.SubServerDTO toDTO(MinecraftSubServer server) {
        return new MinecraftServerDTO.SubServerDTO(server.name(), server.address(), server.online(),
                server.sensor(), server.defaultServer(), server.sort());
    }

    public MinecraftServerMapDTO toDTO(MinecraftServerMap map) {
        return new MinecraftServerMapDTO(map.fileId(), map.originalName(), map.publicAccess(), blankToNull(map.externalUrl()));
    }

    public MinecraftServerDTO toUserDTO(MinecraftServerDTO dto) {
        if (dto.map() == null) {
            return dto;
        }
        MinecraftServerMapDTO map = dto.map().publicAccess() ? dto.map() : dto.map().withoutExternalUrl();
        return new MinecraftServerDTO(
                dto.id(),
                dto.name(),
                dto.descriptionMarkdown(),
                dto.enabled(),
                dto.sort(),
                dto.endpoints(),
                dto.seasons(),
                dto.currentSeason(),
                dto.status(),
                map,
                dto.topology(),
                dto.createdAt(),
                dto.updatedAt()
        );
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    public MinecraftServerDTO.EndpointDTO toDTO(MinecraftServerEndpoint endpoint) {
        return new MinecraftServerDTO.EndpointDTO(endpoint.id(), endpoint.name(), endpoint.host(), endpoint.port(),
                endpoint.edition().name(), endpoint.primaryLine(), endpoint.enabled(), endpoint.sort());
    }

    public MinecraftServerDTO.SeasonDTO toDTO(MinecraftServerSeason season) {
        return new MinecraftServerDTO.SeasonDTO(season.id(), season.name(), season.description(), season.startedAt(),
                season.endedAt(), season.current(), season.sort(), toDTO(season.modpackBinding()));
    }

    public ModpackBindingDTO toDTO(ModpackBinding binding) {
        if (binding == null) return ModpackBindingDTO.none();
        return new ModpackBindingDTO(
                binding.type() == null ? ModpackBindingDTO.TYPE_NONE : binding.type().name(),
                binding.gameVersion(),
                binding.loader(),
                binding.packId(),
                binding.versionId()
        );
    }

    public MinecraftServerStatusDTO toDTO(MinecraftServerStatus status) {
        return new MinecraftServerStatusDTO(status.serverId(), status.status(), status.onlinePlayers(), status.maxPlayers(),
                status.endpoints().stream().map(this::toDTO).toList(), status.checkedAt());
    }

    public MinecraftStatusSnapshotDTO toDTO(MinecraftStatusSnapshot snapshot) {
        return new MinecraftStatusSnapshotDTO(snapshot.id(), snapshot.serverId(), snapshot.status(),
                snapshot.onlinePlayers(), snapshot.maxPlayers(), snapshot.checkedAt());
    }

    public MinecraftEndpointStatusDTO toDTO(MinecraftEndpointStatus status) {
        return new MinecraftEndpointStatusDTO(status.endpointId(), status.status(), status.onlinePlayers(), status.maxPlayers(),
                status.versionName(), status.protocolId(), status.ping(), status.motd(), status.favicon(),
                status.errorMessage(),
                status.players().stream()
                        .map(player -> new MinecraftEndpointStatusDTO.PlayerDTO(player.id(), player.name()))
                        .toList(),
                status.checkedAt());
    }

    public MinecraftSeasonOperationDTO toDTO(MinecraftSeasonOperation operation, Map<String, BigDecimal> realTotals) {
        return new MinecraftSeasonOperationDTO(
                operation.id(),
                operation.serverId(),
                operation.fromSeasonId(),
                operation.toSeasonId(),
                operation.toSeasonName(),
                operation.status().name(),
                operation.rules().stream().map(this::toDTO).toList(),
                operation.adjustments().stream()
                        .map(adjustment -> toDTO(adjustment, realTotals == null ? BigDecimal.ZERO : realTotals.getOrDefault(key(adjustment.userId(), adjustment.assetCode()), BigDecimal.ZERO)))
                        .toList(),
                operation.operatorUserId(),
                operation.remark(),
                operation.createdAt(),
                operation.rolledBackAt()
        );
    }

    public MinecraftInheritanceRuleDTO toDTO(MinecraftInheritanceRule rule) {
        return new MinecraftInheritanceRuleDTO(rule.assetPattern(), rule.minAmount(), rule.maxAmount(), rule.inheritRate(), rule.rangeLabel());
    }

    public MinecraftSeasonAdjustmentDTO toDTO(MinecraftSeasonAdjustment adjustment, BigDecimal realTotal) {
        return new MinecraftSeasonAdjustmentDTO(
                adjustment.userId(),
                adjustment.assetCode(),
                adjustment.inheritedAmount(),
                adjustment.seasonIncomeAmount(),
                adjustment.seasonTotalAmount(),
                realTotal,
                adjustment.nextInheritedAmount(),
                adjustment.walletBalanceBefore(),
                adjustment.deltaAmount(),
                adjustment.direction(),
                adjustment.ruleLabel(),
                adjustment.walletTransactionId(),
                adjustment.rollbackTransactionId()
        );
    }

    public MinecraftPlayerActivityDTO toDTO(MinecraftPlayerActivity activity, long now) {
        return new MinecraftPlayerActivityDTO(
                activity.serverId(),
                activity.playerId(),
                activity.playerName(),
                activity.online(),
                activity.afk(),
                activity.totalOnlineMillisAt(now),
                activity.totalAfkMillisAt(now),
                activity.currentOnlineSince(),
                activity.currentAfkSince(),
                activity.lastJoinedAt(),
                activity.lastQuitAt(),
                activity.updatedAt(),
                activity.subServers().values().stream().map(bucket -> toDTO(bucket, now)).toList()
        );
    }

    public MinecraftPlayerActivityDTO.SubServerDTO toDTO(MinecraftSubServerActivity bucket, long now) {
        return new MinecraftPlayerActivityDTO.SubServerDTO(
                bucket.name(),
                bucket.online(),
                bucket.afk(),
                bucket.onlineAt(now),
                bucket.afkAt(now),
                bucket.currentOnlineSince(),
                bucket.currentAfkSince(),
                bucket.lastJoinedAt(),
                bucket.lastQuitAt()
        );
    }

    private String key(String userId, String assetCode) {
        return userId + ":" + assetCode;
    }
}
