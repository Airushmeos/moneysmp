package at.Airushmeos.moneysmp;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.io.File;
import java.io.IOException;
import java.text.DecimalFormat;
import java.util.*;

public class MoneySMP extends JavaPlugin {

    public static final String PREFIX = "§8[§a§lMoneySMP§8]§r";

    private EconomyManager economyManager;
    private TeamManager teamManager;
    private TransactionManager transactionManager;

    @Override
    public void onEnable() {
        // Init Managers
        this.economyManager = new EconomyManager(this);
        this.teamManager = new TeamManager(this);
        this.transactionManager = new TransactionManager();

        // Register Listeners
        getServer().getPluginManager().registerEvents(new PlayerListener(this), this);

        // Register Commands
        if (getCommand("pay") != null) {
            getCommand("pay").setExecutor(new PayCommand(this));
        }
        if (getCommand("moneysmp") != null) {
            MoneySMPCommand msmpCmd = new MoneySMPCommand(this);
            getCommand("moneysmp").setExecutor(msmpCmd);
            getCommand("moneysmp").setTabCompleter(msmpCmd);
        }

        // Start Actionbar Task (Jede Sekunde)
        new ActionBarTask(this).runTaskTimer(this, 20L, 20L);

        getLogger().info("MoneySMP erfolgreich aktiviert!");
    }

    @Override
    public void onDisable() {
        if (economyManager != null) {
            economyManager.saveData();
        }
        getLogger().info("MoneySMP deaktiviert.");
    }

    public EconomyManager getEconomyManager() { return economyManager; }
    public TeamManager getTeamManager() { return teamManager; }
    public TransactionManager getTransactionManager() { return transactionManager; }

    // ==========================================
    // ECONOMY MANAGER
    // ==========================================
    public static class EconomyManager {
        private final MoneySMP plugin;
        private final File dataFile;
        private FileConfiguration dataConfig;
        private final Map<UUID, Double> balances = new HashMap<>();
        private final Map<String, UUID> nameToUuid = new HashMap<>();
        private final Map<UUID, String> uuidToName = new HashMap<>();
        private final DecimalFormat formatter = new DecimalFormat("#,##0.##");

        public EconomyManager(MoneySMP plugin) {
            this.plugin = plugin;
            this.dataFile = new File(plugin.getDataFolder(), "data.yml");
            loadData();
        }

        public void loadData() {
            if (!dataFile.exists()) {
                plugin.saveResource("data.yml", false);
            }
            dataConfig = YamlConfiguration.loadConfiguration(dataFile);

            if (dataConfig.contains("players")) {
                for (String key : dataConfig.getConfigurationSection("players").getKeys(false)) {
                    UUID uuid = UUID.fromString(key);
                    double money = dataConfig.getDouble("players." + key + ".money", 100);
                    String name = dataConfig.getString("players." + key + ".name");

                    balances.put(uuid, money);
                    if (name != null) {
                        nameToUuid.put(name.toLowerCase(), uuid);
                        uuidToName.put(uuid, name);
                    }
                }
            }
        }

        public void saveData() {
            for (Map.Entry<UUID, Double> entry : balances.entrySet()) {
                String path = "players." + entry.getKey().toString();
                dataConfig.set(path + ".money", entry.getValue());
                dataConfig.set(path + ".name", uuidToName.get(entry.getKey()));
            }
            try {
                dataConfig.save(dataFile);
            } catch (IOException e) {
                plugin.getLogger().severe("Konnte data.yml nicht speichern!");
            }
        }

        public void registerPlayer(UUID uuid, String name) {
            nameToUuid.put(name.toLowerCase(), uuid);
            uuidToName.put(uuid, name);
            balances.putIfAbsent(uuid, 100.0);
            saveData();
        }

        public double getBalance(UUID uuid) {
            return balances.getOrDefault(uuid, 0.0);
        }

        public void setBalance(UUID uuid, double amount) {
            balances.put(uuid, Math.max(0, amount));
        }

        public void addBalance(UUID uuid, double amount) {
            setBalance(uuid, getBalance(uuid) + amount);
        }

        public void removeBalance(UUID uuid, double amount) {
            setBalance(uuid, getBalance(uuid) - amount);
        }

        public UUID getUuidByName(String name) {
            return nameToUuid.get(name.toLowerCase());
        }

        public String getNameByUuid(UUID uuid) {
            return uuidToName.get(uuid);
        }

