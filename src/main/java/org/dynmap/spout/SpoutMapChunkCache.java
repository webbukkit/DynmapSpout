package org.dynmap.spout;

import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;
import java.util.Set;

import org.dynmap.DynmapChunk;
import org.dynmap.DynmapCore;
import org.dynmap.DynmapWorld;
import org.dynmap.Log;
import org.dynmap.common.BiomeMap;
import org.dynmap.utils.MapChunkCache;
import org.dynmap.utils.MapIterator;
import org.dynmap.utils.MapIterator.BlockStep;
import org.spout.api.entity.Entity;
import org.spout.api.geo.World;
import org.spout.api.geo.cuboid.ChunkSnapshot;
import org.spout.api.geo.cuboid.Region;
import org.spout.api.material.BlockMaterial;

/**
 * Container for managing chunks - dependent upon using chunk snapshots, since rendering is off server thread
 */
public class SpoutMapChunkCache implements MapChunkCache {
    private World w;
    private DynmapWorld dw;
    private ListIterator<DynmapChunk> iterator;
    private List<VisibilityLimit> visible_limits = null;
    private List<VisibilityLimit> hidden_limits = null;
    private boolean isempty = true;
    private int x_min, x_max, y_min, y_max, z_min, z_max;
    private int x_dim, xz_dim;
    private ChunkSnapshot[] snaparray; /* Index = (x-x_min) + ((z-z_min)*x_dim) + ((y-ymin)*xz_dom) */
    private List<DynmapChunk> chunks;
    private ChunkSnapshot EMPTY;
    
    private int chunks_read;    /* Number of chunks actually loaded */
    private int chunks_attempted;   /* Number of chunks attempted to load */
    private long total_loadtime;    /* Total time loading chunks, in nanoseconds */
    
    private long exceptions;
    
    private static final BlockStep unstep[] = { BlockStep.X_MINUS, BlockStep.Y_MINUS, BlockStep.Z_MINUS,
        BlockStep.X_PLUS, BlockStep.Y_PLUS, BlockStep.Z_PLUS };
    
    /**
     * Iterator for traversing map chunk cache (base is for non-snapshot)
     */
    public class OurMapIterator implements MapIterator {
        private int x, y, z;  
        private int chunkindex, bx, by, bz, off;  
        private ChunkSnapshot snap;
        private short[] snapids;
        private short[] snapdata;
        private BlockStep laststep;
        private int worldheight;

