package com.example.anchormacro;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.SwordItem;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.lwjgl.glfw.GLFW;

/**
 * Anchor Macro (Fabric, Minecraft 1.21.1)
 *
 * Right-click a block while holding a sword (main hand, not sneaking) and the
 * mod runs this sequence, one step per tick:
 *   1. place a Respawn Anchor on the clicked face
 *   2. charge it with Glowstone
 *   3. place a Glowstone block between you and the anchor
 *   4. right-click the anchor with a Totem of Undying (explodes it)
 *   5. switch back to your sword
 *
 * Needs in hotbar: Respawn Anchor, 2+ Glowstone, Totem (hotbar or offhand).
 * Anchors only explode in the Overworld / End. Toggle the mod with J.
 * Sneak + right-click bypasses the macro.
 *
 * NOTE: Automated combat actions are against the rules on many servers.
 * Use only in singleplayer, your own server, or where it's explicitly allowed.
 */
public class AnchorMacroClient implements ClientModInitializer {

    private enum Step { PLACE_ANCHOR, CHARGE, SHIELD, EXPLODE, RESTORE }

    private static final double REACH_SQ = 4.5 * 4.5;
    private static final int STEP_DELAY = 1; // ticks between steps

    private static boolean enabled = true;
    private static KeyBinding toggleKey;

    private static boolean running = false;
    private static Step step;
    private static int delay;
    private static int swordSlot, anchorSlot, glowSlot, totemSlot; // totemSlot -1 => offhand
    private static BlockHitResult clickHit;
    private static BlockPos anchorPos;

