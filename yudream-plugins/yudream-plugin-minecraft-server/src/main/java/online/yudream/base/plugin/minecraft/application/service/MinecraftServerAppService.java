package online.yudream.base.plugin.minecraft.application.service;

import online.yudream.base.plugin.minecraft.application.assembler.MinecraftServerAppAssembler;
import online.yudream.base.plugin.minecraft.api.PluginMinecraftActivePlayer;
import online.yudream.base.plugin.minecraft.api.PluginMinecraftOnlineWindow;
import online.yudream.base.plugin.minecraft.api.PluginMinecraftPlayerActivity;
import online.yudream.base.plugin.minecraft.api.PluginMinecraftServer;
import online.yudream.base.plugin.minecraft.api.PluginMinecraftService;
import online.yudream.base.plugin.minecraft.api.PluginMinecraftSubServer;
import online.yudream.base.plugin.minecraft.api.PluginMinecraftSubServerActivity;
import online.yudream.base.plugin.minecraft.application.cmd.MinecraftPlayerEventCmd;
import online.yudream.base.plugin.minecraft.application.cmd.MinecraftPlayerSnapshotCmd;
import online.yudream.base.plugin.minecraft.application.cmd.MinecraftSeasonOpenCmd;
import online.yudream.base.plugin.minecraft.application.cmd.MinecraftServerSaveCmd;
import online.yudream.base.plugin.minecraft.application.cmd.MinecraftServerTopologyCmd;
import online.yudream.base.plugin.minecraft.application.dto.MinecraftEconomyRecordDTO;
import online.yudream.base.plugin.minecraft.application.dto.MinecraftSeasonOperationDTO;
import online.yudream.base.plugin.minecraft.application.dto.MinecraftPlayerActivityDTO;
import online.yudream.base.plugin.minecraft.application.dto.MinecraftPageDTO;
import online.yudream.base.plugin.minecraft.application.dto.MinecraftServerDTO;
import online.yudream.base.plugin.minecraft.application.dto.MinecraftStatusSnapshotDTO;
import online.yudream.base.plugin.minecraft.domain.aggregate.MinecraftSeasonOperation;
import online.yudream.base.plugin.minecraft.domain.aggregate.MinecraftServer;
import online.yudream.base.plugin.minecraft.domain.aggregate.MinecraftServerTopology;
import online.yudream.base.plugin.minecraft.domain.aggregate.MinecraftPlayerActivity;
import online.yudream.base.plugin.minecraft.domain.aggregate.MinecraftPlayerActivityEvent;
import online.yudream.base.plugin.minecraft.domain.enumerate.MinecraftEdition;
import online.yudream.base.plugin.minecraft.domain.enumerate.MinecraftSeasonOperationStatus;
import online.yudream.base.plugin.minecraft.domain.repo.MinecraftServerRepository;
import online.yudream.base.plugin.minecraft.domain.valobj.MinecraftEndpointStatus;
import online.yudream.base.plugin.minecraft.domain.valobj.MinecraftInheritanceRule;
import online.yudream.base.plugin.minecraft.domain.valobj.MinecraftSeasonAdjustment;
import online.yudream.base.plugin.minecraft.domain.valobj.MinecraftServerEndpoint;
import online.yudream.base.plugin.minecraft.domain.valobj.MinecraftServerSeason;
import online.yudream.base.plugin.minecraft.domain.valobj.ModpackBinding;
import online.yudream.base.plugin.minecraft.domain.valobj.MinecraftServerStatus;
import online.yudream.base.plugin.minecraft.domain.valobj.MinecraftServerMap;
import online.yudream.base.plugin.minecraft.domain.valobj.MinecraftSubServer;
import online.yudream.base.plugin.minecraft.domain.valobj.MinecraftSubServerActivity;
import online.yudream.base.plugin.spi.system.storage.PluginFileStore;
import online.yudream.base.plugin.spi.system.storage.PluginStoredFile;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Optional;
import online.yudream.base.plugin.minecraft.domain.valobj.MinecraftStatusSnapshot;
import online.yudream.base.plugin.minecraft.infrastructure.service.MinecraftStatusService;
import online.yudream.base.plugin.spi.system.FrameworkServices;
import online.yudream.base.plugin.spi.core.PluginContext;
import online.yudream.base.plugin.wallet.api.PluginWalletAsset;
import online.yudream.base.plugin.wallet.api.PluginWalletBalance;
import online.yudream.base.plugin.wallet.api.PluginWalletChangeRequest;
import online.yudream.base.plugin.wallet.api.PluginWalletService;
import online.yudream.base.plugin.wallet.api.PluginWalletTransaction;
import online.yudream.base.plugin.wallet.api.PluginWalletTransactionQuery;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

public class MinecraftServerAppService implements PluginMinecraftService {

    private static final long MAX_FUTURE_EVENT_MILLIS = 5L * 60 * 1000;
    private static final long DEFAULT_HISTORY_WINDOW_MILLIS = 24L * 60 * 60 * 1000;
    private static final int DEFAULT_HISTORY_LIMIT = 144;
    private static final int MAX_HISTORY_LIMIT = 288;
    private static final int SCAN_PAGE_SIZE = 200;

    private final MinecraftServerRepository repository;
    private final MinecraftStatusService statusService;
    private final FrameworkServices framework;
    private final PluginContext pluginContext;
    private final PluginFileStore files;
    private final MinecraftServerAppAssembler assembler = new MinecraftServerAppAssembler();
    private final Object playerActivityLock = new Object();

    public MinecraftServerAppService(MinecraftServerRepository repository, MinecraftStatusService statusService, PluginContext pluginContext) {
        this(repository, statusService, pluginContext, pluginContext.files());
    }

    public MinecraftServerAppService(MinecraftServerRepository repository, MinecraftStatusService statusService, PluginContext pluginContext, PluginFileStore files) {
        this.repository = repository;
        this.statusService = statusService;
        this.pluginContext = pluginContext;
        this.framework = pluginContext.framework();
        this.files = files;
    }

    public MinecraftPageDTO<MinecraftServerDTO> pageServers(boolean includeDisabled, boolean refreshStatus, int page, int size) {
        int safePage = safePage(page);
        int safeSize = safeSize(size);
        List<MinecraftServerDTO> records = repository.list(safePage, safeSize, includeDisabled).stream()
                .map(server -> toDto(server, refreshStatus ? refreshStatus(server.id()) : repository.findStatus(server.id()).orElse(null)))
                .map(dto -> includeDisabled ? dto : assembler.toUserDTO(dto))
                .toList();
        return new MinecraftPageDTO<>(records, repository.count(includeDisabled));
    }

    public List<MinecraftServerDTO> listServers(boolean includeDisabled, boolean refreshStatus) {
        return allServers(includeDisabled).stream()
                .map(server -> toDto(server, refreshStatus ? refreshStatus(server.id()) : repository.findStatus(server.id()).orElse(null)))
                .toList();
    }

    public MinecraftServerDTO detail(String serverId, boolean refreshStatus) {
        MinecraftServer server = requireServer(serverId);
        MinecraftServerStatus status = refreshStatus ? refreshStatus(server.id()) : repository.findStatus(server.id()).orElse(null);
        return toDto(server, status);
    }

    /** Builds the DTO with the server's reported proxy topology, which is absent for a non-proxy. */
    private MinecraftServerDTO toDto(MinecraftServer server, MinecraftServerStatus status) {
        return assembler.toDTO(server, status, repository.findTopology(server.id()).orElse(null));
    }

    /**
     * Stores the downstream-server list reported by a bridge that targets this server explicitly.
     *
     * @throws IllegalArgumentException when the reported server id does not exist
     */
    public MinecraftServerDTO.TopologyDTO recordTopology(String serverId, MinecraftServerTopologyCmd cmd) {
        return saveTopology(requireServer(serverId), cmd);
    }

    /**
     * Stores a topology reported without a server id, by matching the proxy's own addresses against
     * the configured endpoints. This is what removes the need to copy an Admin server id into the
     * proxy's bridge config: the operator points the bridge at Admin and the entry attaches itself.
     *
     * @return the matched topology, or empty when no server claims any of the addresses
     */
    public Optional<MinecraftServerDTO.TopologyDTO> recordTopologyByAddress(List<String> addresses, MinecraftServerTopologyCmd cmd) {
        return matchByAddress(addresses).map(server -> saveTopology(server, cmd));
    }

