package org.dynmap.spout;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.ListIterator;

import org.dynmap.DynmapChunk;
import org.dynmap.DynmapCore;
import org.dynmap.DynmapWorld;
import org.dynmap.common.BiomeMap;
import org.dynmap.hdmap.HDBlockModels;
import org.dynmap.renderer.RenderPatchFactory;
import org.dynmap.utils.MapChunkCache;
import org.dynmap.utils.MapIterator;
import org.dynmap.utils.BlockStep;
import org.spout.api.component.type.BlockComponent;
import org.spout.api.datatable.SerializableMap;
import org.spout.api.entity.EntitySnapshot;
import org.spout.api.generator.biome.Biome;
import org.spout.api.generator.biome.BiomeManager;
import org.spout.api.geo.LoadOption;
import org.spout.api.geo.World;
import org.spout.api.geo.cuboid.Chunk;
import org.spout.api.geo.cuboid.ChunkSnapshot;
import org.spout.api.geo.cuboid.ChunkSnapshot.EntityType;
import org.spout.api.geo.cuboid.ChunkSnapshot.ExtraData;
import org.spout.api.geo.cuboid.ChunkSnapshot.SnapshotType;
import org.spout.api.geo.cuboid.Region;
import org.spout.api.material.BlockMaterial;
import org.spout.api.util.cuboid.CuboidLightBuffer;
import org.spout.vanilla.material.VanillaMaterial;
import org.dynmap.utils.VisibilityLimit;

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
    
    private static int[] blkidmap = null;    
    
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
        private byte[] snapemit;
        private byte[] snapsky;
        private BlockStep laststep;
        private int worldheight;

        OurMapIterator(int x0, int y0, int z0) {
            initialize(x0, y0, z0);
            worldheight = SpoutWorld.WORLDHEIGHT;
        }
        @Override
        public final void initialize(int x0, int y0, int z0) {
            this.x = x0;
            this.y = y0;
            this.z = z0;
            this.bx = x0 & 0xF;
            this.by = y0 & 0xF;
            this.bz = z0 & 0xF;
            this.chunkindex = ((x >> 4) - x_min) + (((z >> 4) - z_min) * x_dim) + ((y >> 4) * xz_dim);
            this.off = (by<<8) + (bz << 4) + bx;
            snap = getSnap(x, y, z);
            snapids = snap.getBlockIds();
            snapdata = snap.getBlockData();
            snapemit = snap.getBlockLight();
            //TODO - when working in Spout snapsky = snap.getSkyLight();
            snapsky = ffbyte;
            laststep = BlockStep.Y_MINUS;
        }
        @Override
        public final int getBlockTypeID() {
            return blkidmap[snapids[off]];
        }
        @Override
        public final int getBlockData() {
            return snapdata[off] & 0xF;//TODO - is this right?
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
        @Override
        public int getBlockSkyLight() {
            int light = snapsky[off >> 1];
            return 0x0F & (light >> (4 - (4 * (off & 1))));
        }
        @Override
        public final int getBlockEmittedLight() {
            int light = snapemit[off >> 1];
            return 0x0F & (light >> (4 - (4 * (off & 1))));
        }
        @Override
        public final BiomeMap getBiome() {
            //TODO
            return BiomeMap.NULL;
        }
        /**
         * Step current position in given direction
         */
        @Override
        public final void stepPosition(BlockStep step) {
            switch(step.ordinal()) {
            case 0:
                x++;
                bx++;
                off++;
                if(bx == 16) {  /* Next chunk? */
                    try {
                        bx = 0;
                        off -= 16;
                        chunkindex++;
                        snap = snaparray[chunkindex];
                    } catch (ArrayIndexOutOfBoundsException aioobx) {
                        snap = EMPTY;
                        exceptions++;
                    }
                    snapids = snap.getBlockIds();
                    snapdata = snap.getBlockData();
                    snapemit = snap.getBlockLight();
                    //snapsky = snap.getSkyLight();
                }
                break;
            case 1:
                y++;
                by++;
                off+=256;
                if(by == 16) {
                    try {
                        by = 0;
                        off-=16*256;
                        chunkindex += xz_dim;
                        snap = snaparray[chunkindex];
                    } catch (ArrayIndexOutOfBoundsException aioobx) {
                        snap = EMPTY;
                        exceptions++;
                    }
                    snapids = snap.getBlockIds();
                    snapdata = snap.getBlockData();
                    snapemit = snap.getBlockLight();
                    //snapsky = snap.getSkyLight();
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
                    snapemit = snap.getBlockLight();
                    //snapsky = snap.getSkyLight();
                }
                break;
            case 3:
                x--;
                bx--;
                off--;
                if(bx == -1) {  /* Next chunk? */
                    try {
                        bx = 15;
                        off += 16;
                        chunkindex--;
                        snap = snaparray[chunkindex];
                    } catch (ArrayIndexOutOfBoundsException aioobx) {
                        snap = EMPTY;
                        exceptions++;
                    }
                    snapids = snap.getBlockIds();
                    snapdata = snap.getBlockData();
                    snapemit = snap.getBlockLight();
                    //snapsky = snap.getSkyLight();
                }
                break;
            case 4:
                y--;
                by--;
                off-=256;
                if(by == -1) {
                    by = 15;
                    off+=16*256;
                    chunkindex -= xz_dim;
                    try {
                        snap = snaparray[chunkindex];
                    } catch (ArrayIndexOutOfBoundsException aioobx) {
                        snap = EMPTY;
                        exceptions++;
                    }
                    snapids = snap.getBlockIds();
                    snapdata = snap.getBlockData();
                    snapemit = snap.getBlockLight();
                    //snapsky = snap.getSkyLight();
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
                    snapemit = snap.getBlockLight();
                    //snapsky = snap.getSkyLight();
                }
                break;
            }
            laststep = step;
        }
        /**
         * Unstep current position to previous position
         */
        @Override
        public BlockStep unstepPosition() {
            BlockStep ls = laststep;
            stepPosition(unstep[ls.ordinal()]);
            return ls;
        }
        /**
         * Unstep current position in oppisite director of given step
         */
        @Override
        public void unstepPosition(BlockStep s) {
            stepPosition(unstep[s.ordinal()]);
        }
        @Override
        public final void setY(int y) {
            if(y > this.y)
                laststep = BlockStep.Y_PLUS;
            else
                laststep = BlockStep.Y_MINUS;
            this.y = y;
        }
        @Override
        public final int getX() {
            return x;
        }
        @Override
        public final int getY() {
            return y;
        }
        @Override
        public final int getZ() {
            return z;
        }
        @Override
        public final int getBlockTypeIDAt(BlockStep s) {
            BlockStep ls = laststep;
            stepPosition(s);
            int tid = blkidmap[snapids[off]];
            unstepPosition();
            laststep = ls;
            return tid;
        }
        @Override
        public BlockStep getLastStep() {
            return laststep;
        }
        @Override
        public int getWorldHeight() {
            return worldheight;
        }
        @Override
        public long getBlockKey() {
            return (((chunkindex * worldheight) + y) << 8) | (bx << 4) | bz;
        }
        @Override
        public boolean isEmptySection() {
            return (snap == EMPTY);
        }
        @Override
        public int getSmoothGrassColorMultiplier(int[] colormap) {
            BiomeMap bm = getBiome();
            return bm.getModifiedGrassMultiplier(colormap[bm.biomeLookup()]);
        }
        @Override
        public int getSmoothFoliageColorMultiplier(int[] colormap) {
            BiomeMap bm = getBiome();
            return bm.getModifiedFoliageMultiplier(colormap[bm.biomeLookup()]);
        }
        @Override
        public int getSmoothColorMultiplier(int[] colormap, int[] swampmap) {
            BiomeMap bm = getBiome();
            if(bm == BiomeMap.SWAMPLAND)
                return swampmap[bm.biomeLookup()];
            else
                return colormap[bm.biomeLookup()];
        }
        @Override
        public int getSmoothWaterColorMultiplier() {
            BiomeMap bm = getBiome();
            return bm.getWaterColorMult();
        }
        @Override
        public int getSmoothWaterColorMultiplier(int[] colormap) {
            BiomeMap bm = getBiome();
            return colormap[bm.biomeLookup()];
        }
        @Override
        public RenderPatchFactory getPatchFactory() {
            return HDBlockModels.getPatchDefinitionFactory();
        }
        @Override
        public Object getBlockTileEntityField(String fieldId) {
            return null;
        }
        @Override
        public int getBlockTypeIDAt(int xoff, int yoff, int zoff) {
            int xx = this.x + xoff;
            int yy = this.y + yoff;
            int zz = this.z + zoff;
            int idx = ((xx >> 4) - x_min) + (((zz >> 4) - z_min) * x_dim) + ((yy >> 4) * xz_dim);
            try {
                return blkidmap[snaparray[idx].getBlockIds()[ ((yy & 0xF)<<8) + ((zz & 0xF) << 4) + (xx & 0xF)]];
            } catch (Exception x) {
                return 0;
            }
        }
        @Override
        public int getBlockDataAt(int xoff, int yoff, int zoff) {
            int xx = this.x + xoff;
            int yy = this.y + yoff;
            int zz = this.z + zoff;
            int idx = ((xx >> 4) - x_min) + (((zz >> 4) - z_min) * x_dim) + ((yy >> 4) * xz_dim);
            try {
                return snaparray[idx].getBlockData(xx & 0xF, yy, zz & 0xF);
            } catch (Exception x) {
                return 0;
            }
        }
        @Override
        public Object getBlockTileEntityFieldAt(String fieldId, int xoff,
                int yoff, int zoff) {
            return null;
        }
    }
    private static final short[] zero = new short[16*16*16];
    private static final byte[] zerobyte = new byte[16*16*16/2];
    private static final byte[] ffbyte = new byte[16*16*16/2];

    static {
        Arrays.fill(ffbyte, (byte)0xff);
    }
    
    private static class EmptySnapshot extends ChunkSnapshot {
        public EmptySnapshot(World world, float x, float y, float z) {
            super(world, x, y, z);
        }
        @Override
        public BlockMaterial getBlockMaterial(int x, int y, int z) {
            return null;
        }
        @Override
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
        @Override
        public byte getBlockLight(int x, int y, int z) {
            return 0;
        }
        @Override
        public byte[] getBlockLight() {
            return zerobyte;
        }
        @Override
        public byte[] getSkyLight() {
            return ffbyte;
        }
        @Override
        public byte getBlockSkyLight(int x, int y, int z) {
            return 0xF;
        }
        @Override
        public int getBlockFullState(int x, int y, int z) {
            return 0;
        }
        @Override
        public boolean isPopulated() {
            return false;
        }
        @Override
        public BiomeManager getBiomeManager() {
            return null;
        }
        @Override
        public byte getBlockSkyLightRaw(int x, int y, int z) {
            return 0;
        }
        @Override
        public Biome getBiome(int x, int y, int z) {
            return null;
        }
        @Override
        public List<EntitySnapshot> getEntities() {
            return null;
        }
        @Override
        public SerializableMap getDataMap() {
            return null;
        }
        @Override
        public List<BlockComponentSnapshot> getBlockComponents() {
            return null;
        }
        @Override
        public CuboidLightBuffer[] getLightBuffers() {
            return null;
        }
        @Override
        public CuboidLightBuffer getLightBuffer(short arg0) {
            return null;
        }
    }
    /**
     * Construct empty cache
     */
    public SpoutMapChunkCache() {
        if(blkidmap == null) {
            initMaterialMap();
        }
    }
    public void setChunks(SpoutWorld dw, List<DynmapChunk> chunks) {
        w = dw.getWorld();
        this.chunks = chunks;
        this.EMPTY = new EmptySnapshot(w,0,0,0);
        this.y_min = 0;
        this.y_max = (SpoutWorld.WORLDHEIGHT / 16)-1;
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
                    if (limit.doIntersectChunk(chunk.x, chunk.z)) {
                        vis = true;
                        break;
                    }
                }
            }
            if(vis && (hidden_limits != null)) {
                for(VisibilityLimit limit : hidden_limits) {
                    if (limit.doIntersectChunk(chunk.x, chunk.z)) {
                        vis = false;
                        break;
                    }
                }
            }
            chunks_attempted++;

            int rx = chunk.x >> Region.CHUNKS.BITS;
            int ry = 0;
            int rz = chunk.z >> Region.CHUNKS.BITS;
            Region r = null;
            /* Loop through chunks in Y axis */
            for(int yy = 0; yy <= y_max; yy++) {
                int nry = yy >> Region.CHUNKS.BITS;
                if(nry != ry) {
                    r = null;
                    ry = nry;
                }
                if(r == null) {
                    r = w.getRegion(rx, ry, rz, LoadOption.LOAD_ONLY);
                }
                if(r == null) continue;
                Chunk c = r.getChunk(chunk.x & 0xF, yy & 0xF, chunk.z & 0xF, LoadOption.LOAD_ONLY);
                ChunkSnapshot b = null;
                if(c != null) {
                    b = c.getSnapshot(SnapshotType.BOTH, EntityType.NO_ENTITIES, ExtraData.NO_EXTRA_DATA);
                    //if(!loaded) {
                    //    w.unloadChunk(chunk.x, yy, chunk.z, false);
                    //}
                    /* Test if chunk is empty */
                    if(b != null) {
                        short[] bids = b.getBlockIds();
                        byte[] blight = b.getBlockLight();
                        byte[] slight = b.getSkyLight();
                        if(Arrays.equals(bids, zero) && Arrays.equals(blight, zerobyte) && Arrays.equals(slight, ffbyte)) {
                            b = EMPTY;
                        }
                    }
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
     * Add visible area limit - can be called more than once 
     * Needs to be set before chunks are loaded
     * Coordinates are block coordinates
     */
    public void setVisibleRange(VisibilityLimit lim) {
        if(visible_limits == null)
            visible_limits = new ArrayList<VisibilityLimit>();
        visible_limits.add(lim);
    }
    /**
     * Add hidden area limit - can be called more than once 
     * Needs to be set before chunks are loaded
     * Coordinates are block coordinates
     */
    public void setHiddenRange(VisibilityLimit lim) {
        if(hidden_limits == null)
            hidden_limits = new ArrayList<VisibilityLimit>();
        hidden_limits.add(lim);
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
    public boolean isEmptySection(int sx, int sy, int sz) {
        ChunkSnapshot ss = snaparray[(sx - x_min) + (sz - z_min) * x_dim + (sy * xz_dim)];
        return (ss == EMPTY);
    }
    public static void initMaterialMap() {
        int[] bids = new int[0x8000];
        for(int i = 0; i < bids.length; i++) {
            BlockMaterial bm = BlockMaterial.get((short)i);
            if((bm != null) && (bm instanceof VanillaMaterial)) {
                VanillaMaterial vm = (VanillaMaterial)bm;
                bids[i] = vm.getMinecraftId();
            }
        }
        blkidmap = bids;
    }
}
