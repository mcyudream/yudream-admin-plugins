package online.yudream.ysm.folia;

import net.momirealms.craftengine.bukkit.plugin.BukkitCraftEngine;
import net.momirealms.craftengine.core.pack.host.ExternalPackHost;
import net.momirealms.craftengine.core.pack.host.impl.SelfHostHttpServer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import java.nio.file.Path;

/** Separate class so standalone mode loads without CraftEngine. */
final class CraftEnginePackPublisher implements PackPublisher, Listener {
    private final ModelsPlugin plugin;
    private final ExternalPackHost host;
    private volatile boolean closed;

    CraftEnginePackPublisher(ModelsPlugin plugin) {
        this.plugin = plugin;
        this.host = SelfHostHttpServer.instance().openExternalHost();
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        if (!ready()) plugin.getLogger().info("等待 CraftEngine 异步初始化自托管；请确保 CE 已配置 self 类型资源包。");
    }

    @Override public boolean ready() { return !this.closed && SelfHostHttpServer.instance().isEnabled(); }

    @Override public synchronized Download publish(Path file, String sha1) throws Exception {
        if (this.closed) throw new IllegalStateException("CraftEngine 模型托管已关闭");
        var registration = this.host.register(file);
        return player -> {
            if (this.closed) throw new IllegalStateException("CraftEngine 模型托管已关闭");
            var user = BukkitCraftEngine.instance().networkManager().getUser(player);
            if (user == null) throw new IllegalStateException("CraftEngine 尚未建立玩家连接");
            var data = registration.download(user);
            if (data == null) throw new IllegalStateException("CraftEngine 自托管暂不可用");
            if (!sha1.equals(data.sha1())) throw new IllegalStateException("CraftEngine 资源包校验值不一致");
            return data.url();
        };
    }

    @EventHandler public void providerDisabled(PluginDisableEvent event) {
        if (!event.getPlugin().getName().equals("CraftEngine")) return;
        this.plugin.getLogger().warning("CraftEngine 已停用，模型桥接随之关闭；请在 CE 恢复后重启服务器。");
        this.plugin.getServer().getPluginManager().disablePlugin(this.plugin);
    }

    @Override public synchronized void close() {
        if (this.closed) return;
        this.closed = true;
        HandlerList.unregisterAll(this); this.host.close();
    }
}