    /**
     * Re-reads the topology already attached to a server.
     *
     * @throws IllegalArgumentException when the bridge has never reported one, with the reason a
     *                                  proxy cannot be discovered from Admin alone
     */
    public MinecraftServerDTO.TopologyDTO resolveTopology(String serverId) {
        MinecraftServer server = requireServer(serverId);
        return repository.findTopology(server.id())
                .filter(MinecraftServerTopology::reported)
                .map(assembler::toDTO)
                .orElseThrow(() -> new IllegalArgumentException(
                        "该服务器还没有收到代理拓扑上报。代理的子服列表只能由代理自己上报，"
                                + "无法从 Admin 侧探测：请在代理端安装桥接插件，并在其配置中填写 Admin 地址、API Key，"
                                + "以及与本服务器线路一致的地址。"));
    }

    private MinecraftServerDTO.TopologyDTO saveTopology(MinecraftServer server, MinecraftServerTopologyCmd cmd) {
        long reportedAt = cmd.reportedAt() == null || cmd.reportedAt() <= 0 ? System.currentTimeMillis() : cmd.reportedAt();
        MinecraftServerTopology topology = new MinecraftServerTopology(
                server.id(),
                cmd.proxy(),
                cmd.proxyVersion(),
                reportedAt,
                toSubServers(cmd.servers()));
        return assembler.toDTO(repository.saveTopology(topology));
    }

    private List<MinecraftSubServer> toSubServers(List<MinecraftServerTopologyCmd.Server> servers) {
        List<MinecraftSubServer> items = new java.util.ArrayList<>();
        int sort = 0;
        for (MinecraftServerTopologyCmd.Server server : servers) {
            if (server == null || server.name() == null || server.name().isBlank()) {
                continue;
            }
            items.add(new MinecraftSubServer(
                    server.name(),
                    server.address(),
                    server.online() == null ? 0 : server.online(),
                    Boolean.TRUE.equals(server.sensor()),
                    Boolean.TRUE.equals(server.defaultServer()),
                    sort++));
        }
        return items;
    }

    private Optional<MinecraftServer> matchByAddress(List<String> addresses) {
        if (addresses == null || addresses.isEmpty()) {
            return Optional.empty();
        }
        List<String> wanted = addresses.stream()
                .filter(address -> address != null && !address.isBlank())
                .map(MinecraftServerAppService::normalizeAddress)
                .toList();
        if (wanted.isEmpty()) {
            return Optional.empty();
        }
        return allServers(true).stream()
                .filter(server -> server.endpoints().stream()
                        .anyMatch(endpoint -> wanted.contains(normalizeAddress(endpoint.address()))
                                || wanted.contains(normalizeAddress(endpoint.host()))))
                .findFirst();
    }

    /** Compares addresses case-insensitively and treats a missing port as the default one. */
    private static String normalizeAddress(String value) {
        String address = value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
        if (address.isEmpty()) {
            return "";
        }
        if (address.endsWith(":25565")) {
            return address.substring(0, address.length() - ":25565".length());
        }
        return address;
    }

    public MinecraftServerDTO bindSeasonModpack(String serverId, String seasonId, MinecraftServerSaveCmd.ModpackBinding binding) {
        MinecraftServer server = requireServer(serverId);
        String targetSeasonId = requireText(seasonId, "周目 ID 不能为空");
        ModpackBinding nextBinding = toModpackBinding(binding);
        boolean found = false;
        List<MinecraftServerSeason> seasons = new ArrayList<>();
        for (MinecraftServerSeason season : server.seasons()) {
            if (season.id().equals(targetSeasonId)) {
                seasons.add(season.withModpackBinding(nextBinding));
                found = true;
            } else {
                seasons.add(season);
            }
        }
        if (!found) {
            throw new IllegalArgumentException("周目不存在：" + targetSeasonId);
        }
        MinecraftServer saved = repository.save(server.update(null, null, null, null, null, seasons));
        return assembler.toDTO(saved, repository.findStatus(saved.id()).orElse(null));
    }

    public MinecraftServerDTO saveServer(MinecraftServerSaveCmd cmd) {
        MinecraftServer existing = cmd.id() == null || cmd.id().isBlank()
                ? null
                : repository.findById(cmd.id()).orElse(null);
        List<MinecraftServerEndpoint> endpoints = toEndpoints(cmd.endpoints());
        List<MinecraftServerSeason> seasons = toSeasons(cmd.seasons());
        if (seasons.isEmpty()) {
            seasons = List.of(new MinecraftServerSeason(null, "第一周目", "初始周目", System.currentTimeMillis(), null, true, 0, ModpackBinding.none()));
        }
        MinecraftServer server = existing == null
                ? MinecraftServer.create(cmd.name(), cmd.descriptionMarkdown(), cmd.enabled() == null || cmd.enabled(),
                cmd.sort() == null ? 0 : cmd.sort(), endpoints, seasons)
                : existing.update(cmd.name(), cmd.descriptionMarkdown(), cmd.enabled(), cmd.sort(), endpoints, seasons);
        MinecraftServer saved = repository.save(server);
        return assembler.toDTO(saved, repository.findStatus(saved.id()).orElse(null));
    }

    public void deleteServer(String serverId) {
        MinecraftServer server = requireServer(serverId);
        deleteStoredMapFile(server);
        repository.delete(serverId);
    }

    public MinecraftServerDTO saveMap(String serverId, String fileId) {
        MinecraftServer server = requireServer(serverId);
        PluginStoredFile source = framework.platformFile(requireText(fileId, "地图文件不能为空"))
                .orElseThrow(() -> new IllegalArgumentException("平台文件不存在：" + fileId));
        try (var input = source.inputStream()) {
            String contentType = source.contentType();
            if (contentType != null && !contentType.isBlank() && !List.of("application/zip", "application/x-zip-compressed", "application/octet-stream").contains(contentType.toLowerCase(java.util.Locale.ROOT))) {
                throw new IllegalArgumentException("地图文件必须是 ZIP 压缩包");
            }
            byte[] bytes = input.readAllBytes();
            if (bytes.length < 4 || bytes[0] != 'P' || bytes[1] != 'K' || bytes[2] != 3 || bytes[3] != 4) throw new IllegalArgumentException("地图文件必须是 ZIP 压缩包");
            String objectKey = "servers/" + server.id() + "/map.zip";
            deleteStoredMapFile(server);
            files.put(objectKey, new ByteArrayInputStream(bytes), bytes.length, "application/zip");
            MinecraftServer saved = repository.save(server.withMap(MinecraftServerMap.storedFile(fileId, objectKey, fileId + ".zip", false)));
            return assembler.toDTO(saved, repository.findStatus(saved.id()).orElse(null));
        } catch (IOException e) { throw new IllegalStateException("读取地图文件失败", e); }
    }

    public MinecraftServerDTO saveMapLink(String serverId, String url, String originalName) {
        MinecraftServer server = requireServer(serverId);
        deleteStoredMapFile(server);
        MinecraftServer saved = repository.save(server.withMap(MinecraftServerMap.externalLink(url, originalName, false)));
        return assembler.toDTO(saved, repository.findStatus(saved.id()).orElse(null));
    }

    public MinecraftServerDTO setMapPublicAccess(String serverId, boolean publicAccess) {
        MinecraftServer server = requireServer(serverId);
        if (server.map() == null) throw new IllegalArgumentException("服务器尚未上传地图");
        MinecraftServer saved = repository.save(server.withMap(server.map().withPublicAccess(publicAccess)));
        return assembler.toDTO(saved, repository.findStatus(saved.id()).orElse(null));
    }

    public MinecraftServerDTO deleteMap(String serverId) {
        MinecraftServer server = requireServer(serverId);
        if (server.map() == null) throw new IllegalArgumentException("服务器尚未上传地图");
        deleteStoredMapFile(server);
        MinecraftServer saved = repository.save(server.withMap(null));
        return assembler.toDTO(saved, repository.findStatus(saved.id()).orElse(null));
    }

    public PluginStoredFile downloadMap(String serverId, boolean publicOnly, boolean allowClosed) {
        MinecraftServer server = requireServer(serverId);
        if (!allowClosed && !server.enabled()) throw new IllegalArgumentException("服务器不存在");
        MinecraftServerMap map = server.map();
        if (map == null || (publicOnly && !map.publicAccess())) throw new IllegalArgumentException("地图不存在");
        if (map.external()) throw new IllegalArgumentException("请使用网盘链接下载");
        PluginStoredFile file = files.get(map.objectKey());
        if (file == null) throw new IllegalArgumentException("地图文件不存在");
        return file;
    }

