/*******************************************************************************
 * Copyright 2016, the Biomes O' Plenty Team
 * 
 * This work is licensed under a Creative Commons Attribution-NonCommercial-NoDerivatives 4.0 International Public License.
 * 
 * To view a copy of this license, visit http://creativecommons.org/licenses/by-nc-nd/4.0/.
 ******************************************************************************/
package toughasnails.tileentity;

import java.util.*;

import com.google.common.base.Predicate;
import com.google.common.collect.Sets;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.projectile.EntitySmallFireball;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTUtil;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ITickable;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.apache.commons.lang3.tuple.Pair;
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
    public static final boolean ENABLE_DEBUG = false;

    private HashMap<BlockPos, Integer> heatMultiplierByPos;
    
    private int updateTicks;
    private int temperatureModifier;
    private LinkedList<AbstractMap.SimpleEntry<BlockPos,Integer>> invalidatedPos = new LinkedList<>();

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
        this.heatMultiplierByPos = new HashMap<BlockPos, Integer>((int)Math.pow(2, 18), 0.8f);
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

        // If the block isn't powered, we should reset
        if (!world.getBlockState(this.getPos()).getValue(BlockTANTemperatureCoil.POWERED))
        {
            reset();
            return;
        }

        //Verify every second
        if (++updateTicks % 20 == 0) {
            if (this.heatMultiplierByPos.isEmpty()) {
                this.fill();
            }

            //Iterate over all nearby players
            for (EntityPlayer player : world.getEntitiesWithinAABB(EntityPlayer.class, this.getMaxSpreadBox()))
            {
                BlockPos delta = player.getPosition().subtract(this.getPos());
                int distance = Math.abs(delta.getX()) + Math.abs(delta.getY()) + Math.abs(delta.getZ());
                boolean collided = false;

                collided = this.heatMultiplierByPos.get(player.getPosition()) != null;
                
                //Apply temperature modifier if collided
                if (collided)
                {
                    ToughAsNails.logger.warn("TAN Climatized Player Strength: " + this.heatMultiplierByPos.get(player.getPosition()));

                    ITemperature temperature = player.getCapability(TANCapabilities.TEMPERATURE, null);
                    
                    //Apply modifier for 5 seconds
                    temperature.applyModifier("Climatisation", this.temperatureModifier, RATE_MODIFIER, 3 * 20);
                }
            }
        }

        // If invalidated, reindex area once per three seconds
        if (updateTicks % 60 == 0 && !this.invalidatedPos.isEmpty()) {
            ToughAsNails.logger.warn("TAN Reindex Invalidated Positions");
            // @TODO: cleanup first ones with lower strength
            this.fill(this.invalidatedPos);
        }
    }

    public void reset()
    {
        this.heatMultiplierByPos.clear();
    }

    /**
     * Invalidate and mark block for quick reindex
     * @param changedBlockPos BlockPos
     */
    public void handleBlockChange(BlockPos changedBlockPos)
    {
        ToughAsNails.logger.warn("TAN Handle Block Change");

        for (EnumFacing facing : EnumFacing.values()) {
            BlockPos offsetPos = changedBlockPos.offset(facing);

            if (this.heatMultiplierByPos.get(offsetPos) != null) {
                AbstractMap.SimpleEntry<BlockPos,Integer> queueItem = new AbstractMap.SimpleEntry<>(offsetPos, this.heatMultiplierByPos.get(offsetPos));
                this.invalidatedPos.add(queueItem);
            }
        }
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

        AbstractMap.SimpleEntry<BlockPos,Integer> queueItem = new AbstractMap.SimpleEntry<>(originPos, MAX_SPREAD_DISTANCE);
        LinkedList<AbstractMap.SimpleEntry<BlockPos,Integer>> positionsToSpread = new LinkedList<>();
        positionsToSpread.add(queueItem);

        fill(positionsToSpread);
        this.invalidatedPos.clear();

        ToughAsNails.logger.warn("TAN Lookup Heated End");
    }

    public void fill(LinkedList<AbstractMap.SimpleEntry<BlockPos,Integer>> positionsToSpread)
    {
        do {
            AbstractMap.SimpleEntry<BlockPos,Integer> spreadingPair = positionsToSpread.pop();
            BlockPos spreadingPosition = spreadingPair.getKey();

            if (spreadingPair == null) {
                continue;
            }

            if (spreadingPair.getValue() <= 0) {
                continue;
            }

            for (EnumFacing facing : EnumFacing.values())
            {
                BlockPos offsetPos = spreadingPosition.offset(facing);

                if (this.heatMultiplierByPos.get(offsetPos) != null) {
                    continue;
                }

                Integer nextStrength = spreadingPair.getValue() - 1;

                //Only attempt to update tracking for this position if there is air here.
                //Even positions already being tracked should be filled with air.
                if (this.canFill(offsetPos)) {
                    // Add to queue to check spreading
                    AbstractMap.SimpleEntry<BlockPos,Integer> queueItem = new AbstractMap.SimpleEntry<>(offsetPos, nextStrength);
                    positionsToSpread.add(queueItem);

                    /*
                     * Add to map immediately to prevent re-checking
                     * Block positions that were in queue initially (usually only central block) will not be included because
                     * there's no need to warm block itself and quick cached queue should be already in map
                     */
                    this.heatMultiplierByPos.put(offsetPos, nextStrength);
                }
            }
        } while (!positionsToSpread.isEmpty());

        ToughAsNails.logger.warn("TAN Map Items" + this.heatMultiplierByPos.size());
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
    
    private boolean canFill(BlockPos pos)
    {
        //Only spread within enclosed areas, significantly reduces the impact on performance and suits the purpose of coils
        return !this.getWorld().isBlockFullCube(pos) && (!this.getWorld().canSeeSky(pos));
    }
    
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
        return this.heatMultiplierByPos.get(pos) != null;
    }
}
