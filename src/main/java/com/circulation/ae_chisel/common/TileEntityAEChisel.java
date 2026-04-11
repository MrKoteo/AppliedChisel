package com.circulation.ae_chisel.common;

import appeng.api.AEApi;
import appeng.api.config.Actionable;
import appeng.api.config.Upgrades;
import appeng.api.networking.IGridNode;
import appeng.api.networking.crafting.ICraftingLink;
import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.networking.crafting.ICraftingProviderHelper;
import appeng.api.networking.energy.IEnergyGrid;
import appeng.api.networking.events.MENetworkChannelsChanged;
import appeng.api.networking.events.MENetworkCraftingPatternChange;
import appeng.api.networking.events.MENetworkEventSubscribe;
import appeng.api.networking.events.MENetworkPowerStatusChange;
import appeng.api.networking.storage.IStorageGrid;
import appeng.api.networking.ticking.IGridTickable;
import appeng.api.networking.ticking.TickRateModulation;
import appeng.api.networking.ticking.TickingRequest;
import appeng.api.storage.channels.IItemStorageChannel;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.util.AECableType;
import appeng.api.util.AEPartLocation;
import appeng.api.util.DimensionalCoord;
import appeng.api.util.IConfigManager;
import appeng.helpers.DualityInterface;
import appeng.helpers.IInterfaceHost;
import appeng.me.GridAccessException;
import appeng.me.helpers.MachineSource;
import appeng.tile.grid.AENetworkInvTile;
import appeng.tile.inventory.AppEngInternalInventory;
import appeng.util.Platform;
import appeng.util.inv.InvOperation;
import appeng.util.item.AEItemStack;
import appeng.util.item.ItemList;
import com.circulation.ae_chisel.utils.ChiselPatternDetails;
import com.google.common.collect.ImmutableSet;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import lombok.Getter;
import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.items.IItemHandler;
import org.jetbrains.annotations.NotNull;
import team.chisel.api.carving.CarvingUtils;

import java.util.EnumSet;
import java.util.List;

public class TileEntityAEChisel extends AENetworkInvTile implements IInterfaceHost, IGridTickable {

    private static final EnumSet<EnumFacing> sides = EnumSet.complementOf(EnumSet.of(EnumFacing.UP));
    protected final DualityInterface duality = new DualityInterface(this.getProxy(), this);
    protected final AppEngInternalInventory inv = new AppEngInternalInventory(this, 1, 1);
    protected final MachineSource source = new MachineSource(this);
    protected final List<ChiselPatternDetails> patterns = new ObjectArrayList<>();
    protected final ItemList cache = new ItemList();
    private final EnumSet<EnumFacing> targets = EnumSet.allOf(EnumFacing.class);
    @Getter
    private int parallel = 1;

    public void onReady() {
        super.onReady();
        this.getProxy().setIdlePowerUsage(10);
        this.getProxy().setValidSides(sides);
        this.rebuildPatterns();
        this.wakeForCachedOutputs();
    }

    @MENetworkEventSubscribe
    public void stateChange(MENetworkChannelsChanged c) {
        this.duality.notifyNeighbors();
    }

    @MENetworkEventSubscribe
    public void stateChange(MENetworkPowerStatusChange c) {
        this.duality.notifyNeighbors();
    }

    @Override
    @NotNull
    public IItemHandler getInternalInventory() {
        return inv;
    }

    @Override
    public void onChangeInventory(IItemHandler inv, int slot, InvOperation invOperation, ItemStack removed, ItemStack added) {
        switch (invOperation) {
            case EXTRACT, INSERT, SET -> this.rebuildPatterns();
        }
    }

    public void setParallel(int parallel) {
        if (parallel < 1) parallel = 1;
        this.parallel = parallel;
        if (!this.world.isRemote && !inv.getStackInSlot(0).isEmpty()) {
            for (var pattern : this.patterns) {
                pattern.setParallel(this.parallel);
            }
            this.postPatternChange();
        }
    }

    private void rebuildPatterns() {
        this.patterns.clear();

        var stack = this.inv.getStackInSlot(0);
        if (!stack.isEmpty()) {
            var registry = CarvingUtils.getChiselRegistry();
            if (registry != null) {
                var input = AEItemStack.fromItemStack(stack);
                if (!ChiselPatternDetails.addChiselPatterns(input, registry.getItemsForChiseling(stack), this.patterns, this.parallel)) {
                    this.inv.setStackInSlot(0, ItemStack.EMPTY);
                    return;
                }
            }
        }

        this.postPatternChange();
    }

    private void postPatternChange() {
        var node = this.getProxy().getNode();
        if (node == null) {
            return;
        }

        node.getGrid().postEvent(new MENetworkCraftingPatternChange(this, node));
    }

    @Override
    @NotNull
    public NBTTagCompound writeToNBT(NBTTagCompound data) {
        super.writeToNBT(data);
        data.setInteger("parallel", this.parallel);
        NBTTagList list = new NBTTagList();
        for (var stack : this.cache) {
            NBTTagCompound nbt = new NBTTagCompound();
            stack.writeToNBT(nbt);
            list.appendTag(nbt);
        }
        data.setTag("cacheItems", list);
        return data;
    }

