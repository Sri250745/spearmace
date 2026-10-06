package com.example.spearmace;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.EntityHitResult;
import org.lwjgl.glfw.GLFW;

/**
 * Spear + Mace helper (Fabric 1.21.11, Mojang mappings).
 *
 *  - "Spear slot" key: jumps your hotbar selection to the first spear it finds.
 *  - Auto mace: while falling past MIN_FALL blocks with a living entity under
 *    your crosshair, selects the mace; returns to your previous slot on landing.
 *  - Toggle key turns the auto-swap on/off.
 *
 * Slots are found by item, so no configuration is needed.
 * NOTE: auto-swapping is flagged by many multiplayer anticheats. Only use it
 * where it is allowed (singleplayer, your own server, permissive servers).
 */
public class SpearMaceClient implements ClientModInitializer {

    private static final double MIN_FALL = 1.5; // blocks fallen before mace swap kicks in

    private static KeyMapping toggleKey;
    private static KeyMapping spearKey;
    private static boolean enabled = true;
    private static int returnSlot = -1;

    @Override
    public void onInitializeClient() {
        KeyMapping.Category category =
                KeyMapping.Category.register(Identifier.fromNamespaceAndPath("spearmace", "main"));

        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.spearmace.toggle", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, category));
        spearKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.spearmace.spear", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_G, category));

        ClientTickEvents.END_CLIENT_TICK.register(SpearMaceClient::tick);
    }

    private static void tick(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) {
            returnSlot = -1;
            return;
        }

        while (toggleKey.consumeClick()) {
            enabled = !enabled;
            p.displayClientMessage(Component.literal("Spear/Mace auto-swap: " + (enabled ? "ON" : "OFF")), true);
        }

        while (spearKey.consumeClick()) {
            int s = findHotbar(p, false);
            if (s >= 0) p.getInventory().setSelectedSlot(s);
        }

        if (!enabled) return;

        boolean airborne = !p.onGround() && !p.isInWater() && !p.getAbilities().flying && !p.isFallFlying();
        boolean falling = airborne && p.getDeltaMovement().y < 0 && p.fallDistance >= MIN_FALL;

        if (falling && targetInCrosshair(mc, p) && !isMace(p.getMainHandItem())) {
            int m = findHotbar(p, true);
            if (m >= 0) {
                if (returnSlot < 0) returnSlot = p.getInventory().getSelectedSlot();
                p.getInventory().setSelectedSlot(m);
            }
        }

        if (returnSlot >= 0 && p.onGround()) {
            p.getInventory().setSelectedSlot(returnSlot);
            returnSlot = -1;
        }
    }

    private static boolean targetInCrosshair(Minecraft mc, LocalPlayer p) {
        return mc.hitResult instanceof EntityHitResult hit
                && hit.getEntity() instanceof LivingEntity
                && hit.getEntity() != p;
    }

    private static boolean isMace(ItemStack s) {
        return s.is(Items.MACE);
    }

    private static boolean isSpear(ItemStack s) {
        return BuiltInRegistries.ITEM.getKey(s.getItem()).getPath().endsWith("_spear");
    }

    private static int findHotbar(LocalPlayer p, boolean mace) {
        for (int i = 0; i < 9; i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (mace ? isMace(s) : isSpear(s)) return i;
        }
        return -1;
    }
}
