package org.dynmap.spout;

import java.io.File;
import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;

import org.dynmap.DynmapCommonAPI;
import org.dynmap.DynmapCore;
import org.dynmap.DynmapLocation;
import org.dynmap.DynmapWorld;
import org.dynmap.Log;
import org.dynmap.common.BiomeMap;
import org.dynmap.common.DynmapCommandSender;
import org.dynmap.common.DynmapPlayer;
import org.dynmap.common.DynmapServerInterface;
import org.dynmap.common.DynmapListenerManager.EventType;
import org.dynmap.markers.MarkerAPI;
import org.dynmap.spout.permissions.PermissionProvider;
import org.spout.api.command.Command;
import org.spout.api.command.CommandSource;
import org.spout.api.command.RawCommandExecutor;
import org.spout.api.entity.Entity;
import org.spout.api.event.Event;
import org.spout.api.event.EventExecutor;
import org.spout.api.event.block.BlockChangeEvent;
import org.spout.api.event.player.PlayerChatEvent;
import org.spout.api.event.player.PlayerJoinEvent;
import org.spout.api.event.player.PlayerLeaveEvent;
import org.spout.api.exception.CommandException;
import org.spout.api.geo.World;
import org.spout.api.geo.cuboid.Block;
import org.spout.api.geo.discrete.Point;
import org.spout.api.player.Player;
import org.spout.api.plugin.CommonPlugin;
import org.spout.api.plugin.PluginDescriptionFile;
import org.spout.api.plugin.PluginManager;
import org.spout.api.util.Named;
import org.spout.api.Game;
import org.spout.api.ChatColor;

public class DynmapPlugin extends CommonPlugin implements DynmapCommonAPI {
    private final String prefix = "[Dynmap] ";

    private DynmapCore core;
    private PermissionProvider permissions;
    private String version;
    private Game game;
    public SpoutEventProcessor sep;
//TODO    public SnapshotCache sscache;

    public static DynmapPlugin plugin;

    public DynmapPlugin() {
        plugin = this;
    }
    
    /**
     * Server access abstraction class
     */
    public class SpoutServer implements DynmapServerInterface {
        public void scheduleServerTask(Runnable run, long delay) {
            game.getScheduler().scheduleSyncDelayedTask(DynmapPlugin.this, run, delay);
        }
        
        public DynmapPlayer[] getOnlinePlayers() {
            Player[] players = game.getOnlinePlayers();
            DynmapPlayer[] dplay = new DynmapPlayer[players.length];
            for(int i = 0; i < players.length; i++)
                dplay[i] = new SpoutPlayer(players[i]);
            return dplay;
        }

        public void reload() {
            PluginManager pluginManager = game.getPluginManager();
            pluginManager.disablePlugin(DynmapPlugin.this);
            pluginManager.enablePlugin(DynmapPlugin.this);
        }

        public DynmapPlayer getPlayer(String name) {
            Player p = game.getPlayer(name, true);
            if(p != null) {
                return new SpoutPlayer(p);
            }
            return null;
        }

        public Set<String> getIPBans() {
//TODO          return getServer().getIPBans();
            return Collections.emptySet();
        }

        public <T> Future<T> callSyncMethod(final Callable<T> task) {
            final FutureTask<T> ft = new FutureTask<T>(task);
            final Object o = new Object();
            synchronized(o) {
                game.getScheduler().scheduleSyncDelayedTask(DynmapPlugin.this, new Runnable() {
                    public void run() {
                        try {
                            ft.run();
                        } finally {
                            synchronized(o) {
                                o.notify();
                            }
                        }
                    }
                });
                try { o.wait(); } catch (InterruptedException ix) {}
            }
            return ft;
        }

        public String getServerName() {
            return game.getName();
        }

        public boolean isPlayerBanned(String pid) {
//TODO            OfflinePlayer p = getServer().getOfflinePlayer(pid);
//TODO            if((p != null) && p.isBanned())
//TODO                return true;
            return false;
        }

        public String stripChatColor(String s) {
            return ChatColor.strip(s);
        }
        
        private Set<EventType> registered = new HashSet<EventType>();

