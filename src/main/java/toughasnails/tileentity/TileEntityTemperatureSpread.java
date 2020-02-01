/*******************************************************************************
 * Copyright 2016, the Biomes O' Plenty Team
 * 
 * This work is licensed under a Creative Commons Attribution-NonCommercial-NoDerivatives 4.0 International Public License.
 * 
 * To view a copy of this license, visit http://creativecommons.org/licenses/by-nc-nd/4.0/.
 ******************************************************************************/
package toughasnails.tileentity;

import java.util.*;

import com.google.common.collect.Sets;

import net.minecraft.block.Block;
import net.minecraft.block.BlockColored;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.item.EnumDyeColor;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTUtil;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ITickable;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.apache.commons.lang3.Validate;
import toughasnails.api.TANCapabilities;
import toughasnails.api.stat.capability.ITemperature;
import toughasnails.api.temperature.ITemperatureRegulator;
import toughasnails.api.temperature.Temperature;
import toughasnails.block.BlockTANTemperatureCoil;
import toughasnails.core.ToughAsNails;

public class TileEntityTemperatureSpread extends TileEntity implements ITickable, ITemperatureRegulator
{
    public static final int MAX_SPREAD_DISTANCE = 50;
    public static final int RATE_MODIFIER = -500;
    public static final boolean ENABLE_DEBUG = true;
    public static final boolean HORIZONTAL = true;

    private HashMap<BlockPos, Integer> spreadedBlocks;
    private HashMap<BlockPos, Integer> edgeBlocks;

    private HashMap<BlockPos, Integer> debugLayer;
    
    private int updateTicks;
    private int temperatureModifier;
    private LinkedList<SpreadBlock> invalidatedPos = new LinkedList<>();

    private AxisAlignedBB maxSpreadBox;
    
    public TileEntityTemperatureSpread() 
    {
        /**
         * We could reserve (MAX_SPREAD_DISTANCE * 2 + 1) ^ 3
         * But actually we should allocate memory for only potential filled
         * Blocks, so considering we have diagonals
         * ##X##
         * #XXX#
         * XXXXX
         * #XXX#
         * ##X##
         * Actual size will be 1353601 for 101x101x101 (distance = 50): nearest 2^21 = 2097152
         * Or 171801 for 51x51x51 (distance = 25): nearest 2^18 = 262144
         * Considering that it's unlikely will be more than 50% of the available area let's keep it to
         * 2^16 and 2^18 for 51 and 101 respectively with 80% load factor
         * Spasibo Artyom
         */
        this.spreadedBlocks = new HashMap<>((int)Math.pow(2, 18), 0.8f);
        this.edgeBlocks = new HashMap<>((int)Math.pow(2, 8));

        if (TileEntityTemperatureSpread.ENABLE_DEBUG) {
            this.debugLayer = new HashMap<>();
            ToughAsNails.logger.warn("TAN New Climatizer");
        }
    }
    
    public TileEntityTemperatureSpread(int temperatureModifier)
    {
        this();
        
        this.temperatureModifier = temperatureModifier;
    }
    