        public Map<UUID, Double> getAllBalances() {
            return Collections.unmodifiableMap(balances);
        }

        public String formatMoney(double amount) {
            return formatter.format(amount);
        }
    }

    // ==========================================
    // TEAM MANAGER
    // ==========================================
    public static class TeamManager {
        private final MoneySMP plugin;
        private final Scoreboard scoreboard;
        private int teamCount = 4;
        private int teamMax = 0;

        private final List<TeamData> availableTeams = Arrays.asList(
                new TeamData("Red", NamedTextColor.RED, "§c"),
                new TeamData("Blue", NamedTextColor.BLUE, "§9"),
                new TeamData("Green", NamedTextColor.GREEN, "§a"),
                new TeamData("Yellow", NamedTextColor.YELLOW, "§e"),
                new TeamData("Purple", NamedTextColor.DARK_PURPLE, "§5"),
                new TeamData("Aqua", NamedTextColor.AQUA, "§b"),
                new TeamData("Orange", NamedTextColor.GOLD, "§6"),
                new TeamData("Pink", NamedTextColor.LIGHT_PURPLE, "§d")
        );

        private final Map<UUID, String> playerTeams = new HashMap<>();

        public TeamManager(MoneySMP plugin) {
            this.plugin = plugin;
            this.scoreboard = Bukkit.getScoreboardManager().getMainScoreboard();
            setupVanillaTeams();
        }

        private void setupVanillaTeams() {
            for (TeamData td : availableTeams) {
                String teamName = "msmp_" + td.name();
                Team team = scoreboard.getTeam(teamName);
                if (team == null) {
                    team = scoreboard.registerNewTeam(teamName);
                }
                team.color(td.color());
                team.setAllowFriendlyFire(true);
                team.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.ALWAYS);
            }
        }

        public void syncPlayer(Player player) {
            String tName = playerTeams.get(player.getUniqueId());
            if (tName != null) {
                Team team = scoreboard.getTeam("msmp_" + tName);
                if (team != null && !team.hasEntry(player.getName())) {
                    team.addEntry(player.getName());
                }
            }
        }

        public void setPlayerTeam(UUID uuid, String teamName) {
            Player player = Bukkit.getPlayer(uuid);

            if (player != null) {
                Team old = scoreboard.getEntryTeam(player.getName());
                if (old != null) old.removeEntry(player.getName());
            }

            if (teamName == null) {
                playerTeams.remove(uuid);
                return;
            }

            playerTeams.put(uuid, teamName);
            if (player != null) {
                Team newTeam = scoreboard.getTeam("msmp_" + teamName);
                if (newTeam != null) {
                    newTeam.addEntry(player.getName());
                }
            }
        }

        public String getPlayerTeam(UUID uuid) {
            return playerTeams.get(uuid);
        }

        public String getTeamColorPrefix(String teamName) {
            for (TeamData td : availableTeams) {
                if (td.name().equalsIgnoreCase(teamName)) {
                    return td.legacyCode();
                }
            }
            return "§7";
        }

        public void clearAllTeams() {
            playerTeams.clear();
            for (Player p : Bukkit.getOnlinePlayers()) {
                Team team = scoreboard.getEntryTeam(p.getName());
                if (team != null && team.getName().startsWith("msmp_")) {
                    team.removeEntry(p.getName());
                }
            }
        }

        public int getTeamCount() { return teamCount; }
        public void setTeamCount(int teamCount) { this.teamCount = teamCount; }

        public int getTeamMax() { return teamMax; }
        public void setTeamMax(int teamMax) { this.teamMax = teamMax; }

        public List<TeamData> getAvailableTeams() { return availableTeams; }

