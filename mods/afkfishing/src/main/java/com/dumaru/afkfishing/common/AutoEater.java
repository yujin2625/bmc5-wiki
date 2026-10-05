package com.dumaru.afkfishing.common;

import com.dumaru.afkfishing.AfkConfig;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * 배고프면 음식을 먹는다. Right Click Harvest가 수확할 때마다 배고픔을 깎기 때문에 필요하다.
 * 먹기는 사용 키를 누르고 있어야 계속되므로 먹는 동안만 사용 키를 눌린 상태로 둔다.
 * 그동안 블록을 가리키면 사용 키가 블록을 우클릭하므로 하늘을 본다.
 */
public final class AutoEater {
    public enum FoodMode {
        OFF("끔"),
        NON_CROP("작물 제외"),
        ANY("아무거나");

        public final String label;

        FoodMode(String label) {
            this.label = label;
        }
    }

    public enum Result { RUNNING, DONE, FAILED }

    private static final int EAT_TIMEOUT = 200;

    private boolean active;
    private int ticks;
    private int startFood;

    public boolean isActive() {
        return active;
    }

    /** 지금 먹어야 하는지. cropProduct는 NON_CROP 모드에서 먹지 않을 아이템. */
    public boolean needsEat(LocalPlayer player, Predicate<ItemStack> cropProduct) {
        if (AfkConfig.FOOD_MODE.get() == FoodMode.OFF || player.isSleeping()) {
            return false;
        }
        return player.getFoodData().getFoodLevel() <= AfkConfig.EAT_BELOW.get() && findFood(player, cropProduct) >= 0;
    }

    /** 먹을 음식 슬롯. 해로운 효과가 없는 것 중 허기를 가장 많이 채우는 것. */
    public static int findFood(LocalPlayer player, Predicate<ItemStack> cropProduct) {
        FoodMode mode = AfkConfig.FOOD_MODE.get();
        if (mode == FoodMode.OFF) {
            return -1;
        }
        Inventory inv = player.getInventory();
        int best = -1;
        int bestNutrition = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inv.getItem(i);
            if (!isSafeFood(stack) || (mode == FoodMode.NON_CROP && cropProduct.test(stack))) {
                continue;
            }
            int nutrition = stack.get(DataComponents.FOOD).nutrition();
            if (nutrition > bestNutrition) {
                bestNutrition = nutrition;
                best = i;
            }
        }
        return best;
    }

    public static boolean isSafeFood(ItemStack stack) {
        FoodProperties food = stack.get(DataComponents.FOOD);
        // 효과가 붙은 음식(썩은 살점, 독감자, 복어 등)과 순간이동하는 후렴과는 먹지 않는다.
        return food != null && food.effects().isEmpty() && !stack.is(Items.CHORUS_FRUIT) && food.nutrition() > 0;
    }

    public void begin(LocalPlayer player) {
        active = true;
        ticks = 0;
        startFood = player.getFoodData().getFoodLevel();
    }

    public Result tick(Minecraft mc, LocalPlayer player, Predicate<ItemStack> cropProduct) {
        ticks++;
        int food = player.getFoodData().getFoodLevel();
        if (food >= 20 || (food >= 18 && !player.isUsingItem())) {
            return finish(mc, Result.DONE);
        }
        if (ticks > EAT_TIMEOUT) {
            return finish(mc, food > startFood ? Result.DONE : Result.FAILED);
        }
        int slot = findFood(player, cropProduct);
        if (slot < 0 && !player.isUsingItem()) {
            return finish(mc, food > startFood ? Result.DONE : Result.FAILED);
        }
        if (player.isUsingItem()) {
            mc.options.keyUse.setDown(true);
            return Result.RUNNING;
        }
        ItemStack held = player.getInventory().getSelected();
        net.minecraft.world.item.Item want = player.getInventory().getItem(slot).getItem();
        if (!held.is(want) && !InvUtil.ensureInHand(mc, player, s -> s.is(want))) {
            return Result.RUNNING;
        }
        player.setXRot(-90); // 하늘을 보고 먹는다 (블록 우클릭 방지)
        mc.gameMode.useItem(player, InteractionHand.MAIN_HAND);
        mc.options.keyUse.setDown(true);
        return Result.RUNNING;
    }

    public void cancel(Minecraft mc) {
        if (active) {
            finish(mc, Result.FAILED);
        }
    }

    private Result finish(Minecraft mc, Result result) {
        active = false;
        mc.options.keyUse.setDown(false);
        if (mc.player != null && mc.player.isUsingItem()) {
            mc.gameMode.releaseUsingItem(mc.player);
        }
        return result;
    }
}
