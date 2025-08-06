package com.latmod.mods.projectex.tile;

import com.latmod.mods.projectex.ProjectEXConfig;
import com.latmod.mods.projectex.ProjectEXUtils;
import com.latmod.mods.projectex.integration.PersonalEMC;
import com.jaquadro.minecraft.storagedrawers.api.capabilities.IItemRepository;
import com.jaquadro.minecraft.storagedrawers.api.capabilities.CapabilityItemRepository;
import net.minecraftforge.fml.common.Optional;
import moze_intel.projecte.api.ProjectEAPI;
import moze_intel.projecte.api.capabilities.IKnowledgeProvider;
import moze_intel.projecte.api.event.PlayerAttemptCondenserSetEvent;
import moze_intel.projecte.api.tile.IEmcAcceptor;
import moze_intel.projecte.config.ProjectEConfig;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.server.SPacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ITickable;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.common.util.Constants;
import net.minecraftforge.items.CapabilityItemHandler;
import net.minecraftforge.items.IItemHandlerModifiable;
import net.minecraftforge.items.ItemHandlerHelper;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.*;
import java.util.function.Predicate;
import net.minecraft.util.NonNullList;
import com.jaquadro.minecraft.storagedrawers.api.capabilities.IItemRepository.ItemRecord;

/**
 * TileLink with Storage Drawers capability support
 */
@Optional.Interface(iface = "com.jaquadro.minecraft.storagedrawers.api.capabilities.IItemRepository", modid = "storagedrawers")
public class TileLink extends TileEntity implements IItemHandlerModifiable, ITickable, IEmcAcceptor, IItemRepository {
	// Owner, display name, and dirty flag
	public UUID owner = new UUID(0L, 0L);
	public String name = "";
	public boolean dirty = false;

	// Input and output storage
	public final ItemStack[] inputSlots;
	public final ItemStack[] outputSlots;
	public long storedEMC = 0L;

	public TileLink(int numInput, int numOutput) {
		inputSlots = new ItemStack[numInput];
		outputSlots = new ItemStack[numOutput];
		Arrays.fill(inputSlots, ItemStack.EMPTY);
		Arrays.fill(outputSlots, ItemStack.EMPTY);
	}

	//—— NBT Serialization ——

	@Override
	public void readFromNBT(NBTTagCompound nbt) {
		super.readFromNBT(nbt);
		owner = nbt.getUniqueId("owner");
		name = nbt.getString("name");
		storedEMC = Math.min(Long.MAX_VALUE, (long) nbt.getDouble("emc"));

		Arrays.fill(inputSlots, ItemStack.EMPTY);
		NBTTagList inList = nbt.getTagList("input", Constants.NBT.TAG_COMPOUND);
		for (int i = 0; i < inList.tagCount(); i++) {
			NBTTagCompound tag = inList.getCompoundTagAt(i);
			inputSlots[tag.getByte("Slot")] = new ItemStack(tag);
		}

		Arrays.fill(outputSlots, ItemStack.EMPTY);
		NBTTagList outList = nbt.getTagList("output", Constants.NBT.TAG_COMPOUND);
		if (outList.tagCount() == 0 && nbt.hasKey("output")) {
			outputSlots[0] = ProjectEXUtils.fixOutput(new ItemStack(nbt.getCompoundTag("output")));
		} else {
			for (int i = 0; i < outList.tagCount(); i++) {
				NBTTagCompound tag = outList.getCompoundTagAt(i);
				outputSlots[tag.getByte("Slot")] = ProjectEXUtils.fixOutput(new ItemStack(tag));
			}
		}
	}

	@Override
	public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
		nbt.setUniqueId("owner", owner);
		nbt.setString("name", name);
		if (storedEMC > 0) nbt.setDouble("emc", storedEMC);

		NBTTagList outList = new NBTTagList();
		for (int i = 0; i < outputSlots.length; i++) {
			ItemStack s = outputSlots[i];
			if (!s.isEmpty()) {
				s.setCount(1);
				NBTTagCompound tag = s.serializeNBT();
				tag.setByte("Slot", (byte) i);
				outList.appendTag(tag);
			}
		}
		nbt.setTag("output", outList);