    public MinecraftServerStatus refreshStatus(String serverId) {
        MinecraftServer server = requireServer(serverId);
        MinecraftServerStatus previousStatus = repository.findStatus(server.id()).orElse(null);
        List<MinecraftEndpointStatus> statuses = server.endpoints().stream()
                .map(statusService::ping)
                .toList();
        MinecraftServerStatus status = repository.saveStatus(MinecraftServerStatus.from(server.id(), statuses));
        repository.saveStatusSnapshot(MinecraftStatusSnapshot.from(status));
        boolean confirmedOffline = !"ONLINE".equals(status.status())
                && previousStatus != null
                && !"ONLINE".equals(previousStatus.status());
        boolean confirmedEmpty = "ONLINE".equals(status.status()) && status.onlinePlayers() == 0;
        if (confirmedOffline || confirmedEmpty) {
            recoverPlayersFromOfflineServer(server.id(), confirmedOffline ? previousStatus : status, status.checkedAt());
        }
        return status;
    }

    public MinecraftServerDTO userDetail(String serverId, boolean refreshStatus) {
        MinecraftServerDTO detail = assembler.toUserDTO(detail(serverId, refreshStatus));
        if (!detail.enabled()) {
            throw new IllegalArgumentException("服务器不存在");
        }
        return detail;
    }

    /** Closed servers remain available only through the explicit archive surface. */
    public MinecraftPageDTO<MinecraftServerDTO> archivedServers(int page, int size) {
        List<MinecraftServerDTO> records = allServers(true).stream().filter(server -> !server.enabled())
                .skip((long) (safePage(page) - 1) * safeSize(size)).limit(safeSize(size))
                .map(server -> assembler.toUserDTO(toDto(server, repository.findStatus(server.id()).orElse(null)))).toList();
        long total = allServers(true).stream().filter(server -> !server.enabled()).count();
        return new MinecraftPageDTO<>(records, total);
    }

    public MinecraftServerDTO archivedDetail(String serverId) {
        MinecraftServerDTO detail = assembler.toUserDTO(detail(serverId, false));
        if (detail.enabled()) throw new IllegalArgumentException("关闭服务器不存在");
        return detail;
    }

    public void refreshEnabledServers() {
        for (MinecraftServer server : allServers(false)) {
            try {
                refreshStatus(server.id());
            } catch (RuntimeException ignored) {
            }
        }
    }

    public List<MinecraftStatusSnapshotDTO> statusHistory(String serverId, Long since, int limit) {
        requireServer(serverId);
        long from = since == null || since <= 0
                ? System.currentTimeMillis() - DEFAULT_HISTORY_WINDOW_MILLIS
                : normalizeTimestamp(since);
        int safeLimit = Math.max(Math.min(limit <= 0 ? DEFAULT_HISTORY_LIMIT : limit, MAX_HISTORY_LIMIT), 1);
        return repository.listStatusSnapshots(serverId, from, safeLimit).stream()
                .map(assembler::toDTO)
                .toList();
    }

    public boolean walletEnabled() {
        try {
            Class.forName("online.yudream.base.plugin.wallet.api.PluginWalletService", false, getClass().getClassLoader());
            return pluginContext != null && pluginContext.service("yudream-wallet", PluginWalletService.class).isPresent();
        } catch (ClassNotFoundException | LinkageError ignored) {
            return false;
        }
    }

    public MinecraftSeasonOperationDTO previewOpenSeason(String serverId, MinecraftSeasonOpenCmd cmd, String operatorUserId) {
        requireWalletEnabled();
        MinecraftSeasonOperation operation = buildSeasonOperation(requireServer(serverId), cmd, operatorUserId);
        return assembler.toDTO(operation, realIncomeTotals(wallet()));
    }

    public MinecraftSeasonOperationDTO openSeason(String serverId, MinecraftSeasonOpenCmd cmd, String operatorUserId) {
        requireWalletEnabled();
        MinecraftServer server = requireServer(serverId);
        MinecraftSeasonOperation preview = buildSeasonOperation(server, cmd, operatorUserId);
        List<MinecraftSeasonAdjustment> appliedAdjustments = applyAdjustments(preview);
        MinecraftSeasonOperation applied = repository.saveOperation(preview.applied(appliedAdjustments));
        repository.save(openSeasonOnServer(server, applied, cmd));
        return assembler.toDTO(applied, realIncomeTotals(wallet()));
    }

    public MinecraftSeasonOperationDTO rollbackSeasonOperation(String operationId, String operatorUserId) {
        requireWalletEnabled();
        MinecraftSeasonOperation operation = repository.findOperation(operationId)
                .orElseThrow(() -> new IllegalArgumentException("周目操作不存在：" + operationId));
        if (operation.status() != MinecraftSeasonOperationStatus.APPLIED) {
            throw new IllegalArgumentException("只有已应用的周目操作可以撤回");
        }
        MinecraftSeasonOperation latest = repository.listOperations(operation.serverId(), 1, 1).stream().findFirst().orElse(null);
        if (latest == null || !latest.id().equals(operation.id())) {
            throw new IllegalArgumentException("只能撤回该服务器最新一次周目操作");
        }
        List<MinecraftSeasonAdjustment> rolledBackAdjustments = rollbackAdjustments(operation);
        MinecraftSeasonOperation rolledBack = repository.saveOperation(operation.rolledBack(rolledBackAdjustments));
        repository.save(rollbackSeasonOnServer(requireServer(operation.serverId()), operation));
        return assembler.toDTO(rolledBack, realIncomeTotals(wallet()));
    }

    public MinecraftPageDTO<MinecraftSeasonOperationDTO> operations(String serverId, int page, int size) {
        PluginWalletService wallet = walletOrNull();
        Map<String, BigDecimal> realIncomeTotals = wallet == null ? Map.of() : realIncomeTotals(wallet);
        List<MinecraftSeasonOperationDTO> records = repository.listOperations(serverId, safePage(page), safeSize(size)).stream()
                .map(operation -> assembler.toDTO(operation, realIncomeTotals))
                .toList();
        return new MinecraftPageDTO<>(records, repository.countOperations(serverId));
    }

    public MinecraftPageDTO<MinecraftEconomyRecordDTO> userRecords(String serverId, String userId, int page, int size) {
        MinecraftServer server = requireServer(serverId);
        MinecraftServerSeason currentSeason = server.currentSeason();
        Long startAt = currentSeason == null ? null : currentSeason.startedAt();
        int safePage = safePage(page);
        int safeSize = safeSize(size);
        int fetchSize = safePage * safeSize;
        PluginWalletService wallet = walletOrNull();
        List<MinecraftEconomyRecordDTO> walletRecords = wallet == null
                ? List.of()
                : wallet.transactions(new PluginWalletTransactionQuery(
                                null, null, null, requireText(userId, "用户不能为空"), startAt, null, 1, fetchSize
                        )).stream()
                        .map(transaction -> new MinecraftEconomyRecordDTO(transaction.id(), "WALLET", transaction.source(), transaction.type(),
                                transaction.assetCode(), transaction.amount(), transaction.businessNo(), transaction.remark(), transaction.createdAt()))
                        .toList();
        List<MinecraftEconomyRecordDTO> seasonRecords = allOperations(serverId).stream()
                .flatMap(operation -> operation.adjustments().stream()
                        .filter(adjustment -> userId.equals(adjustment.userId()))
                        .map(adjustment -> new MinecraftEconomyRecordDTO(
                                operation.id() + ":" + adjustment.assetCode(),
                                "SEASON_INHERIT",
                                "MINECRAFT_SEASON",
                                operation.status().name(),
                                adjustment.assetCode(),
                                adjustment.deltaAmount(),
                                adjustment.walletTransactionId(),
                                operation.toSeasonName() + " 继承处理：" + adjustment.ruleLabel(),
                                operation.createdAt()
                        )))
                .toList();
        List<MinecraftEconomyRecordDTO> all = java.util.stream.Stream.concat(walletRecords.stream(), seasonRecords.stream())
                .sorted(Comparator.comparingLong(MinecraftEconomyRecordDTO::createdAt).reversed())
                .toList();
        List<MinecraftEconomyRecordDTO> records = all.stream()
                .skip((long) (safePage - 1) * safeSize)
                .limit(safeSize)
                .toList();
        return new MinecraftPageDTO<>(records, all.size());
    }

    public MinecraftPlayerActivityDTO recordJoin(String serverId, MinecraftPlayerEventCmd cmd) {
        requireServer(serverId);
        synchronized (playerActivityLock) {
            long eventAt = eventAt(cmd);
            MinecraftPlayerActivity activity = activity(serverId, cmd).join(cmd.subServer(), cmd.playerName(), eventAt);
            recordActivityEvent(serverId, cmd, MinecraftPlayerActivityEvent.Type.JOIN, eventAt);
            return assembler.toDTO(repository.savePlayerActivity(activity), System.currentTimeMillis());
        }
    }

