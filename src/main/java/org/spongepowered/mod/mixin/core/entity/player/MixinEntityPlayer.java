/*
 * This file is part of Sponge, licensed under the MIT License (MIT).
 *
 * Copyright (c) SpongePowered <https://www.spongepowered.org>
 * Copyright (c) contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.spongepowered.mod.mixin.core.entity.player;

import com.flowpowered.math.vector.Vector3d;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayer.EnumStatus;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.DamageSource;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.entity.Transform;
import org.spongepowered.api.event.SpongeEventFactory;
import org.spongepowered.api.event.action.SleepingEvent;
import org.spongepowered.api.event.cause.Cause;
import org.spongepowered.api.event.cause.NamedCause;
import org.spongepowered.api.util.RespawnLocation;
import org.spongepowered.api.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.common.entity.player.ISpongeUser;
import org.spongepowered.common.util.VecHelper;
import org.spongepowered.common.world.DimensionManager;
import org.spongepowered.mod.mixin.core.entity.living.MixinEntityLivingBase;

import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.UUID;

@Mixin(value = EntityPlayer.class, priority = 1001)
public abstract class MixinEntityPlayer extends MixinEntityLivingBase implements ISpongeUser {

    private EntityPlayer nmsPlayer = (EntityPlayer)(Object) this;

    @Shadow public InventoryPlayer inventory;
    @Shadow public BlockPos playerLocation;
    @Shadow protected boolean sleeping;
    @Shadow private int sleepTimer;
    @Shadow private BlockPos spawnChunk;
    @Shadow private boolean spawnForced;
    @Shadow(remap = false) private HashMap<Integer, BlockPos> spawnChunkMap;
    @Shadow(remap = false) private HashMap<Integer, Boolean> spawnForcedMap;

    @Shadow public abstract EntityItem dropItem(ItemStack droppedItem, boolean dropAround, boolean traceItem);
    @Shadow public abstract void setSpawnPoint(BlockPos pos, boolean force);
    @Shadow(remap = false)
    public abstract void setSpawnChunk(BlockPos pos, boolean forced, int dimension);

    /**
     * @author blood
     * @reason Reroutes to our damage hook
     *
     * @param damageSource The damage source
     * @param damage The damage
     */
    @Overwrite
    protected void damageEntity(DamageSource damageSource, float damage) {
        this.damageEntityHook(damageSource, damage);
    }

    // Restore methods to original as we handle PlayerTossEvent in DropItemEvent
    /**
     * @author blood - October 16th, 2015
     * @reason Redirects to our method for event handling
     *
     * @param dropAll The damage source
     */
    @Overwrite
    public EntityItem dropOneItem(boolean dropAll) {
        return this.dropItem(this.inventory.decrStackSize(this.inventory.currentItem, dropAll && this.inventory.getCurrentItem() != null ? this.inventory.getCurrentItem().stackSize : 1), false, true);
    }

    /**
     * @author blood - October 16th, 2015
     * @reason Redirects to our method for event handling
     *
     * @param itemStackIn The itemstack to drop
     * @param unused Unused parameter
     */
    @Overwrite
    public EntityItem dropPlayerItemWithRandomChoice(ItemStack itemStackIn, boolean unused) {
        return this.dropItem(itemStackIn, false, false);
    }

    @Inject(method = "trySleep", at = @At(value = "RETURN", ordinal = 0))
    private void onSleepEvent(BlockPos bedLocation, CallbackInfoReturnable<EntityPlayer.EnumStatus> cir) {
        if (cir.getReturnValue() == EnumStatus.OK) {
            // A cut-down version of trySleep handling for OK status
            if (this.nmsPlayer.isRiding()) {
                this.nmsPlayer.dismountRidingEntity();
            }
            this.setSize(0.2F, 0.2F);
            this.nmsPlayer.setPosition((double) ((float) bedLocation.getX() + 0.5F), (double) ((float) bedLocation.getY() + 0.6875F),
                    (double) ((float) bedLocation.getZ() + 0.5F));
            this.sleeping = true;
            this.sleepTimer = 0;
            this.playerLocation = bedLocation;
            this.nmsPlayer.motionX = this.nmsPlayer.motionZ = this.nmsPlayer.motionY = 0.0D;
            if (!this.worldObj.isRemote) {
                this.worldObj.updateAllPlayersSleepingFlag();
            }
        }
    }

    /**
     * @author JBYoshi - November 23rd, 2015
     * @reason implement SpongeAPI events.
     *
     * @param immediately Whether to be woken up immediately
     * @param updateWorldFlag Whether to update the world
     * @param setSpawn Whether the player has successfully set up spawn
     */
    @Overwrite
    public void wakeUpPlayer(boolean immediately, boolean updateWorldFlag, boolean setSpawn) {
        IBlockState iblockstate = this.nmsPlayer.worldObj.getBlockState(this.playerLocation);

        Transform<World> newLocation = null;
        if (this.playerLocation != null && iblockstate.getBlock().isBed(iblockstate, this.nmsPlayer.worldObj, this.playerLocation, this.nmsPlayer)) {
            iblockstate.getBlock().setBedOccupied(this.nmsPlayer.worldObj, this.playerLocation, this.nmsPlayer, false);
            BlockPos blockpos = iblockstate.getBlock().getBedSpawnPosition(iblockstate, this.nmsPlayer.worldObj, this.playerLocation, this.nmsPlayer);

            if (blockpos == null) {
                blockpos = this.nmsPlayer.playerLocation.up();
            }

            newLocation = this.getTransform().setPosition(new Vector3d(blockpos.getX() + 0.5F, blockpos.getY() + 0.1F, blockpos.getZ() + 0.5F));
        }

        SleepingEvent.Post post = null;
        if (!this.nmsPlayer.worldObj.isRemote) {
            post = SpongeEventFactory.createSleepingEventPost(Cause.of(NamedCause.source(this)),
                this.getWorld().createSnapshot(VecHelper.toVector3i(this.playerLocation)), Optional.ofNullable(newLocation), this, setSpawn);
            Sponge.getEventManager().post(post);
            if (post.isCancelled()) {
                return;
            }

            net.minecraftforge.event.ForgeEventFactory.onPlayerWakeup(this.nmsPlayer, immediately, updateWorldFlag, setSpawn);
            this.setSize(0.6F, 1.8F);
            if (post.getSpawnTransform().isPresent()) {
                this.setTransform(post.getSpawnTransform().get());
            }
        } else {
            net.minecraftforge.event.ForgeEventFactory.onPlayerWakeup(this.nmsPlayer, immediately, updateWorldFlag, setSpawn);
            this.setSize(0.6F, 1.8F);
        }

        this.sleeping = false;

        if (!this.nmsPlayer.worldObj.isRemote && updateWorldFlag) {
            this.nmsPlayer.worldObj.updateAllPlayersSleepingFlag();
        }

        this.sleepTimer = immediately ? 0 : 100;

        if (setSpawn) {
            this.setSpawnPoint(this.playerLocation, false);
        }
        if (post != null) {
            Sponge.getGame().getEventManager().post(SpongeEventFactory.createSleepingEventFinish(post.getCause(),
                    this.getWorld().createSnapshot(VecHelper.toVector3i(this.playerLocation)), this));
        }
    }

    // TODO 1.9 Update - Zidane's deal with the next two methods.
    @Override
    public Map<UUID, RespawnLocation> getBedlocations() {
        Map<UUID, RespawnLocation> locations = Maps.newHashMap();
        if (this.spawnChunk != null) {
            locations.put(DimensionManager.dimIdToUuid(0), RespawnLocation.builder() //TODO - Zidane needs to come up with a way to get the uuid by dimension id
                    .world(DimensionManager.dimIdToUuid(0)) //TODO - Zidane needs to come up with a way to get the uuid by dimension id
                    .position(VecHelper.toVector3d(this.spawnChunk))
                    .forceSpawn(this.spawnForced)
                    .build());
        }
        for (Entry<Integer, BlockPos> entry : this.spawnChunkMap.entrySet()) {
            UUID uuid = DimensionManager.dimIdToUuid(entry.getKey()); //TODO - Zidane needs to come up with a way to get the uuid by dimension id
            if (uuid != null) {
                Boolean forced = this.spawnForcedMap.get(entry.getKey());
                locations.put(uuid, RespawnLocation.builder()
                        .world(uuid)
                        .position(VecHelper.toVector3d(entry.getValue()))
                        .forceSpawn(forced == null ? false : forced)
                        .build());
            }
        }
        return locations;
    }

    @Override
    public boolean setBedLocations(Map<UUID, RespawnLocation> locations) {
        // Clear all existing values
        this.spawnChunkMap.clear();
        this.spawnForcedMap.clear();
        setSpawnChunk(null, false, 0);
        // Add replacement values
        for (Entry<UUID, RespawnLocation> entry : locations.entrySet()) {
            int dim = DimensionManager.uuidToDimId(entry.getKey()); //TODO - Zidane needs to come up with a way to get the uuid by dimension id
            if (dim != Integer.MIN_VALUE) {
                setSpawnChunk(VecHelper.toBlockPos(entry.getValue().getPosition()), entry.getValue().isForced(), dim);
            }
        }
        return true;
    }

    @Override
    public ImmutableMap<UUID, RespawnLocation> removeAllBeds() {
        ImmutableMap<UUID, RespawnLocation> locations = ImmutableMap.copyOf(getBedlocations());
        this.spawnChunkMap.clear();
        this.spawnForcedMap.clear();
        setSpawnChunk(null, false, 0);
        return locations;
    }
}