    @Override
    public void readFromNBT(NBTTagCompound data) {
        super.readFromNBT(data);
        this.parallel = Math.max(1, data.getInteger("parallel"));
        for (var pattern : this.patterns) {
            pattern.setParallel(this.parallel);
        }
        this.cache.resetStatus();
        var list = data.getTagList("cacheItems", 10);
        for (var nbtBase : list) {
            this.cache.addStorage(AEItemStack.fromNBT((NBTTagCompound) nbtBase));
        }
    }

    @Override
    public DualityInterface getInterfaceDuality() {
        return duality;
    }

    @Override
    public EnumSet<EnumFacing> getTargets() {
        return targets;
    }

    @Override
    public TileEntity getTileEntity() {
        return this;
    }

    @Override
    public int getInstalledUpgrades(Upgrades upgrades) {
        return 0;
    }

    @Override
    public IItemHandler getInventoryByName(String s) {
        return null;
    }

    @Override
    public void provideCrafting(ICraftingProviderHelper providerHelper) {
        if (this.getProxy().isActive()) {
            for (var pattern : patterns) {
                providerHelper.addCraftingOption(this, pattern);
            }
        }
    }

    @Override
    public boolean pushPattern(ICraftingPatternDetails details, InventoryCrafting crafting) {
        if (details.getCondensedInputs().length == 0 || details.getCondensedOutputs().length == 0) return false;
        var inputD = details.getCondensedInputs()[0].getDefinition();
        for (var i = 0; i < crafting.getSizeInventory(); i++) {
            var input = crafting.getStackInSlot(i);
            if (input.isEmpty()) continue;
            if (inputD.isItemEqual(input)) {
                var out = details.getCondensedOutputs()[0].copy().setStackSize(input.getCount());
                this.queueOutput(out);
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean isBusy() {
        return false;
    }

    @Override
    public ImmutableSet<ICraftingLink> getRequestedJobs() {
        return this.duality.getRequestedJobs();
    }

    @Override
    public IAEItemStack injectCraftedItems(ICraftingLink link, IAEItemStack items, Actionable actionable) {
        return this.duality.injectCraftedItems(link, items, actionable);
    }

    @Override
    public void jobStateChange(ICraftingLink iCraftingLink) {
        this.duality.jobStateChange(iCraftingLink);
    }

    @Override
    public IConfigManager getConfigManager() {
        return this.duality.getConfigManager();
    }

    @Override
    public DimensionalCoord getLocation() {
        return new DimensionalCoord(this);
    }

    @Override
    @NotNull
    public AECableType getCableConnectionType(@NotNull AEPartLocation dir) {
        return AECableType.SMART;
    }

    @Override
    public boolean hasCapability(Capability<?> capability, EnumFacing facing) {
        return false;
    }

    @Override
    public <T> T getCapability(Capability<T> capability, EnumFacing facing) {
        return null;
    }

    @Override
    public @NotNull TickingRequest getTickingRequest(@NotNull IGridNode node) {
        return adjustTickingRequestForCachedOutputs(this.duality.getTickingRequest(node), !this.cache.isEmpty());
    }

    @Override
    public @NotNull TickRateModulation tickingRequest(@NotNull IGridNode node, int i) {
        if (cache.isEmpty()) return TickRateModulation.SLOWER;
        var grid = node.getGrid();
        IEnergyGrid energyGrid = grid.getCache(IEnergyGrid.class);
        IStorageGrid storageGrid = grid.getCache(IStorageGrid.class);
        var storage = storageGrid.getInventory(AEApi.instance().storage().getStorageChannel(IItemStorageChannel.class));
        ItemList o = null;
        for (var stack : cache) {
            if (stack == null || stack.getStackSize() == 0) continue;
            var newItem = Platform.poweredInsert(energyGrid, storage, stack, source, Actionable.MODULATE);
            if (newItem != null && newItem.getStackSize() != 0) {
                if (o == null) o = new ItemList();
                o.addStorage(newItem);
            }
        }
        cache.resetStatus();
        if (o != null) {
            for (var stack : o) {
                cache.addStorage(stack);
            }
        }
        return TickRateModulation.URGENT;
    }

    static TickingRequest adjustTickingRequestForCachedOutputs(@NotNull TickingRequest request, boolean hasCachedOutputs) {
        if (!hasCachedOutputs || !request.isSleeping) {
            return request;
        }
        return new TickingRequest(request.minTickRate, request.maxTickRate, false, request.canBeAlerted);
    }

    private void queueOutput(@NotNull IAEItemStack output) {
        boolean shouldWake = this.cache.isEmpty();
        this.cache.addStorage(output);
        if (shouldWake) {
            this.wakeForCachedOutputs();
        }
    }

    private void wakeForCachedOutputs() {
        if (this.cache.isEmpty()) {
            return;
        }

        var node = this.getProxy().getNode();
        if (node == null) {
            return;
        }

        try {
            this.getProxy().getTick().wakeDevice(node);
        } catch (GridAccessException ignored) {

        }
    }
}