        public boolean requestEventNotification(EventType type) {
            if(registered.contains(type))
                return true;
            switch(type) {
                case WORLD_LOAD:
                case WORLD_UNLOAD:
                    /* Already called for normal world activation/deactivation */
                    break;
                case WORLD_SPAWN_CHANGE:
//TODO                    sep.registerEvent(Type.SPAWN_CHANGE, new WorldListener() {
//TODO                        @Override
//TODO                        public void onSpawnChange(SpawnChangeEvent evt) {
//TODO                            DynmapWorld w = new SpoutWorld(evt.getWorld());
//TODO                            core.listenerManager.processWorldEvent(EventType.WORLD_SPAWN_CHANGE, w);
//TODO                        }
//TODO                    });
                    break;
                case PLAYER_JOIN:
                case PLAYER_QUIT:
                    /* Already handled */
                    break;
                case PLAYER_BED_LEAVE:
//TODO                    sep.registerEvent(Type.PLAYER_BED_LEAVE, new PlayerListener() {
//TODO                        @Override
//TODO                        public void onPlayerBedLeave(PlayerBedLeaveEvent evt) {
//TODO                            DynmapPlayer p = new BukkitPlayer(evt.getPlayer());
//TODO                            core.listenerManager.processPlayerEvent(EventType.PLAYER_BED_LEAVE, p);
//TODO                        }
//TODO                    });
                    break;
                case PLAYER_CHAT:
                    sep.registerEvent(PlayerChatEvent.class, new EventExecutor() {
                        public void execute(Event evt) {
                            PlayerChatEvent chatevt = (PlayerChatEvent)evt;
                            DynmapPlayer p = null;
                            if(chatevt.getPlayer() != null)
                                p = new SpoutPlayer(chatevt.getPlayer());
                            core.listenerManager.processChatEvent(EventType.PLAYER_CHAT, p, chatevt.getMessage());
                        }
                    });
                    break;
                case BLOCK_BREAK:
                    //TODO - doing this for all block changes, not just breaks
                    sep.registerEvent(BlockChangeEvent.class, new EventExecutor() {
                        public void execute(Event evt) {
                            BlockChangeEvent bce = (BlockChangeEvent)evt;
                            Block b = bce.getBlock();
                            core.listenerManager.processBlockEvent(EventType.BLOCK_BREAK, b.getBlockId(),
                                    b.getWorld().getName(), b.getX(), b.getY(), b.getZ());
                        }
                    });
                    break;
                case SIGN_CHANGE:
                    //TODO - no event yet 
                    //bep.registerEvent(Type.SIGN_CHANGE, new BlockListener() {
                    //    @Override
                    //    public void onSignChange(SignChangeEvent evt) {
                    //        Block b = evt.getBlock();
                    //        Location l = b.getLocation();
                    //        String[] lines = evt.getLines();    /* Note: changes to this change event - intentional */
                    //        DynmapPlayer dp = null;
                    //        Player p = evt.getPlayer();
                    //        if(p != null) dp = new BukkitPlayer(p);
                    //        core.listenerManager.processSignChangeEvent(EventType.SIGN_CHANGE, b.getType().getId(),
                    //                l.getWorld().getName(), l.getBlockX(), l.getBlockY(), l.getBlockZ(), lines, dp);
                    //    }
                    //});
                    break;
                default:
                    Log.severe("Unhandled event type: " + type);
                    return false;
            }
            return true;
        }
        
        public boolean sendWebChatEvent(String source, String name, String msg) {
            DynmapWebChatEvent evt = new DynmapWebChatEvent(source, name, msg);
            game.getEventManager().callEvent(evt);
            return (evt.isCancelled() == false);
        }
        
        public void broadcastMessage(String msg) {
            game.broadcastMessage(msg);
        }

        public String[] getBiomeIDs() {
            BiomeMap[] b = BiomeMap.values();
            String[] bname = new String[b.length];
            for(int i = 0; i < bname.length; i++)
                bname[i] = b[i].toString();
            return bname;
        }
        
        public double getCacheHitRate() {
//TODO            return sscache.getHitRate();
            return 0.0;
        }
        public void resetCacheStats() {
//TODO            sscache.resetStats();
        }

        public DynmapWorld getWorldByName(String wname) {
            World w = game.getWorld(wname);
            if(w != null) {
                return new SpoutWorld(w);
            }
            return null;
        }
    }
    /**
     * Player access abstraction class
     */
    public class SpoutPlayer extends SpoutCommandSender implements DynmapPlayer {
        private Player player;
        
        public SpoutPlayer(Player p) {
            super(p);
            player = p;
        }