    @Override
    public void onInitializeClient() {
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.anchormacro.toggle",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_J,
                "category.anchormacro"));

        UseBlockCallback.EVENT.register(AnchorMacroClient::onUseBlock);
        ClientTickEvents.END_CLIENT_TICK.register(AnchorMacroClient::onTick);
    }

    // ---------------------------------------------------------------- trigger

    private static ActionResult onUseBlock(PlayerEntity player, World world, Hand hand, BlockHitResult hit) {
        if (!enabled || running || !world.isClient() || hand != Hand.MAIN_HAND) return ActionResult.PASS;
        if (player.isSneaking()) return ActionResult.PASS;
        if (!(player.getStackInHand(hand).getItem() instanceof SwordItem)) return ActionResult.PASS;

        MinecraftClient mc = MinecraftClient.getInstance();
        PlayerInventory inv = player.getInventory();

        swordSlot = inv.selectedSlot;
        anchorSlot = findHotbar(inv, Items.RESPAWN_ANCHOR, 1);
        glowSlot = findHotbar(inv, Items.GLOWSTONE, 2);
        totemSlot = findHotbar(inv, Items.TOTEM_OF_UNDYING, 1);
        boolean totemOffhand = player.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING);

        StringBuilder missing = new StringBuilder();
        if (anchorSlot < 0) missing.append("Respawn Anchor ");
        if (glowSlot < 0) missing.append("2x Glowstone ");
        if (totemSlot < 0 && !totemOffhand) missing.append("Totem ");
        if (missing.length() > 0) {
            player.sendMessage(Text.literal("Anchor Macro missing: " + missing.toString().trim()), true);
            return ActionResult.PASS;
        }
        if (totemSlot < 0) totemSlot = -1; // use offhand

        BlockPos target = hit.getBlockPos().offset(hit.getSide());
        if (!world.getBlockState(target).isReplaceable()) return ActionResult.PASS;
        if (player.getEyePos().squaredDistanceTo(hit.getPos()) > REACH_SQ) return ActionResult.PASS;

        clickHit = hit;
        anchorPos = target;
        step = Step.PLACE_ANCHOR;
        delay = 0;
        running = true;
        return ActionResult.FAIL; // cancel the normal sword click
    }

    // ------------------------------------------------------------ state machine

    private static void onTick(MinecraftClient mc) {
        while (toggleKey.wasPressed()) {
            enabled = !enabled;
            if (mc.player != null) {
                mc.player.sendMessage(Text.literal("Anchor Macro: " + (enabled ? "ON" : "OFF")), true);
            }
        }

        if (!running) return;
        if (mc.player == null || mc.world == null || mc.interactionManager == null) {
            running = false;
            return;
        }
        if (delay > 0) { delay--; return; }

        switch (step) {
            case PLACE_ANCHOR -> {
                select(mc, anchorSlot);
                mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, clickHit);
                mc.player.swingHand(Hand.MAIN_HAND);
                next(Step.CHARGE);
            }
            case CHARGE -> {
                if (!mc.world.getBlockState(anchorPos).isOf(Blocks.RESPAWN_ANCHOR)) {
                    abort(mc, "Anchor wasn't placed");
                    return;
                }
                select(mc, glowSlot);
                interact(mc, Hand.MAIN_HAND, anchorPos, faceTowardPlayer(mc, anchorPos));
                next(Step.SHIELD);
            }
            case SHIELD -> {
                placeShield(mc);
                next(Step.EXPLODE);
            }
            case EXPLODE -> {
                Hand hand;
                if (totemSlot >= 0) {
                    select(mc, totemSlot);
                    hand = Hand.MAIN_HAND;
                } else {
                    hand = Hand.OFF_HAND;
                }
                interact(mc, hand, anchorPos, faceTowardPlayer(mc, anchorPos));
                next(Step.RESTORE);
            }
            case RESTORE -> {
                select(mc, swordSlot);
                running = false;
            }
        }
    }

    private static void placeShield(MinecraftClient mc) {
        // Put the glowstone one block from the anchor, toward the player (horizontal).
        Direction toward = Direction.getFacing(
                mc.player.getX() - (anchorPos.getX() + 0.5), 0,
                mc.player.getZ() - (anchorPos.getZ() + 0.5));
        BlockPos shield = anchorPos.offset(toward);

        if (!mc.world.getBlockState(shield).isReplaceable()) return;

        select(mc, glowSlot);
        // Anchor right-click would charge it again, so click a different neighbour.
        for (Direction d : Direction.values()) {
            BlockPos support = shield.offset(d);
            if (support.equals(anchorPos)) continue;
            if (mc.world.getBlockState(support).isSolidBlock(mc.world, support)) {
                interact(mc, Hand.MAIN_HAND, support, d.getOpposite());
                return;
            }
        }
    }

    // ----------------------------------------------------------------- helpers

    private static void next(Step s) {
        step = s;
        delay = STEP_DELAY;
    }

    private static void abort(MinecraftClient mc, String msg) {
        select(mc, swordSlot);
        mc.player.sendMessage(Text.literal("Anchor Macro: " + msg), true);
        running = false;
    }

    private static void select(MinecraftClient mc, int slot) {
        if (slot < 0 || slot > 8) return;
        mc.player.getInventory().selectedSlot = slot;
        if (mc.getNetworkHandler() != null) {
            mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(slot));
        }
    }

    private static void interact(MinecraftClient mc, Hand hand, BlockPos pos, Direction side) {
        Vec3d hitPos = Vec3d.ofCenter(pos).add(
                side.getOffsetX() * 0.5, side.getOffsetY() * 0.5, side.getOffsetZ() * 0.5);
        BlockHitResult hit = new BlockHitResult(hitPos, side, pos, false);
        mc.interactionManager.interactBlock(mc.player, hand, hit);
        mc.player.swingHand(hand);
    }

    private static Direction faceTowardPlayer(MinecraftClient mc, BlockPos pos) {
        return Direction.getFacing(
                mc.player.getX() - (pos.getX() + 0.5),
                mc.player.getEyeY() - (pos.getY() + 0.5),
                mc.player.getZ() - (pos.getZ() + 0.5));
    }

    private static int findHotbar(PlayerInventory inv, Item item, int minCount) {
        for (int i = 0; i < 9; i++) {
            ItemStack s = inv.getStack(i);
            if (s.isOf(item) && s.getCount() >= minCount) return i;
        }
        return -1;
    }
}
