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
package org.spongepowered.mod.mixin.core.forge;

import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityHopper;
import net.minecraft.util.EnumFacing;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.VanillaInventoryCodeHooks;
import org.apache.commons.lang3.tuple.Pair;
import org.spongepowered.api.event.SpongeEventFactory;
import org.spongepowered.api.event.cause.Cause;
import org.spongepowered.api.event.item.inventory.ChangeInventoryEvent;
import org.spongepowered.api.item.inventory.Inventory;
import org.spongepowered.api.item.inventory.ItemStackSnapshot;
import org.spongepowered.api.item.inventory.Slot;
import org.spongepowered.api.item.inventory.property.SlotIndex;
import org.spongepowered.api.item.inventory.transaction.SlotTransaction;
import org.spongepowered.api.item.inventory.type.OrderedInventory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;
import org.spongepowered.common.SpongeImpl;
import org.spongepowered.common.interfaces.IMixinInventory;
import org.spongepowered.common.item.inventory.adapter.InventoryAdapter;
import org.spongepowered.common.item.inventory.adapter.impl.MinecraftInventoryAdapter;
import org.spongepowered.common.item.inventory.util.ItemStackUtil;

@Mixin(VanillaInventoryCodeHooks.class)
public class MixinVanillaInventoryCodeHooks {

    @Inject(method = "insertHook", locals = LocalCapture.CAPTURE_FAILEXCEPTION,
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/item/ItemStack;isEmpty()Z", ordinal = 1))
    private static void afterPutStackInSlots(TileEntityHopper hopper, CallbackInfoReturnable<Boolean> cir,
            EnumFacing hopperFacing, Pair<IItemHandler, Object> destinationResult, IItemHandler itemHandler,
            Object desination, int i, ItemStack originalSlotContents,
            ItemStack insertStack, ItemStack remainder) {
        // after putStackInInventoryAllSlots
        // if the transfer worked
        if (remainder.isEmpty()) {
            Slot slot = ((OrderedInventory) ((MinecraftInventoryAdapter) hopper).query(OrderedInventory.class)).getSlot(SlotIndex.of(i)).get();
            SlotTransaction trans = new SlotTransaction(slot, ItemStackUtil.snapshotOf(originalSlotContents), ItemStackUtil.snapshotOf(slot.peek().orElse(
                    org.spongepowered.api.item.inventory.ItemStack.empty())));
            ((IMixinInventory) hopper).getCapturedTransactions().add(trans);
            Cause.Builder builder = Cause.source(hopper);

            // TODO problem: Transfer affects 2 inventories!
            ChangeInventoryEvent.Transfer event =
                    SpongeEventFactory.createChangeInventoryEventTransfer(builder.build(), ((Inventory) hopper), ((IMixinInventory) hopper).getCapturedTransactions());

            SpongeImpl.postEvent(event);
            if (event.isCancelled()) {
                // restore inventories
                for (SlotTransaction transaction : event.getTransactions()) {
                    transaction.getSlot().set(transaction.getOriginal().createStack());
                }
                // TODO do we want to try to transfer more items? or just cancel everything
                remainder = originalSlotContents; // vanilla thinks there was not enough place for item in target
            }

            ((IMixinInventory) hopper).getCapturedTransactions().clear();
        }
    }

    @Shadow private static ItemStack insertStack(TileEntity source, Object destination, IItemHandler destInventory, ItemStack stack, int slot) {
        throw new AbstractMethodError("Shadow");
    }

    @Redirect(method = "putStackInInventoryAllSlots", at = @At(value = "INVOKE", target = "Lnet/minecraftforge/items/VanillaInventoryCodeHooks;insertStack(Lnet/minecraft/tileentity/TileEntity;Ljava/lang/Object;Lnet/minecraftforge/items/IItemHandler;Lnet/minecraft/item/ItemStack;I)Lnet/minecraft/item/ItemStack;"))
    private static ItemStack onInsertStack(TileEntity source, Object destination, IItemHandler destInventory, ItemStack stack, int slot) {
        if (!(destination instanceof InventoryAdapter)) {
            return insertStack(source, destination, destInventory, stack, slot); // TODO check what IITemHandler is
        }

        Slot inventorySlot = ((OrderedInventory) ((MinecraftInventoryAdapter) destination).query(OrderedInventory.class)).getSlot(SlotIndex.of(slot)).get();
        ItemStackSnapshot from = inventorySlot.peek().map(ItemStackUtil::snapshotOf).orElse(ItemStackSnapshot.NONE);

        ItemStack remaining = insertStack(source, destination, destInventory, stack, slot);

        ItemStackSnapshot to = inventorySlot.peek().map(ItemStackUtil::snapshotOf).orElse(ItemStackSnapshot.NONE);

        if (source instanceof IMixinInventory) {
            ((IMixinInventory) source).getCapturedTransactions()
                    .add(new SlotTransaction(inventorySlot, from, to));;
        }
        return remaining;
    }


}
