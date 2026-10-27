package online.yudream.ysm.folia;

import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.event.server.ServiceRegisterEvent;
import org.bukkit.event.server.ServiceUnregisterEvent;
import org.bukkit.plugin.java.JavaPlugin;

/** Keeps optional API types behind a lazy adapter so the model runtime works without HandbookCore. */
final class ModelsHandbook implements Listener, AutoCloseable {
    static final NamespacedKey CATEGORY = new NamespacedKey("ysm", "guide");
    private static final String SERVICE = "com.example.handbookcore.api.HandbookService";
    private final JavaPlugin plugin;
    private volatile Bridge bridge;
    private boolean closed;

    ModelsHandbook(JavaPlugin plugin) { this.plugin = plugin; }

    void enable() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        reload();
    }

    synchronized void reload() {
        var core = Bukkit.getPluginManager().getPlugin("HandbookCore");
        if (closed || !plugin.isEnabled() || !plugin.getConfig().getBoolean("handbook.enabled", true)
                || core == null || !core.isEnabled()) {
            disconnect();
            return;
        }
        try {
            Bridge replacement = ApiBridge.connect(plugin);
            if (replacement == null) { disconnect(); return; }
            replacement.publish(); // Core atomically replaces only this owner's contribution.
            Bridge previous = bridge;
            bridge = replacement;
            if (previous != null && !previous.uses(replacement.provider())) release(previous);
            if (previous == null) plugin.getLogger().info("已注册 HandbookCore 模型指南：ysm:guide（8 篇）。");
        } catch (LinkageError | RuntimeException error) {
            plugin.getLogger().log(Level.WARNING, "模型指南注册失败，请检查 HandbookCore API 版本。", error);
        }
    }

    void open(Player player) {
        if (!player.hasPermission("ysm.use")) { player.sendMessage("你没有使用玩家模型的权限。"); return; }
        Bridge current = bridge;
        if (current != null) {
            if (!player.hasPermission("handbookcore.use")) { player.sendMessage("你没有阅读手册的权限（handbookcore.use）。"); return; }
            try { if (current.open(player)) return; }
            catch (LinkageError | RuntimeException error) {
                plugin.getLogger().log(Level.WARNING, "模型指南暂时无法打开。", error);
            }
        }
        player.sendMessage(plugin.getConfig().getBoolean("handbook.enabled", true)
                ? "模型指南暂不可用，请联系管理员启用 HandbookCore。可用 /model status 查看模型状态。"
                : "模型指南已由管理员关闭。可用 /model status 查看模型状态。");
    }

    @EventHandler public void registered(ServiceRegisterEvent event) {
        if (event.getProvider().getService().getName().equals(SERVICE)) reload();
    }
    @EventHandler public synchronized void unregistered(ServiceUnregisterEvent event) {
        if (event.getProvider().getService().getName().equals(SERVICE)
                && bridge != null && bridge.uses(event.getProvider().getProvider())) reload();
    }
    @EventHandler public void enabled(PluginEnableEvent event) {
        if (event.getPlugin().getName().equals("HandbookCore")) reload();
    }
    @EventHandler public synchronized void disabled(PluginDisableEvent event) {
        if (event.getPlugin().getName().equals("HandbookCore")) disconnect();
    }

    @Override public synchronized void close() {
        closed = true;
        HandlerList.unregisterAll(this);
        disconnect();
    }
    private void disconnect() {
        Bridge current = bridge;
        bridge = null;
        if (current != null) release(current);
    }
    private void release(Bridge current) {
        try { current.close(); }
        catch (LinkageError | RuntimeException error) {
            plugin.getLogger().log(Level.WARNING, "模型指南清理失败。", error);
        }
    }

    private interface Bridge {
        void publish();
        boolean open(Player player);
        Object provider();
        default boolean uses(Object provider) { return provider() == provider; }
        void close();
    }

    private static final class ApiBridge implements Bridge {
        private final JavaPlugin plugin;
        private final com.example.handbookcore.api.HandbookService service;
        private ApiBridge(JavaPlugin plugin, com.example.handbookcore.api.HandbookService service) {
            this.plugin = plugin; this.service = service;
        }
        static Bridge connect(JavaPlugin plugin) {
            var service = com.example.handbookcore.api.HandbookApi.serviceOrNull();
            return service == null ? null : new ApiBridge(plugin, service);
        }
        public void publish() {
            service.replace(plugin, ModelsGuide.build(plugin.getConfig().getString("website-url", ""),
                    plugin.getConfig().getLong("refresh-cooldown-seconds", 60)));
        }
        public boolean open(Player player) { return service.openCategory(player, CATEGORY); }
        public Object provider() { return service; }
        public void close() { service.unregisterAll(plugin); }
    }
}