        public boolean isConnected() {
            return player.isOnline();
        }

        public String getName() {
            return player.getName();
        }

        public String getDisplayName() {
            return player.getDisplayName();
        }

        public boolean isOnline() {
            return player.isOnline();
        }

        public DynmapLocation getLocation() {
            Point p = player.getEntity().getTransform().getPosition();
            return toLoc(p);
        }

        public String getWorld() {
            Entity e = player.getEntity();
            if(e != null) {
                World w = e.getWorld();
                if(w != null) {
                    return w.getName();
                }
            }
            return null;
        }

        public InetSocketAddress getAddress() {
            return new InetSocketAddress(player.getAddress(), 0);
        }

        public boolean isSneaking() {
            //TODO
            return false;
        }

        public int getHealth() {
            //TODO
            return 0;
        }

        public int getArmorPoints() {
            //TODO
            return 0;
        }

        public DynmapLocation getBedSpawnLocation() {
            //TODO
            return null;
        }
    }
    /* Handler for generic console command sender */
    public class SpoutCommandSender implements DynmapCommandSender {
        private CommandSource sender;

        public SpoutCommandSender(CommandSource send) {
            sender = send;
        }
        
        public boolean hasPrivilege(String privid) {
            return permissions.has(sender, privid);
        }

        public void sendMessage(String msg) {
            sender.sendMessage(msg);
        }

        public boolean isConnected() {
            return true;
        }

        public boolean isOp() {
//TODO            return sender.isInGroup(group)
            return false;
        }
    }
    
    @Override
    public void onEnable() {
        
        game = getGame();
        
        PluginDescriptionFile pdfFile = this.getDescription();
        version = pdfFile.getVersion();

        /* Initialize event processor */
        if(sep == null)
            sep = new SpoutEventProcessor(this);
 
        /* Set up player login/quit event handler */
        registerPlayerLoginListener();

        /* Set up permissions */    
        permissions = new PermissionProvider(); /* Use spout default */

        /* Get and initialize data folder */
        File dataDirectory = this.getDataFolder();
        if(dataDirectory.exists() == false)
            dataDirectory.mkdirs();
        /* Get MC version */
        String mcver = game.getVersion();
        
        /* Instantiate core */
        if(core == null)
            core = new DynmapCore();
        /* Inject dependencies */
        core.setPluginVersion(version);
        core.setMinecraftVersion(mcver);
        core.setDataFolder(dataDirectory);
        core.setServer(new SpoutServer());
        
        /* Enable core */
        if(!core.enableCore()) {
            this.setEnabled(false);
            return;
        }
//TODO        sscache = new SnapshotCache(core.getSnapShotCacheSize());
        
        game.getScheduler().scheduleSyncDelayedTask(this, new Runnable() {
            public void run() {
                /* Initialized the currently loaded worlds */
                for (World world : game.getWorlds()) {
                    SpoutWorld w = new SpoutWorld(world);
                    if(core.processWorldLoad(w))    /* Have core process load first - fire event listeners if good load after */
                        core.listenerManager.processWorldEvent(EventType.WORLD_LOAD, w);
                }
            }
        }, 100);
    
        /* Register our update trigger events */
        registerEvents();
        
        Command cmd = game.getRootCommand().addSubCommand(new Named() {
            public String getName() { return "dynmap"; }
        }, "dynmap");
        cmd.setRawExecutor(new RawCommandExecutor() {
            public void execute(CommandSource sender, String[] args,
                    int baseIndex, boolean fuzzyLookup) throws CommandException {
                DynmapCommandSender dsender;
                if(sender instanceof Player) {
                    dsender = new SpoutPlayer((Player)sender);
                }
                else {
                    dsender = new SpoutCommandSender(sender);
                }
                String[] cmdargs = new String[args.length-1];
                System.arraycopy(args, 1, cmdargs, 0, cmdargs.length);
                if(!core.processCommand(dsender, args[0], "dynmap", cmdargs))
                    throw new CommandException("Bad Command");
                
            } });
    }
    
    @Override
    public void onDisable() {
        /* Reset registered listeners */
        sep.cleanup();
        /* Disable core */
        core.disableCore();

//TODO        if(sscache != null) {
//TODO            sscache.cleanup();
//TODO            sscache = null; 
//TODO        }
    }

    public String getPrefix() {
        return prefix;
    }
    