    public MinecraftPlayerActivityDTO recordQuit(String serverId, MinecraftPlayerEventCmd cmd) {
        requireServer(serverId);
        synchronized (playerActivityLock) {
            long eventAt = eventAt(cmd);
            MinecraftPlayerActivity activity = activity(serverId, cmd).quit(cmd.subServer(), cmd.playerName(), eventAt);
            recordActivityEvent(serverId, cmd, MinecraftPlayerActivityEvent.Type.QUIT, eventAt);
            return assembler.toDTO(repository.savePlayerActivity(activity), System.currentTimeMillis());
        }
    }

    public MinecraftPlayerActivityDTO recordAfkStart(String serverId, MinecraftPlayerEventCmd cmd) {
        requireServer(serverId);
        synchronized (playerActivityLock) {
            long eventAt = eventAt(cmd);
            MinecraftPlayerActivity activity = activity(serverId, cmd).startAfk(cmd.subServer(), cmd.playerName(), eventAt);
            recordActivityEvent(serverId, cmd, MinecraftPlayerActivityEvent.Type.AFK_START, eventAt);
            return assembler.toDTO(repository.savePlayerActivity(activity), System.currentTimeMillis());
        }
    }

    public MinecraftPlayerActivityDTO recordAfkEnd(String serverId, MinecraftPlayerEventCmd cmd) {
        requireServer(serverId);
        synchronized (playerActivityLock) {
            long eventAt = eventAt(cmd);
            MinecraftPlayerActivity activity = activity(serverId, cmd).endAfk(cmd.subServer(), cmd.playerName(), eventAt);
            recordActivityEvent(serverId, cmd, MinecraftPlayerActivityEvent.Type.AFK_END, eventAt);
            return assembler.toDTO(repository.savePlayerActivity(activity), System.currentTimeMillis());
        }
    }

    /**
     * 按权威名册对账在线玩家。
     *
     * <p>分组形态下对账是<b>子服维度</b>的：只为本次上报真正列出的子服收尾，否则一份 paper 的
     * 快照会把 fabric 上的人全部关掉。扁平形态保持改动前的整服语义。
     *
     * @return 本次上报的名册人数
     */
    public int reconcilePlayerSnapshot(String serverId, MinecraftPlayerSnapshotCmd cmd) {
        requireServer(serverId);
        long observedAt = normalizeTimestamp(cmd.observedAt());
        if (cmd.grouped()) {
            return reconcileGroupedSnapshot(serverId, cmd, observedAt);
        }
        return reconcileFlatSnapshot(serverId, cmd, observedAt);
    }

    /** 扁平快照：没有子服维度，整台服务器的在线玩家都要在名册里，语义与改动前一致。 */
    private int reconcileFlatSnapshot(String serverId, MinecraftPlayerSnapshotCmd cmd, long observedAt) {
        Map<String, MinecraftPlayerSnapshotCmd.Player> reported = cmd.players().stream()
                .collect(Collectors.toMap(
                        player -> requireText(player.playerId(), "玩家 ID 不能为空"),
                        player -> player,
                        (first, ignored) -> first,
                        LinkedHashMap::new));
        synchronized (playerActivityLock) {
            for (MinecraftPlayerActivity activity : allStoredPlayerActivities(serverId)) {
                if (activity.online()) {
                    MinecraftPlayerSnapshotCmd.Player player = reported.remove(activity.playerId());
                    if (player == null && observedAt >= activity.updatedAt()) {
                        closeActivity(activity, MinecraftPlayerActivityEvent.Type.SERVER_SNAPSHOT, observedAt);
                    }
                }
            }
            for (MinecraftPlayerSnapshotCmd.Player player : reported.values()) {
                MinecraftPlayerActivity activity = repository.findPlayerActivity(serverId, player.playerId())
                        .orElseGet(() -> MinecraftPlayerActivity.empty(
                                serverId, player.playerId(), player.playerName(), observedAt));
                if (!activity.online()) {
                    repository.savePlayerActivityEvent(MinecraftPlayerActivityEvent.create(
                            serverId, player.playerId(), player.playerName(),
                            MinecraftPlayerActivityEvent.Type.JOIN, observedAt));
                    repository.savePlayerActivity(activity.join(player.playerName(), observedAt));
                }
            }
        }
        return cmd.players().size();
    }

    /**
     * 分组快照：每个列出的子服独立对账。
     *
     * <p>一份只列出 paper 的上报不会碰 fabric 上的任何人；列出的子服即使名册为空也会把它上面
     * 的人关掉，这正是“该子服现在没人”的表达方式。
     */
    private int reconcileGroupedSnapshot(String serverId, MinecraftPlayerSnapshotCmd cmd, long observedAt) {
        Map<String, Map<String, MinecraftPlayerSnapshotCmd.Player>> reported = new LinkedHashMap<>();
        for (MinecraftPlayerSnapshotCmd.Server server : cmd.servers()) {
            Map<String, MinecraftPlayerSnapshotCmd.Player> roster = reported.computeIfAbsent(
                    server.subServer(), key -> new LinkedHashMap<>());
            for (MinecraftPlayerSnapshotCmd.Player player : server.players()) {
                roster.putIfAbsent(requireText(player.playerId(), "玩家 ID 不能为空"), player);
            }
        }
        synchronized (playerActivityLock) {
            for (MinecraftPlayerActivity activity : allStoredPlayerActivities(serverId)) {
                if (observedAt < activity.updatedAt()) {
                    continue;
                }
                MinecraftPlayerActivity current = activity;
                // 一次快照可能同时关掉多台子服上开着的区间，因此收尾事件按子服逐条写：
                // 一条不分子服的收尾事件虽然也能参与任意子服的回放，但那样就分不清是谁被关了。
                List<String> closedSubServers = new ArrayList<>();
                for (Map.Entry<String, Map<String, MinecraftPlayerSnapshotCmd.Player>> entry : reported.entrySet()) {
                    MinecraftSubServerActivity bucket = current.subServers().get(entry.getKey());
                    if (bucket == null || !bucket.online()) {
                        continue;
                    }
                    if (entry.getValue().containsKey(current.playerId())) {
                        continue;
                    }
                    current = current.quit(entry.getKey(), current.playerName(), observedAt);
                    closedSubServers.add(entry.getKey());
                }
                if (!closedSubServers.isEmpty()) {
                    for (String subServer : closedSubServers) {
                        repository.savePlayerActivityEvent(MinecraftPlayerActivityEvent.create(
                                serverId, activity.playerId(), activity.playerName(), subServer,
                                MinecraftPlayerActivityEvent.Type.SERVER_SNAPSHOT, observedAt));
                    }
                    repository.savePlayerActivity(current);
                }
            }
            for (Map.Entry<String, Map<String, MinecraftPlayerSnapshotCmd.Player>> entry : reported.entrySet()) {
                for (MinecraftPlayerSnapshotCmd.Player player : entry.getValue().values()) {
                    MinecraftPlayerActivity activity = repository.findPlayerActivity(serverId, player.playerId())
                            .orElseGet(() -> MinecraftPlayerActivity.empty(
                                    serverId, player.playerId(), player.playerName(), observedAt));
                    MinecraftSubServerActivity bucket = activity.subServers().get(entry.getKey());
                    if (bucket != null && bucket.online()) {
                        continue;
                    }
                    repository.savePlayerActivityEvent(MinecraftPlayerActivityEvent.create(
                            serverId, player.playerId(), player.playerName(), entry.getKey(),
                            MinecraftPlayerActivityEvent.Type.JOIN, observedAt));
                    repository.savePlayerActivity(activity.join(entry.getKey(), player.playerName(), observedAt));
                }
            }
        }
        return reported.values().stream().mapToInt(Map::size).sum();
    }

    public MinecraftPageDTO<MinecraftPlayerActivityDTO> playerActivities(String serverId, int page, int size) {
        requireServer(serverId);
        long now = System.currentTimeMillis();
        List<MinecraftPlayerActivityDTO> records = repository.listPlayerActivities(serverId, safePage(page), safeSize(size)).stream()
                .map(activity -> assembler.toDTO(activity, now))
                .toList();
        return new MinecraftPageDTO<>(records, repository.countPlayerActivities(serverId));
    }

    public List<MinecraftPlayerActivityDTO> onlinePlayerActivities(String serverId) {
        return allPlayerActivities(serverId).stream().filter(MinecraftPlayerActivityDTO::online).toList();
    }

    public List<MinecraftPlayerActivityDTO> allPlayerActivities(String serverId) {
        int page = 1;
        int size = 200;
        List<MinecraftPlayerActivityDTO> result = new ArrayList<>();
        while (true) {
            MinecraftPageDTO<MinecraftPlayerActivityDTO> batch = playerActivities(serverId, page, size);
            result.addAll(batch.records());
            if ((long) page * size >= batch.total()) {
                return List.copyOf(result);
            }
            page++;
        }
    }

    @Override
    public List<PluginMinecraftServer> minecraftServers(boolean includeDisabled) {
        return listServers(includeDisabled, false).stream()
                .map(this::toPluginServer)
                .toList();
    }