		NBTTagList inList = new NBTTagList();
		for (int i = 0; i < inputSlots.length; i++) {
			ItemStack s = inputSlots[i];
			if (!s.isEmpty()) {
				NBTTagCompound tag = s.serializeNBT();
				tag.setByte("Slot", (byte) i);
				inList.appendTag(tag);
			}
		}
		nbt.setTag("input", inList);
		return super.writeToNBT(nbt);
	}

	@Override
	public NBTTagCompound getUpdateTag() {
		return writeToNBT(new NBTTagCompound());
	}

	@Override
	public SPacketUpdateTileEntity getUpdatePacket() {
		return new SPacketUpdateTileEntity(pos, 0, getUpdateTag());
	}

	@Override
	public void onDataPacket(NetworkManager net, SPacketUpdateTileEntity pkt) {
		readFromNBT(pkt.getNbtCompound());
	}

	//—— Capabilities ——

	@Override
	public boolean hasCapability(Capability<?> cap, @Nullable EnumFacing side) {
		if (cap == CapabilityItemHandler.ITEM_HANDLER_CAPABILITY) return true;
		if (cap == CapabilityItemRepository.ITEM_REPOSITORY_CAPABILITY) return true;
		return super.hasCapability(cap, side);
	}

	@SuppressWarnings("unchecked")
	@Override
	public <T> T getCapability(Capability<T> cap, @Nullable EnumFacing side) {
		if (cap == CapabilityItemHandler.ITEM_HANDLER_CAPABILITY) {
			return (T) this;
		}
		if (cap == CapabilityItemRepository.ITEM_REPOSITORY_CAPABILITY) {
			return (T) this;
		}
		return super.getCapability(cap, side);
	}

	//—— IItemHandlerModifiable ——

	@Override
	public int getSlots() {
		return inputSlots.length + outputSlots.length;
	}

	@Override
	public ItemStack getStackInSlot(int slot) {
		if (slot < inputSlots.length) return inputSlots[slot];
		if (world.isRemote || owner.getLeastSignificantBits() == 0 && owner.getMostSignificantBits() == 0)
			return ItemStack.EMPTY;
		int idx = slot - inputSlots.length;
		ItemStack proto = outputSlots[idx];
		if (proto.isEmpty()) return ItemStack.EMPTY;
		long val = getCachedEMC(proto);
		if (val <= 0) return ItemStack.EMPTY;
		int count = getCountFor(val, ProjectEXConfig.general.emc_link_max_out);
		if (count <= 0) return ItemStack.EMPTY;
		proto.setCount(count);
		return proto;
	}

	@Override
	public void setStackInSlot(int slot, ItemStack stack) {
		if (slot < inputSlots.length) {
			inputSlots[slot] = stack;
			markDirty();
		}
	}

	@Override
	public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
		if (slot >= inputSlots.length || stack.isEmpty() || !ProjectEAPI.getEMCProxy().hasValue(stack)) return stack;
		ItemStack existing = inputSlots[slot];
		int limit = Math.min(stack.getMaxStackSize(), 64) - existing.getCount();
		if (limit <= 0) return stack;
		int toInsert = Math.min(limit, stack.getCount());
		if (!simulate) {
			if (existing.isEmpty()) inputSlots[slot] = ItemHandlerHelper.copyStackWithSize(stack, toInsert);
			else existing.grow(toInsert);
			markDirty();
		}
		if (stack.getCount() > toInsert) return ItemHandlerHelper.copyStackWithSize(stack, stack.getCount() - toInsert);
		return ItemStack.EMPTY;
	}

	@Override
	public ItemStack extractItem(int slot, int amount, boolean simulate) {
		if (slot < inputSlots.length || amount <= 0 || world.isRemote)
			return ItemStack.EMPTY;


		ItemStack proto = outputSlots[slot - inputSlots.length];
		if (proto.isEmpty()) return ItemStack.EMPTY;
		boolean matching = Arrays.stream(outputSlots).anyMatch(output -> ItemStack.areItemsEqual(output, proto));

		if (!matching) {
			return ItemStack.EMPTY;
		}

		long val = getCachedEMC(proto);
		if (val <= 0L) return ItemStack.EMPTY;

		IKnowledgeProvider prov = PersonalEMC.get(world, owner);
		long personalEmc = prov != null ? prov.getEmc() : 0L;
		long totalEmc    = storedEMC + personalEmc;
		if (totalEmc < val) return ItemStack.EMPTY;

		// honour small requests, but never exceed your EMC budget:
		int toExtract = (int) Math.min((long)amount, totalEmc / val);

		ItemStack result = proto.copy();
		result.setCount(toExtract);

		if (!simulate) {
			long cost = val * toExtract;

			// 1) drain from your storedEMC
			long fromStored = Math.min(storedEMC, cost);
			storedEMC -= fromStored;

			// 2) drain any remainder from personal EMC
			long remaining = cost - fromStored;
			if (remaining > 0 && prov != null) {
				PersonalEMC.remove(prov, remaining);
			}

			markDirty();
		}

		return result;
	}


	@Override
	public int getSlotLimit(int slot) {
		return slot < inputSlots.length ? 64 : ProjectEXConfig.general.emc_link_max_out;
	}

	@Override
	public boolean isItemValid(int slot, ItemStack stack) {
		return slot < inputSlots.length && ProjectEAPI.getEMCProxy().hasValue(stack);
	}

	//—— ITickable ——

	@Override
	public void update() {
		if (world.isRemote) return;
		IKnowledgeProvider prov = PersonalEMC.get(world, owner);
		boolean sync = false;
		for (int i = 0, len = inputSlots.length; i < len; i++) {
			ItemStack in = inputSlots[i];
			if (!in.isEmpty()) {
				long val = getCachedEMC(in);
				if (val > 0) {
					if (prov != null && learnItems()) sync |= prov.addKnowledge(ProjectEXUtils.fixOutput(in));
					storedEMC += in.getCount() * val * ProjectEConfig.difficulty.covalenceLoss;
					inputSlots[i] = ItemStack.EMPTY;
					markDirty();
				}
			}
		}
		if (prov != null && storedEMC > 0) {
			PersonalEMC.add(prov, storedEMC);
			storedEMC = 0;
			markDirty();
		}
		if (sync) {
			EntityPlayerMP player = world.getMinecraftServer().getPlayerList().getPlayerByUUID(owner);
			if (player != null) prov.sync(player);
		}
		if (dirty) {
			dirty = false;
			world.markChunkDirty(pos, this);
		}
	}

	@Override
	public void onLoad() {
		if (world.isRemote) world.tickableTileEntities.remove(this);
		super.validate();
	}

	//—— IEmcAcceptor ——

	@Override
	public long acceptEMC(EnumFacing facing, long v) {
		if (!world.isRemote) {
			storedEMC += v;
			markDirty();
		}
		return v;
	}

	@Override
	public long getStoredEmc() {
		return storedEMC;
	}

	@Override
	public long getMaximumEmc() {
		return Long.MAX_VALUE;
	}

	/**
	 * Allows external mods or GUI to set the output slot contents.
	 */
	public boolean setOutputStack(EntityPlayer player, int slot, ItemStack stack, boolean addKnowledge) {
		stack = ProjectEXUtils.fixOutput(stack);
		IKnowledgeProvider prov = PersonalEMC.get(player);
		if (addKnowledge) {
			ProjectEXUtils.addKnowledge(player, prov, stack);
		}
		if (ProjectEAPI.getEMCProxy().hasValue(stack) && (addKnowledge || prov.hasKnowledge(stack))) {
			if (!MinecraftForge.EVENT_BUS.post(new PlayerAttemptCondenserSetEvent(player, stack))) {
				this.outputSlots[slot] = stack;
				markDirty();
				return true;
			}
		}
		return false;
	}

	//—— Helpers ——

	/**
	 * Mark this tile dirty for saving/sync.
	 * Overridden to be public so it can be accessed where needed.
	 */
	@Override
	public void markDirty() {
		dirty = true;
	}

	/**
	 * Indicates whether this link should add learned items to knowledge provider.
	 * Subclasses (e.g., TileLinkMK2) can override this.
	 */
	protected boolean learnItems() {
		return false;
	}

	private int getCountFor(long value, int maxOut) {
		long emc = storedEMC;
		IKnowledgeProvider prov = PersonalEMC.get(world, owner);
		if (prov != null) emc = prov.getEmc();
		if (emc < value) return 0;
		return (int) Math.min(maxOut, emc / value);
	}

	private long getCachedEMC(ItemStack stack) {
		Item item = stack.getItem();
        return ProjectEAPI.getEMCProxy().getValue(stack);
	}