    public final MarkerAPI getMarkerAPI() {
        return core.getMarkerAPI();
    }

    public final boolean markerAPIInitialized() {
        return core.markerAPIInitialized();
    }

    public final boolean sendBroadcastToWeb(String sender, String msg) {
        return core.sendBroadcastToWeb(sender, msg);
    }

    public final int triggerRenderOfVolume(String wid, int minx, int miny, int minz,
            int maxx, int maxy, int maxz) {
        return core.triggerRenderOfVolume(wid, minx, miny, minz, maxx, maxy, maxz);
    }

    public final int triggerRenderOfBlock(String wid, int x, int y, int z) {
        return core.triggerRenderOfBlock(wid, x, y, z);
    }

    public final void setPauseFullRadiusRenders(boolean dopause) {
        core.setPauseFullRadiusRenders(dopause);
    }

    public final boolean getPauseFullRadiusRenders() {
        return core.getPauseFullRadiusRenders();
    }

    public final void setPauseUpdateRenders(boolean dopause) {
        core.setPauseUpdateRenders(dopause);
    }

    public final boolean getPauseUpdateRenders() {
        return core.getPauseUpdateRenders();
    }

    public final void setPlayerVisiblity(String player, boolean is_visible) {
        core.setPlayerVisiblity(player, is_visible);
    }

    public final boolean getPlayerVisbility(String player) {
        return core.getPlayerVisbility(player);
    }

    public final void postPlayerMessageToWeb(String playerid, String playerdisplay,
            String message) {
        core.postPlayerMessageToWeb(playerid, playerdisplay, message);
    }

    public final void postPlayerJoinQuitToWeb(String playerid, String playerdisplay,
            boolean isjoin) {
        core.postPlayerJoinQuitToWeb(playerid, playerdisplay, isjoin);
    }

    public final String getDynmapCoreVersion() {
        return core.getDynmapCoreVersion();
    }

    public final void setPlayerVisiblity(Player player, boolean is_visible) {
        core.setPlayerVisiblity(player.getName(), is_visible);
    }

    public final boolean getPlayerVisbility(Player player) {
        return core.getPlayerVisbility(player.getName());
    }

    public final void postPlayerMessageToWeb(Player player, String message) {
        core.postPlayerMessageToWeb(player.getName(), player.getDisplayName(), message);
    }

    public void postPlayerJoinQuitToWeb(Player player, boolean isjoin) {
        core.postPlayerJoinQuitToWeb(player.getName(), player.getDisplayName(), isjoin);
    }

    public String getDynmapVersion() {
        return version;
    }
    
    private static DynmapLocation toLoc(Point p) {
        return new DynmapLocation(p.getWorld().getName(), p.getX(), p.getY(), p.getZ());
    }
    
    private void registerPlayerLoginListener() {
        sep.registerEvent(PlayerJoinEvent.class, new EventExecutor() {
            public void execute(Event evt) {
                PlayerJoinEvent pje = (PlayerJoinEvent)evt;
                SpoutPlayer dp = new SpoutPlayer(pje.getPlayer());                
                core.listenerManager.processPlayerEvent(EventType.PLAYER_JOIN, dp);
            }
        });
        sep.registerEvent(PlayerLeaveEvent.class, new EventExecutor() {
            public void execute(Event evt) {
                PlayerLeaveEvent pje = (PlayerLeaveEvent)evt;
                SpoutPlayer dp = new SpoutPlayer(pje.getPlayer());                
                core.listenerManager.processPlayerEvent(EventType.PLAYER_QUIT, dp);
            }
        });
    }

    private boolean onplace;
    private boolean onbreak;
    private boolean onblockform;
    private boolean onblockfade;
    private boolean onblockspread;
    private boolean onblockfromto;
    private boolean onblockphysics;
    private boolean onleaves;
    private boolean onburn;
    private boolean onpiston;
    private boolean onplayerjoin;
    private boolean onplayermove;
    private boolean ongeneratechunk;
    private boolean onloadchunk;
    private boolean onexplosion;

