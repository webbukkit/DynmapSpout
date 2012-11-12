package org.dynmap.spout;
/**
 * Spout specific implementation of DynmapWorld
 */
import java.util.List;

import org.dynmap.DynmapChunk;
import org.dynmap.DynmapLocation;
import org.dynmap.DynmapWorld;
import org.dynmap.utils.MapChunkCache;
import org.spout.api.geo.World;
import org.spout.api.geo.discrete.Point;
import org.spout.api.geo.discrete.Transform;
import org.spout.api.material.BlockMaterial;

public class SpoutWorld extends DynmapWorld {
    private World world;
    private String env;
    private boolean isnether;
//TODO    private static BlockLightLevel bll = new BlockLightLevel();
    
    public SpoutWorld(World w) {
        super(w.getName(), w.getHeight(), 64);
        
        world = w;
        /* Hackish - but no generic way to spot nether */
        String cls = w.getGenerator().getClass().getName();
        if(cls.contains("NetherGenerator"))
            env = "nether";
        else if(cls.contains("TheEndGenerator"))
            env = "the_end";
        else
            env = "normal";
        isnether = env.equals("nether");
    }
    /* Test if world is nether */
    @Override
    public boolean isNether() {
        return isnether;
    }
    /* Get world spawn location */
    @Override
    public DynmapLocation getSpawnLocation() {
        DynmapLocation dloc = new DynmapLocation(getName(), 0, 64, 0);
        Transform t = world.getSpawnPoint();
        if(t != null) {
            Point p = t.getPosition();
            dloc.x = p.getX(); dloc.y = p.getY();
            dloc.z = p.getZ(); dloc.world = getName();
        }
        return dloc;
    }
    /* Get world time */
    @Override
    public long getTime() {
        return world.getAge() / 50L; /* 50msec per tick */
    }
    /* World is storming */
    @Override
    public boolean hasStorm() {
//TOOD        return world.hasStorm();
        return false;
    }
    /* World is thundering */
    @Override
    public boolean isThundering() {
//TODO        return world.isThundering();
        return false;
    }
    /* World is loaded */
    @Override
    public boolean isLoaded() {
        return (world != null);
    }
    /* Get light level of block */
    @Override
    public int getLightLevel(int x, int y, int z) {
        //return world.getBlockMaterial(x, y, z).getLightLevel();
        return 15;
    }
    /* Get highest Y coord of given location */
    @Override
    public int getHighestBlockYAt(int x, int z) {
        for(int y = (world.getHeight()-1); y >= 0; y--) {
            if(world.getBlockMaterial(x, y, z) != BlockMaterial.AIR)
                return y+1;
        }
        return 0;
    }
    /* Test if sky light level is requestable */
    @Override
    public boolean canGetSkyLightLevel() {
//TODO        return bll.isReady();
        return true;
    }
    /* Return sky light level */
    @Override
    public int getSkyLightLevel(int x, int y, int z) {
//TODO  return bll.getSkyLightLevel(world.getBlockAt(x, y, z));
        return 15;
    }
    /**
     * Get world environment ID (lower case - normal, the_end, nether)
     */
    @Override
    public String getEnvironment() {
        return env;
    }
    /**
     * Get map chunk cache for world
     */
    @Override
    public MapChunkCache getChunkCache(List<DynmapChunk> chunks) {
        SpoutMapChunkCache c = new SpoutMapChunkCache();
        c.setChunks(this, chunks);
        return c;
    }
    
    public World getWorld() {
        return world;
    }
    @Override
    public void setWorldUnloaded() {
        //TODO: not supported
    }
}
