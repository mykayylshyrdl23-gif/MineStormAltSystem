package me.minestorm.altingsystem;

import me.minestorm.altingsystem.commands.AltCommand;
import me.minestorm.altingsystem.commands.AltReportCommand;
import me.minestorm.altingsystem.commands.AltToggleCommand;
import me.minestorm.altingsystem.listeners.SensitivityListener;
import me.minestorm.altingsystem.managers.SensitivityManager;
import me.minestorm.altingsystem.util.ChatTheme;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

public class MineStormAltingSystemPlugin extends JavaPlugin {

    public static final String GRIM_PLUGIN_NAME = "GrimAC";
    public static final String ALERT_PERMISSION = "minestorm.alerts";

    private static final String GRIM_API_CLASS = "ac.grim.grimac.api.GrimAbstractAPI";

    private SensitivityManager sensitivityManager;
    private final Set<UUID> disabledAlerts = ConcurrentHashMap.newKeySet();

    // GrimAC API handles (resolved reflectively so the plugin has no compile-time Grim dependency)
    private volatile boolean trackingEnabled = false;
    private Object grimApi;
    private Method getGrimUserMethod;
    private Method getReplacementsMethod; // optional - a direct-getter fallback exists

    @Override
    public void onEnable() {
        sensitivityManager = new SensitivityManager(this);
        sensitivityManager.migrateFromYaml();

        registerCommands();
        getServer().getPluginManager().registerEvents(new SensitivityListener(this, sensitivityManager), this);

        boolean ok = initGrim();
        trackingEnabled = ok;
        if (ok) {
            getLogger().info("GrimAC API detected. Sensitivity tracking is active.");
        } else {
            handleGrimFailure();
            // GrimAC may finish registering its API slightly later on some setups: re-check once.
            Bukkit.getScheduler().runTaskLater(this, this::recheckGrim, 200L);
        }

        getLogger().info("MineStormAltingSystem has been enabled.");
    }

    @Override
    public void onDisable() {
        trackingEnabled = false;
        if (sensitivityManager != null) {
            sensitivityManager.close();
        }
        getLogger().info("MineStormAltingSystem has been disabled.");
    }

    private void registerCommands() {
        PluginCommand alt = getCommand("alt");
        if (alt != null) {
            AltCommand executor = new AltCommand(this, sensitivityManager);
            alt.setExecutor(executor);
            alt.setTabCompleter(executor);
        }

        PluginCommand altReport = getCommand("altreport");
        if (altReport != null) {
            altReport.setExecutor(new AltReportCommand(sensitivityManager));
        }

        PluginCommand altToggle = getCommand("alttoggle");
        if (altToggle != null) {
            altToggle.setExecutor(new AltToggleCommand(this));
        }
    }

    // ------------------------------------------------------------------
    // GrimAC detection
    // ------------------------------------------------------------------

    /**
     * Attempts to hook into the GrimAC API.
     *
     * @return true if GrimAC is enabled and its API service is reachable
     */
    private boolean initGrim() {
        grimApi = null;
        getGrimUserMethod = null;
        getReplacementsMethod = null;

        try {
            Plugin grim = getServer().getPluginManager().getPlugin(GRIM_PLUGIN_NAME);
            if (grim == null || !grim.isEnabled()) {
                return false;
            }

            Class<?> apiClass = Class.forName(GRIM_API_CLASS, true, grim.getClass().getClassLoader());
            RegisteredServiceProvider<?> registration = Bukkit.getServicesManager().getRegistration(apiClass);
            if (registration == null) {
                return false;
            }

            Object api = registration.getProvider();
            if (api == null) {
                return false;
            }

            Method getUser = findMethod(apiClass, api, "getGrimUser", UUID.class);
            if (getUser == null) {
                return false;
            }

            grimApi = api;
            getGrimUserMethod = getUser;
            getReplacementsMethod = findMethod(apiClass, api, "getVariableReplacements");
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            getLogger().warning("Could not reach the GrimAC API: " + e);
            return false;
        }
    }

    /** Looks the method up on the API interface first, then on the implementation class. */
    private Method findMethod(Class<?> apiClass, Object api, String name, Class<?>... params) {
        try {
            return apiClass.getMethod(name, params);
        } catch (NoSuchMethodException ignored) {
            // fall through to implementation class
        }
        try {
            Method method = api.getClass().getMethod(name, params);
            method.setAccessible(true);
            return method;
        } catch (NoSuchMethodException | RuntimeException ignored) {
            return null;
        }
    }

