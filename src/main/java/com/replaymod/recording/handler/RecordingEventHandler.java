package com.replaymod.recording.handler;

import com.replaymod.core.events.PreRenderCallback;
import com.replaymod.recording.mixin.IntegratedServerAccessor;
import com.replaymod.recording.packet.PacketListener;
import de.johni0702.minecraft.gui.utils.EventRegistrations;
import de.johni0702.minecraft.gui.versions.callbacks.PreTickCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.network.packet.s2c.play.BlockBreakingProgressS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityAnimationS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityAttachS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityEquipmentUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityPositionS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityS2CPacket;
import net.minecraft.network.packet.s2c.play.EntitySetHeadYawS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerSpawnS2CPacket;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.network.Packet;
import net.minecraft.server.integrated.IntegratedServer;
// FIXME not (yet?) 1.13 import net.minecraftforge.event.entity.minecart.MinecartInteractEvent;

//#if FABRIC<1
//$$ import net.minecraft.network.play.server.SCollectItemPacket;
//$$ import net.minecraftforge.event.entity.player.PlayerSleepInBedEvent;
//$$ import net.minecraftforge.eventbus.api.SubscribeEvent;
//$$ import net.minecraftforge.event.entity.player.PlayerEvent.ItemPickupEvent;
//#endif

//#if MC>=11600
import com.mojang.datafixers.util.Pair;
//#endif

//#if MC>=11400
//#else
//$$ import net.minecraft.network.play.server.SPacketUseBed;
//#endif

//#if MC>=11100
import net.minecraft.util.collection.DefaultedList;
//#endif

//#if MC>=10904
import net.minecraft.network.packet.s2c.play.EntityTrackerUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.WorldEventS2CPacket;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.util.Hand;
//#endif

//#if MC>=10800
import net.minecraft.util.math.BlockPos;
//#else
//$$ import net.minecraft.util.MathHelper;
//#endif

import java.util.Collections;
import java.util.List;
import java.util.Objects;

import static com.replaymod.core.versions.MCVer.*;

public class RecordingEventHandler extends EventRegistrations {

    private final MinecraftClient mc = getMinecraft();
    private final PacketListener packetListener;

    private Double lastX, lastY, lastZ;
    private final List<ItemStack> playerItems = DefaultedList.ofSize(6, ItemStack.EMPTY);
    private int ticksSinceLastCorrection;
    private boolean wasSleeping;
    private int lastRiding = -1;
    private Integer rotationYawHeadBefore;

    public RecordingEventHandler(PacketListener packetListener) {
        this.packetListener = packetListener;
    }

    @Override
    public void register() {
        super.register();
        ((RecordingEventSender) mc.worldRenderer).setRecordingEventHandler(this);
    }

    @Override
    public void unregister() {
        super.unregister();
        RecordingEventSender recordingEventSender = ((RecordingEventSender) mc.worldRenderer);
        if (recordingEventSender.getRecordingEventHandler() == this) {
            recordingEventSender.setRecordingEventHandler(null);
        }
    }

    //#if MC>=10904
    public void onPacket(Packet<?> packet) {
        packetListener.save(packet);
    }
    //#endif

    public void spawnRecordingPlayer() {
        try {
            ClientPlayerEntity player = mc.player;
            assert player != null;
            packetListener.save(new PlayerSpawnS2CPacket(player));
            //#if MC>=11903
            //$$ packetListener.save(new EntityTrackerUpdateS2CPacket(player.getId(), player.getDataTracker().getChangedEntries()));
            //#elseif MC>=11500
            packetListener.save(new EntityTrackerUpdateS2CPacket(player.getEntityId(), player.getDataTracker(), true));
            //#endif
            lastX = lastY = lastZ = null;
            //#if MC>=11100
            playerItems.clear();
            //#else
            //$$ Collections.fill(playerItems, null);
            //#endif
            lastRiding = -1;
            wasSleeping = false;
        } catch(Exception e) {
            e.printStackTrace();
        }
    }