    @Override
    public Optional<PluginMinecraftServer> minecraftServer(String serverId) {
        if (serverId == null || serverId.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(toPluginServer(detail(serverId, false)));
    }

    @Override
    public List<PluginMinecraftPlayerActivity> minecraftPlayerActivities(String serverId, int page, int size) {
        return playerActivities(serverId, page, size).records().stream()
                .map(this::toPluginActivity)
                .toList();
    }

    /**
     * 玩家在各子服上的时长拆分。
     *
     * <p>新增的读取方法：{@link #minecraftPlayerActivities} 仍然返回跨子服合计，本方法返回明细，
     * 既有消费方（周目继承、活动证明）的签名与语义不变。
     */
    @Override
    public List<PluginMinecraftSubServerActivity> minecraftSubServerActivities(String serverId, String playerId) {
        if (serverId == null || serverId.isBlank() || playerId == null || playerId.isBlank()) {
            return List.of();
        }
        MinecraftPlayerActivity activity = repository.findPlayerActivity(serverId, playerId).orElse(null);
        if (activity == null) {
            return List.of();
        }
        long now = System.currentTimeMillis();
        return activity.subServers().values().stream()
                .map(bucket -> new PluginMinecraftSubServerActivity(
                        activity.serverId(),
                        activity.playerId(),
                        bucket.name(),
                        bucket.online(),
                        bucket.afk(),
                        bucket.onlineAt(now),
                        bucket.afkAt(now),
                        bucket.currentOnlineSince(),
                        bucket.currentAfkSince(),
                        bucket.lastJoinedAt(),
                        bucket.lastQuitAt()))
                .toList();
    }

    @Override
    public Optional<PluginMinecraftOnlineWindow> minecraftOnlineWindow(String serverId, String playerId, long windowStart, long windowEnd) {
        return minecraftOnlineWindow(serverId, playerId, "", windowStart, windowEnd);
    }

    /**
     * 按子服限定的时间窗统计；{@code subServer} 为空表示整服。
     *
     * <p>群组服下同一个玩家的时间分散在多台子服上，整服口径会把它们加在一起；指定子服后只回放该
     * 子服的事件。改造之前写入的事件没有子服维度，它们按「不限定」处理，因此仍会参与任意子服的
     * 回放——按子服统计时真正的历史归因只能从改造之后算起。
     */
    @Override
    public Optional<PluginMinecraftOnlineWindow> minecraftOnlineWindow(String serverId, String playerId, String subServer,
                                                                      long windowStart, long windowEnd) {
        if (windowStart <= 0 || windowEnd <= windowStart) return Optional.empty();
        MinecraftPlayerActivity activity = repository.findPlayerActivity(serverId, playerId).orElse(null);
        if (activity == null) return Optional.empty();
        List<MinecraftPlayerActivityEvent> events = allPlayerActivityEvents(serverId, playerId);
        if (events.isEmpty()) return Optional.empty();
        WindowStat stat = windowStat(events, subServer, windowStart, windowEnd);
        return Optional.of(new PluginMinecraftOnlineWindow(serverId, playerId, activity.playerName(), windowStart, windowEnd,
                stat.onlineMillis(), stat.afkMillis(), Math.max(0, stat.onlineMillis() - stat.afkMillis())));
    }

    @Override
    public List<PluginMinecraftActivePlayer> minecraftActivePlayers(String serverId, long windowStart, long windowEnd) {
        return minecraftActivePlayers(serverId, "", windowStart, windowEnd);
    }

    @Override
    public List<PluginMinecraftActivePlayer> minecraftActivePlayers(String serverId, String subServer,
                                                                   long windowStart, long windowEnd) {
        if (windowStart <= 0 || windowEnd <= windowStart) return List.of();
        Map<String, List<MinecraftPlayerActivityEvent>> eventsByPlayer = new LinkedHashMap<>();
        for (MinecraftPlayerActivityEvent event : repository.allPlayerActivityEvents(serverId)) {
            eventsByPlayer.computeIfAbsent(event.playerId(), key -> new ArrayList<>()).add(event);
        }
        List<PluginMinecraftActivePlayer> result = new ArrayList<>();
        for (Map.Entry<String, List<MinecraftPlayerActivityEvent>> entry : eventsByPlayer.entrySet()) {
            WindowStat stat = windowStat(entry.getValue(), subServer, windowStart, windowEnd);
            if (stat.onlineMillis() <= 0 && stat.firstJoinAt() <= 0) {
                continue;
            }
            String playerName = entry.getValue().get(entry.getValue().size() - 1).playerName();
            result.add(new PluginMinecraftActivePlayer(serverId, entry.getKey(), playerName, stat.firstJoinAt(),
                    stat.onlineMillis(), stat.afkMillis(), Math.max(0, stat.onlineMillis() - stat.afkMillis())));
        }
        return result;
    }

    /**
     * 该服务器在 Admin 侧已知的子服列表，来自桥接上报的拓扑。
     *
     * <p>非代理端（单机服）没有拓扑，返回空列表；管理端据此决定要不要显示子服选择。为了让界面能
     * 标出默认入口与传感器状态，这里连同 {@code sensor} / {@code defaultServer} 一起下发。
     */
    @Override
    public List<PluginMinecraftSubServer> minecraftSubServers(String serverId) {
        if (serverId == null || serverId.isBlank()) {
            return List.of();
        }
        return repository.findTopology(serverId.trim())
                .map(topology -> topology.servers().stream()
                        .map(sub -> new PluginMinecraftSubServer(sub.name(), sub.address(), sub.online(),
                                sub.sensor(), sub.defaultServer(), sub.sort()))
                        .toList())
                .orElseGet(List::of);
    }

    private record WindowStat(long firstJoinAt, long onlineMillis, long afkMillis) {
    }

    /**
     * 回放事件流水，算出窗口内的在线与挂机时长。
     *
     * <p>两个容易算错的点，都在这里收口：
     *
     * <ul>
     *   <li><b>尚未发生的时间不算已在线。</b>活动时段可能还在进行（{@code windowEnd} 在未来），而
     *       此刻仍在线的人会留下一个没有收尾的事件。若把这段开放区间直接算到 {@code windowEnd}，
     *       一个刚进服的人也会被记成「在线满整个活动周期」。因此回放的有效终点取
     *       {@code min(windowEnd, 现在)}。</li>
     *   <li><b>一个区间的归属由整段决定，而不是逐事件判断。</b>升级期间同一次会话的 JOIN 与 QUIT
     *       可能一个没有子服字段（旧版写入丢掉了）、一个带子服名。只认开启事件的名字，会让这段
     *       区间在具名子服下开不出也关不掉，最终被算到窗口末尾；只认收尾事件，又会把整段误记到
     *       收尾那台子服。这里的规则是：优先用开启事件的名字，开启事件为空时退回收尾事件的名字；
     *       两者都为空即无法归属——整服口径照常计入，具名子服一律不计入。</li>
     * </ul>
     *
     * <p>{@code subServer} 为空表示整服口径，此时所有区间都计入，与他人为改动前的行为一致。
     */
    private WindowStat windowStat(List<MinecraftPlayerActivityEvent> events, String subServer, long windowStart, long windowEnd) {
        long onlineMillis = 0;
        long afkMillis = 0;
        long firstJoinAt = 0;
        Long onlineSince = null;
        Long afkSince = null;
        // 当前区间的归属：开启事件的名字，为空时留待收尾事件补上。
        String openSubServer = "";
        long effectiveEnd = Math.min(windowEnd, System.currentTimeMillis());
        for (MinecraftPlayerActivityEvent event : events) {
            long at = event.occurredAt();
            if (at > effectiveEnd) break;
            switch (event.type()) {
                case JOIN -> {
                    if (onlineSince == null) {
                        onlineSince = at;
                        openSubServer = event.subServer();
                    }
                    // 别的子服的 JOIN 不能让玩家在这台子服上算作「窗口内上线过」。
                    if (firstJoinAt == 0 && at >= windowStart
                            && attributedTo(event.subServer(), "", subServer)) {
                        firstJoinAt = at;
                    }
                }
                case QUIT, SERVER_OFFLINE, SERVER_SNAPSHOT -> {
                    if (attributedTo(openSubServer, event.subServer(), subServer)) {
                        onlineMillis += overlap(onlineSince, at, windowStart, effectiveEnd);
                        afkMillis += overlap(afkSince, at, windowStart, effectiveEnd);
                    }
                    onlineSince = null;
                    afkSince = null;
                    openSubServer = "";
                }
                case AFK_START -> {
                    if (onlineSince == null) {
                        onlineSince = at;
                        openSubServer = event.subServer();
                    }
                    if (afkSince == null) afkSince = at;
                }
                case AFK_END -> {
                    if (attributedTo(openSubServer, event.subServer(), subServer)) {
                        afkMillis += overlap(afkSince, at, windowStart, effectiveEnd);
                    }
                    afkSince = null;
                }
            }
        }
        if (attributedTo(openSubServer, "", subServer)) {
            onlineMillis += overlap(onlineSince, effectiveEnd, windowStart, effectiveEnd);
            afkMillis += overlap(afkSince, effectiveEnd, windowStart, effectiveEnd);
        }
        return new WindowStat(firstJoinAt, onlineMillis, afkMillis);
    }

    /**
     * 这段区间是否计入当前口径。
     *
     * @param openSubServer  开启事件带的子服名，可为空
     * @param closeSubServer 收尾事件带的子服名，可为空
     * @param filter         当前口径；为空表示整服
     */
    private static boolean attributedTo(String openSubServer, String closeSubServer, String filter) {
        String target = filter == null ? "" : filter.trim();
        if (target.isEmpty()) {
            return true;
        }
        String opener = openSubServer == null ? "" : openSubServer.trim();
        String attributed = !opener.isEmpty()
                ? opener
                : (closeSubServer == null ? "" : closeSubServer.trim());
        // 无法归属（开启与收尾都没有子服名）的区间不计入任何具名子服：宁可少算，不可错记。
        return target.equals(attributed);
    }

    private void recordActivityEvent(String serverId, MinecraftPlayerEventCmd cmd, MinecraftPlayerActivityEvent.Type type, long occurredAt) {
        // 事件必须和时长桶带同一个子服，否则按子服回放时间窗时这条事件会落进错误的维度。
        repository.savePlayerActivityEvent(MinecraftPlayerActivityEvent.create(
                serverId, cmd.playerId(), cmd.playerName(), cmd.subServer(), type, occurredAt));
    }

    private void recoverPlayersFromOfflineServer(String serverId, MinecraftServerStatus previousStatus, long detectedAt) {
        synchronized (playerActivityLock) {
            List<MinecraftPlayerActivity> activities = allStoredPlayerActivities(serverId);
            for (MinecraftPlayerActivity activity : activities) {
                if (!activity.online()) {
                    continue;
                }
                if (activity.updatedAt() > detectedAt) {
                    continue;
                }
                long lastTrustedAt = previousStatus == null ? activity.updatedAt() : previousStatus.checkedAt();
                long disconnectedAt = Math.min(detectedAt,
                        Math.max(activity.updatedAt(), Math.max(activity.currentOnlineSince(), lastTrustedAt)));
                closeActivity(activity, MinecraftPlayerActivityEvent.Type.SERVER_OFFLINE, disconnectedAt);
            }
        }
    }

    private void closeActivity(MinecraftPlayerActivity activity, MinecraftPlayerActivityEvent.Type type, long occurredAt) {
        repository.savePlayerActivityEvent(MinecraftPlayerActivityEvent.create(
                activity.serverId(), activity.playerId(), activity.playerName(), type, occurredAt));
        // 整服级别的兜底：同时在多个子服上的玩家必须全部收尾，否则仍会被判定为在线。
        repository.savePlayerActivity(activity.quitAll(activity.playerName(), occurredAt));
    }

    private List<MinecraftPlayerActivity> allStoredPlayerActivities(String serverId) {
        List<MinecraftPlayerActivity> result = new ArrayList<>();
        int page = 1;
        while (true) {
            List<MinecraftPlayerActivity> batch = repository.listPlayerActivities(serverId, page, SCAN_PAGE_SIZE);
            result.addAll(batch);
            if (batch.size() < SCAN_PAGE_SIZE) {
                return result;
            }
            page++;
        }
    }

    private List<MinecraftPlayerActivityEvent> allPlayerActivityEvents(String serverId, String playerId) {
        List<MinecraftPlayerActivityEvent> result = new ArrayList<>();
        int page = 1;
        while (true) {
            List<MinecraftPlayerActivityEvent> batch = repository.listPlayerActivityEvents(serverId, playerId, page, SCAN_PAGE_SIZE);
            result.addAll(batch);
            if (batch.size() < SCAN_PAGE_SIZE) return result;
            page++;
        }
    }

    private long overlap(Long start, long end, long windowStart, long windowEnd) {
        if (start == null || end <= start) return 0;
        return Math.max(0, Math.min(end, windowEnd) - Math.max(start, windowStart));
    }

    private PluginMinecraftServer toPluginServer(MinecraftServerDTO dto) {
        MinecraftServerDTO.SeasonDTO season = dto.currentSeason();
        return new PluginMinecraftServer(
                dto.id(),
                dto.name(),
                dto.descriptionMarkdown(),
                dto.enabled(),
                season == null ? null : season.id(),
                season == null ? null : season.name(),
                season == null ? null : season.startedAt(),
                dto.createdAt(),
                dto.updatedAt(),
                minecraftSubServers(dto.id())
        );
    }

    private PluginMinecraftPlayerActivity toPluginActivity(MinecraftPlayerActivityDTO dto) {
        return new PluginMinecraftPlayerActivity(
                dto.serverId(),
                dto.playerId(),
                dto.playerName(),
                dto.online(),
                dto.afk(),
                dto.totalOnlineMillis(),
                dto.totalAfkMillis(),
                dto.currentOnlineSince(),
                dto.currentAfkSince(),
                dto.lastJoinedAt(),
                dto.lastQuitAt(),
                dto.updatedAt()
        );
    }

    private MinecraftSeasonOperation buildSeasonOperation(MinecraftServer server, MinecraftSeasonOpenCmd cmd, String operatorUserId) {
        long startedAt = cmd.startedAt() == null ? System.currentTimeMillis() : cmd.startedAt();
        MinecraftServerSeason currentSeason = server.currentSeason();
        if (currentSeason != null && currentSeason.startedAt() != null && startedAt <= currentSeason.startedAt()) {
            throw new IllegalArgumentException("新周目开始时间必须晚于当前周目开始时间");
        }
        List<MinecraftInheritanceRule> rules = normalizeRules(cmd.rules());
        String toSeasonId = UUID.randomUUID().toString();
        List<MinecraftSeasonAdjustment> adjustments = calculateAdjustments(server, currentSeason, startedAt, rules);
        return MinecraftSeasonOperation.preview(
                server.id(),
                currentSeason == null ? null : currentSeason.id(),
                toSeasonId,
                requireText(cmd.name(), "新周目名称不能为空"),
                rules,
                adjustments,
                operatorUserId,
                cmd.remark()
        );
    }

    private List<MinecraftSeasonAdjustment> calculateAdjustments(MinecraftServer server, MinecraftServerSeason currentSeason,
                                                                 long endAt, List<MinecraftInheritanceRule> rules) {
        PluginWalletService wallet = wallet();
        Map<String, PluginWalletAsset> assets = wallet.assets().stream()
                .collect(Collectors.toMap(PluginWalletAsset::code, asset -> asset, (first, second) -> first, LinkedHashMap::new));
        Map<String, BigDecimal> balances = balances(wallet, assets);
        Map<String, BigDecimal> seasonIncome = incomeTotals(wallet, currentSeason == null ? null : currentSeason.startedAt(), endAt);
        Map<String, BigDecimal> inherited = inheritedTotals(server.id(), currentSeason == null ? null : currentSeason.id());

        LinkedHashSet<String> keys = new LinkedHashSet<>();
        keys.addAll(inherited.keySet());
        keys.addAll(seasonIncome.keySet());

        return keys.stream()
                .map(key -> {
                    String[] parts = key.split(":", 2);
                    String userId = parts[0];
                    String assetCode = parts[1];
                    PluginWalletAsset asset = assets.get(assetCode);
                    if (asset == null) {
                        return null;
                    }
                    BigDecimal inheritedAmount = inherited.getOrDefault(key, BigDecimal.ZERO);
                    BigDecimal incomeAmount = seasonIncome.getOrDefault(key, BigDecimal.ZERO);
                    BigDecimal seasonTotal = inheritedAmount.add(incomeAmount);
                    MinecraftInheritanceRule rule = matchRule(rules, assetCode, seasonTotal);
                    BigDecimal nextInherited = scale(seasonTotal.multiply(rule.inheritRate()), asset.scale());
                    BigDecimal walletBalance = balances.getOrDefault(key, BigDecimal.ZERO.setScale(asset.scale()));
                    BigDecimal delta = nextInherited.subtract(walletBalance);
                    return new MinecraftSeasonAdjustment(userId, assetCode, inheritedAmount, incomeAmount, seasonTotal,
                            nextInherited, walletBalance, delta, direction(delta), "可使用 " + rule.assetPattern() + " " + rule.rangeLabel() + " x " + rule.inheritRate(), null, null);
                })
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(MinecraftSeasonAdjustment::userId).thenComparing(MinecraftSeasonAdjustment::assetCode))
                .toList();
    }