        OurMapIterator(int x0, int y0, int z0) {
            initialize(x0, y0, z0);
            worldheight = w.getHeight();
        }
        public final void initialize(int x0, int y0, int z0) {
            this.x = x0;
            this.y = y0;
            this.z = z0;
            this.bx = x0 & 0xF;
            this.by = y0 & 0xF;
            this.bz = z0 & 0xF;
            this.chunkindex = ((x >> 4) - x_min) + (((z >> 4) - z_min) * x_dim) + ((y >> 4) * xz_dim);
            this.off = (bx<<8) + (bz << 4) + by;
            snap = getSnap(x, y, z);
            snapids = snap.getBlockIds();
            snapdata = snap.getBlockData();
            laststep = BlockStep.Y_MINUS;
        }
        public final int getBlockTypeID() {
            return snapids[off];
        }
        public final int getBlockData() {
            return snapdata[off];
        }
        private final ChunkSnapshot getSnap(int x, int y, int z) {
            int idx = ((x>>4) - x_min) + (((z >> 4) - z_min) * x_dim) + ((y >> 4) * xz_dim);
            try {
                return snaparray[idx];
            } catch (ArrayIndexOutOfBoundsException ioobx) {
                exceptions++;
                return EMPTY;
            }
        }
        public int getBlockSkyLight() {
            //TODO - no sky light API
            return 15;
        }
        public final int getBlockEmittedLight() {
            //TODO - no emitted light API
            return 0;
        }
        public final BiomeMap getBiome() {
            //TODO
            return BiomeMap.NULL;
        }
        public double getRawBiomeTemperature() {
            //TODO
            return 0.5;
        }
        public double getRawBiomeRainfall() {
            //TODO
            return 0.5;
        }
        /**
         * Step current position in given direction
         */
        public final void stepPosition(BlockStep step) {
            switch(step.ordinal()) {
            case 0:
                x++;
                bx++;
                off+=256;
                if(bx == 16) {  /* Next chunk? */
                    try {
                        bx = 0;
                        off -= 16*256;
                        chunkindex++;
                        snap = snaparray[chunkindex];
                    } catch (ArrayIndexOutOfBoundsException aioobx) {
                        snap = EMPTY;
                        exceptions++;
                    }
                    snapids = snap.getBlockIds();
                    snapdata = snap.getBlockData();
                }
                break;
            case 1:
                y++;
                by++;
                off++;
                if(by == 16) {
                    try {
                        by = 0;
                        off-=16;
                        chunkindex += xz_dim;
                        snap = snaparray[chunkindex];
                    } catch (ArrayIndexOutOfBoundsException aioobx) {
                        snap = EMPTY;
                        exceptions++;
                    }
                    snapids = snap.getBlockIds();
                    snapdata = snap.getBlockData();
                }
                break;
            case 2:
                z++;
                bz++;
                off+=16;
                if(bz == 16) {  /* Next chunk? */
                    try {
                        bz = 0;
                        off -= 256;
                        chunkindex += x_dim;
                        snap = snaparray[chunkindex];
                    } catch (ArrayIndexOutOfBoundsException aioobx) {
                        snap = EMPTY;
                        exceptions++;
                    }
                    snapids = snap.getBlockIds();
                    snapdata = snap.getBlockData();
                }
                break;
            case 3:
                x--;
                bx--;
                off-=256;
                if(bx == -1) {  /* Next chunk? */
                    try {
                        bx = 15;
                        off += 16*256;
                        chunkindex--;
                        snap = snaparray[chunkindex];
                    } catch (ArrayIndexOutOfBoundsException aioobx) {
                        snap = EMPTY;
                        exceptions++;
                    }
                    snapids = snap.getBlockIds();
                    snapdata = snap.getBlockData();
                }
                break;
            case 4:
                y--;
                by--;
                off--;
                if(by == -1) {
                    by = 15;
                    off+=16;
                    chunkindex -= xz_dim;
                    try {
                        snap = snaparray[chunkindex];
                    } catch (ArrayIndexOutOfBoundsException aioobx) {
                        snap = EMPTY;
                        exceptions++;
                    }
                    snapids = snap.getBlockIds();
                    snapdata = snap.getBlockData();
                }
                break;
            case 5:
                z--;
                bz--;
                off-=16;
                if(bz == -1) {  /* Next chunk? */
                    try {
                        bz = 15;
                        off += 256;
                        chunkindex -= x_dim;
                        snap = snaparray[chunkindex];
                    } catch (ArrayIndexOutOfBoundsException aioobx) {
                        snap = EMPTY;
                        exceptions++;
                    }
                    snapids = snap.getBlockIds();
                    snapdata = snap.getBlockData();
                }
                break;
            }
            laststep = step;
        }
        /**
         * Unstep current position to previous position
         */
        public BlockStep unstepPosition() {
            BlockStep ls = laststep;
            stepPosition(unstep[ls.ordinal()]);
            return ls;
        }
        /**
         * Unstep current position in oppisite director of given step
         */
        public void unstepPosition(BlockStep s) {
            stepPosition(unstep[s.ordinal()]);
        }
        public final void setY(int y) {
            if(y > this.y)
                laststep = BlockStep.Y_PLUS;
            else
                laststep = BlockStep.Y_MINUS;
            this.y = y;
        }
        public final int getX() {
            return x;
        }
        public final int getY() {
            return y;
        }
        public final int getZ() {
            return z;
        }
        public final int getBlockTypeIDAt(BlockStep s) {
            BlockStep ls = laststep;
            stepPosition(s);
            int tid = snapids[off];
            unstepPosition();
            laststep = ls;
            return tid;
        }
        public BlockStep getLastStep() {
            return laststep;
        }
        public int countSmoothedSwampBiomes() {
            //TODO
            return 0;
        }
        public int getWorldHeight() {
            return worldheight;
        }
     }
    private static class EmptySnapshot extends ChunkSnapshot {
        private short[] zero = new short[16*16*16];
        public EmptySnapshot(World world, float x, float y, float z) {
            super(world, x, y, z);
        }
        public BlockMaterial getBlockMaterial(int x, int y, int z) {
            return null;
        }
        public short getBlockId(int x, int y, int z) {
            return 0;
        }
        public short getBlockData(int x, int y, int z) {
            return 0;
        }
        @Override
        public short[] getBlockIds() {
            return zero;
        }

