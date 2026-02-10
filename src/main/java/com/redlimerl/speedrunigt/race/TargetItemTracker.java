package com.redlimerl.speedrunigt.race;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

@Environment(EnvType.CLIENT)
public final class TargetItemTracker {
    private TargetItemTracker() {}

    public static void tick(MinecraftClient client) {
        RaceSessionManager race = RaceSessionManager.getInstance();
        if (race.getState() != RaceState.RUNNING) return;

        ClientPlayerEntity player = client.player;
        if (player == null) return;

        Identifier targetId = race.getTargetItemId().orElse(null);
        if (targetId == null) return;

        Item targetItem = Registries.ITEM.get(targetId);

        if (containsItem(player.getInventory(), player, targetItem)) {
            race.finishRun(RaceSessionManager.FinishReason.TARGET_OBTAINED);
            return;
        }

        if (!player.isAlive()) {
             race.finishRun(RaceSessionManager.FinishReason.DEATH);
        }
    }

    private static boolean containsItem(PlayerInventory inventory, ClientPlayerEntity player, Item targetItem) {
        for (ItemStack stack : inventory.getMainStacks()) {
            if (stack != null && !stack.isEmpty() && stack.isOf(targetItem)) return true;
        }

        ItemStack offhand = player.getOffHandStack();
        if (offhand != null && !offhand.isEmpty() && offhand.isOf(targetItem)) return true;

        for (var entry : PlayerInventory.EQUIPMENT_SLOTS.int2ObjectEntrySet()) {
            EquipmentSlot slot = entry.getValue();
            if (slot.getType() == EquipmentSlot.Type.HUMANOID_ARMOR) {
                ItemStack stack = inventory.getStack(entry.getIntKey());
                if (stack != null && !stack.isEmpty() && stack.isOf(targetItem)) return true;
            }
        }

        return false;
    }
}