    //TODO: Stagger updates if necessary, so verification occurs slower away from the base position
    //Doesn't really seem necessary at the moment, it appears to be fast enough
    @Override
    public void update() 
    {
        World world = this.getWorld();

        // We don't need to handle such calculations on client side
        if (world.isRemote) {
            return;
        }

        // If the block isn't powered, we should reset
        if (!world.getBlockState(this.getPos()).getValue(BlockTANTemperatureCoil.POWERED))
        {
            reset();
            return;
        }

        //Verify every second
        if (++updateTicks % 20 == 0) {
            if (this.spreadedBlocks.isEmpty()) {
                this.fill();
                this.drawDebugLayer(world);
            }

            //Iterate over all nearby players
            for (EntityPlayer player : world.getEntitiesWithinAABB(EntityPlayer.class, this.getMaxSpreadBox()))
            {
                BlockPos delta = player.getPosition().subtract(this.getPos());
                int distance = Math.abs(delta.getX()) + Math.abs(delta.getY()) + Math.abs(delta.getZ());
                boolean collided = false;

                collided = this.spreadedBlocks.get(player.getPosition()) != null;
                
                //Apply temperature modifier if collided
                if (collided)
                {
                    ToughAsNails.logger.warn("TAN Climatized Player Strength: " + this.spreadedBlocks.get(player.getPosition()));

                    ITemperature temperature = player.getCapability(TANCapabilities.TEMPERATURE, null);
                    
                    //Apply modifier for 5 seconds
                    temperature.applyModifier("Climatisation", this.temperatureModifier, RATE_MODIFIER, 3 * 20);
                }
            }
        }

        // Every two seconds - check edges
        // Every ten seconds - check spread value
        // If invalidated, reindex area once per three seconds
        if ((updateTicks % (2 * 20)) == 0) {
            this.reindexEdges();

            if ((updateTicks % (5 * 20)) == 0) {
                this.reindexSpreadValue();
            }

            if (!this.invalidatedPos.isEmpty()) {
                ToughAsNails.logger.warn("TAN Starting Cleanup, Invalidated: " + this.invalidatedPos.size());
                LinkedList<SpreadBlock> refillQueue = this.cleanupInvalidated();
                ToughAsNails.logger.warn("TAN Starting Refill, In Queue: " + refillQueue.size());
                this.fill(refillQueue);
                ToughAsNails.logger.warn("TAN End Refill");
                this.drawDebugLayer(world);
            }
        }
    }

    public void reset()
    {
        this.edgeBlocks.clear();
        this.spreadedBlocks.clear();
    }

    private void reindexEdges()
    {
        Integer invalidatedCount = 0;

        // Use iterators to be able to remove items while working
        // Eventually it should be removed on invalidation,
        // Otherwise path will be blocked and old, blocked areas will be filled
        for (Iterator<BlockPos> edgeBlocksIterator = this.edgeBlocks.keySet().iterator(); edgeBlocksIterator.hasNext(); ) {
            BlockPos pos = edgeBlocksIterator.next();

            if (this.canFill(pos)) {
                invalidatedCount += this.invalidateAround(pos);
            }
        }

        if (TileEntityTemperatureSpread.ENABLE_DEBUG && invalidatedCount > 0) {
            ToughAsNails.logger.warn("TAN Invalidated Edges: " + invalidatedCount);
        }
    }

    private void reindexSpreadValue()
    {
        Integer invalidatedCount = 0;

        // Use iterators to be able to remove items while working
        // Eventually it should be removed on invalidation,
        // Otherwise path will be blocked and old, blocked areas will be filled
        for (Iterator<BlockPos> spreadBlocksIterator = this.spreadedBlocks.keySet().iterator(); spreadBlocksIterator.hasNext(); ) {
            BlockPos pos = spreadBlocksIterator.next();

            if (!this.canFill(pos)) {
                invalidatedCount += this.invalidateAround(pos);
            }
        }

        if (TileEntityTemperatureSpread.ENABLE_DEBUG && invalidatedCount > 0) {
            ToughAsNails.logger.warn("TAN Invalidated Spread: " + invalidatedCount);
        }
    }

    private Integer invalidateAround(BlockPos pos)
    {
        Integer invalidatedCount = 0;

        // We're invalidating not the block itself - we just removing it
        // We invalidating blocks next to it to rebuild value
        for (EnumFacing facing : EnumFacing.values()) {
            if (TileEntityTemperatureSpread.HORIZONTAL && (facing == EnumFacing.DOWN || facing == EnumFacing.UP)) {
                continue;
            }

            BlockPos offsetPos = pos.offset(facing);

            if (this.spreadedBlocks.get(offsetPos) != null) {
                SpreadBlock invalidatedSpreadBlock = new SpreadBlock(offsetPos, this.spreadedBlocks.get(offsetPos));
                this.invalidatedPos.add(invalidatedSpreadBlock);
                invalidatedCount++;
            }
        }

        return invalidatedCount;
    }

