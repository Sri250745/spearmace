package com.example.spearmace;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
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
 *  - Click (attack): switches to your spear so the lunge fires, holds it for a
 *    few ticks, then switches to the first food item in your hotbar.
 *  - Falling onto a target: switches to your mace, returns on landing.
 *  - Lunge key (default G): switches to the spear, fires the spear attack for
 *    you (that is the lunge), holds the spear briefly, then switches to food.
 *  - Toggle key: turns all auto-swapping on/off.
 *
 * Slots are found by item, so no configuration is needed.
 * NOTE: auto-swapping is flagged by many multiplayer anticheats. Only use it
 * where it is allowed (singleplayer, your own server, permissive servers).
 */
public class SpearMaceClient implements ClientModInitializer {

    private static final double MIN_FALL = 1.5;      // blocks fallen before mace swap kicks in
    private static final int LUNGE_HOLD_TICKS = 5;   // ticks to keep the spear out after a click (20 ticks = 1 second)

    private static KeyMapping toggleKey;
    private static KeyMapping lungeKey;
    private static boolean enabled = true;
    private static int returnSlot = -1;
    private static boolean attackWasDown = false;
    private static int foodCountdown = -1;
    private static boolean pendingLunge = false;

    @Override
    public void onInitializeClient() {
        KeyMapping.Category category =
                KeyMapping.Category.register(Identifier.fromNamespaceAndPath("spearmace", "main"));

        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.spearmace.toggle", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, category));
        lungeKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.spearmace.spear", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_G, category));

        ClientTickEvents.START_CLIENT_TICK.register(SpearMaceClient::startTick);
        ClientTickEvents.END_CLIENT_TICK.register(SpearMaceClient::tick);
    }

    /** Runs before the game handles clicks this tick, so the spear is already in hand when the attack happens. */
    private static void startTick(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null || mc.screen != null) {
            attackWasDown = false;
            foodCountdown = -1;
            pendingLunge = false;
            return;
        }

        // Fire the lunge click one tick after the slot change, so the spear is definitely selected.
        if (pendingLunge) {
            pendingLunge = false;
            KeyMapping.click(InputConstants.getKey(mc.options.keyAttack.saveString()));
        }

        while (lungeKey.consumeClick()) {
            if (!enabled) continue;
            int s = findHotbar(p, false);
            if (s >= 0) {
                p.getInventory().setSelectedSlot(s);
                returnSlot = -1;
                pendingLunge = true;
                foodCountdown = LUNGE_HOLD_TICKS + 1;
            }
        }

        boolean down = mc.options.keyAttack.isDown();
        if (enabled && down && !attackWasDown) {
            boolean fallingOnTarget = isFalling(p) && targetInCrosshair(mc, p);
            if (!fallingOnTarget) {
                int s = findHotbar(p, false);
                if (s >= 0) {
                    p.getInventory().setSelectedSlot(s);
                    returnSlot = -1;
                    foodCountdown = LUNGE_HOLD_TICKS;
                }
            }
        }
        attackWasDown = down;

        if (foodCountdown > 0) {
            foodCountdown--;
        } else if (foodCountdown == 0) {
            int f = findFood(p);
            if (f >= 0) p.getInventory().setSelectedSlot(f);
            foodCountdown = -1;
        }
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

        if (!enabled) return;

        if (isFalling(p) && targetInCrosshair(mc, p) && !isMace(p.getMainHandItem())) {
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

    private static boolean isFalling(LocalPlayer p) {
        boolean airborne = !p.onGround() && !p.isInWater() && !p.getAbilities().flying && !p.isFallFlying();
        return airborne && p.getDeltaMovement().y < 0 && p.fallDistance >= MIN_FALL;
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

    private static boolean isFood(ItemStack s) {
        return s.has(DataComponents.FOOD);
    }

    private static int findHotbar(LocalPlayer p, boolean mace) {
        for (int i = 0; i < 9; i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (mace ? isMace(s) : isSpear(s)) return i;
        }
        return -1;
    }

    private static int findFood(LocalPlayer p) {
        for (int i = 0; i < 9; i++) {
            if (isFood(p.getInventory().getItem(i))) return i;
        }
        return -1;
    }
}
