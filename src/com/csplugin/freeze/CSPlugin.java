package com.csplugin.freeze;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * CSFreeze 传送冻结插件 (Minecraft 1.20.1 Bukkit/Spigot/Paper)
 *
 * 功能:
 *  - /cs point <1-100>   站在方块上设置坐标点(管理员, 重复设置同编号会覆盖旧坐标)
 *  - /cs start           所有玩家随机传送到坐标点并冻结(1人1点, 不能动)
 *  - /cs jiechu          解除所有玩家的冻结, 恢复正常行动
 *  - 坐标保存在 config.yml 的 points 下, 可直接编辑文件修改
 */
public class CSPlugin extends JavaPlugin implements CommandExecutor, Listener, TabCompleter {

    /** 已冻结的玩家: UUID -> 原始行走速度/飞行速度 */
    private final Map<UUID, float[]> frozen = new HashMap<>();
    private final Random random = new Random();
    /** 解除冻结倒数任务ID, -1 表示没有进行中的倒数 */
    private int jiechuTaskId = -1;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);
        getCommand("cs").setExecutor(this);
        getCommand("cs").setTabCompleter(this);
        getLogger().info("CSFreeze 插件已启用");
    }

    @Override
    public void onDisable() {
        if (jiechuTaskId != -1) {
            getServer().getScheduler().cancelTask(jiechuTaskId);
            jiechuTaskId = -1;
        }
        unfreezeAll();
        getLogger().info("CSFreeze 插件已禁用, 已解除所有玩家冻结");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("§e用法: /cs point <1-100>  |  /cs start  |  /cs jiechu  |  /cs whitelist");
            return true;
        }

        String sub = args[0].toLowerCase();

        if (sub.equals("point")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("§c只有玩家可以站在方块上设置坐标点");
                return true;
            }
            Player player = (Player) sender;
            if (!hasAdmin(player)) return true;
            if (args.length < 2) {
                player.sendMessage("§e用法: /cs point <1-100>");
                return true;
            }
            int id;
            try {
                id = Integer.parseInt(args[1]);
            } catch (NumberFormatException e) {
                player.sendMessage("§c坐标编号必须是数字");
                return true;
            }
            if (id < 1 || id > 100) {
                player.sendMessage("§c坐标编号必须在 1-100 之间");
                return true;
            }
            setPoint(player, id);
            return true;
        }

        if (sub.equals("start")) {
            if (sender instanceof Player && !hasAdmin((Player) sender)) return true;
            doStart(sender);
            return true;
        }

        if (sub.equals("jiechu")) {
            if (sender instanceof Player && !hasAdmin((Player) sender)) return true;
            startJiechuCountdown();
            return true;
        }

        if (sub.equals("whitelist")) {
            if (sender instanceof Player && !hasAdmin((Player) sender)) return true;
            return handleWhitelist(sender, args);
        }

        sender.sendMessage("§c未知子命令: " + args[0] + "  (可用: point / start / jiechu / whitelist)");
        return true;
    }

    /** 权限检查: cs.admin, 默认 op 拥有 */
    private boolean hasAdmin(Player player) {
        if (player.hasPermission("cs.admin")) return true;
        player.sendMessage("§c你没有权限使用此命令");
        return false;
    }

    /** 设置/覆盖坐标点 (玩家站立的方块位置) */
    private void setPoint(Player player, int id) {
        Location loc = player.getLocation();
        double x = loc.getBlockX() + 0.5;
        double y = loc.getBlockY();
        double z = loc.getBlockZ() + 0.5;

        getConfig().set("points." + id + ".world", loc.getWorld().getName());
        getConfig().set("points." + id + ".x", x);
        getConfig().set("points." + id + ".y", y);
        getConfig().set("points." + id + ".z", z);
        getConfig().set("points." + id + ".yaw", (double) loc.getYaw());
        getConfig().set("points." + id + ".pitch", (double) loc.getPitch());
        saveConfig();

        player.sendMessage("§a已" + (hasPoint(id) ? "覆盖" : "设置") + "坐标点 §e#" + id
                + " §a(" + loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ()
                + ") 世界: " + loc.getWorld().getName());
    }

    private boolean hasPoint(int id) {
        return getConfig().get("points." + id) != null;
    }

    /** /cs start: 随机分配坐标并冻结 */
    private void doStart(CommandSender sender) {
        ConfigurationSection points = getConfig().getConfigurationSection("points");
        if (points == null || points.getKeys(false).isEmpty()) {
            sender.sendMessage("§c尚未设置任何坐标点, 请先使用 /cs point <1-100> 设置");
            return;
        }

        List<Player> allPlayers = new ArrayList<>(Bukkit.getOnlinePlayers());
        if (allPlayers.isEmpty()) {
            sender.sendMessage("§c当前没有在线玩家");
            return;
        }

        // 白名单玩家(主持人)不参与传送和冻结
        List<Player> players = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (Player p : allPlayers) {
            if (isWhitelisted(p)) {
                skipped.add(p.getName());
            } else {
                players.add(p);
            }
        }
        if (players.isEmpty()) {
            sender.sendMessage("§c所有在线玩家都在白名单中, 无玩家可传送");
            return;
        }
        if (!skipped.isEmpty()) {
            sender.sendMessage("§e白名单主持人跳过传送和冻结: " + String.join(", ", skipped));
        }

        // 读取所有可用的坐标点
        List<Location> locations = new ArrayList<>();
        for (String key : points.getKeys(false)) {
            Location loc = loadPoint(key);
            if (loc != null) locations.add(loc);
        }
        if (locations.isEmpty()) {
            sender.sendMessage("§c坐标点所在的世界未加载, 无法传送");
            return;
        }

        Collections.shuffle(players, random);
        Collections.shuffle(locations, random);

        int count = Math.min(players.size(), locations.size());
        if (count < players.size()) {
            sender.sendMessage("§e坐标点不足: 可用 " + count + " 个点, 在线 " + players.size()
                    + " 名玩家, 剩余玩家不传送");
        }

        // 取消可能正在进行的解除倒数, 先解除上次的冻结状态, 再重新开始
        if (jiechuTaskId != -1) {
            getServer().getScheduler().cancelTask(jiechuTaskId);
            jiechuTaskId = -1;
        }
        unfreezeAll();

        for (int i = 0; i < count; i++) {
            Player p = players.get(i);
            p.teleport(locations.get(i));
            freeze(p);
        }

        Bukkit.broadcastMessage("§6游戏开始! " + count + " 名玩家已随机分配到坐标点并被冻结");
        sender.sendMessage("§a已传送并冻结 " + count + " 名玩家, 每人一个坐标点");
    }

    /** 冻结玩家: 记录原速度, 速度归零 (不能移动) */
    private void freeze(Player p) {
        if (frozen.containsKey(p.getUniqueId())) return;
        frozen.put(p.getUniqueId(), new float[]{p.getWalkSpeed(), p.getFlySpeed()});
        p.setWalkSpeed(0f);
        p.setFlySpeed(0f);
        p.sendMessage("§c你已被冻结, 不能移动!");
    }

    /** 解除单个玩家冻结 */
    private void unfreezePlayer(Player p) {
        float[] speeds = frozen.remove(p.getUniqueId());
        if (speeds == null) return;
        p.setWalkSpeed(speeds[0]);
        p.setFlySpeed(speeds[1]);
        p.sendMessage("§a你已解除冻结, 可以正常行动");
    }

    /** 解除所有玩家冻结 */
    private void unfreezeAll() {
        for (UUID uuid : new ArrayList<>(frozen.keySet())) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) {
                unfreezePlayer(p);
            } else {
                frozen.remove(uuid);
            }
        }
    }

    /** /cs jiechu: 5 秒倒数后解除所有玩家冻结 */
    private void startJiechuCountdown() {
        if (jiechuTaskId != -1) {
            getServer().getScheduler().cancelTask(jiechuTaskId);
            jiechuTaskId = -1;
        }
        Bukkit.broadcastMessage("§e5 秒后解除所有玩家冻结!");
        final int[] count = {5};
        jiechuTaskId = getServer().getScheduler().scheduleSyncRepeatingTask(this, () -> {
            if (count[0] > 1) {
                count[0]--;
                Bukkit.broadcastMessage("§e" + count[0] + " 秒后解除冻结!");
            } else {
                getServer().getScheduler().cancelTask(jiechuTaskId);
                jiechuTaskId = -1;
                unfreezeAll();
                Bukkit.broadcastMessage("§a已解除冻结, 所有玩家可以正常行动");
            }
        }, 20L, 20L);
    }

    /** 从配置读取坐标点, 世界不存在则跳过 */
    private Location loadPoint(String key) {
        ConfigurationSection sec = getConfig().getConfigurationSection("points." + key);
        if (sec == null) return null;
        String worldName = sec.getString("world");
        World world = worldName == null ? null : Bukkit.getWorld(worldName);
        if (world == null) {
            getLogger().warning("坐标点 #" + key + " 的世界不存在或未加载: " + worldName);
            return null;
        }
        return new Location(world,
                sec.getDouble("x"), sec.getDouble("y"), sec.getDouble("z"),
                (float) sec.getDouble("yaw", 0), (float) sec.getDouble("pitch", 0));
    }

    /** 冻结状态下禁止改变所在方块 (允许转头) */
    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        if (!frozen.containsKey(e.getPlayer().getUniqueId())) return;
        Location from = e.getFrom();
        Location to = e.getTo();
        if (from.getBlockX() != to.getBlockX()
                || from.getBlockY() != to.getBlockY()
                || from.getBlockZ() != to.getBlockZ()) {
            e.setTo(from);
            e.setCancelled(true);
        }
    }

    /** 玩家退出时解除冻结, 防止内存泄漏 */
    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        float[] speeds = frozen.remove(p.getUniqueId());
        if (speeds != null) {
            p.setWalkSpeed(speeds[0]);
            p.setFlySpeed(speeds[1]);
        }
    }

    /** 白名单管理: /cs whitelist <add|remove|list> [名字] */
    private boolean handleWhitelist(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§e用法: /cs whitelist <add|remove|list> [玩家名]");
            return true;
        }
        String action = args[1].toLowerCase();

        if (action.equals("list")) {
            List<String> wl = getWhitelist();
            if (wl.isEmpty()) {
                sender.sendMessage("§e白名单为空");
            } else {
                sender.sendMessage("§a白名单 (" + wl.size() + "): §f" + String.join(", ", wl));
            }
            return true;
        }

        if (action.equals("add") || action.equals("remove")) {
            if (args.length < 3) {
                sender.sendMessage("§e用法: /cs whitelist " + action + " <玩家名>");
                return true;
            }
            String name = args[2];
            if (action.equals("add")) {
                addWhitelist(sender, name);
            } else {
                removeWhitelist(sender, name);
            }
            return true;
        }

        sender.sendMessage("§c未知白名单操作: " + args[1] + "  (可用: add / remove / list)");
        return true;
    }

    /** 加入白名单 (名称不区分大小写, 保存为小写) */
    private void addWhitelist(CommandSender sender, String name) {
        List<String> wl = new ArrayList<>(getConfig().getStringList("whitelist"));
        for (String s : wl) {
            if (s.equalsIgnoreCase(name)) {
                sender.sendMessage("§e" + name + " 已在白名单中");
                return;
            }
        }
        wl.add(name.toLowerCase());
        getConfig().set("whitelist", wl);
        saveConfig();
        sender.sendMessage("§a已将 " + name + " 加入白名单, 不会参与传送和冻结");

        // 若该玩家在线且正被冻结, 立即解除
        Player p = Bukkit.getPlayerExact(name);
        if (p != null && frozen.containsKey(p.getUniqueId())) {
            unfreezePlayer(p);
            p.sendMessage("§a你已被加入主持人白名单, 已解除冻结");
        }
    }

    /** 移出白名单 */
    private void removeWhitelist(CommandSender sender, String name) {
        List<String> wl = new ArrayList<>(getConfig().getStringList("whitelist"));
        boolean removed = wl.removeIf(s -> s.equalsIgnoreCase(name));
        if (!removed) {
            sender.sendMessage("§e" + name + " 不在白名单中");
            return;
        }
        getConfig().set("whitelist", wl);
        saveConfig();
        sender.sendMessage("§a已将 " + name + " 移出白名单");
    }

    /** 读取白名单列表 */
    private List<String> getWhitelist() {
        List<String> list = new ArrayList<>();
        for (String s : getConfig().getStringList("whitelist")) {
            if (!s.isEmpty()) list.add(s);
        }
        return list;
    }

    /** 判断玩家是否在白名单中 (不区分大小写) */
    private boolean isWhitelisted(Player p) {
        for (String s : getWhitelist()) {
            if (s.equalsIgnoreCase(p.getName())) return true;
        }
        return false;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filter(Arrays.asList("point", "start", "jiechu", "whitelist"), args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("point")) {
            ConfigurationSection points = getConfig().getConfigurationSection("points");
            if (points != null) {
                return filter(new ArrayList<>(points.getKeys(false)), args[1]);
            }
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("whitelist")) {
            return filter(Arrays.asList("add", "remove", "list"), args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("whitelist")) {
            if (args[1].equalsIgnoreCase("add")) {
                List<String> names = new ArrayList<>();
                for (Player p : Bukkit.getOnlinePlayers()) names.add(p.getName());
                return filter(names, args[2]);
            }
            if (args[1].equalsIgnoreCase("remove")) {
                return filter(getWhitelist(), args[2]);
            }
        }
        return Collections.emptyList();
    }

    private List<String> filter(List<String> list, String prefix) {
        List<String> result = new ArrayList<>();
        for (String s : list) {
            if (s.toLowerCase().startsWith(prefix.toLowerCase())) result.add(s);
        }
        return result;
    }
}
