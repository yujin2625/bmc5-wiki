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
        ANY("아무거나"),
        BASKET("도시락 바구니만");

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
    private float eatYaw;
    private int toggles;
    private int nextToggleAt;

    // ---- Supplementaries 도시락 바구니 (리플렉션: 모드가 없어도 문제없게) ----

    private static final net.minecraft.resources.ResourceLocation BASKET_ID =
            net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("supplementaries", "lunch_basket");

    public static boolean isBasket(ItemStack stack) {
        return !stack.isEmpty() && net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(BASKET_ID);
    }

    /** 바구니 안 내용 (LunchBaskedContent). 없으면 null. */
    private static Object basketContent(ItemStack stack) {
        if (!isBasket(stack)) {
            return null;
        }
        for (net.minecraft.core.component.TypedDataComponent<?> c : stack.getComponents()) {
            Object v = c.value();
            if (v != null && v.getClass().getSimpleName().startsWith("LunchBasked")) {
                return v;
            }
        }
        return null;
    }

    /** 바구니에서 지금 고른 음식 (먹으면 이게 나간다). 비었으면 빈 스택. */
    public static ItemStack basketSelected(ItemStack stack) {
        Object content = basketContent(stack);
        if (content == null) {
            return ItemStack.EMPTY;
        }
        try {
            Object selected = content.getClass().getMethod("getSelected").invoke(content);
            return selected instanceof ItemStack s ? s : ItemStack.EMPTY;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return ItemStack.EMPTY;
        }
    }

    /** 바구니가 열려 있는지(먹기 모드). */
    private static boolean basketOpen(ItemStack stack) {
        Object content = basketContent(stack);
        if (content == null) {
            return false;
        }
        try {
            return (Boolean) content.getClass().getMethod("canEatFrom").invoke(content);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

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

    /** 먹을 음식 슬롯. 해로운 효과가 없는 것 중 허기를 가장 많이 채우는 것. 바구니 모드면 음식이 든 도시락 바구니. */
    public static int findFood(LocalPlayer player, Predicate<ItemStack> cropProduct) {
        FoodMode mode = AfkConfig.FOOD_MODE.get();
        if (mode == FoodMode.OFF) {
            return -1;
        }
        if (mode == FoodMode.BASKET) {
            return InvUtil.find(player, s -> isBasket(s) && !basketSelected(s).isEmpty());
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
        eatYaw = player.getYRot();
        toggles = 0;
        nextToggleAt = 0;
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
        boolean basket = AfkConfig.FOOD_MODE.get() == FoodMode.BASKET;
        if (basket) {
            if (!isBasket(held) || basketSelected(held).isEmpty()) {
                if (!InvUtil.ensureInHand(mc, player, s -> isBasket(s) && !basketSelected(s).isEmpty())) {
                    return Result.RUNNING;
                }
                held = player.getInventory().getSelected();
            }
        } else {
            net.minecraft.world.item.Item want = player.getInventory().getItem(slot).getItem();
            if (!held.is(want) && !InvUtil.ensureInHand(mc, player, s -> s.is(want))) {
                return Result.RUNNING;
            }
        }
        // 하늘 쪽을 보고 먹는다 (사용 키가 블록을 우클릭하지 않게)
        Look.INSTANCE.setAngles(player, eatYaw, -70f, 3f);
        boolean pointingAtBlock = mc.hitResult != null && mc.hitResult.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK;
        if ((!Look.INSTANCE.aligned(player, 6f) || pointingAtBlock) && ticks < 40) {
            return Result.RUNNING;
        }
        if (basket && !basketOpen(held)) {
            // 도시락 바구니는 왼클릭으로 열어야(먹기 모드) 안의 음식을 먹을 수 있다. 하늘을 보고 한 번 왼클릭.
            if (ticks >= nextToggleAt) {
                if (++toggles > 3) {
                    return finish(mc, Result.FAILED);
                }
                net.minecraft.client.KeyMapping.click(mc.options.keyAttack.getKey());
                nextToggleAt = ticks + 15; // 서버에서 바뀐 상태가 돌아올 때까지
            }
            return Result.RUNNING;
        }
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