    private void registerEvents() {
//        BlockListener blockTrigger = new BlockListener() {
//            @Override
//            public void onBlockPlace(BlockPlaceEvent event) {
//                if(event.isCancelled())
//                    return;
//                Location loc = event.getBlock().getLocation();
//                String wn = loc.getWorld().getName();
//                sscache.invalidateSnapshot(wn, loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
//                if(onplace) {
//                    mapManager.touch(wn, loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(), "blockplace");
//                }
//            }
//
//            @Override
//            public void onBlockBreak(BlockBreakEvent event) {
//                if(event.isCancelled())
//                    return;
//                Location loc = event.getBlock().getLocation();
//                String wn = loc.getWorld().getName();
//                sscache.invalidateSnapshot(wn, loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
//                if(onbreak) {
//                    mapManager.touch(wn, loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(), "blockbreak");
//                }
//            }
//
//            @Override
//            public void onLeavesDecay(LeavesDecayEvent event) {
//                if(event.isCancelled())
//                    return;
//                Location loc = event.getBlock().getLocation();
//                String wn = loc.getWorld().getName();
//                sscache.invalidateSnapshot(wn, loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
//                if(onleaves) {
//                    mapManager.touch(wn, loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(), "leavesdecay");
//                }
//            }
//            
//            @Override
//            public void onBlockBurn(BlockBurnEvent event) {
//                if(event.isCancelled())
//                    return;
//                Location loc = event.getBlock().getLocation();
//                String wn = loc.getWorld().getName();
//                sscache.invalidateSnapshot(wn, loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
//                if(onburn) {
//                    mapManager.touch(wn, loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(), "blockburn");
//                }
//            }
//            
//            @Override
//            public void onBlockForm(BlockFormEvent event) {
//                if(event.isCancelled())
//                    return;
//                Location loc = event.getBlock().getLocation();
//                String wn = loc.getWorld().getName();
//                sscache.invalidateSnapshot(wn, loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
//                if(onblockform) {
//                    mapManager.touch(wn, loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(), "blockform");
//                }
//            }
//
//            @Override
//            public void onBlockFade(BlockFadeEvent event) {
//                if(event.isCancelled())
//                    return;
//                Location loc = event.getBlock().getLocation();
//                String wn = loc.getWorld().getName();
//                sscache.invalidateSnapshot(wn, loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
//                if(onblockfade) {
//                    mapManager.touch(wn, loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(), "blockfade");
//                }
//            }
//            
//            @Override
//            public void onBlockSpread(BlockSpreadEvent event) {
//                if(event.isCancelled())
//                    return;
//                Location loc = event.getBlock().getLocation();
//                String wn = loc.getWorld().getName();
//                sscache.invalidateSnapshot(wn, loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
//                if(onblockspread) {
//                    mapManager.touch(wn, loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(), "blockspread");
//                }
//            }
//
//            @Override
//            public void onBlockFromTo(BlockFromToEvent event) {
//                if(event.isCancelled())
//                    return;
//                Location loc = event.getToBlock().getLocation();
//                String wn = loc.getWorld().getName();
//                sscache.invalidateSnapshot(wn, loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
//                if(onblockfromto)
//                    mapManager.touch(wn, loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(), "blockfromto");
//                loc = event.getBlock().getLocation();
//                wn = loc.getWorld().getName();
//                sscache.invalidateSnapshot(wn, loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
//                if(onblockfromto)
//                    mapManager.touch(wn, loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(), "blockfromto");
//            }
//            
//            @Override
//            public void onBlockPhysics(BlockPhysicsEvent event) {
//                if(event.isCancelled())
//                    return;
//                Location loc = event.getBlock().getLocation();
//                String wn = loc.getWorld().getName();
//                sscache.invalidateSnapshot(wn, loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
//                if(onblockphysics) {
//                    mapManager.touch(wn, loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(), "blockphysics");
//                }
//            }
//
//            @Override
//            public void onBlockPistonRetract(BlockPistonRetractEvent event) {
//                if(event.isCancelled())
//                    return;
//                Block b = event.getBlock();
//                Location loc = b.getLocation();
//                BlockFace dir;
//                try {   /* Workaround Bukkit bug = http://leaky.bukkit.org/issues/1227 */
//                    dir = event.getDirection();
//                } catch (ClassCastException ccx) {
//                    dir = BlockFace.NORTH;
//                }
//                String wn = loc.getWorld().getName();
//                int x = loc.getBlockX(), y = loc.getBlockY(), z = loc.getBlockZ();
//                sscache.invalidateSnapshot(wn, x, y, z);
//                if(onpiston)
//                    mapManager.touch(wn, x, y, z, "pistonretract");
//                for(int i = 0; i < 2; i++) {
//                    x += dir.getModX();
//                    y += dir.getModY();
//                    z += dir.getModZ();
//                    sscache.invalidateSnapshot(wn, x, y, z);
//                    if(onpiston)
//                        mapManager.touch(wn, x, y, z, "pistonretract");
//                }
//            }
//            @Override
//            public void onBlockPistonExtend(BlockPistonExtendEvent event) {
//                if(event.isCancelled())
//                    return;
//                Block b = event.getBlock();
//                Location loc = b.getLocation();
//                BlockFace dir;
//                try {   /* Workaround Bukkit bug = http://leaky.bukkit.org/issues/1227 */
//                    dir = event.getDirection();
//                } catch (ClassCastException ccx) {
//                    dir = BlockFace.NORTH;
//                }
//                String wn = loc.getWorld().getName();
//                int x = loc.getBlockX(), y = loc.getBlockY(), z = loc.getBlockZ();
//                sscache.invalidateSnapshot(wn, x, y, z);
//                if(onpiston)
//                    mapManager.touch(wn, x, y, z, "pistonretract");
//                for(int i = 0; i < 1+event.getLength(); i++) {
//                    x += dir.getModX();
//                    y += dir.getModY();
//                    z += dir.getModZ();
//                    sscache.invalidateSnapshot(wn, x, y, z);
//                    if(onpiston)
//                        mapManager.touch(wn, x, y, z, "pistonretract");
//                }
//            }
//        };
//        
//        // To trigger rendering.
//        onplace = core.isTrigger("blockplaced");
//        bep.registerEvent(Event.Type.BLOCK_PLACE, blockTrigger);
//            
//        onbreak = core.isTrigger("blockbreak");
//        bep.registerEvent(Event.Type.BLOCK_BREAK, blockTrigger);
//            
//        if(core.isTrigger("snowform")) Log.info("The 'snowform' trigger has been deprecated due to Bukkit changes - use 'blockformed'");
//            
//        onleaves = core.isTrigger("leavesdecay");
//        bep.registerEvent(Event.Type.LEAVES_DECAY, blockTrigger);
//            
//        onburn = core.isTrigger("blockburn");
//        bep.registerEvent(Event.Type.BLOCK_BURN, blockTrigger);
//
//        onblockform = core.isTrigger("blockformed");
//        bep.registerEvent(Event.Type.BLOCK_FORM, blockTrigger);
//            
//        onblockfade = core.isTrigger("blockfaded");
//        bep.registerEvent(Event.Type.BLOCK_FADE, blockTrigger);
//            
//        onblockspread = core.isTrigger("blockspread");
//        bep.registerEvent(Event.Type.BLOCK_SPREAD, blockTrigger);
//
//        onblockfromto = core.isTrigger("blockfromto");
//        bep.registerEvent(Event.Type.BLOCK_FROMTO, blockTrigger);
//
//        onblockphysics = core.isTrigger("blockphysics");
//        bep.registerEvent(Event.Type.BLOCK_PHYSICS, blockTrigger);
//
//        onpiston = core.isTrigger("pistonmoved");
//        bep.registerEvent(Event.Type.BLOCK_PISTON_EXTEND, blockTrigger);
//        bep.registerEvent(Event.Type.BLOCK_PISTON_RETRACT, blockTrigger);
//        /* Register player event trigger handlers */
//        PlayerListener playerTrigger = new PlayerListener() {
//            @Override
//            public void onPlayerJoin(PlayerJoinEvent event) {
//                if(onplayerjoin) {
//                    Location loc = event.getPlayer().getLocation();
//                    mapManager.touch(loc.getWorld().getName(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(), "playerjoin");
//                }
//                core.listenerManager.processPlayerEvent(EventType.PLAYER_JOIN, new BukkitPlayer(event.getPlayer()));
//            }
//            @Override
//            public void onPlayerQuit(PlayerQuitEvent event) {
//                core.listenerManager.processPlayerEvent(EventType.PLAYER_QUIT, new BukkitPlayer(event.getPlayer()));
//            }
//
//            @Override
//            public void onPlayerMove(PlayerMoveEvent event) {
//                Location loc = event.getPlayer().getLocation();
//                mapManager.touch(loc.getWorld().getName(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(), "playermove");
//            }
//        };
//
//        onplayerjoin = core.isTrigger("playerjoin");
//        onplayermove = core.isTrigger("playermove");
//        bep.registerEvent(Event.Type.PLAYER_JOIN, playerTrigger);
//        bep.registerEvent(Event.Type.PLAYER_QUIT, playerTrigger);
//        if(onplayermove)
//            bep.registerEvent(Event.Type.PLAYER_MOVE, playerTrigger);
//
//        /* Register entity event triggers */
//        EntityListener entityTrigger = new EntityListener() {
//            @Override
//            public void onEntityExplode(EntityExplodeEvent event) {
//                Location loc = event.getLocation();
//                String wname = loc.getWorld().getName();
//                int minx, maxx, miny, maxy, minz, maxz;
//                minx = maxx = loc.getBlockX();
//                miny = maxy = loc.getBlockY();
//                minz = maxz = loc.getBlockZ();
//                /* Calculate volume impacted by explosion */
//                List<Block> blocks = event.blockList();
//                for(Block b: blocks) {
//                    Location l = b.getLocation();
//                    int x = l.getBlockX();
//                    if(x < minx) minx = x;
//                    if(x > maxx) maxx = x;
//                    int y = l.getBlockY();
//                    if(y < miny) miny = y;
//                    if(y > maxy) maxy = y;
//                    int z = l.getBlockZ();
//                    if(z < minz) minz = z;
//                    if(z > maxz) maxz = z;
//                }
//                sscache.invalidateSnapshot(wname, minx, miny, minz, maxx, maxy, maxz);
//                if(onexplosion) {
//                    mapManager.touchVolume(wname, minx, miny, minz, maxx, maxy, maxz, "entityexplode");
//                }
//            }
//        };
//        onexplosion = core.isTrigger("explosion");
//        bep.registerEvent(Event.Type.ENTITY_EXPLODE, entityTrigger);
//        
//        
//        /* Register world event triggers */
//        WorldListener worldTrigger = new WorldListener() {
//            @Override
//            public void onChunkLoad(ChunkLoadEvent event) {
//                if(DynmapCore.ignore_chunk_loads)
//                    return;
//                Chunk c = event.getChunk();
//                /* Touch extreme corners */
//                int x = c.getX() << 4;
//                int z = c.getZ() << 4;
//                mapManager.touchVolume(event.getWorld().getName(), x, 0, z, x+15, 128, z+16, "chunkload");
//            }
//            @Override
//            public void onChunkPopulate(ChunkPopulateEvent event) {
//                Chunk c = event.getChunk();
//                /* Touch extreme corners */
//                int x = c.getX() << 4;
//                int z = c.getZ() << 4;
//                mapManager.touchVolume(event.getWorld().getName(), x, 0, z, x+15, 128, z+16, "chunkpopulate");
//            }
//            @Override
//            public void onWorldLoad(WorldLoadEvent event) {
//                core.updateConfigHashcode();
//                SpoutWorld w = new SpoutWorld(event.getWorld());
//                if(core.processWorldLoad(w))    /* Have core process load first - fire event listeners if good load after */
//                    core.listenerManager.processWorldEvent(EventType.WORLD_LOAD, w);
//            }
//            @Override
//            public void onWorldUnload(WorldUnloadEvent event) {
//                core.updateConfigHashcode();
//                DynmapWorld w = core.getWorld(event.getWorld().getName());
//                if(w != null)
//                    core.listenerManager.processWorldEvent(EventType.WORLD_UNLOAD, w);
//            }
//        };
//
//        ongeneratechunk = core.isTrigger("chunkgenerated");
//        if(ongeneratechunk) {
//            bep.registerEvent(Event.Type.CHUNK_POPULATED, worldTrigger);
//        }
//        onloadchunk = core.isTrigger("chunkloaded");
//        if(onloadchunk) { 
//            bep.registerEvent(Event.Type.CHUNK_LOAD, worldTrigger);
//        }
//
//        // To link configuration to real loaded worlds.
//        bep.registerEvent(Event.Type.WORLD_LOAD, worldTrigger);
//        bep.registerEvent(Event.Type.WORLD_UNLOAD, worldTrigger);
    }

    public void assertPlayerInvisibility(String player, boolean is_invisible,
            String plugin_id) {
        core.assertPlayerInvisibility(player, is_invisible, plugin_id);
    }

}