    private List<MinecraftSeasonAdjustment> applyAdjustments(MinecraftSeasonOperation operation) {
        PluginWalletService wallet = wallet();
        List<MinecraftSeasonAdjustment> applied = new ArrayList<>();
        try {
            for (MinecraftSeasonAdjustment adjustment : operation.adjustments()) {
                BigDecimal delta = adjustment.deltaAmount();
                if (delta == null || delta.signum() == 0) {
                    applied.add(adjustment);
                    continue;
                }
                String businessNo = "mc-season:" + operation.id() + ":" + adjustment.userId() + ":" + adjustment.assetCode();
                PluginWalletTransaction transaction = delta.signum() > 0
                        ? wallet.credit(new PluginWalletChangeRequest(adjustment.userId(), adjustment.assetCode(), delta, businessNo, resetRemark(operation, adjustment)))
                        : wallet.debit(new PluginWalletChangeRequest(adjustment.userId(), adjustment.assetCode(), delta.abs(), businessNo, resetRemark(operation, adjustment)));
                applied.add(adjustment.withWalletTransaction(transaction.id()));
            }
            return List.copyOf(applied);
        } catch (RuntimeException e) {
            rollbackPartialAdjustments(operation, applied, e);
            throw e;
        }
    }

    private void rollbackPartialAdjustments(MinecraftSeasonOperation operation, List<MinecraftSeasonAdjustment> applied, RuntimeException original) {
        PluginWalletService wallet = wallet();
        for (MinecraftSeasonAdjustment adjustment : applied) {
            if (adjustment.walletTransactionId() == null || adjustment.deltaAmount() == null || adjustment.deltaAmount().signum() == 0) {
                continue;
            }
            try {
                BigDecimal amount = adjustment.deltaAmount().abs();
                String businessNo = "mc-season-partial-rollback:" + operation.id() + ":" + adjustment.userId() + ":" + adjustment.assetCode();
                if (adjustment.deltaAmount().signum() > 0) {
                    wallet.debit(new PluginWalletChangeRequest(adjustment.userId(), adjustment.assetCode(), amount, businessNo, "周目继承失败自动撤回"));
                } else {
                    wallet.credit(new PluginWalletChangeRequest(adjustment.userId(), adjustment.assetCode(), amount, businessNo, "周目继承失败自动撤回"));
                }
            } catch (RuntimeException rollbackError) {
                original.addSuppressed(rollbackError);
            }
        }
    }

