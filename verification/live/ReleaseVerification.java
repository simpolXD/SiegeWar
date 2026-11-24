import com.abyssaldominion.siegewar.*;
import com.abyssaldominion.siegewar.core.MatchEngine.State;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.flags.Flags;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Chest;
import org.bukkit.command.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.*;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Staging-only checks using real Paper entities/events and connected offline test clients. */
public final class ReleaseVerification extends JavaPlugin implements Listener {
    AbyssalDominionSiegeWarPlugin p;
    Player a, d;
    UUID generation;
    final List<String> passed = new ArrayList<>();
    final Map<UUID, ItemStack[]> before = new HashMap<>();
    double observed;
    boolean deny;
    public void onEnable() {
        p = (AbyssalDominionSiegeWarPlugin) Bukkit.getPluginManager().getPlugin("AbyssalDominionSiegeWar");
        Bukkit.getPluginManager().registerEvents(this, this);
        getCommand("releasecheck").setExecutor(this);
    }
    void check(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
        passed.add(name); getLogger().info("PASS " + name);
        try { Files.write(getDataFolder().toPath().resolve("results.txt"), passed); }
        catch (Exception ex) { throw new RuntimeException(ex); }
    }
    void later(long ticks, Runnable test) {
        Bukkit.getScheduler().runTaskLater(this, () -> {
            try { test.run(); }
            catch (Throwable ex) { getLogger().log(java.util.logging.Level.SEVERE, "RELEASE_CHECK_FAILED", ex); }
        }, ticks);
    }
    void until(BooleanSupplier ready, int attempts, Runnable done) {
        if (ready.getAsBoolean()) { done.run(); return; }
        if (attempts == 0) throw new AssertionError("Timed out waiting for test stage");
        later(5, () -> until(ready, attempts - 1, done));
    }
    void command(Player actor, String value) {
        var cmd = p.getCommand("siegewar");
        cmd.getExecutor().onCommand(actor, cmd, "siegewar", value.split(" "));
    }
    void at(Player player, double x, double y, double z) {
        if (!p.snapshots().teleport(player, new Location(Bukkit.getWorld("world"), x, y, z)))
            throw new AssertionError("Test teleport cancelled");
    }
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof ConsoleCommandSender)) return true;
        try {
            if (args.length > 0 && args[0].equals("config")) { config(); return true; }
            a = Objects.requireNonNull(Bukkit.getPlayerExact("SiegeA"));
            d = Objects.requireNonNull(Bukkit.getPlayerExact("SiegeD"));
            Files.createDirectories(getDataFolder().toPath());
            until(() -> !p.snapshots().hasPending() && !p.game().anyBusy(), 80, () -> {
                try { config(); setup(); } catch (Exception ex) { throw new RuntimeException(ex); }
            });
        } catch (Throwable ex) { getLogger().log(java.util.logging.Level.SEVERE, "RELEASE_CHECK_FAILED", ex); }
        return true;
    }
    void config() throws Exception {
        Files.createDirectories(getDataFolder().toPath());
        Path file = p.getDataFolder().toPath().resolve("config.yml");
        String original = Files.readString(file);
        try {
            var y = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file.toFile());
            y.set("blacklisted-items", List.of("NOT_A_REAL_MATERIAL")); y.save(file.toFile());
            boolean rejected = false;
            try { new Settings(p); } catch (IllegalArgumentException expected) { rejected = true; }
            check(rejected, "Invalid blacklist material rejected");
            Files.writeString(file, original + "\nteam-capacity: 3\n");
            rejected = false;
            try { new Settings(p); } catch (Exception expected) { rejected = true; }
            check(rejected, "Duplicate YAML keys rejected");
            Files.writeString(file, original);
            y = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file.toFile());
            y.set("blacklisted-items", List.of()); y.save(file.toFile());
            check(new Settings(p).blacklistedItems.isEmpty(), "Empty blacklist disables material restrictions");
        } finally { Files.writeString(file, original); }
        Path kitFile=p.getDataFolder().toPath().resolve("kits.yml"), abilityFile=p.getDataFolder().toPath().resolve("abilities.yml");
        byte[] oldKits=Files.exists(kitFile)?Files.readAllBytes(kitFile):null;
        byte[] oldAbilities=Files.exists(abilityFile)?Files.readAllBytes(abilityFile):null;
        try {
            var old=org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file.toFile());
            var kits=new org.bukkit.configuration.file.YamlConfiguration(); kits.set("kits",old.get("kits")); kits.set("kits.wizard.price",91); kits.save(kitFile.toFile());
            var abilities=new org.bukkit.configuration.file.YamlConfiguration(); abilities.set("abilities",old.get("abilities")); abilities.set("abilities.support_wand.cooldown-seconds",17); abilities.save(abilityFile.toFile());
            old.set("kits",null); old.set("abilities",null); old.set("blacklisted-items",null); old.save(file.toFile());
            DefaultUpgrade.apply(p); Settings migrated=new Settings(p);
            check(migrated.kits.get("wizard").price()==91 && migrated.abilities.get("support_wand").cooldownSeconds()==17,
                    "Legacy edited kits and abilities migrate to canonical config");
            check(migrated.blacklistedItems.contains(Material.ELYTRA),"Legacy config receives blacklist defaults");
        } finally {
            Files.writeString(file,original);
            if(oldKits==null)Files.deleteIfExists(kitFile);else Files.write(kitFile,oldKits);
            if(oldAbilities==null)Files.deleteIfExists(abilityFile);else Files.write(abilityFile,oldAbilities);
        }
        int handlers = HandlerList.getRegisteredListeners(p).size();
        p.reloadAll(); p.reloadAll();
        check(handlers == HandlerList.getRegisteredListeners(p).size(), "Repeated idle reload keeps one listener set");
    }
    void setup() throws Exception {
        if (p.arenas().all().containsKey("release") && p.templates().interrupted(p.game().definition("release"))) {
            p.game().reset("release",Bukkit.getConsoleSender());
            until(() -> !p.game().busy("release") && !p.templates().interrupted(p.game().definition("release")), 120, () -> {
                try { setup(); } catch (Exception ex) { throw new RuntimeException(ex); }
            });
            return;
        }
        a.setOp(true); d.setOp(false);
        p.settings().general.set("command-interval-millis", 0);
        p.settings().general.set("respawn-seconds", 1);
        World w = Bukkit.getWorld("world");
        for (int x = 100; x <= 120; x++) for (int z = 100; z <= 120; z++) w.getBlockAt(x,64,z).setType(Material.STONE);
        for (int x = 45; x <= 55; x++) for (int z = 95; z <= 105; z++) w.getBlockAt(x,64,z).setType(Material.STONE);
        w.getBlockAt(104,65,104).setType(Material.CHEST);
        Chest chest = (Chest) w.getBlockAt(104,65,104).getState();
        chest.getBlockInventory().setItem(0,new ItemStack(Material.DIAMOND,3));
        at(a,50.5,65,100.5); at(d,51.5,65,100.5);
        command(a,"setlobby"); command(a,"arena create release");
        at(a,103.5,65,102.5); command(a,"arena setspawn release attackers");
        at(a,117.5,65,102.5); command(a,"arena setspawn release defenders");
        at(a,118.5,65,110.5); command(a,"arena setspawn release king");
        at(a,110.5,68,110.5); command(a,"arena setspawn release spectator");
        at(a,50.5,65,100.5);
        command(a,"arena setbounds release 100 64 100 120 80 120");
        command(a,"arena countdown release 100"); command(a,"arena timer release 300");
        command(a,"arena capture release");
        until(() -> !p.game().busy("release") && p.templates().exists(p.game().definition("release")), 120, () -> {
            try {
                check(p.game().validate("release").isEmpty(), "Router setup and FAWE template validate");
                p.game().enable("release", true); a.setOp(false);
                prepare(a, true); prepare(d, false);
                p.game().join(a,"release",false); p.game().join(d,"release",false);
                until(() -> p.game().session(a.getUniqueId()) != null && p.game().session(d.getUniqueId()) != null
                        && !p.snapshots().pending(a.getUniqueId()) && !p.snapshots().pending(d.getUniqueId()), 80, this::waiting);
            } catch (Exception ex) { throw new RuntimeException(ex); }
        });
    }
    void prepare(Player player, boolean attacker) {
        player.closeInventory(); player.getInventory().clear(); player.setItemOnCursor(null);
        player.setGameMode(GameMode.SURVIVAL);
        player.getAttribute(Attribute.MAX_HEALTH).setBaseValue(40);
        player.getAttribute(Attribute.MAX_ABSORPTION).setBaseValue(8);
        player.setHealth(34); player.setAbsorptionAmount(attacker ? 4 : 0);
        player.setFoodLevel(16); player.setTotalExperience(144); player.setLevel(12); player.setExp(.75f);
        ItemStack sentinel = new ItemStack(Material.DIAMOND,13);
        var meta = sentinel.getItemMeta(); meta.displayName(net.kyori.adventure.text.Component.text("Own property " + player.getName())); sentinel.setItemMeta(meta);
        player.getInventory().setItem(0,sentinel);
        player.getInventory().setItem(7,new ItemStack(Material.WIND_CHARGE,4));
        player.getInventory().setItem(8,new ItemStack(Material.ELYTRA));
        player.getInventory().setChestplate(new ItemStack(Material.IRON_CHESTPLATE));
        player.getInventory().setItemInOffHand(new ItemStack(Material.SHIELD));
        player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED,6000,0));
        before.put(player.getUniqueId(),Arrays.stream(player.getInventory().getContents()).map(i -> i == null ? null : i.clone()).toArray(ItemStack[]::new));
    }
    void waiting() {
        var s = p.game().session(a.getUniqueId()); generation = s.generation;
        check(a.getInventory().getItem(0).equals(before.get(a.getUniqueId())[0]), "Join keeps original hotbar item");
        check(a.getInventory().getChestplate().getType() == Material.IRON_CHESTPLATE
                && a.getInventory().getItemInOffHand().getType() == Material.SHIELD, "Join keeps own armor and offhand");
        check(a.getLevel() == 12 && a.getHealth() == 34 && a.getAbsorptionAmount() == 4 && a.getFoodLevel() == 16,
                "Join keeps health absorption XP and hunger (health="+a.getHealth()+", absorption="+a.getAbsorptionAmount()+", level="+a.getLevel()+", food="+a.getFoodLevel()+")");
        check(a.hasPotionEffect(PotionEffectType.SPEED), "Join keeps original potion effect");
        p.game().forceStart("release");
        check(p.game().active(a) && p.game().active(d), "Match starts with both real clients");
        check(a.getAttribute(Attribute.MAX_HEALTH).getValue() == 40 && a.getHealth() == 40, "Spawn respects legitimate 40 HP attribute");
        check(a.getInventory().getItem(0).equals(before.get(a.getUniqueId())[0]), "Kit grant does not overwrite occupied own slot");
        check(s.king.getAttribute(Attribute.MAX_HEALTH).getValue() == 30 && s.king.getHealth() == 30, "King max health matches config");
        ItemStack issued = Arrays.stream(a.getInventory().getContents()).filter(i -> p.game().currentItem(a,i)).findFirst().orElseThrow();
        ItemStack forged = issued.clone(); var m = forged.getItemMeta();
        m.getPersistentDataContainer().set(p.game().itemKey,PersistentDataType.STRING,UUID.randomUUID().toString()); forged.setItemMeta(m);
        check(!p.game().currentItem(a,forged), "Unknown issued item ID rejected on real item metadata");
        var event = new PlayerInteractEvent(a,Action.RIGHT_CLICK_AIR,a.getInventory().getItem(7),null,org.bukkit.block.BlockFace.SELF,EquipmentSlot.HAND);
        Bukkit.getPluginManager().callEvent(event);
        check(event.useItemInHand() == Event.Result.DENY && a.getInventory().getItem(7).getAmount() == 4,
                "Blacklisted wind charge denied without confiscation");
        var glide = new EntityToggleGlideEvent(a,true); Bukkit.getPluginManager().callEvent(glide);
        check(glide.isCancelled(), "Blacklisted elytra glide event denied");
        Inventory external = Bukkit.createInventory(null,27); a.openInventory(external);
        check(a.getOpenInventory().getTopInventory() != external, "External inventory opening denied");
        ItemStack stew=Arrays.stream(a.getInventory().getContents()).filter(i->"healing_stew".equals(p.abilities().id(i))).findFirst().orElseThrow();
        var deniedUse=new PlayerInteractEvent(a,Action.RIGHT_CLICK_AIR,stew,null,org.bukkit.block.BlockFace.SELF,EquipmentSlot.HAND);
        deniedUse.setUseItemInHand(Event.Result.DENY);Bukkit.getPluginManager().callEvent(deniedUse);
        p.abilities().use(a,"sight"); long cooldown = s.cooldowns.get(a.getUniqueId()).get("sight");
        p.abilities().use(a,"sight");
        check(s.cooldowns.get(a.getUniqueId()).get("sight") == cooldown, "Repeated ability keeps its existing cooldown");
        later(80, () -> combat(s, cooldown));
    }
    void combat(Game.Session s, long cooldown) {
        check(s.charges.get(a.getUniqueId()).get("healing_stew")==2,"Denied item interaction cannot schedule an ability or consume a charge");
        d.setNoDamageTicks(0); double hp = d.getHealth(); observed = -1;
        d.damage(2,a);
        check(observed > 0 && Math.abs(hp-d.getHealth()-observed) < .00001 && d.getHealth() > 30,
                "Vanilla final damage is applied once through native event (observed="+observed+", before="+hp+", after="+d.getHealth()+", absorption="+d.getAbsorptionAmount()+")");
        deny = true; d.setNoDamageTicks(0); hp = d.getHealth(); d.damage(100,a); deny = false;
        check(d.getHealth() == hp && !s.dead.contains(d.getUniqueId()), "Later damage cancellation prevents death and reward side effects");
        s.king.setNoDamageTicks(0); s.king.damage(2,a);
        check(s.king.getHealth() > 0 && s.king.getHealth() < 30, "Ordinary King hit does not force instant death");
        TNTPrimed tnt = a.getWorld().spawn(s.king.getLocation(),TNTPrimed.class); tnt.setFuseTicks(200); tnt.setYield(0); tnt.setSource(a);
        hp = s.king.getHealth(); s.king.setNoDamageTicks(0); s.king.damage(100,tnt); tnt.remove();
        check(s.king.getHealth() == hp, "TNT cannot damage King");
        var left = new PlayerInteractEvent(a,Action.LEFT_CLICK_BLOCK,a.getInventory().getItemInMainHand(),
                a.getWorld().getBlockAt(103,64,103),org.bukkit.block.BlockFace.UP,EquipmentSlot.HAND);
        Bukkit.getPluginManager().callEvent(left);
        check(left.useInteractedBlock()!=Event.Result.DENY,"Native left-click path permits terrain interaction");
        var creative = new org.bukkit.event.inventory.InventoryCreativeEvent(a.getOpenInventory(),org.bukkit.event.inventory.InventoryType.SlotType.QUICKBAR,36,new ItemStack(Material.DIAMOND,64));
        Bukkit.getPluginManager().callEvent(creative);
        check(creative.isCancelled(),"Creative inventory injection event denied in session");
        check(a.breakBlock(a.getWorld().getBlockAt(102,64,102)), "Managed region permits active participant terrain break");
        check(a.breakBlock(a.getWorld().getBlockAt(104,65,104)), "Active participant can break template chest");
        d.setHealth(0);
        later(65, () -> {
            check(p.game().active(d) && d.getHealth() == 40, "Real vanilla death respawns at legitimate max health");
            check(d.getInventory().getItem(0).equals(before.get(d.getUniqueId())[0]), "Own inventory survives actual death");
            check(s.cooldowns.get(a.getUniqueId()).get("sight") == cooldown, "Other player death does not reset ability cooldown");
            p.game().stop("release");
            until(() -> p.game().arena("release") != null && !p.game().arena("release").generation.equals(generation)
                    && !p.snapshots().pending(a.getUniqueId()) && !p.snapshots().pending(d.getUniqueId()), 160, this::returned);
        });
    }
    void returned() {
        for (Player player : List.of(a,d)) {
            check(Arrays.equals(before.get(player.getUniqueId()),player.getInventory().getContents()),
                    "Exact own inventory restored for " + player.getName());
            check(player.getLevel() == 12 && Math.abs(player.getExp()-.75f) < .001 && player.getHealth() == 34,
                    "Own XP and health restored for " + player.getName());
        }
        check(a.getWorld().getBlockAt(102,64,102).getType() == Material.STONE, "FAWE regenerates broken terrain");
        Chest chest = (Chest)a.getWorld().getBlockAt(104,65,104).getState();
        check(chest.getBlockInventory().getItem(0).equals(new ItemStack(Material.DIAMOND,3)), "FAWE restores original container contents");
        var manager = WorldGuard.getInstance().getPlatform().getRegionContainer().get(BukkitAdapter.adapt(a.getWorld()));
        check(manager.getRegion("asw_release").getFlag(Flags.BLOCK_BREAK) == null, "Original WorldGuard break flag restored");
        check(p.store().pending().isEmpty(), "Recovery escrow consumed after durable restore");
        fullInventory();
    }
    void fullInventory() {
        for(int i=0;i<36;i++)a.getInventory().setItem(i,new ItemStack(Material.STONE,i+1));
        ItemStack[] own=Arrays.stream(a.getInventory().getContents()).map(i->i==null?null:i.clone()).toArray(ItemStack[]::new);
        p.game().join(a,"release",false);p.game().join(d,"release",false);
        until(()->p.game().session(a.getUniqueId())!=null&&p.game().session(d.getUniqueId())!=null
                &&!p.snapshots().pending(a.getUniqueId())&&!p.snapshots().pending(d.getUniqueId()),80,()->{
            p.game().forceStart("release");
            UUID fullGeneration=p.game().session(a.getUniqueId()).generation;
            boolean intact=true;for(int i=0;i<36;i++)if(!own[i].equals(a.getInventory().getItem(i)))intact=false;
            check(intact,"Full owned storage is not overwritten by kit grants");
            p.game().stop("release");
            until(()->p.game().arena("release")!=null&&!p.game().arena("release").generation.equals(fullGeneration)
                    &&!p.snapshots().pending(a.getUniqueId())&&!p.snapshots().pending(d.getUniqueId()),160,()->{
                check(Arrays.equals(own,a.getInventory().getContents()),"Full owned inventory restores exactly");
                check(p.store().pending().isEmpty(),"Second match recovery remains idempotent");
                getLogger().info("RELEASE_CHECK_COMPLETE checks="+passed.size());
            });
        });
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void deny(EntityDamageByEntityEvent e) { if (deny && e.getEntity() == d) e.setCancelled(true); }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void observe(EntityDamageByEntityEvent e) { if (e.getEntity() == d) observed = e.getFinalDamage(); }
}