    private void recheckGrim() {
        if (trackingEnabled) {
            return;
        }
        if (initGrim()) {
            trackingEnabled = true;
            getLogger().info("GrimAC API is now available. Sensitivity tracking has been ENABLED.");
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.isOp() || p.hasPermission(ALERT_PERMISSION)) {
                    p.sendMessage(ChatTheme.PREFIX + ChatColor.GREEN + "GrimAC detected - sensitivity tracking is now active.");
                }
            }
        } else {
            getLogger().warning("GrimAC is still unavailable. Sensitivity tracking remains disabled.");
        }
    }

    private void handleGrimFailure() {
        getLogger().severe("==================================================");
        getLogger().severe("GrimAC is missing, disabled, or its API cannot be reached.");
        getLogger().severe("Sensitivity tracking and alt alerts have been DISABLED.");
        getLogger().severe("Install/enable GrimAC and restart the server.");
        getLogger().severe("==================================================");
        for (Player p : Bukkit.getOnlinePlayers()) {
            sendGrimProblem(p);
        }
    }

    /** Sends the GrimAC problem notice to an operator / alert-permission holder. */
    public void sendGrimProblem(Player p) {
        if (!p.isOp() && !p.hasPermission(ALERT_PERMISSION)) {
            return;
        }
        p.sendMessage(ChatTheme.divider());
        p.sendMessage(ChatTheme.PREFIX + ChatColor.RED + ChatColor.BOLD + "GrimAC NOT DETECTED");
        p.sendMessage("  " + ChatColor.GRAY + "The GrimAC API is unavailable, so "
                + ChatColor.AQUA + "sensitivity tracking is disabled" + ChatColor.GRAY + ".");
        p.sendMessage("  " + ChatColor.GRAY + "Cached data can still be viewed with "
                + ChatColor.AQUA + "/alt" + ChatColor.GRAY + " and " + ChatColor.AQUA + "/altreport"
                + ChatColor.GRAY + ".");
        p.sendMessage(ChatTheme.divider());
    }

    public boolean isTrackingEnabled() {
        return trackingEnabled;
    }

    // ------------------------------------------------------------------
    // Alert toggles
    // ------------------------------------------------------------------

    public boolean hasAlertsDisabled(UUID uuid) {
        return disabledAlerts.contains(uuid);
    }

    public void setAlertsDisabled(UUID uuid, boolean disabled) {
        if (disabled) {
            disabledAlerts.add(uuid);
        } else {
            disabledAlerts.remove(uuid);
        }
    }

    // ------------------------------------------------------------------
    // Grim sensitivity engine
    // ------------------------------------------------------------------

    /**
     * Reads the player's horizontal/vertical sensitivity from GrimAC.
     * Call from the main thread.
     *
     * @return {hSens, vSens} (either may be "N/A"), or null if unavailable
     */
    @SuppressWarnings("unchecked")
    public String[] getGrimSensitivity(Player player) {
        if (!trackingEnabled || grimApi == null || getGrimUserMethod == null) {
            return null;
        }

        try {
            Object grimUser = getGrimUserMethod.invoke(grimApi, player.getUniqueId());
            if (grimUser == null) {
                return null;
            }

            String hSens = null;
            String vSens = null;

            // Primary source: Grim's placeholder replacements (same values as /grim profile)
            if (getReplacementsMethod != null) {
                Object replacements = getReplacementsMethod.invoke(grimApi);
                if (replacements instanceof Map) {
                    for (Map.Entry<?, ?> entry : ((Map<?, ?>) replacements).entrySet()) {
                        if (!(entry.getKey() instanceof String) || !(entry.getValue() instanceof Function)) {
                            continue;
                        }
                        String key = (String) entry.getKey();
                        Function<Object, Object> func = (Function<Object, Object>) entry.getValue();
                        if (key.contains("h_sensitivity")) {
                            hSens = String.valueOf(func.apply(grimUser));
                        } else if (key.contains("v_sensitivity")) {
                            vSens = String.valueOf(func.apply(grimUser));
                        }
                    }
                }
            }

            // Fallback: direct getters on the GrimUser (newer API versions)
            if (hSens == null) {
                hSens = readDirectSensitivity(grimUser, "getHorizontalSensitivity");
            }
            if (vSens == null) {
                vSens = readDirectSensitivity(grimUser, "getVerticalSensitivity");
            }

            return new String[]{hSens != null ? hSens : "N/A", vSens != null ? vSens : "N/A"};
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return null;
        }
    }

    /** Formats a raw 0..1 sensitivity as Grim does (0..200%). */
    private String readDirectSensitivity(Object grimUser, String methodName) {
        try {
            Method method = grimUser.getClass().getMethod(methodName);
            try {
                method.setAccessible(true);
            } catch (RuntimeException ignored) {
                // public method on a public type - invocation can still work
            }
            Object value = method.invoke(grimUser);
            if (value instanceof Number) {
                return Math.round(((Number) value).doubleValue() * 200.0D) + "%";
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // getter not present in this Grim version
        }
        return null;
    }
}
