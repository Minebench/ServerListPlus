/*
 *        _____                     __    _     _   _____ _
 *       |   __|___ ___ _ _ ___ ___|  |  |_|___| |_|  _  | |_ _ ___
 *       |__   | -_|  _| | | -_|  _|  |__| |_ -|  _|   __| | | |_ -|
 *       |_____|___|_|  \_/|___|_| |_____|_|___|_| |__|  |_|___|___|
 *
 *  ServerListPlus - http://git.io/slp
 *    > The most customizable server status ping plugin for Minecraft!
 *  Copyright (c) 2014, Minecrell <https://github.com/Minecrell>
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package net.minecrell.serverlistplus.bukkit;

import net.minecrell.serverlistplus.bukkit.handlers.BukkitEventHandler;
import net.minecrell.serverlistplus.bukkit.handlers.Handlers;
import net.minecrell.serverlistplus.bukkit.handlers.ProtocolLibHandler;
import net.minecrell.serverlistplus.bukkit.handlers.StatusHandler;
import net.minecrell.serverlistplus.core.ServerListPlusCore;
import net.minecrell.serverlistplus.core.ServerListPlusException;
import net.minecrell.serverlistplus.core.config.CoreConf;
import net.minecrell.serverlistplus.core.config.PluginConf;
import net.minecrell.serverlistplus.core.favicon.FaviconHelper;
import net.minecrell.serverlistplus.core.favicon.FaviconSource;
import net.minecrell.serverlistplus.core.player.PlayerIdentity;
import net.minecrell.serverlistplus.core.plugin.ServerListPlusPlugin;
import net.minecrell.serverlistplus.core.plugin.ServerType;
import net.minecrell.serverlistplus.core.status.StatusManager;
import net.minecrell.serverlistplus.core.status.StatusRequest;
import net.minecrell.serverlistplus.core.util.Helper;
import net.minecrell.serverlistplus.core.util.InstanceStorage;

import java.awt.image.BufferedImage;
import java.net.InetAddress;
import java.util.List;
import java.util.logging.Handler;

import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheBuilderSpec;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;

import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.util.CachedServerIcon;

import org.mcstats.MetricsLite;

import static net.minecrell.serverlistplus.core.logging.Logger.DEBUG;
import static net.minecrell.serverlistplus.core.logging.Logger.ERROR;
import static net.minecrell.serverlistplus.core.logging.Logger.INFO;

public class BukkitPlugin extends BukkitPluginBase implements ServerListPlusPlugin {
    private final boolean spigot;

    public BukkitPlugin() {
        // Check if server is running Spigot
        boolean spigot = false;
        try {
            Class.forName("org.spigotmc.SpigotConfig");
            spigot = true;
        } catch (ClassNotFoundException ignored) {}

        this.spigot = spigot;
    }

    private ServerListPlusCore core;

    private final CacheLoader<InetAddress, StatusRequest> requestLoader = new CacheLoader<InetAddress,
            StatusRequest>() {
        @Override
        public StatusRequest load(InetAddress client) throws Exception {
            return core.createRequest(client);
        }
    };
    private LoadingCache<InetAddress, StatusRequest> requestCache;
    private String requestCacheConf;

    private StatusHandler bukkit, protocol;

    private final CacheLoader<FaviconSource, Optional<CachedServerIcon>> faviconLoader =
            new CacheLoader<FaviconSource, Optional<CachedServerIcon>>() {
        @Override
        public Optional<CachedServerIcon> load(FaviconSource source) throws Exception {
            // Try loading the favicon
            BufferedImage image = FaviconHelper.loadSafely(core, source);
            if (image == null) return Optional.absent(); // Favicon loading failed
            else return Optional.of(getServer().loadServerIcon(image)); // Success!
        }
    };
    private LoadingCache<FaviconSource, Optional<CachedServerIcon>> faviconCache;

    private Listener loginListener;

    private MetricsLite metrics;

    @Override
    public void onEnable() {
        this.bukkit = new BukkitEventHandler(this);
        if (Handlers.checkProtocolLib()) {
            this.protocol = new ProtocolLibHandler(this);
        } else getLogger().log(ERROR, "ProtocolLib IS NOT INSTALLED! Most features will NOT work!");

        try { // Load the core first
            this.core = new ServerListPlusCore(this);
            getLogger().log(INFO, "Successfully loaded!");
        } catch (ServerListPlusException e) {
            getLogger().log(INFO, "Please fix the error before restarting the server!");
            disablePlugin(); return; // Disable bukkit to show error in /plugins
        } catch (Exception e) {
            getLogger().log(ERROR, "An internal error occurred while loading the core!", e);
            disablePlugin(); return; // Disable bukkit to show error in /plugins
        }

        // Register commands
        getCommand("serverlistplus").setExecutor(new ServerListPlusCommand());
        getLogger().info(getDisplayName() + " enabled.");
    }

    @Override
    public void onDisable() {
        getLogger().info(getDisplayName() + " disabled.");
        // BungeeCord closes the log handlers automatically, but Bukkit does not
        for (Handler handler : getLogger().getHandlers())
            handler.close();
    }

    // Commands
    public final class ServerListPlusCommand implements TabExecutor {
        private ServerListPlusCommand() {}

        @Override
        public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
            core.executeCommand(new BukkitCommandSender(sender), cmd.getName(), args); return true;
        }

        @Override
        public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
            return core.tabComplete(new BukkitCommandSender(sender), cmd.getName(), args);
        }
    }

    // Player tracking
    public final class LoginListener implements Listener {
        private LoginListener() {}

        @EventHandler
        public void onPlayerLogin(AsyncPlayerPreLoginEvent event) {
            core.addClient(event.getAddress(), new PlayerIdentity(event.getUniqueId(), event.getName()));
        }
    }

    public final class OfflineModeLoginListener implements Listener {
        private OfflineModeLoginListener() {}

        @EventHandler
        public void onPlayerLogin(PlayerLoginEvent event) {
            core.addClient(event.getAddress(), new PlayerIdentity(event.getPlayer().getUniqueId(),
                    event.getPlayer().getName()));
        }
    }

    @Override
    public ServerListPlusCore getCore() {
        return core;
    }

    @Override
    public ServerType getServerType() {
        return spigot ? ServerType.SPIGOT : ServerType.BUKKIT;
    }

    @Override
    public String getServerImplementation() {
        return getServer().getVersion();
    }

    public StatusRequest getRequest(InetAddress client) {
        return requestCache.getUnchecked(client);
    }

    public CachedServerIcon getFavicon(FaviconSource source) {
        Optional<CachedServerIcon> result = faviconCache.getUnchecked(source);
        return result.isPresent() ? result.get() : null;
    }

    @Override
    public String getRandomPlayer() {
        Player player = Helper.nextEntry(getServer().getOnlinePlayers());
        return player != null ? player.getName() : null;
    }

    @Override
    public Integer getOnlinePlayersAt(String location) {
        World world = getServer().getWorld(location);
        return world != null ? world.getPlayers().size() : null;
    }

    @Override
    public Cache<?, ?> getRequestCache() {
        return requestCache;
    }


    @Override
    public LoadingCache<FaviconSource, Optional<CachedServerIcon>> getFaviconCache() {
        return faviconCache;
    }

    @Override
    public String colorize(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    @Override
    public void initialize(ServerListPlusCore core) {

    }

    @Override
    public void reloadCaches(ServerListPlusCore core) {
        CoreConf conf = core.getConf(CoreConf.class);
        // Check if request cache configuration has been changed
        if (requestCacheConf == null || requestCache == null || !requestCacheConf.equals(conf.Caches.Request)) {
            if (requestCache != null) {
                // Delete the request cache
                getLogger().log(DEBUG, "Deleting old request cache due to configuration changes.");
                requestCache.invalidateAll();
                requestCache.cleanUp();
                this.requestCache = null;
            }

            getLogger().log(DEBUG, "Creating new request cache...");

            try {
                Preconditions.checkArgument(conf.Caches != null, "Cache configuration section not found");
                this.requestCacheConf = conf.Caches.Request;
                this.requestCache = CacheBuilder.from(requestCacheConf).build(requestLoader);
            } catch (IllegalArgumentException e) {
                getLogger().log(ERROR, "Unable to create request cache using configuration settings.", e);
                this.requestCacheConf = core.getDefaultConf(CoreConf.class).Caches.Request;
                this.requestCache = CacheBuilder.from(requestCacheConf).build(requestLoader);
            }

            getLogger().log(DEBUG, "Request cache created.");
        }
    }

    @Override
    public void reloadFaviconCache(CacheBuilderSpec spec) {
        if (spec != null) {
            this.faviconCache = CacheBuilder.from(spec).build(faviconLoader);
        } else {
            // Delete favicon cache
            faviconCache.invalidateAll();
            faviconCache.cleanUp();
            this.faviconCache = null;
        }
    }

    @Override
    public void configChanged(InstanceStorage<Object> confs) {
        // Player tracking
        if (confs.get(PluginConf.class).PlayerTracking) {
            if (loginListener == null) {
                registerListener(this.loginListener = spigot || getServer().getOnlineMode()
                        ? new LoginListener() : new OfflineModeLoginListener());
                getLogger().log(DEBUG, "Registered player tracking listener.");
            }
        } else if (loginListener != null) {
            unregisterListener(loginListener);
            this.loginListener = null;
            getLogger().log(DEBUG, "Unregistered player tracking listener.");
        }

        // Plugin statistics
        if (confs.get(PluginConf.class).Stats) {
            if (metrics == null)
                try {
                    this.metrics = new MetricsLite(this);
                    metrics.enable();
                    metrics.start();
                } catch (Throwable e) {
                    getLogger().log(DEBUG, "Failed to enable plugin statistics: " + Helper.causedError(e));
                }
        } else if (metrics != null)
            try {
                metrics.disable();
                this.metrics = null;
            } catch (Throwable e) {
                getLogger().log(DEBUG, "Failed to disable plugin statistics: " + Helper.causedError(e));
            }
    }

    @Override
    public void statusChanged(StatusManager status) {
        // Status packet listener
        if (status.hasChanges()) {
            if (bukkit.register())
                getLogger().log(DEBUG, "Registered ping event handler.");
            if (protocol == null)
                getLogger().log(ERROR, "ProtocolLib IS NOT INSTALLED! Most features will NOT work!");
            else if (protocol.register())
                getLogger().log(DEBUG, "Registered status protocol handler.");
        } else {
            if (bukkit.unregister())
                getLogger().log(DEBUG, "Unregistered ping event handler.");
            if (protocol != null && protocol.unregister())
                getLogger().log(DEBUG, "Unregistered status protocol handler.");
        }
    }
}