    //#if MC>=10904
    public void onClientEffect(int type, BlockPos pos, int data) {
        try {
            // Send to all other players in ServerWorldEventHandler#playEvent
            packetListener.save(new WorldEventS2CPacket(type, pos, data, false));
        } catch(Exception e) {
            e.printStackTrace();
        }
    }
    //#endif

    { on(PreTickCallback.EVENT, this::onPlayerTick); }
    private void onPlayerTick() {
        if (mc.player == null) return;
        ClientPlayerEntity player = mc.player;
        try {

            boolean force = false;
            if(lastX == null || lastY == null || lastZ == null) {
                force = true;
                lastX = player.getX();
                lastY = player.getY();
                lastZ = player.getZ();
            }

            ticksSinceLastCorrection++;
            if(ticksSinceLastCorrection >= 100) {
                ticksSinceLastCorrection = 0;
                force = true;
            }

            double dx = player.getX() - lastX;
            double dy = player.getY() - lastY;
            double dz = player.getZ() - lastZ;

            lastX = player.getX();
            lastY = player.getY();
            lastZ = player.getZ();

            //#if MC>=10904
            final double maxRelDist = 8.0;
            //#else
            //$$ final double maxRelDist = 4.0;
            //#endif

            Packet packet;
            if (force || Math.abs(dx) > maxRelDist || Math.abs(dy) > maxRelDist || Math.abs(dz) > maxRelDist) {
                //#if MC>=10800
                packet = new EntityPositionS2CPacket(player);
                //#else
                //$$ // In 1.7.10 the client player entity has its posY at eye height
                //$$ // but for all other entities it's at their feet (as it should be).
                //$$ // So, to correctly position the player, we teleport them to their feet (i.a. directly after spawn).
                //$$ // Note: this leaves the lastY value offset by the eye height but because it's only used for relative
                //$$ //       movement, that doesn't matter.
                //$$ S18PacketEntityTeleport teleportPacket = new S18PacketEntityTeleport(player);
                //$$ packet = new S18PacketEntityTeleport(
                //$$         teleportPacket.func_149451_c(),
                //$$         teleportPacket.func_149449_d(),
                //$$         MathHelper.floor_double(player.boundingBox.minY * 32),
                //$$         teleportPacket.func_149446_f(),
                //$$         teleportPacket.func_149450_g(),
                //$$         teleportPacket.func_149447_h()
                //$$ );
                //#endif
            } else {
                byte newYaw = (byte) ((int) (player.yaw * 256.0F / 360.0F));
                byte newPitch = (byte) ((int) (player.pitch * 256.0F / 360.0F));

                //#if MC>=11400
                packet = new EntityS2CPacket.RotateAndMoveRelative(
                //#else
                //$$ packet = new SPacketEntity.S17PacketEntityLookMove(
                //#endif
                        player.getEntityId(),
                        //#if MC>=10904
                        (short) Math.round(dx * 4096), (short) Math.round(dy * 4096), (short) Math.round(dz * 4096),
                        //#else
                        //$$ (byte) Math.round(dx * 32), (byte) Math.round(dy * 32), (byte) Math.round(dz * 32),
                        //#endif
                        newYaw, newPitch
                        //#if MC>=11600
                        , player.isOnGround()
                        //#else
                        //#if MC>=10800
                        //$$ , player.onGround
                        //#endif
                        //#endif
                );
            }

            packetListener.save(packet);

            //HEAD POS
            int rotationYawHead = ((int)(player.headYaw * 256.0F / 360.0F));

            if(!Objects.equals(rotationYawHead, rotationYawHeadBefore)) {
                packetListener.save(new EntitySetHeadYawS2CPacket(player, (byte) rotationYawHead));
                rotationYawHeadBefore = rotationYawHead;
            }

            packetListener.save(new EntityVelocityUpdateS2CPacket(player.getEntityId(),
                    //#if MC>=11400
                    player.getVelocity()
                    //#else
                    //$$ player.motionX, player.motionY, player.motionZ
                    //#endif
            ));

            //Animation Packets
            //Swing Animation
            if (player.handSwinging && player.handSwingTicks == 0) {
                packetListener.save(new EntityAnimationS2CPacket(
                        player,
                        //#if MC>=10904
                        player.preferredHand == Hand.MAIN_HAND ? 0 : 3
                        //#else
                        //$$ 0
                        //#endif
                ));
            }

			/*
        //Potion Effect Handling
		List<Integer> found = new ArrayList<Integer>();
		for(PotionEffect pe : (Collection<PotionEffect>)player.getActivePotionEffects()) {
			found.add(pe.getPotionID());
			if(lastEffects.contains(found)) continue;
			S1DPacketEntityEffect pee = new S1DPacketEntityEffect(entityID, pe);
			packetListener.save(pee);
		}

		for(int id : lastEffects) {
			if(!found.contains(id)) {
				S1EPacketRemoveEntityEffect pre = new S1EPacketRemoveEntityEffect(entityID, new PotionEffect(id, 0));
				packetListener.save(pre);
			}
		}

		lastEffects = found;
			 */

            //Inventory Handling
            //#if MC>=10904
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                ItemStack stack = player.getEquippedStack(slot);
                int index = slot.ordinal();
            //#else
            //$$ for (int slot = 0; slot < 5; slot++) {
            //$$     ItemStack stack = player.getEquipmentInSlot(slot);
            //$$     int index = slot;
            //#endif
                if (!ItemStack.areEqual(playerItems.get(index), stack)) {
                    // ItemStack has internal mutability, so we need to make a copy now if we want to compare its
                    // current state with future states (e.g. dropping on modern versions will set the count to zero).
                    stack = stack != null ? stack.copy() : null;
                    playerItems.set(index, stack);
                    //#if MC>=11600
                    packetListener.save(new EntityEquipmentUpdateS2CPacket(player.getEntityId(), Collections.singletonList(Pair.of(slot, stack))));
                    //#else
                    //$$ packetListener.save(new EntityEquipmentUpdateS2CPacket(player.getEntityId(), slot, stack));
                    //#endif
                }
            }

            //Leaving Ride

            Entity vehicle = player.getVehicle();
            int vehicleId = vehicle == null ? -1 : vehicle.getEntityId();
            if (lastRiding != vehicleId) {
                lastRiding = vehicleId;
                packetListener.save(new EntityAttachS2CPacket(
                        //#if MC<10904
                        //$$ 0,
                        //#endif
                        player,
                        vehicle
                ));
            }

            //Sleeping
            if(!player.isSleeping() && wasSleeping) {
                packetListener.save(new EntityAnimationS2CPacket(player, 2));
                wasSleeping = false;
            }

        } catch(Exception e1) {
            e1.printStackTrace();
        }
    }

    //#if MC>=10800
    public void onBlockBreakAnim(int breakerId, BlockPos pos, int progress) {
    //#else
    //$$ public void onBlockBreakAnim(int breakerId, int x, int y, int z, int progress) {
    //#endif
        PlayerEntity thePlayer = mc.player;
        if (thePlayer != null && breakerId == thePlayer.getEntityId()) {
            packetListener.save(new BlockBreakingProgressS2CPacket(breakerId,
                    //#if MC>=10800
                    pos,
                    //#else
                    //$$ x, y, z,
                    //#endif
                    progress));
        }
    }

    { on(PreRenderCallback.EVENT, this::checkForGamePaused); }
    private void checkForGamePaused() {
        if (mc.isIntegratedServerRunning()) {
            IntegratedServer server =  mc.getServer();
            if (server != null && ((IntegratedServerAccessor) server).isGamePaused()) {
                packetListener.setServerWasPaused();
            }
        }
    }

    public interface RecordingEventSender {
        void setRecordingEventHandler(RecordingEventHandler recordingEventHandler);
        RecordingEventHandler getRecordingEventHandler();
    }
}