    /**
     * Cleanup STARTING from invalidated, do not remove invalidated blocks
     * themselves, we invalidate blocks around
     *
     * @return
     */
    private LinkedList<SpreadBlock> cleanupInvalidated()
    {
        LinkedList<SpreadBlock> refillQueue = new LinkedList<>();
        LinkedList<SpreadBlock> cleanupQueue = new LinkedList<>();
        Integer cleaned = 0;

        // Sort by strength so we may not need to cleanup some blocks that are already dependable
        this.invalidatedPos.sort(Comparator.comparing(SpreadBlock::getStrength).reversed());

        for (SpreadBlock invalidatedBlock : this.invalidatedPos) {
            Integer maxStrength = invalidatedBlock.getStrength();
            boolean stillValid = true;

            // Already reindexed and have different strength comparing to the state when we invalidated
            // Remember that we sorting before cleanup
            // OR already removed by invalidated block with more strength
            // This is not only optimizes process, but also removes less
            // Powerful invalidated blocks
            if (this.spreadedBlocks.get(invalidatedBlock.getPos()) == null || this.spreadedBlocks.get(invalidatedBlock.getPos()) > maxStrength) {
                stillValid = false;
            } else {
                refillQueue.add(invalidatedBlock);
                ToughAsNails.logger.warn("TAN Added to Queue: " + invalidatedBlock.getPos());
            }

            cleanupQueue.add(invalidatedBlock);

            do {
                SpreadBlock cleaningBlock = cleanupQueue.pop();
                this.spreadedBlocks.remove(cleaningBlock.getPos());
                cleaned++;

                ToughAsNails.logger.warn("TAN Cleaned: " + cleaningBlock.getPos());

                for (EnumFacing facing : EnumFacing.values()) {
                    BlockPos offsetPos = cleaningBlock.getPos().offset(facing);

                    // Just remove, we'll rebuild edges
                    if (this.edgeBlocks.get(offsetPos) != null) {
                        this.edgeBlocks.remove(offsetPos);
                    }

                    // Don't need to cleanup what's not indexed or what have more strength cause we removed
                    // Block that is already at some distance
                    if (this.spreadedBlocks.get(offsetPos) == null || this.spreadedBlocks.get(offsetPos) > maxStrength) {
                        continue;
                    }

                    // If it has same strength nearby -- we need to put it into queue to make sure we reindex from all edges
                    if (stillValid && this.spreadedBlocks.get(offsetPos).equals(maxStrength)) {
                        refillQueue.add(new SpreadBlock(offsetPos, maxStrength));
                        ToughAsNails.logger.warn("TAN Added to Queue: " + offsetPos);
                    } else /* if *(this.spreadedBlocks.get(offsetPos) < maxStrength) - no need to check, see above */ {
                        SpreadBlock cleanupBlock = new SpreadBlock(offsetPos, this.spreadedBlocks.get(offsetPos));
                        cleanupQueue.add(cleanupBlock);
                    }
                }
            } while (!cleanupQueue.isEmpty());

            // Restore block removed due to algorithm imperfection
            // The block was added to start cleaning queue
            // If it's still valid then continue reindex from it
            if (stillValid) {
                this.spreadedBlocks.put(invalidatedBlock.getPos(), invalidatedBlock.getStrength());
            }
        }

        if (TileEntityTemperatureSpread.ENABLE_DEBUG) {
            ToughAsNails.logger.warn("TAN Cleaned: " + cleaned);
        }

        this.invalidatedPos.clear();

        return refillQueue;
    }

    /**
     * For better performance we'll use somewhat mix of Flood fill and Forest Fire algorithms
     * Similar thing Minecraft uses for light calculation
     *
     * @todo Why not to warm up only limited value?
     */
    public void fill()
    {
        reset();

        ToughAsNails.logger.warn("TAN Lookup Heated Area");

        BlockPos originPos = this.getPos();

        SpreadBlock queueItem = new SpreadBlock(originPos, MAX_SPREAD_DISTANCE);
        LinkedList<SpreadBlock> positionsToSpread = new LinkedList<>();
        positionsToSpread.add(queueItem);

        fill(positionsToSpread);
        this.invalidatedPos.clear();

        ToughAsNails.logger.warn("TAN Lookup Heated End");
    }