        @Override
        public short[] getBlockData() {
            return zero;
        }
        @Override
        public Region getRegion() {
            return null;
        }
        public Set<Entity> getEntities() {
            return null;
        }
    }
    /**
     * Construct empty cache
     */
    public SpoutMapChunkCache() {
    }
    public void setChunks(SpoutWorld dw, List<DynmapChunk> chunks) {
        w = dw.getWorld();
        this.chunks = chunks;
        this.EMPTY = new EmptySnapshot(w,0,0,0);
        this.y_min = 0;
        this.y_max = (w.getHeight() / 16)-1;
        /* Compute range */
        if(chunks.size() == 0) {
            this.x_min = 0;
            this.x_max = 0;
            this.z_min = 0;
            this.z_max = 0;
            x_dim = 1;            
        }
        else {
            x_min = x_max = chunks.get(0).x;
            z_min = z_max = chunks.get(0).z;
            for(DynmapChunk c : chunks) {
                if(c.x > x_max)
                    x_max = c.x;
                if(c.x < x_min)
                    x_min = c.x;
                if(c.z > z_max)
                    z_max = c.z;
                if(c.z < z_min)
                    z_min = c.z;
            }
            x_dim = x_max - x_min + 1;            
        }
        xz_dim = x_dim * (z_max - z_min + 1);
    
        snaparray = new ChunkSnapshot[x_dim * (z_max-z_min+1) * (y_max-y_min+1)];

    }
    public int loadChunks(int max_to_load) {
        long t0 = System.nanoTime();
        int cnt = 0;
        if(iterator == null)
            iterator = chunks.listIterator();
        
        DynmapCore.setIgnoreChunkLoads(true);
        // Load the required chunks.
        while((cnt < max_to_load) && iterator.hasNext()) {
            DynmapChunk chunk = iterator.next();
            boolean vis = true;
            if(visible_limits != null) {
                vis = false;
                for(VisibilityLimit limit : visible_limits) {
                    if((chunk.x >= limit.x0) && (chunk.x <= limit.x1) && (chunk.z >= limit.z0) && (chunk.z <= limit.z1)) {
                        vis = true;
                        break;
                    }
                }
            }
            if(vis && (hidden_limits != null)) {
                for(VisibilityLimit limit : hidden_limits) {
                    if((chunk.x >= limit.x0) && (chunk.x <= limit.x1) && (chunk.z >= limit.z0) && (chunk.z <= limit.z1)) {
                        vis = false;
                        break;
                    }
                }
            }
            chunks_attempted++;
            /* Loop through chunks in Y axis */
            for(int yy = 0; yy <= y_max; yy++) {
                org.spout.api.geo.cuboid.Chunk c = w.getChunk(chunk.x, yy, chunk.z, false);
                ChunkSnapshot b = null;
                if(c != null) {
                    b = c.getSnapshot(false);
                }
                snaparray[(chunk.x-x_min) + (chunk.z - z_min)*x_dim + (yy*xz_dim)] = b;
            }
            cnt++;
        }
        DynmapCore.setIgnoreChunkLoads(false);

        if(iterator.hasNext() == false) {   /* If we're done */
            isempty = true;
            /* Fill missing chunks with empty dummy chunk */
            for(int i = 0; i < snaparray.length; i++) {
                if(snaparray[i] == null)
                    snaparray[i] = EMPTY;
                else if(snaparray[i] != EMPTY)
                    isempty = false;
            }
        }
        total_loadtime += System.nanoTime() - t0;

        return cnt;
    }
    /**
     * Test if done loading
     */
    public boolean isDoneLoading() {
        if(iterator != null)
            return !iterator.hasNext();
        return false;
    }
    /**
     * Test if all empty blocks
     */
    public boolean isEmpty() {
        return isempty;
    }
    /**
     * Unload chunks
     */
    public void unloadChunks() {
        for(int i = 0; i < snaparray.length; i++) {
            snaparray[i] = null;
        }
    }
    /**
     * Get block ID at coordinates
     */
    public int getBlockTypeID(int x, int y, int z) {
        ChunkSnapshot ss = snaparray[((x>>4) - x_min) + ((z>>4) - z_min) * x_dim + ((y>>4) * xz_dim)];
        return ss.getBlockIds()[((x&0xF)<<8)|((z&0xF)<<4)|(y&0xF)];
    }
    /**
     * Get block data at coordiates
     */
    public byte getBlockData(int x, int y, int z) {
        ChunkSnapshot ss = snaparray[((x>>4) - x_min) + ((z>>4) - z_min) * x_dim + ((y>>4) * xz_dim)];
        return (byte)ss.getBlockData()[((x&0xF)<<8)|((z&0xF)<<4)|(y&0xF)];
    }
    /* Get sky light level
     */
    public int getBlockSkyLight(int x, int y, int z) {
        //TODO
        return 15;
    }
    /* Get emitted light level
     */
    public int getBlockEmittedLight(int x, int y, int z) {
        //TODO
        return 0;
    }
    public BiomeMap getBiome(int x, int z) {
        //TODO
        return BiomeMap.NULL;
    }
    public double getRawBiomeTemperature(int x, int z) {
        //TODO
        return 0.5;
    }
    public double getRawBiomeRainfall(int x, int z) {
        //TODO
        return 0.5;
    }