        public record TeamData(String name, NamedTextColor color, String legacyCode) {}
    }

    // ==========================================
    // TRANSACTION MANAGER
    // ==========================================
    public static class TransactionManager {
        private final List<Transaction> transactions = new ArrayList<>();

        public void log(String type, String from, String to, double amount, String note) {
            transactions.add(new Transaction(type, from, to, amount, note, System.currentTimeMillis()));
        }

        public List<Transaction> getRecentTransactions(long maxAgeSeconds) {
            List<Transaction> result = new ArrayList<>();
            long now = System.currentTimeMillis();
            for (int i = transactions.size() - 1; i >= 0; i--) {
                Transaction tx = transactions.get(i);
                long ageInSec = (now - tx.timestamp()) / 1000;
                if (ageInSec <= maxAgeSeconds) {
                    result.add(tx);
                }
            }
            return result;
        }

        public record Transaction(String type, String from, String to, double amount, String note, long timestamp) {}

        public static long parseTimeToSeconds(String input) {
            try {
                long totalSeconds = 0;
                String number = "";
                for (char c : input.toCharArray()) {
                    if (Character.isDigit(c)) {
                        number += c;
                    } else {
                        if (number.isEmpty()) continue;
                        long val = Long.parseLong(number);
                        switch (c) {
                            case 's' -> totalSeconds += val;
                            case 'm' -> totalSeconds += val * 60;
                            case 'h' -> totalSeconds += val * 3600;
                            case 'd' -> totalSeconds += val * 86400;
                        }
                        number = "";
                    }
                }
                if (!number.isEmpty()) {
                    totalSeconds += Long.parseLong(number);
                }
                return totalSeconds;
            } catch (Exception e) {
                return -1;
            }
        }

        public static String formatTimeAgo(long seconds) {
            if (seconds < 60) return seconds + "s";
            if (seconds < 3600) return (seconds / 60) + "m";
            if (seconds < 86400) return (seconds / 3600) + "h";
            return (seconds / 86400) + "d";
        }
    }

    // ==========================================
    // ACTIONBAR TASK
    // ==========================================
    public static class ActionBarTask extends BukkitRunnable {
        private final MoneySMP plugin;
        private static final Map<UUID, String> notificationMap = new HashMap<>();
        private static final Map<UUID, Integer> timerMap = new HashMap<>();

        public ActionBarTask(MoneySMP plugin) {
            this.plugin = plugin;
        }

        public static void setNotification(UUID uuid, String text, int seconds) {
            notificationMap.put(uuid, text);
            timerMap.put(uuid, seconds);
        }

        @Override
        public void run() {
            for (Player player : Bukkit.getOnlinePlayers()) {
                UUID uuid = player.getUniqueId();

                if (notificationMap.containsKey(uuid)) {
                    int timeLeft = timerMap.getOrDefault(uuid, 0) - 1;
                    if (timeLeft > 0) {
                        timerMap.put(uuid, timeLeft);
                        sendActionBar(player, notificationMap.get(uuid));
                        continue;
                    } else {
                        notificationMap.remove(uuid);
                        timerMap.remove(uuid);
                    }
                }

                double bal = plugin.getEconomyManager().getBalance(uuid);
                String team = plugin.getTeamManager().getPlayerTeam(uuid);
                String teamLabel = "§7No Team";
                if (team != null) {
                    teamLabel = plugin.getTeamManager().getTeamColorPrefix(team) + team;
                }

                String actionBarMsg = "§a§l$ §e" + plugin.getEconomyManager().formatMoney(bal) + "  §8|  §7Team: " + teamLabel;
                sendActionBar(player, actionBarMsg);
            }
        }

        private void sendActionBar(Player player, String legacyText) {
            Component comp = LegacyComponentSerializer.legacySection().deserialize(legacyText);
            player.sendActionBar(comp);
        }
    }

    // ==========================================
    // PLAYER LISTENER
    // ==========================================
    public static class PlayerListener implements Listener {
        private final MoneySMP plugin;

        public PlayerListener(MoneySMP plugin) {
            this.plugin = plugin;
        }

        @EventHandler
        public void onJoin(PlayerJoinEvent event) {
            Player player = event.getPlayer();
            plugin.getEconomyManager().registerPlayer(player.getUniqueId(), player.getName());
            plugin.getTeamManager().syncPlayer(player);
        }

        @EventHandler
        public void onDeath(PlayerDeathEvent event) {
            Player victim = event.getEntity();
            Player killer = victim.getKiller();

            if (killer == null) return;

            // Killer erhält $20
            plugin.getEconomyManager().addBalance(killer.getUniqueId(), 20);
            double killerBal = plugin.getEconomyManager().getBalance(killer.getUniqueId());
            String kMsg = "§a§l+ $20  §7Kill Reward!  §8|  §a$ §e" + plugin.getEconomyManager().formatMoney(killerBal);
            ActionBarTask.setNotification(killer.getUniqueId(), kMsg, 6);
            plugin.getTransactionManager().log("KILL", killer.getName(), victim.getName(), 20, "Kill reward");

            // Opfer verliert $20
            plugin.getEconomyManager().removeBalance(victim.getUniqueId(), 20);
            double victimBal = plugin.getEconomyManager().getBalance(victim.getUniqueId());
            String vMsg = "§c§l- $20  §7Killed by §f" + killer.getName() + "§7!  §8|  §a$ §e" + plugin.getEconomyManager().formatMoney(victimBal);
            ActionBarTask.setNotification(victim.getUniqueId(), vMsg, 6);
            plugin.getTransactionManager().log("DEATH", victim.getName(), killer.getName(), 20, "Death penalty");
        }
    }

    // ==========================================
    // COMMAND: /pay
    // ==========================================
    public static class PayCommand implements CommandExecutor {
        private final MoneySMP plugin;

        public PayCommand(MoneySMP plugin) {
            this.plugin = plugin;
        }

        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("§cNur Spieler können diesen Befehl ausführen.");
                return true;
            }

            if (args.length < 2) {
                player.sendMessage(MoneySMP.PREFIX + " §cVerwendung: /pay <spieler> <betrag>");
                return true;
            }

            String targetName = args[0];
            double amount;
            try {
                amount = Double.parseDouble(args[1]);
            } catch (NumberFormatException e) {
                player.sendMessage(MoneySMP.PREFIX + " §cUngültiger Betrag.");
                return true;
            }

            if (amount <= 0) {
                player.sendMessage(MoneySMP.PREFIX + " §cDer Betrag muss größer als 0 sein.");
                return true;
            }

            if (targetName.equalsIgnoreCase(player.getName())) {
                player.sendMessage(MoneySMP.PREFIX + " §cDu kannst dir selbst kein Geld senden.");
                return true;
            }

            double senderBal = plugin.getEconomyManager().getBalance(player.getUniqueId());
            if (senderBal < amount) {
                player.sendMessage(MoneySMP.PREFIX + " §cNicht genügend Geld! §7Du hast §e$" + plugin.getEconomyManager().formatMoney(senderBal) + "§7.");
                return true;
            }

            UUID targetUuid = plugin.getEconomyManager().getUuidByName(targetName);
            if (targetUuid == null) {
                player.sendMessage(MoneySMP.PREFIX + " §cSpieler §f" + targetName + " §cwurde nicht gefunden.");
                return true;
            }

            plugin.getEconomyManager().removeBalance(player.getUniqueId(), amount);
            plugin.getEconomyManager().addBalance(targetUuid, amount);

            double newSenderBal = plugin.getEconomyManager().getBalance(player.getUniqueId());
            double newTargetBal = plugin.getEconomyManager().getBalance(targetUuid);

            plugin.getTransactionManager().log("PAY", player.getName(), targetName, amount, "Player payment");

            player.sendMessage(MoneySMP.PREFIX + " §aDu hast §e$" + plugin.getEconomyManager().formatMoney(amount) + " §aan §f" + targetName + " §agewiesen. Kontostand: §e$" + plugin.getEconomyManager().formatMoney(newSenderBal));

            Player targetPlayer = Bukkit.getPlayer(targetUuid);
            if (targetPlayer != null) {
                String notif = "§a§l+ $" + plugin.getEconomyManager().formatMoney(amount) + "  §7von §f" + player.getName() + "  §8|  §a$ §e" + plugin.getEconomyManager().formatMoney(newTargetBal);
                ActionBarTask.setNotification(targetUuid, notif, 6);
                targetPlayer.sendMessage(MoneySMP.PREFIX + " §e" + player.getName() + " §ahat dir §e$" + plugin.getEconomyManager().formatMoney(amount) + " §agewiesen! Kontostand: §e$" + plugin.getEconomyManager().formatMoney(newTargetBal));
            }

            return true;
        }
    }

    // ==========================================
    // COMMAND: /moneysmp
    // ==========================================
    public static class MoneySMPCommand implements CommandExecutor, TabCompleter {
        private final MoneySMP plugin;

        public MoneySMPCommand(MoneySMP plugin) {
            this.plugin = plugin;
        }

        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            boolean isAdmin = sender.hasPermission("moneysmp.admin") || sender.isOp();

            if (args.length == 0) {
                sendHelp(sender, isAdmin);
                return true;
            }

            String sub = args[0].toLowerCase();

            switch (sub) {
                case "balance" -> {
                    if (args.length == 1) {
                        if (!(sender instanceof Player player)) {
                            sender.sendMessage("§cVerwendung: /moneysmp balance <spieler>");
                            return true;
                        }
                        double bal = plugin.getEconomyManager().getBalance(player.getUniqueId());
                        player.sendMessage(MoneySMP.PREFIX + " §7Dein Kontostand: §a§l$§e " + plugin.getEconomyManager().formatMoney(bal));
                    } else {
                        String targetName = args[1];
                        UUID targetUuid = plugin.getEconomyManager().getUuidByName(targetName);
                        if (targetUuid == null) {
                            sender.sendMessage(MoneySMP.PREFIX + " §cSpieler §f" + targetName + " §cwar noch nie online.");
                            return true;
                        }
                        double bal = plugin.getEconomyManager().getBalance(targetUuid);
                        sender.sendMessage(MoneySMP.PREFIX + " §e" + targetName + "§7's Kontostand: §a§l$§e " + plugin.getEconomyManager().formatMoney(bal));
                    }
                }
                case "myteam" -> {
                    if (!(sender instanceof Player player)) {
                        sender.sendMessage("§cNur Spieler können diesen Befehl ausführen.");
                        return true;
                    }
                    String team = plugin.getTeamManager().getPlayerTeam(player.getUniqueId());
                    if (team == null) {
                        player.sendMessage(MoneySMP.PREFIX + " §7Du bist in keinem Team.");
                    } else {
                        player.sendMessage(MoneySMP.PREFIX + " §7Dein Team: " + plugin.getTeamManager().getTeamColorPrefix(team) + "§l" + team);
                    }
                }
                case "teams" -> {
                    sender.sendMessage("");
                    sender.sendMessage(MoneySMP.PREFIX + " §7Aktive Teams  §8|  §7Anzahl: §f" + plugin.getTeamManager().getTeamCount());
                    sender.sendMessage("");

                    int tc = plugin.getTeamManager().getTeamCount();
                    List<TeamManager.TeamData> teams = plugin.getTeamManager().getAvailableTeams();

                    for (int i = 0; i < tc; i++) {
                        TeamManager.TeamData td = teams.get(i);
                        List<String> members = new ArrayList<>();
                        for (Player p : Bukkit.getOnlinePlayers()) {
                            String pTeam = plugin.getTeamManager().getPlayerTeam(p.getUniqueId());
                            if (td.name().equalsIgnoreCase(pTeam)) {
                                members.add(td.legacyCode() + p.getName());
                            }
                        }

                        if (!members.isEmpty()) {
                            sender.sendMessage("  " + td.legacyCode() + "§l" + td.name() + " §8(" + members.size() + ")  §8»  " + String.join("§7, ", members));
                        } else {
                            sender.sendMessage("  " + td.legacyCode() + "§l" + td.name() + " §8(0)  §8»  §7Leer");
                        }
                    }
                    sender.sendMessage("");
                }
                case "give" -> {
                    if (!checkAdmin(sender, isAdmin)) return true;
                    if (args.length < 3) {
                        sender.sendMessage(MoneySMP.PREFIX + " §cVerwendung: /moneysmp give <spieler> <betrag>");
                        return true;
                    }
                    String name = args[1];
                    double amt = parseDouble(args[2]);
                    if (amt <= 0) {
                        sender.sendMessage(MoneySMP.PREFIX + " §cBetrag muss über 0 sein.");
                        return true;
                    }
                    UUID uuid = plugin.getEconomyManager().getUuidByName(name);
                    if (uuid == null) {
                        sender.sendMessage(MoneySMP.PREFIX + " §cSpieler §f" + name + " §cnicht gefunden.");
                        return true;
                    }
                    plugin.getEconomyManager().addBalance(uuid, amt);
                    double nBal = plugin.getEconomyManager().getBalance(uuid);
                    plugin.getTransactionManager().log("GIVE", sender.getName(), name, amt, "Admin give");

                    sender.sendMessage(MoneySMP.PREFIX + " §a§e$" + plugin.getEconomyManager().formatMoney(amt) + " §aan §f" + name + " §agegeben. Neuer Kontostand: §e$" + plugin.getEconomyManager().formatMoney(nBal));
                    Player p = Bukkit.getPlayer(uuid);
                    if (p != null) p.sendMessage(MoneySMP.PREFIX + " §aDu hast §e$" + plugin.getEconomyManager().formatMoney(amt) + " §aerhalten! Neuer Kontostand: §e$" + plugin.getEconomyManager().formatMoney(nBal));
                }
                case "take" -> {
                    if (!checkAdmin(sender, isAdmin)) return true;
                    if (args.length < 3) {
                        sender.sendMessage(MoneySMP.PREFIX + " §cVerwendung: /moneysmp take <spieler> <betrag>");
                        return true;
                    }
                    String name = args[1];
                    double amt = parseDouble(args[2]);
                    if (amt <= 0) {
                        sender.sendMessage(MoneySMP.PREFIX + " §cBetrag muss über 0 sein.");
                        return true;
                    }
                    UUID uuid = plugin.getEconomyManager().getUuidByName(name);
                    if (uuid == null) {
                        sender.sendMessage(MoneySMP.PREFIX + " §cSpieler §f" + name + " §cnicht gefunden.");
                        return true;
                    }
                    plugin.getEconomyManager().removeBalance(uuid, amt);
                    double nBal = plugin.getEconomyManager().getBalance(uuid);
                    plugin.getTransactionManager().log("TAKE", sender.getName(), name, amt, "Admin take");

                    sender.sendMessage(MoneySMP.PREFIX + " §c§e$" + plugin.getEconomyManager().formatMoney(amt) + " §cvon §f" + name + " §cabgezogen. Neuer Kontostand: §e$" + plugin.getEconomyManager().formatMoney(nBal));
                    Player p = Bukkit.getPlayer(uuid);
                    if (p != null) p.sendMessage(MoneySMP.PREFIX + " §c§e$" + plugin.getEconomyManager().formatMoney(amt) + " §cwurden dir abgezogen. Neuer Kontostand: §e$" + plugin.getEconomyManager().formatMoney(nBal));
                }
                case "set" -> {
                    if (!checkAdmin(sender, isAdmin)) return true;
                    if (args.length < 3) {
                        sender.sendMessage(MoneySMP.PREFIX + " §cVerwendung: /moneysmp set <spieler> <betrag>");
                        return true;
                    }
                    String name = args[1];
                    double amt = parseDouble(args[2]);
                    if (amt < 0) {
                        sender.sendMessage(MoneySMP.PREFIX + " §cKontostand kann nicht negativ sein.");
                        return true;
                    }
                    UUID uuid = plugin.getEconomyManager().getUuidByName(name);
                    if (uuid == null) {
                        sender.sendMessage(MoneySMP.PREFIX + " §cSpieler §f" + name + " §cnicht gefunden.");
                        return true;
                    }
                    plugin.getEconomyManager().setBalance(uuid, amt);
                    plugin.getTransactionManager().log("SET", sender.getName(), name, amt, "Admin set");

                    sender.sendMessage(MoneySMP.PREFIX + " §aKontostand von §f" + name + " §aauf §e$" + plugin.getEconomyManager().formatMoney(amt) + " §agewechselt.");
                    Player p = Bukkit.getPlayer(uuid);
                    if (p != null) p.sendMessage(MoneySMP.PREFIX + " §7Dein Kontostand wurde auf §e$" + plugin.getEconomyManager().formatMoney(amt) + " §agesetzt.");
                }
                case "reset" -> {
                    if (!checkAdmin(sender, isAdmin)) return true;
                    int count = 0;
                    for (UUID uuid : plugin.getEconomyManager().getAllBalances().keySet()) {
                        plugin.getEconomyManager().setBalance(uuid, 100);
                        plugin.getTeamManager().setPlayerTeam(uuid, null);
                        count++;
                    }
                    plugin.getTeamManager().clearAllTeams();
                    plugin.getTransactionManager().log("RESET", sender.getName(), "ALLE SPIELER", 100, "Full Reset");

                    sender.sendMessage("");
                    sender.sendMessage(MoneySMP.PREFIX + " §a§lFull Reset! §7Reset von §e" + count + " §7Spielern auf §a§l$100 §7und alle Teams gelöscht.");
                    sender.sendMessage("");
                    Bukkit.broadcastMessage(MoneySMP.PREFIX + " §aAlle Kontostände wurden auf §l$100 §azurückgesetzt und die Teams geleert!");
                }
                case "fine" -> {
                    if (!checkAdmin(sender, isAdmin)) return true;
                    if (args.length < 4) {
                        sender.sendMessage(MoneySMP.PREFIX + " §cVerwendung: /moneysmp fine <spieler> <betrag> <grund>");
                        return true;
                    }
                    String name = args[1];
                    double amt = parseDouble(args[2]);
                    String reason = String.join(" ", Arrays.copyOfRange(args, 3, args.length));
                    if (amt <= 0) {
                        sender.sendMessage(MoneySMP.PREFIX + " §cStrafe muss über 0 sein.");
                        return true;
                    }
                    UUID uuid = plugin.getEconomyManager().getUuidByName(name);
                    if (uuid == null) {
                        sender.sendMessage(MoneySMP.PREFIX + " §cSpieler §f" + name + " §cnicht gefunden.");
                        return true;
                    }

                    plugin.getEconomyManager().removeBalance(uuid, amt);
                    double nBal = plugin.getEconomyManager().getBalance(uuid);
                    plugin.getTransactionManager().log("FINE", sender.getName(), name, amt, reason);

                    Bukkit.broadcastMessage("");
                    Bukkit.broadcastMessage(MoneySMP.PREFIX + " §c§l⚠ STRAFE AUSGESPROCHEN ⚠");
                    Bukkit.broadcastMessage("  §7Spieler: §f" + name);
                    Bukkit.broadcastMessage("  §7Betrag: §c-$" + plugin.getEconomyManager().formatMoney(amt));
                    Bukkit.broadcastMessage("  §7Grund: §f" + reason);
                    Bukkit.broadcastMessage("  §7Neuer Kontostand: §e$" + plugin.getEconomyManager().formatMoney(nBal));
                    Bukkit.broadcastMessage("");

                    Player target = Bukkit.getPlayer(uuid);
                    if (target != null) {
                        String notif = "§c§l- $" + plugin.getEconomyManager().formatMoney(amt) + "  §7Strafe: §f" + reason + "  §8|  §a$ §e" + plugin.getEconomyManager().formatMoney(nBal);
                        ActionBarTask.setNotification(uuid, notif, 8);
                        target.sendMessage(MoneySMP.PREFIX + " §c§lDu hast eine Strafe von §e$" + plugin.getEconomyManager().formatMoney(amt) + " §cerhalten! Grund: §f" + reason);
                    }
                }
                case "transaction" -> {
                    if (!checkAdmin(sender, isAdmin)) return true;
                    if (args.length < 2) {
                        sender.sendMessage(MoneySMP.PREFIX + " §cVerwendung: /moneysmp transaction <zeit>  §7z.B. 30m, 2h, 1d");
                        return true;
                    }
                    String rawTime = args[1];
                    long seconds = TransactionManager.parseTimeToSeconds(rawTime);
                    if (seconds <= 0) {
                        sender.sendMessage(MoneySMP.PREFIX + " §cUngültiges Zeitformat.");
                        return true;
                    }
                    List<TransactionManager.Transaction> txs = plugin.getTransactionManager().getRecentTransactions(seconds);

                    sender.sendMessage("");
                    sender.sendMessage("§8§m━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
                    sender.sendMessage("  " + MoneySMP.PREFIX + " §aTransaktions-Log  §8|  §7Letzte §f" + rawTime);
                    sender.sendMessage("§8§m━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");

                    for (TransactionManager.Transaction tx : txs) {
                        String col = switch (tx.type()) {
                            case "PAY" -> "§a";
                            case "KILL" -> "§6";
                            case "FINE" -> "§c";
                            case "GIVE" -> "§b";
                            case "TAKE" -> "§4";
                            case "SET" -> "§5";
                            default -> "§8";
                        };
                        String ago = TransactionManager.formatTimeAgo((System.currentTimeMillis() - tx.timestamp()) / 1000);
                        sender.sendMessage("  " + col + "§l[" + tx.type() + "]  §8Vor " + ago + "  §8|  §e$" + plugin.getEconomyManager().formatMoney(tx.amount()));
                        sender.sendMessage("    §7Von: §f" + tx.from() + "  §8➜  §7An: §f" + tx.to());
                        if (!tx.note().isEmpty()) {
                            sender.sendMessage("    §7Grund: §f" + tx.note());
                        }
                    }
                    sender.sendMessage("§8§m━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
                }
                case "teamcount" -> {
                    if (!checkAdmin(sender, isAdmin)) return true;
                    if (args.length < 2) {
                        sender.sendMessage(MoneySMP.PREFIX + " §cVerwendung: /moneysmp teamcount <1-8>");
                        return true;
                    }
                    int count = (int) parseDouble(args[1]);
                    if (count < 1 || count > 8) {
                        sender.sendMessage(MoneySMP.PREFIX + " §cAnzahl muss zwischen 1 und 8 liegen.");
                        return true;
                    }
                    plugin.getTeamManager().setTeamCount(count);
                    sender.sendMessage(MoneySMP.PREFIX + " §aAktive Teams gesetzt auf: §e" + count);
                }
                case "teammax" -> {
                    if (!checkAdmin(sender, isAdmin)) return true;
                    if (args.length < 2) {
                        sender.sendMessage(MoneySMP.PREFIX + " §cVerwendung: /moneysmp teammax <zahl>");
                        return true;
                    }
                    int max = (int) parseDouble(args[1]);
                    plugin.getTeamManager().setTeamMax(max);
                    sender.sendMessage(MoneySMP.PREFIX + " §aMaximal pro Team gesetzt auf: §e" + max);
                }
                case "team" -> {
                    if (!checkAdmin(sender, isAdmin)) return true;
                    if (args.length >= 4 && args[1].equalsIgnoreCase("set")) {
                        String target = args[2];
                        String tName = args[3];
                        UUID uuid = plugin.getEconomyManager().getUuidByName(target);
                        if (uuid == null) {
                            sender.sendMessage(MoneySMP.PREFIX + " §cSpieler nicht gefunden.");
                            return true;
                        }
                        plugin.getTeamManager().setPlayerTeam(uuid, tName);
                        sender.sendMessage(MoneySMP.PREFIX + " §aTeam von §f" + target + " §aauf §e" + tName + " §agesetzt.");
                    } else {
                        sender.sendMessage(MoneySMP.PREFIX + " §cVerwendung: /moneysmp team set <spieler> <team>");
                    }
                }
                case "randomteams" -> {
                    if (!checkAdmin(sender, isAdmin)) return true;
                    List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());
                    Collections.shuffle(players);

                    int tc = plugin.getTeamManager().getTeamCount();
                    List<TeamManager.TeamData> teams = plugin.getTeamManager().getAvailableTeams();

                    for (int i = 0; i < players.size(); i++) {
                        Player p = players.get(i);
                        TeamManager.TeamData assigned = teams.get(i % tc);
                        plugin.getTeamManager().setPlayerTeam(p.getUniqueId(), assigned.name());
                    }
                    sender.sendMessage(MoneySMP.PREFIX + " §a" + players.size() + " Spieler zufällig auf " + tc + " Teams verteilt!");
                }
                default -> sendHelp(sender, isAdmin);
            }

            return true;
        }

        private boolean checkAdmin(CommandSender sender, boolean isAdmin) {
            if (!isAdmin) {
                sender.sendMessage(MoneySMP.PREFIX + " §cKeine Berechtigung.");
                return false;
            }
            return true;
        }

        private double parseDouble(String str) {
            try {
                return Double.parseDouble(str);
            } catch (Exception e) {
                return 0;
            }
        }

        private void sendHelp(CommandSender sender, boolean isAdmin) {
            sender.sendMessage("");
            sender.sendMessage("§8§m━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            sender.sendMessage("  " + MoneySMP.PREFIX + " §aBefehlsübersicht");
            sender.sendMessage("§8§m━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            sender.sendMessage("  §f/moneysmp balance §8[spieler]");
            sender.sendMessage("  §f/moneysmp myteam");
            sender.sendMessage("  §f/moneysmp teams");
            sender.sendMessage("  §f/pay §e<spieler> <betrag>");
            if (isAdmin) {
                sender.sendMessage("");
                sender.sendMessage("  §7§lAdmin Befehle:");
                sender.sendMessage("  §f/moneysmp give §e<spieler> <betrag>");
                sender.sendMessage("  §f/moneysmp take §e<spieler> <betrag>");
                sender.sendMessage("  §f/moneysmp set §e<spieler> <betrag>");
                sender.sendMessage("  §f/moneysmp reset");
                sender.sendMessage("  §f/moneysmp fine §e<spieler> <betrag> <grund>");
                sender.sendMessage("  §f/moneysmp teammax §e<zahl>");
                sender.sendMessage("  §f/moneysmp teamcount §e<1-8>");
                sender.sendMessage("  §f/moneysmp randomteams");
                sender.sendMessage("  §f/moneysmp team set §e<spieler> <team>");
                sender.sendMessage("  §f/moneysmp transaction §e<zeit>");
            }
            sender.sendMessage("§8§m━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            sender.sendMessage("");
        }

        @Override
        public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
            if (args.length == 1) {
                return List.of("balance", "myteam", "teams", "give", "take", "set", "reset", "fine", "teamcount", "teammax", "randomteams", "team", "transaction");
            }
            return Collections.emptyList();
        }
    }
}