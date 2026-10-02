package me.minestorm.altingsystem.managers;

import me.minestorm.altingsystem.MineStormAltingSystemPlugin;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public class SensitivityManager {

    private final MineStormAltingSystemPlugin plugin;
    private final Map<UUID, SensitivityData> sensitivityCache;
    /** Guards every use of the JDBC connection (flush task, migration, shutdown). */
    private final Object dbLock = new Object();
    private Connection connection;
    private BukkitTask saveTask;

    public SensitivityManager(MineStormAltingSystemPlugin plugin) {
        this.plugin = plugin;
        this.sensitivityCache = new ConcurrentHashMap<>();
        connect();
        createTable();
        loadCacheFromDatabase();
        startPeriodicSave();
    }

    private void connect() {
        try {
            File dataFolder = plugin.getDataFolder();
            if (!dataFolder.exists() && !dataFolder.mkdirs()) {
                plugin.getLogger().warning("Could not create plugin data folder.");
            }
            File databaseFile = new File(dataFolder, "database.db");

            try {
                Class.forName("org.sqlite.JDBC");
            } catch (ClassNotFoundException ignored) {
                // Fall back to DriverManager auto-discovery
            }

            String url = "jdbc:sqlite:" + databaseFile.getAbsolutePath();
            connection = DriverManager.getConnection(url);

            try (Statement stmt = connection.createStatement()) {
                stmt.execute("PRAGMA journal_mode=WAL;");
                stmt.execute("PRAGMA synchronous=NORMAL;");
            }
        } catch (SQLException e) {
            connection = null;
            plugin.getLogger().log(Level.SEVERE, "Could not connect to SQLite database!", e);
        }
    }

    private void createTable() {
        if (connection == null) return;
        String sql = "CREATE TABLE IF NOT EXISTS sensitivities (" +
                "uuid VARCHAR(36) PRIMARY KEY, " +
                "name VARCHAR(16) NOT NULL, " +
                "hSens VARCHAR(32) NOT NULL, " +
                "vSens VARCHAR(32) NOT NULL" +
                ");";
        synchronized (dbLock) {
            try (Statement stmt = connection.createStatement()) {
                stmt.execute(sql);
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_sens ON sensitivities(hSens, vSens);");
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Could not create sensitivities table!", e);
            }
        }
    }

    /** Imports a legacy sensitivities.yml (if present) into SQLite. */
    public void migrateFromYaml() {
        File yamlFile = new File(plugin.getDataFolder(), "sensitivities.yml");
        if (!yamlFile.exists()) {
            return;
        }

        plugin.getLogger().info("Found sensitivities.yml! Migrating data to SQLite...");
        YamlConfiguration config = YamlConfiguration.loadConfiguration(yamlFile);

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            int count = 0;
            try {
                for (String uuidStr : config.getKeys(false)) {
                    ConfigurationSection section = config.getConfigurationSection(uuidStr);
                    if (section == null) continue;

                    String name = section.getString("name", "Unknown");
                    String hSens = section.getString("hSens", "N/A");
                    String vSens = section.getString("vSens", "N/A");

                    UUID uuid;
                    try {
                        uuid = UUID.fromString(uuidStr);
                    } catch (IllegalArgumentException invalid) {
                        continue;
                    }

                    saveSensitivityToDatabase(uuid, name, hSens, vSens);
                    if (isValid(hSens) && isValid(vSens)) {
                        sensitivityCache.put(uuid, new SensitivityData(name, hSens, vSens));
                    }
                    count++;
                }

                plugin.getLogger().info("Successfully migrated " + count + " players to the SQLite database!");

                File migratedFile = new File(plugin.getDataFolder(), "sensitivities.yml.old");
                if (!yamlFile.renameTo(migratedFile)) {
                    plugin.getLogger().warning("Could not rename sensitivities.yml after migration.");
                }
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to migrate sensitivities.yml to SQLite!", e);
            }
        });
    }

    /**
     * Updates the in-memory cache only. The periodic save task (or close())
     * persists it to disk in a single batched transaction.
     */
    public void saveSensitivity(UUID uuid, String name, String hSens, String vSens) {
        if (!isValid(hSens) || !isValid(vSens)) return;
        sensitivityCache.put(uuid, new SensitivityData(name, hSens, vSens));
    }

    private void saveSensitivityToDatabase(UUID uuid, String name, String hSens, String vSens) {
        if (!isValid(hSens) || !isValid(vSens)) return;

        String sql = "INSERT OR REPLACE INTO sensitivities (uuid, name, hSens, vSens) VALUES (?, ?, ?, ?)";
        synchronized (dbLock) {
            if (connection == null) return;
            try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
                pstmt.setString(1, uuid.toString());
                pstmt.setString(2, name);
                pstmt.setString(3, hSens);
                pstmt.setString(4, vSens);
                pstmt.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to save sensitivity for " + name, e);
            }
        }
    }

    /** Cache-only lookup of a stored sensitivity by player name. */
    public String[] getOfflineSensitivity(String name) {
        for (SensitivityData data : sensitivityCache.values()) {
            if (data.name.equalsIgnoreCase(name)) {
                return new String[]{data.hSens, data.vSens};
            }
        }
        return null;
    }

    /** Cache-only alt lookup: every other known account with an identical sensitivity pair. */
    public List<String> findAlts(String hSens, String vSens, String excludeName) {
        List<String> alts = new ArrayList<>();
        if (!isValid(hSens) || !isValid(vSens)) return alts;

        for (SensitivityData data : sensitivityCache.values()) {
            if (data.hSens.equals(hSens) && data.vSens.equals(vSens)
                    && !data.name.equalsIgnoreCase(excludeName)) {
                alts.add(data.name);
            }
        }
        return alts;
    }

    public Map<String, List<String>> getSensitivityGroups() {
        Map<String, List<String>> groups = new HashMap<>();

        for (SensitivityData data : sensitivityCache.values()) {
            if (isValid(data.hSens) && isValid(data.vSens)) {
                String sensKey = data.hSens + " | " + data.vSens;
                groups.computeIfAbsent(sensKey, k -> new ArrayList<>()).add(data.name);
            }
        }
        return groups;
    }

    public boolean isValid(String sens) {
        if (sens == null || "N/A".equals(sens)) return false;
        return !(sens.equals("0") || sens.equals("0.0") || sens.equals("0%") || sens.equals("0.0%"));
    }

    private void loadCacheFromDatabase() {
        if (connection == null) return;
        String sql = "SELECT uuid, name, hSens, vSens FROM sensitivities";
        synchronized (dbLock) {
            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery(sql)) {
                while (rs.next()) {
                    try {
                        UUID uuid = UUID.fromString(rs.getString("uuid"));
                        sensitivityCache.put(uuid, new SensitivityData(
                                rs.getString("name"), rs.getString("hSens"), rs.getString("vSens")));
                    } catch (IllegalArgumentException badRow) {
                        plugin.getLogger().warning("Skipping database row with an invalid UUID.");
                    }
                }
                plugin.getLogger().info("Loaded " + sensitivityCache.size() + " sensitivity records into cache.");
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to load sensitivity cache!", e);
            }
        }
    }

    private void startPeriodicSave() {
        // Every 30 minutes (36000 ticks), asynchronously
        saveTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::flushCacheToDatabase, 36000L, 36000L);
    }

    /** Writes the whole cache in one batched transaction. */
    private void flushCacheToDatabase() {
        if (sensitivityCache.isEmpty()) return;

        // Snapshot so we hold the DB lock for as little time as possible
        Map<UUID, SensitivityData> snapshot = new HashMap<>(sensitivityCache);
        String sql = "INSERT OR REPLACE INTO sensitivities (uuid, name, hSens, vSens) VALUES (?, ?, ?, ?)";

        synchronized (dbLock) {
            if (connection == null) return;

            boolean originalAutoCommit = true;
            try {
                originalAutoCommit = connection.getAutoCommit();
                connection.setAutoCommit(false);

                try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
                    for (Map.Entry<UUID, SensitivityData> entry : snapshot.entrySet()) {
                        SensitivityData data = entry.getValue();
                        pstmt.setString(1, entry.getKey().toString());
                        pstmt.setString(2, data.name);
                        pstmt.setString(3, data.hSens);
                        pstmt.setString(4, data.vSens);
                        pstmt.addBatch();
                    }
                    pstmt.executeBatch();
                }

                connection.commit();
                plugin.getLogger().info("Flushed " + snapshot.size() + " sensitivity records from cache to database.");
            } catch (SQLException e) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackEx) {
                    plugin.getLogger().log(Level.SEVERE, "Rollback failed!", rollbackEx);
                }
                plugin.getLogger().log(Level.SEVERE, "Failed to flush sensitivity cache!", e);
            } finally {
                try {
                    connection.setAutoCommit(originalAutoCommit);
                } catch (SQLException e) {
                    plugin.getLogger().log(Level.WARNING, "Could not restore auto-commit.", e);
                }
            }
        }
    }

    public void close() {
        if (saveTask != null) {
            saveTask.cancel();
        }

        flushCacheToDatabase();

        synchronized (dbLock) {
            try {
                if (connection != null && !connection.isClosed()) {
                    connection.close();
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Error while closing database connection.", e);
            }
            connection = null;
        }
    }

    private static final class SensitivityData {
        final String name;
        final String hSens;
        final String vSens;

        SensitivityData(String name, String hSens, String vSens) {
            this.name = name;
            this.hSens = hSens;
            this.vSens = vSens;
        }
    }
}