    private List<MinecraftSeasonAdjustment> rollbackAdjustments(MinecraftSeasonOperation operation) {
        PluginWalletService wallet = wallet();
        List<MinecraftSeasonAdjustment> results = new ArrayList<>();
        for (MinecraftSeasonAdjustment adjustment : operation.adjustments()) {
            if (adjustment.walletTransactionId() == null || adjustment.deltaAmount() == null || adjustment.deltaAmount().signum() == 0) {
                results.add(adjustment);
                continue;
            }
            BigDecimal amount = adjustment.deltaAmount().abs();
            String businessNo = "mc-season-rollback:" + operation.id() + ":" + adjustment.userId() + ":" + adjustment.assetCode();
            PluginWalletTransaction transaction = adjustment.deltaAmount().signum() > 0
                    ? wallet.debit(new PluginWalletChangeRequest(adjustment.userId(), adjustment.assetCode(), amount, businessNo, "撤回周目继承入账：" + operation.toSeasonName()))
                    : wallet.credit(new PluginWalletChangeRequest(adjustment.userId(), adjustment.assetCode(), amount, businessNo, "撤回周目继承扣账：" + operation.toSeasonName()));
            results.add(adjustment.withRollbackTransaction(transaction.id()));
        }
        return List.copyOf(results);
    }

    private MinecraftPlayerActivity activity(String serverId, MinecraftPlayerEventCmd cmd) {
        String playerId = requireText(cmd.playerId(), "玩家 ID 不能为空");
        long eventAt = eventAt(cmd);
        return repository.findPlayerActivity(serverId, playerId)
                .orElseGet(() -> MinecraftPlayerActivity.empty(serverId, playerId, cmd.playerName(), eventAt));
    }

    private long eventAt(MinecraftPlayerEventCmd cmd) {
        Long value = cmd.eventAt();
        return normalizeTimestamp(value);
    }

    private long normalizeTimestamp(Long value) {
        long now = System.currentTimeMillis();
        if (value == null || value <= 0) {
            return now;
        }
        long normalized = value < 10_000_000_000L ? value * 1000 : value;
        return Math.min(normalized, now + MAX_FUTURE_EVENT_MILLIS);
    }

    private String resetRemark(MinecraftSeasonOperation operation, MinecraftSeasonAdjustment adjustment) {
        return "周目余额重置：" + operation.toSeasonName()
                + "，本周目可使用 " + adjustment.seasonTotalAmount()
                + "，下周目可使用 " + adjustment.nextInheritedAmount();
    }

    private MinecraftServer openSeasonOnServer(MinecraftServer server, MinecraftSeasonOperation operation, MinecraftSeasonOpenCmd cmd) {
        long startedAt = cmd.startedAt() == null ? System.currentTimeMillis() : cmd.startedAt();
        List<MinecraftServerSeason> nextSeasons = new ArrayList<>();
        for (MinecraftServerSeason season : server.seasons()) {
            if (season.id().equals(operation.fromSeasonId())) {
                nextSeasons.add(new MinecraftServerSeason(season.id(), season.name(), season.description(), season.startedAt(), startedAt, false, season.sort(), season.modpackBinding()));
            } else {
                nextSeasons.add(season.withCurrent(false));
            }
        }
        int nextSort = nextSeasons.stream().mapToInt(MinecraftServerSeason::sort).max().orElse(0) + 10;
        nextSeasons.add(new MinecraftServerSeason(operation.toSeasonId(), operation.toSeasonName(), cmd.description(), startedAt, null, true, nextSort, ModpackBinding.none()));
        return server.update(null, null, null, null, null, nextSeasons);
    }

    private MinecraftServer rollbackSeasonOnServer(MinecraftServer server, MinecraftSeasonOperation operation) {
        List<MinecraftServerSeason> nextSeasons = server.seasons().stream()
                .filter(season -> !season.id().equals(operation.toSeasonId()))
                .map(season -> season.id().equals(operation.fromSeasonId())
                        ? new MinecraftServerSeason(season.id(), season.name(), season.description(), season.startedAt(), null, true, season.sort(), season.modpackBinding())
                        : season.withCurrent(false))
                .toList();
        return server.update(null, null, null, null, null, nextSeasons);
    }

    private Map<String, BigDecimal> balances(PluginWalletService wallet, Map<String, PluginWalletAsset> assets) {
        Map<String, BigDecimal> results = new HashMap<>();
        for (String assetCode : assets.keySet()) {
            int page = 1;
            while (true) {
                List<PluginWalletBalance> balances = wallet.listBalances(assetCode, page, SCAN_PAGE_SIZE);
                for (PluginWalletBalance balance : balances) {
                    results.put(key(balance.userId(), balance.assetCode()), balance.balance());
                }
                if (balances.size() < SCAN_PAGE_SIZE) {
                    break;
                }
                page++;
            }
        }
        return results;
    }

    private Map<String, BigDecimal> incomeTotals(PluginWalletService wallet, Long startAt, Long endAt) {
        return walletTransactions(wallet, startAt, endAt).stream()
                .filter(this::isSeasonIncomeSource)
                .collect(Collectors.groupingBy(transaction -> key(transaction.toUserId(), transaction.assetCode()),
                        Collectors.mapping(PluginWalletTransaction::amount, Collectors.reducing(BigDecimal.ZERO, BigDecimal::add))));
    }

    private List<PluginWalletTransaction> walletTransactions(PluginWalletService wallet, Long startAt, Long endAt) {
        List<PluginWalletTransaction> results = new ArrayList<>();
        int page = 1;
        while (true) {
            List<PluginWalletTransaction> items = wallet.transactions(new PluginWalletTransactionQuery(null, "CREDIT", null, null, startAt, endAt, page, SCAN_PAGE_SIZE));
            results.addAll(items);
            if (items.size() < SCAN_PAGE_SIZE) {
                return results;
            }
            page++;
        }
    }

    private Map<String, BigDecimal> realIncomeTotals(PluginWalletService wallet) {
        return incomeTotals(wallet, null, null);
    }