    /**
     * Get cache iterator
     */
    public MapIterator getIterator(int x, int y, int z) {
        return new OurMapIterator(x, y, z);
    }
    /**
     * Set hidden chunk style (default is FILL_AIR)
     */
    public void setHiddenFillStyle(HiddenChunkStyle style) {
    }
    /**
     * Set autogenerate - must be done after at least one visible range has been set
     */
    public void setAutoGenerateVisbileRanges(DynmapWorld.AutoGenerateOption generateopt) {
        if((generateopt != DynmapWorld.AutoGenerateOption.NONE) && ((visible_limits == null) || (visible_limits.size() == 0))) {
            Log.severe("Cannot setAutoGenerateVisibleRanges() without visible ranges defined");
            return;
        }
    }
    /**
     * Add visible area limit - can be called more than once 
     * Needs to be set before chunks are loaded
     * Coordinates are block coordinates
     */
    public void setVisibleRange(VisibilityLimit lim) {
        VisibilityLimit limit = new VisibilityLimit();
        if(lim.x0 > lim.x1) {
            limit.x0 = (lim.x1 >> 4); limit.x1 = ((lim.x0+15) >> 4);
        }
        else {
            limit.x0 = (lim.x0 >> 4); limit.x1 = ((lim.x1+15) >> 4);
        }
        if(lim.z0 > lim.z1) {
            limit.z0 = (lim.z1 >> 4); limit.z1 = ((lim.z0+15) >> 4);
        }
        else {
            limit.z0 = (lim.z0 >> 4); limit.z1 = ((lim.z1+15) >> 4);
        }
        if(visible_limits == null)
            visible_limits = new ArrayList<VisibilityLimit>();
        visible_limits.add(limit);
    }
    /**
     * Add hidden area limit - can be called more than once 
     * Needs to be set before chunks are loaded
     * Coordinates are block coordinates
     */
    public void setHiddenRange(VisibilityLimit lim) {
        VisibilityLimit limit = new VisibilityLimit();
        if(lim.x0 > lim.x1) {
            limit.x0 = (lim.x1 >> 4); limit.x1 = ((lim.x0+15) >> 4);
        }
        else {
            limit.x0 = (lim.x0 >> 4); limit.x1 = ((lim.x1+15) >> 4);
        }
        if(lim.z0 > lim.z1) {
            limit.z0 = (lim.z1 >> 4); limit.z1 = ((lim.z0+15) >> 4);
        }
        else {
            limit.z0 = (lim.z0 >> 4); limit.z1 = ((lim.z1+15) >> 4);
        }
        if(hidden_limits == null)
            hidden_limits = new ArrayList<VisibilityLimit>();
        hidden_limits.add(limit);
    }

    public boolean setChunkDataTypes(boolean blockdata, boolean biome, boolean highestblocky, boolean rawbiome) {
        return true;
    }

    public DynmapWorld getWorld() {
        return dw;
    }

    public int getChunksLoaded() {
        return chunks_read;
    }

    public int getChunkLoadsAttempted() {
        return chunks_attempted;
    }

    public long getTotalRuntimeNanos() {
        return total_loadtime;
    }

    public long getExceptionCount() {
        return exceptions;
    }

}