    public void fill(LinkedList<SpreadBlock> positionsToSpread)
    {
        if (positionsToSpread.isEmpty()) {
            if (TileEntityTemperatureSpread.ENABLE_DEBUG) {
                ToughAsNails.logger.warn("TAN Got Empty Queue to Fill");
            }

            return;
        }

        do {
            SpreadBlock spreadBlock = positionsToSpread.pop();
            BlockPos spreadingPosition = spreadBlock.getPos();

            if (spreadBlock == null) {
                continue;
            }

            if (spreadBlock.getStrength() <= 0) {
                continue;
            }

            for (EnumFacing facing : EnumFacing.values())
            {
                if (TileEntityTemperatureSpread.HORIZONTAL && (facing == EnumFacing.DOWN || facing == EnumFacing.UP)) {
                    continue;
                }

                BlockPos offsetPos = spreadingPosition.offset(facing);

                if (this.spreadedBlocks.get(offsetPos) != null) {
                    continue;
                }

                Integer nextStrength = spreadBlock.getStrength() - 1;

                //Only attempt to update tracking for this position if there is air here.
                //Even positions already being tracked should be filled with air.
                if (this.canFill(offsetPos)) {
                    // Add to queue to check spreading
                    SpreadBlock queueItem = new SpreadBlock(offsetPos, nextStrength);
                    positionsToSpread.add(queueItem);

                    /*
                     * Add to map immediately to prevent re-checking
                     * Block positions that were in queue initially (usually only central block) will not be included because
                     * there's no need to warm block itself and quick cached queue should be already in map
                     */
                    this.spreadedBlocks.put(offsetPos, nextStrength);
                } else {
                    this.addEdge(offsetPos, nextStrength);
                }
            }
        } while (!positionsToSpread.isEmpty());

        ToughAsNails.logger.warn("TAN Spread Volume: " + this.spreadedBlocks.size());
        ToughAsNails.logger.warn("TAN Edge Blocks: " + this.edgeBlocks.size());
    }

    private boolean canFill(BlockPos pos)
    {
        //Only spread within enclosed areas, significantly reduces the impact on performance and suits the purpose of coils
        return !this.getWorld().isBlockFullCube(pos) && (!this.getWorld().canSeeSky(pos));
    }

    private void addEdge(BlockPos pos, Integer strength)
    {
        Integer currentStrength = this.edgeBlocks.get(pos);

        if (currentStrength != null && currentStrength > strength) {
            strength = currentStrength;
        }

        this.edgeBlocks.put(pos, strength);
    }

    private void drawDebugLayer(World world)
    {
        if (!TileEntityTemperatureSpread.HORIZONTAL || !TileEntityTemperatureSpread.ENABLE_DEBUG) {
            return;
        }

        IBlockState woolBlockState = Blocks.WOOL.getDefaultState();
        IBlockState airBlockState = Blocks.AIR.getDefaultState();

        for (BlockPos pos : this.debugLayer.keySet()) {
            world.setBlockState(pos, airBlockState);
        }

        this.debugLayer.clear();

        for (BlockPos pos : this.spreadedBlocks.keySet()) {
            BlockPos debugBlockPos = pos.offset(EnumFacing.DOWN);

            world.setBlockState(debugBlockPos, woolBlockState.withProperty(BlockColored.COLOR, EnumDyeColor.ORANGE));
            this.debugLayer.put(debugBlockPos, 1);
        }

        for (BlockPos pos : this.edgeBlocks.keySet()) {
            BlockPos debugBlockPos = pos.offset(EnumFacing.DOWN);

            world.setBlockState(debugBlockPos, woolBlockState.withProperty(BlockColored.COLOR, EnumDyeColor.BLUE));
            this.debugLayer.put(debugBlockPos, -1);
        }
    }

    /**
     * Cannot be defined on initialization time, should be lazy initialized.
     *
     * @return AxisAlignedBB
     */
    private AxisAlignedBB getMaxSpreadBox()
    {
        if (this.maxSpreadBox == null) {
            this.maxSpreadBox = new AxisAlignedBB(this.pos.getX() - MAX_SPREAD_DISTANCE, this.pos.getY() - MAX_SPREAD_DISTANCE, this.pos.getZ() - MAX_SPREAD_DISTANCE, this.pos.getX() + MAX_SPREAD_DISTANCE, this.pos.getY() + MAX_SPREAD_DISTANCE, this.pos.getZ() + MAX_SPREAD_DISTANCE);
        }

        return this.maxSpreadBox;
    }