    private Map<String, BigDecimal> inheritedTotals(String serverId, String seasonId) {
        if (seasonId == null) {
            return Map.of();
        }
        return allOperations(serverId).stream()
                .filter(operation -> operation.status() == MinecraftSeasonOperationStatus.APPLIED)
                .filter(operation -> seasonId.equals(operation.toSeasonId()))
                .findFirst()
                .map(operation -> operation.adjustments().stream()
                        .collect(Collectors.toMap(
                                adjustment -> key(adjustment.userId(), adjustment.assetCode()),
                                MinecraftSeasonAdjustment::nextInheritedAmount,
                                (first, second) -> second
                        )))
                .orElse(Map.of());
    }

    private MinecraftInheritanceRule matchRule(List<MinecraftInheritanceRule> rules, String assetCode, BigDecimal amount) {
        return rules.stream()
                .filter(rule -> rule.matchesAsset(assetCode))
                .sorted(Comparator.comparingInt(rule -> specificity(rule.assetPattern())))
                .filter(rule -> rule.matchesAmount(amount))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("币种 " + assetCode + " 的累计金额 " + amount + " 未匹配继承规则，请补充覆盖该金额区间的通配规则"));
    }

    private int specificity(String pattern) {
        return pattern == null || pattern.contains("*") ? 10 : 0;
    }

    private boolean isSeasonIncomeSource(PluginWalletTransaction transaction) {
        return !"MINECRAFT_SEASON".equals(transaction.source());
    }

    private List<MinecraftInheritanceRule> normalizeRules(List<MinecraftSeasonOpenCmd.Rule> rules) {
        List<MinecraftInheritanceRule> result = (rules == null || rules.isEmpty() ? defaultRules() : rules.stream()
                .map(rule -> new MinecraftInheritanceRule(rule.assetPattern(), rule.minAmount(), rule.maxAmount(), rule.inheritRate()))
                .toList());
        validateRuleCoverage(result);
        return result;
    }

    private List<MinecraftInheritanceRule> defaultRules() {
        return List.of(
                new MinecraftInheritanceRule("*", BigDecimal.ZERO, new BigDecimal("100"), new BigDecimal("0.5")),
                new MinecraftInheritanceRule("*", new BigDecimal("100"), new BigDecimal("200"), new BigDecimal("0.6")),
                new MinecraftInheritanceRule("*", new BigDecimal("200"), new BigDecimal("350"), new BigDecimal("0.75")),
                new MinecraftInheritanceRule("*", new BigDecimal("350"), new BigDecimal("500"), new BigDecimal("0.85")),
                new MinecraftInheritanceRule("*", new BigDecimal("500"), null, new BigDecimal("0.9"))
        );
    }

    private void validateRuleCoverage(List<MinecraftInheritanceRule> rules) {
        Map<String, List<MinecraftInheritanceRule>> groups = rules.stream()
                .collect(Collectors.groupingBy(MinecraftInheritanceRule::assetPattern));
        for (Map.Entry<String, List<MinecraftInheritanceRule>> entry : groups.entrySet()) {
            List<MinecraftInheritanceRule> items = entry.getValue().stream()
                    .sorted(Comparator.comparing(MinecraftInheritanceRule::minAmount))
                    .toList();
            BigDecimal cursor = BigDecimal.ZERO;
            for (MinecraftInheritanceRule rule : items) {
                if (rule.minAmount().compareTo(cursor) != 0) {
                    throw new IllegalArgumentException("币种通配 " + entry.getKey() + " 的继承区间不连续，缺少从 " + cursor + " 开始的规则");
                }
                if (rule.maxAmount() == null) {
                    cursor = null;
                    break;
                }
                cursor = rule.maxAmount();
            }
            if (cursor != null) {
                throw new IllegalArgumentException("币种通配 " + entry.getKey() + " 缺少无上限继承规则");
            }
        }
        if (rules.stream().noneMatch(rule -> "*".equals(rule.assetPattern()))) {
            throw new IllegalArgumentException("至少需要一组 * 通配规则用于兜底处理所有币种");
        }
    }

    private List<MinecraftServerEndpoint> toEndpoints(List<MinecraftServerSaveCmd.Endpoint> endpoints) {
        if (endpoints == null) {
            return List.of();
        }
        return endpoints.stream()
                .map(item -> new MinecraftServerEndpoint(item.id(), item.name(), item.host(), item.port() == null ? 0 : item.port(),
                        MinecraftEdition.of(item.edition()), Boolean.TRUE.equals(item.primaryLine()),
                        item.enabled() == null || item.enabled(), item.sort() == null ? 0 : item.sort()))
                .toList();
    }

    private List<MinecraftServerSeason> toSeasons(List<MinecraftServerSaveCmd.Season> seasons) {
        if (seasons == null) {
            return List.of();
        }
        return seasons.stream()
                .map(item -> new MinecraftServerSeason(item.id(), item.name(), item.description(), item.startedAt(), item.endedAt(),
                        Boolean.TRUE.equals(item.current()), item.sort() == null ? 0 : item.sort(),
                        toModpackBinding(item.modpackBinding())))
                .toList();
    }

    /**
     * 把命令层 DTO 形式的绑定转成 Domain 值对象。null/缺省 type 视作 NONE；
     * 字段与 type 不匹配时静默降级为 NONE（不让校验链路过早抛错，影响其它合法字段保存）。
     */
    private ModpackBinding toModpackBinding(MinecraftServerSaveCmd.ModpackBinding binding) {
        if (binding == null) return ModpackBinding.none();
        String type = binding.type();
        if (type == null || type.isBlank() || "NONE".equalsIgnoreCase(type)) {
            return ModpackBinding.none();
        }
        return switch (type.toUpperCase(java.util.Locale.ROOT)) {
            case "VANILLA" -> {
                if (binding.gameVersion() == null || binding.loader() == null) {
                    yield ModpackBinding.none();
                }
                yield ModpackBinding.vanilla(binding.gameVersion().trim(), binding.loader().trim());
            }
            case "MRPACK" -> {
                if (binding.packId() == null || binding.packId().isBlank()) {
                    yield ModpackBinding.none();
                }
                yield ModpackBinding.mrpack(binding.packId().trim(), binding.versionId());
            }
            default -> ModpackBinding.none();
        };
    }

    private MinecraftServer requireServer(String serverId) {
        return repository.findById(requireText(serverId, "服务器 ID 不能为空"))
                .orElseThrow(() -> new IllegalArgumentException("服务器不存在：" + serverId));
    }

    private List<MinecraftServer> allServers(boolean includeDisabled) {
        List<MinecraftServer> result = new ArrayList<>();
        int page = 1;
        while (true) {
            List<MinecraftServer> batch = repository.list(page, SCAN_PAGE_SIZE, includeDisabled);
            result.addAll(batch);
            if (batch.size() < SCAN_PAGE_SIZE) {
                return result;
            }
            page++;
        }
    }

    private List<MinecraftSeasonOperation> allOperations(String serverId) {
        List<MinecraftSeasonOperation> result = new ArrayList<>();
        int page = 1;
        while (true) {
            List<MinecraftSeasonOperation> batch = repository.listOperations(serverId, page, SCAN_PAGE_SIZE);
            result.addAll(batch);
            if (batch.size() < SCAN_PAGE_SIZE) {
                return result;
            }
            page++;
        }
    }

    private PluginWalletService wallet() {
        requireWalletEnabled();
        return pluginContext.service("yudream-wallet", PluginWalletService.class)
                .orElseThrow(() -> new IllegalStateException("钱包插件未启用，无法执行周目货币继承"));
    }

    private PluginWalletService walletOrNull() {
        if (!walletEnabled()) {
            return null;
        }
        return pluginContext.service("yudream-wallet", PluginWalletService.class).orElse(null);
    }

    private void requireWalletEnabled() {
        if (!walletEnabled()) {
            throw new IllegalStateException("钱包插件未启用，无法执行周目货币操作");
        }
    }

    private BigDecimal scale(BigDecimal value, int scale) {
        return value.setScale(Math.max(scale, 0), RoundingMode.DOWN);
    }

    private String direction(BigDecimal delta) {
        if (delta.signum() > 0) {
            return "CREDIT";
        }
        if (delta.signum() < 0) {
            return "DEBIT";
        }
        return "NONE";
    }

    private int safePage(int page) {
        return Math.max(page, 1);
    }

    private int safeSize(int size) {
        return Math.max(Math.min(size, SCAN_PAGE_SIZE), 1);
    }

    private String key(String userId, String assetCode) {
        return requireText(userId, "用户不能为空") + ":" + requireText(assetCode, "币种不能为空");
    }

    private String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private void deleteStoredMapFile(MinecraftServer server) {
        if (server.map() != null && !server.map().objectKey().isBlank()) {
            files.delete(server.map().objectKey());
        }
    }
}
