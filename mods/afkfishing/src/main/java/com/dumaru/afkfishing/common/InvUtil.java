package com.dumaru.afkfishing.common;

import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/** 인벤토리 검색과 손에 드는 처리. 슬롯 번호는 Inventory 기준 (0~8 핫바, 9~35 메인). */
public final class InvUtil {
    private InvUtil() {
    }

    /** 선택된 칸 → 핫바 → 메인 인벤토리 순으로 찾는다. 없으면 -1. */
    public static int find(LocalPlayer player, Predicate<ItemStack> filter) {
        Inventory inv = player.getInventory();
        if (filter.test(inv.getSelected())) {
            return inv.selected;
        }
        for (int i = 0; i < 36; i++) {
            if (filter.test(inv.getItem(i))) {
                return i;
            }
        }
        return -1;
    }

    public static int count(LocalPlayer player, Item item) {
        Inventory inv = player.getInventory();
        int total = 0;
        for (int i = 0; i < 36; i++) {
            if (inv.getItem(i).is(item)) {
                total += inv.getItem(i).getCount();
            }
        }
        if (player.getOffhandItem().is(item)) {
            total += player.getOffhandItem().getCount();
        }
        return total;
    }

    public static int freeSlots(LocalPlayer player) {
        Inventory inv = player.getInventory();
        int free = 0;
        for (int i = 0; i < 36; i++) {
            if (inv.getItem(i).isEmpty()) {
                free++;
            }
        }
        return free;
    }

    /** 내구도가 minDurability보다 많이 남았는지 (내구도 없는 아이템은 항상 true). */
    public static boolean hasDurability(ItemStack stack, int minDurability) {
        return !stack.isDamageableItem() || stack.getMaxDamage() - stack.getDamageValue() > minDurability;
    }

    /**
     * 조건에 맞는 아이템을 손에 든다. 이미 들고 있으면 true.
     * 핫바에 있으면 칸을 바꾸고 true, 메인 인벤토리에 있으면 선택된 핫바 칸과 바꾸고 false(서버 반영 후 다음 틱).
     * 없거나 다른 창이 열려 있으면 false.
     */
    public static boolean ensureInHand(Minecraft mc, LocalPlayer player, Predicate<ItemStack> filter) {
        Inventory inv = player.getInventory();
        if (filter.test(inv.getSelected())) {
            return true;
        }
        int slot = find(player, filter);
        if (slot < 0) {
            return false;
        }
        if (Inventory.isHotbarSlot(slot)) {
            inv.selected = slot;
            return true;
        }
        if (player.containerMenu == player.inventoryMenu) {
            // 인벤토리 메뉴에서 메인 인벤토리 9~35는 메뉴 슬롯 번호와 같다. SWAP 버튼 = 핫바 칸 번호.
            mc.gameMode.handleInventoryMouseClick(player.inventoryMenu.containerId, slot, inv.selected, ClickType.SWAP, player);
        }
        return false;
    }

    /** 빈 손으로 바꾼다 (핫바에 빈 칸이 있으면). 바꿀 수 없으면 false. */
    public static boolean selectEmptyHand(LocalPlayer player) {
        Inventory inv = player.getInventory();
        if (inv.getSelected().isEmpty()) {
            return true;
        }
        for (int i = 0; i < 9; i++) {
            if (inv.getItem(i).isEmpty()) {
                inv.selected = i;
                return true;
            }
        }
        return false;
    }

    /** 이 블록을 가장 빨리 부수는 도구가 있는 슬롯. 맨손보다 나은 게 없으면 -1. */
    public static int bestToolSlot(LocalPlayer player, BlockState state, int minDurability) {
        Inventory inv = player.getInventory();
        int best = -1;
        float bestSpeed = 1.0f;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty() || !hasDurability(stack, minDurability)) {
                continue;
            }
            float speed = stack.getDestroySpeed(state);
            if (speed > bestSpeed + 0.01f || (best >= 0 && speed == bestSpeed && i == inv.selected)) {
                bestSpeed = speed;
                best = i;
            }
        }
        return best;
    }
}