    /** Returns true if verified, false if regen is required *//*
    public boolean verify()
    {
        for (Set<BlockPos> trackedPositions : this.filledPositions)
        {
            for (BlockPos pos : trackedPositions)
            {
                if (!this.canFill(pos)) return false;
            }
        }

        return true;
    }*/
    
    /*@Override
    public void readFromNBT(NBTTagCompound compound)
    {
        super.readFromNBT(compound);

        if (compound.hasKey("FilledPositions"))
        {
            this.temperatureModifier = compound.getInteger("TemperatureModifier");
            
            NBTTagCompound filledCompound = compound.getCompoundTag("FilledPositions");
            
            for (int strength = 0; strength <= MAX_SPREAD_DISTANCE; strength++)
            {
                if (!filledCompound.hasKey("Strength" + strength)) throw new IllegalArgumentException("Compound missing strength sub-compound Strength" + strength + "!");
                
                NBTTagCompound strengthCompound = filledCompound.getCompoundTag("Strength" + strength);
                this.filledPositions[strength] = readPosSet(strengthCompound);
            }
            this.spawnedEntities = Sets.newConcurrentHashSet(); //Recreate spawned entities set and repopulate later
            
            NBTTagCompound obstructedCompound = compound.getCompoundTag("ObstructedPositions");
            this.obstructedPositions = readPosSet(obstructedCompound);
        }
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound compound)
    {
        super.writeToNBT(compound);
        
        NBTTagCompound filledCompound = new NBTTagCompound();
        
        compound.setInteger("TemperatureModifier", this.temperatureModifier);
        
        for (int i = 0; i <= MAX_SPREAD_DISTANCE; i++)
        {
            NBTTagCompound strengthCompound = new NBTTagCompound();
            writePosSet(strengthCompound, this.filledPositions[i]);
            filledCompound.setTag("Strength" + i, strengthCompound);
        }
        compound.setTag("FilledPositions", filledCompound);
        
        NBTTagCompound obstructedCompound = new NBTTagCompound();
        writePosSet(obstructedCompound, this.obstructedPositions);
        compound.setTag("ObstructedPositions", obstructedCompound);
        
        return compound;
    }*/
    
    @Override
    public boolean shouldRefresh(World world, BlockPos pos, IBlockState oldState, IBlockState newSate)
    {
        //This should function as it does in Vanilla, using Forges setup appears to break fireball spawning when the state is changed
        return oldState.getBlock() != newSate.getBlock();
    }
    
    private void writePosSet(NBTTagCompound compound, Set<BlockPos> posSet)
    {
        compound.setInteger("Count", posSet.size());
        
        int index = 0;
        
        for (BlockPos pos : posSet)
        {
            compound.setTag("Pos" + index, NBTUtil.createPosTag(pos));
            index++;
        }
    }
    
    private Set<BlockPos> readPosSet(NBTTagCompound compound)
    {
        if (!compound.hasKey("Count")) throw new IllegalArgumentException("Compound is not a valid pos set");
        
        int count = compound.getInteger("Count");
        Set<BlockPos> posSet = Sets.newConcurrentHashSet();
        
        for (int i = 0; i < count; i++)
        {
            BlockPos pos = NBTUtil.getPosFromTag(compound.getCompoundTag("Pos" + i));
            if (pos != null) posSet.add(pos);
        }
        
        return posSet;
    }

    @Override
    public Temperature getRegulatedTemperature() 
    {
        return new Temperature(this.temperatureModifier);
    }

    @Override
    public boolean isPosRegulated(BlockPos pos) 
    {
        return this.spreadedBlocks.get(pos) != null;
    }

    // Immutable after creation, that's safer
    class SpreadBlock {
        private BlockPos pos;
        private Integer strength;

        public SpreadBlock(BlockPos pos, Integer strength)
        {
            Validate.notNull(pos, "Position cannot be null");
            Validate.notNull(strength, "Strength cannot be null");

            this.pos = pos;
            this.strength = strength;
        }

        public Integer getStrength()
        {
            return this.strength;
        }

        public BlockPos getPos()
        {
            return this.pos;
        }
    }
}
