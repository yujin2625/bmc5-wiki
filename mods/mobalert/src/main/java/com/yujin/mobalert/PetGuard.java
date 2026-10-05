package com.yujin.mobalert;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.EntityHitResult;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.common.ItemAbilities;
import org.jetbrains.annotations.Nullable;

/**
 * 내 펫 보호 (클라이언트 전용).
 * 공격 판정은 서버가 하지만 공격 패킷은 클라이언트가 보내므로, 내 펫을 칠 공격이면 패킷을 보내기 전에 취소한다.
 * - 직접 공격: 조준한 대상이 내 펫이면 취소
 * - 휩쓸기: 서버와 같은 조건으로 휩쓸기가 나갈지 계산하고, 범위 안에 내 펫이 있으면 취소
 * 웅크린 채로 때리면 보호하지 않는다.
 */
public final class PetGuard {
    private PetGuard() {}

    public static void onInput(InputEvent.InteractionKeyMappingTriggered e) {
        if (!e.isAttack()) return;
        MobAlertConfig cfg = MobAlertConfig.get();
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (!cfg.petGuard || player == null || player.isShiftKeyDown()) return;
        if (!(mc.hitResult instanceof EntityHitResult hit)) return;
        Entity target = hit.getEntity();

        if (isMyPet(player, target)) {
            cancel(e, player, Component.translatable("mobalert.pet.blocked", Variants.typeName(target)));
            return;
        }
        if (cfg.petGuardSweep && wouldSweep(player)) {
            LivingEntity pet = petInSweepRange(player, target);
            if (pet != null) {
                cancel(e, player, Component.translatable("mobalert.pet.blocked_sweep", Variants.typeName(pet)));
            }
        }
    }

    public static boolean isMyPet(LocalPlayer player, Entity e) {
        // TamableAnimal(늑대, 고양이, 앵무새, 대부분의 모드 펫)은 주인 UUID 가 클라이언트에 동기화된다.
        return e instanceof OwnableEntity o && player.getUUID().equals(o.getOwnerUUID());
    }

    /** Player.attack 의 휩쓸기 조건을 클라이언트에서 그대로 계산한다. */
    private static boolean wouldSweep(LocalPlayer player) {
        boolean fullyCharged = player.getAttackStrengthScale(0.5f) > 0.9f;
        boolean sprintKnockback = player.isSprinting() && fullyCharged;
        double moved = player.walkDist - player.walkDistO;
        if (!fullyCharged || sprintKnockback || !player.onGround() || moved >= player.getSpeed()) return false;
        return player.getMainHandItem().canPerformAction(ItemAbilities.SWORD_SWEEP);
    }

    @Nullable
    private static LivingEntity petInSweepRange(LocalPlayer player, Entity target) {
        ItemStack stack = player.getMainHandItem();
        double reachSq = Mth.square(player.entityInteractionRange());
        for (LivingEntity e : player.level().getEntitiesOfClass(LivingEntity.class, stack.getSweepHitBox(player, target))) {
            if (e == player || e == target || player.isAlliedTo(e)) continue;
            if (e instanceof ArmorStand) continue;
            if (player.distanceToSqr(e) >= reachSq) continue;
            if (isMyPet(player, e)) return e;
        }
        return null;
    }

    private static void cancel(InputEvent.InteractionKeyMappingTriggered e, LocalPlayer player, Component msg) {
        e.setCanceled(true);
        e.setSwingHand(false);
        player.displayClientMessage(msg.copy().withStyle(ChatFormatting.YELLOW), true);
    }
}