//—— IItemRepository (Storage Drawers) ——//

	@Override
	@Optional.Method(modid = "storagedrawers")
	@Nonnull
	public NonNullList<ItemRecord> getAllItems() {
		NonNullList<ItemRecord> list = NonNullList.create();
		// total EMC available (player‐EMC has priority)
		IKnowledgeProvider prov = PersonalEMC.get(world, owner);
		long emc = prov != null ? prov.getEmc() : storedEMC;

		for (ItemStack proto : outputSlots) {
			if (proto.isEmpty()) continue;

			long val = getCachedEMC(proto);
			if (val <= 0) continue;

			long possible = emc / val;
			if (possible <= 0) continue;

			// clamp to int range
			int count = (int) Math.min((long) Integer.MAX_VALUE, possible);
			ItemStack copy = proto.copy();
			copy.setCount(count);
			list.add(new ItemRecord(copy, count));
		}
		return list;
	}

	@Override
	@Optional.Method(modid = "storagedrawers")
	@Nonnull
	public ItemStack insertItem(@Nonnull ItemStack stack, boolean simulate, Predicate<ItemStack> predicate) {
		IKnowledgeProvider knowledgeProvider = PersonalEMC.get(world, owner);
		boolean syncKnowledge = false;

		if (stack.isEmpty() || (predicate != null && !predicate.test(stack))) {
			return stack;
		}
		// reject anything without an EMC value
		long val = getCachedEMC(stack);
		if (val <= 0) {
			return stack;
		}

		if (!simulate) {
			// convert entire stack straight into EMC, applying covalenceLoss:
			double rawGain = stack.getCount() * (double) val * ProjectEConfig.difficulty.covalenceLoss;
			long gain = (long) rawGain;    // cast from double → long
			storedEMC += gain;
			markDirty();
			if (owner.getLeastSignificantBits() != 0L || owner.getMostSignificantBits() != 0L) {
				if (knowledgeProvider != null && learnItems())
				{
					syncKnowledge = knowledgeProvider.addKnowledge(ProjectEXUtils.fixOutput(stack));
					if (syncKnowledge) {
						EntityPlayerMP player = world.getMinecraftServer().getPlayerList().getPlayerByUUID(owner);

						if (player != null) {
							knowledgeProvider.sync(player);
						}
					}
				}

			}
		}
		// we accepted the whole stack
		return ItemStack.EMPTY;
	}


	@Override
	@Optional.Method(modid = "storagedrawers")
	@Nonnull
	public ItemStack extractItem(
			@Nonnull ItemStack prototype,
			int amount,
			boolean simulate,
			Predicate<ItemStack> predicate
	) {

		if (prototype.isEmpty() || (predicate != null && !predicate.test(prototype))) {
			return ItemStack.EMPTY;
		}

		boolean matching = Arrays.stream(outputSlots).anyMatch(output -> ItemStack.areItemsEqual(output, prototype));

		if (!matching) {
			return ItemStack.EMPTY;
		}

		long val = getCachedEMC(prototype);
		if (val <= 0L) {
			return ItemStack.EMPTY;
		}

		IKnowledgeProvider prov = PersonalEMC.get(world, owner);
		long personalEmc = prov != null ? prov.getEmc() : 0L;
		long totalEmc    = storedEMC + personalEmc;
		if (totalEmc < val) {
			return ItemStack.EMPTY;
		}

		// honour small requests, cap by EMC budget
		int toExtract = (int) Math.min((long)amount, totalEmc / val);

		ItemStack result = prototype.copy();
		result.setCount(toExtract);

		if (!simulate) {
			long cost = val * toExtract;

			long fromStored = Math.min(storedEMC, cost);
			storedEMC -= fromStored;

			long remaining = cost - fromStored;
			if (remaining > 0 && prov != null) {
				PersonalEMC.remove(prov, remaining);
			}

			markDirty();
		}

		return result;
	}

}
