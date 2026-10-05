package com.dumaru.afkfishing.common;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * 지정한 보관함(상자, 태클박스 등)까지 걸어가서 열고, 조건에 맞는 인벤토리 아이템을 Shift+클릭으로 옮기고,
 * 닫은 뒤 (지정했으면) 원래 자리로 돌아온다. 보관함마다 넣을 아이템 조건과 남길 개수를 따로 준다.
 */
public final class DepositModule {
    public enum Result { RUNNING, DONE, FAILED }

    private enum Phase { NONE, MOVE, OPEN, WAIT_OPEN, MOVE_ITEMS, RETURN }

    /** 보관함 하나. keep = 이 아이템을 인벤토리에 최소 몇 개 남길지. */
    public record Target(BlockPos pos, String name, Predicate<ItemStack> filter, ToIntFunction<Item> keep) {
    }

    private static final double REACH = 4.0;
    private static final int OPEN_TIMEOUT = 40;

    private final String prefix;
    private final Random random = new Random();
    private final List<Target> queue = new ArrayList<>();
    private Target current;
    private Phase phase = Phase.NONE;
    private int phaseTicks;
    private int faceStart;
    private Vec3 returnSpot;
    private final List<Integer> slots = new ArrayList<>();
    private int nextClickAt;
    private int pending = -1;
    private Item pendingItem;
    private int pendingCount;
    private int moved;
    private boolean leftovers;

    public DepositModule(String prefix) {
        this.prefix = prefix;
    }

    public boolean isActive() {
        return phase != Phase.NONE;
    }

    public String label() {
        return switch (phase) {
            case MOVE -> current.name + "(으)로 이동 중";
            case OPEN, WAIT_OPEN -> current.name + " 여는 중";
            case MOVE_ITEMS -> current.name + "에 넣는 중";
            case RETURN -> "낚시 자리로 복귀 중";
            default -> "";
        };
    }

    /** 이번 정리에서 옮긴 아이템 수. */
    public int moved() {
        return moved;
    }

    /** 넣지 못하고 남은 게 있었는지 (보관함이 가득 참 등). */
    public boolean hadLeftovers() {
        return leftovers;
    }

    /** 이 보관함에 넣을 게 하나라도 있는지. */
    public static boolean hasAnything(LocalPlayer player, Target target) {
        return !inventorySlots(player, target).isEmpty();
    }

    public void begin(List<Target> targets, Vec3 returnSpot) {
        queue.clear();
        queue.addAll(targets);
        this.returnSpot = returnSpot;
        moved = 0;
        leftovers = false;
        current = null;
        nextTarget(Minecraft.getInstance().player);
    }

    public void cancel(Minecraft mc) {
        if (phase == Phase.NONE) {
            return;
        }
        Navigator.INSTANCE.cancel();
        LocalPlayer player = mc.player;
        if (player != null && (phase == Phase.WAIT_OPEN || phase == Phase.MOVE_ITEMS) && player.containerMenu != player.inventoryMenu) {
            player.closeContainer();
        }
        phase = Phase.NONE;
    }

    public Result tick(Minecraft mc, LocalPlayer player) {
        phaseTicks++;
        switch (phase) {
            case MOVE -> {
                Navigator.Result r = Navigator.INSTANCE.tick(player);
                if (r == Navigator.Result.ARRIVED) {
                    setPhase(Phase.OPEN);
                } else if (r == Navigator.Result.FAILED) {
                    Util.notify(player, prefix, current.name + "까지 가지 못했습니다 (" + Navigator.INSTANCE.failReason() + ").",
                            ChatFormatting.GOLD);
                    leftovers = true;
                    nextTarget(player);
                }
            }
            case OPEN -> tickOpen(mc, player);
            case WAIT_OPEN -> {
                if (player.containerMenu != player.inventoryMenu) {
                    setPhase(Phase.MOVE_ITEMS);
                } else if (phaseTicks > OPEN_TIMEOUT) {
                    Util.notify(player, prefix, current.name + " " + current.pos.toShortString() + "을(를) 열지 못했습니다.",
                            ChatFormatting.GOLD);
                    leftovers = true;
                    nextTarget(player);
                }
            }
            case MOVE_ITEMS -> tickMoveItems(mc, player);
            case RETURN -> {
                Navigator.Result r = Navigator.INSTANCE.tick(player);
                if (r == Navigator.Result.ARRIVED) {
                    phase = Phase.NONE;
                    return Result.DONE;
                } else if (r == Navigator.Result.FAILED) {
                    phase = Phase.NONE;
                    return Result.FAILED;
                }
            }
            default -> {
                return Result.DONE;
            }
        }
        return phase == Phase.NONE ? Result.DONE : Result.RUNNING;
    }

    private void setPhase(Phase next) {
        phase = next;
        phaseTicks = 0;
        faceStart = -1;
    }

    private void nextTarget(LocalPlayer player) {
        while (!queue.isEmpty()) {
            Target t = queue.remove(0);
            if (!hasAnything(player, t)) {
                continue;
            }
            current = t;
            if (player.getEyePosition().distanceTo(Vec3.atCenterOf(t.pos)) <= REACH) {
                setPhase(Phase.OPEN);
            } else {
                Navigator.INSTANCE.start(Navigator.reach(t.pos, REACH - 0.3), null, 0, 900);
                setPhase(Phase.MOVE);
            }
            return;
        }
        // 다 넣었으면 원래 자리로
        if (returnSpot != null && Util.horizontalDistance(returnSpot, player.position()) > 0.15) {
            Navigator.INSTANCE.start(Navigator.near(returnSpot, 0.6), returnSpot, 0.08, 900);
            setPhase(Phase.RETURN);
        } else {
            phase = Phase.NONE;
        }
    }

    private void tickOpen(Minecraft mc, LocalPlayer player) {
        BlockPos pos = current.pos;
        Direction face = Util.faceToward(player, pos);
        Vec3 hit = Util.faceCenter(pos, face);
        Look.INSTANCE.lookAt(player, hit, 1.2f);
        if (faceStart < 0) {
            faceStart = phaseTicks;
        }
        if (!Look.INSTANCE.aligned(player, 6f) && phaseTicks - faceStart <= 25) {
            return;
        }
        if (player.isShiftKeyDown()) {
            return; // 웅크린 채 우클릭하면 태클박스가 통째로 회수되므로 열지 않는다
        }
        if (player.getMainHandItem().getItem() instanceof BlockItem) {
            InvUtil.selectEmptyHand(player); // 블록을 들고 우클릭하면 놓일 수 있다
        }
        mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, new BlockHitResult(hit, face, pos, false));
        player.swing(InteractionHand.MAIN_HAND);
        setPhase(Phase.WAIT_OPEN);
    }

    private void tickMoveItems(Minecraft mc, LocalPlayer player) {
        AbstractContainerMenu menu = player.containerMenu;
        if (menu == player.inventoryMenu) {
            nextTarget(player); // 창이 닫혀 버림
            return;
        }
        // 지난번에 옮긴 칸이 그대로면 보관함이 가득 찬 것
        if (pending >= 0) {
            ItemStack now = menu.getSlot(pending).getItem();
            if (now.is(pendingItem) && now.getCount() >= pendingCount) {
                leftovers = true;
                slots.removeIf(i -> menu.getSlot(i).getItem().is(pendingItem));
            } else {
                moved += pendingCount - (now.is(pendingItem) ? now.getCount() : 0);
            }
            pending = -1;
        }
        if (phaseTicks == 3) {
            slots.clear();
            slots.addAll(menuSlots(player, menu, inventorySlots(player, current)));
            nextClickAt = 4;
        }
        if (phaseTicks < nextClickAt) {
            return;
        }
        if (slots.isEmpty()) {
            player.closeContainer();
            nextTarget(player);
            return;
        }
        int menuSlot = slots.remove(0);
        ItemStack stack = menu.getSlot(menuSlot).getItem();
        if (!stack.isEmpty()) {
            pending = menuSlot;
            pendingItem = stack.getItem();
            pendingCount = stack.getCount();
            mc.gameMode.handleInventoryMouseClick(menu.containerId, menuSlot, 0, ClickType.QUICK_MOVE, player);
        }
        // 사람처럼 클릭 사이에 조금씩 다른 틈
        nextClickAt = phaseTicks + 2 + random.nextInt(3);
    }

    /** 넣을 인벤토리 칸(0~35). 남길 개수를 지키도록 큰 묶음부터 고른다. */
    private static List<Integer> inventorySlots(LocalPlayer player, Target target) {
        Inventory inv = player.getInventory();
        Map<Item, List<int[]>> byItem = new HashMap<>();
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && target.filter.test(stack)) {
                byItem.computeIfAbsent(stack.getItem(), k -> new ArrayList<>()).add(new int[]{i, stack.getCount()});
            }
        }
        List<Integer> result = new ArrayList<>();
        for (Map.Entry<Item, List<int[]>> e : byItem.entrySet()) {
            int total = InvUtil.count(player, e.getKey());
            int keep = target.keep.applyAsInt(e.getKey());
            List<int[]> stacks = e.getValue();
            stacks.sort((a, b) -> b[1] - a[1]);
            for (int[] s : stacks) {
                if (total - s[1] >= keep) {
                    result.add(s[0]);
                    total -= s[1];
                }
            }
        }
        return result;
    }

    private static List<Integer> menuSlots(LocalPlayer player, AbstractContainerMenu menu, List<Integer> invSlots) {
        List<Integer> result = new ArrayList<>();
        Inventory inv = player.getInventory();
        for (int invSlot : invSlots) {
            for (Slot slot : menu.slots) {
                if (slot.container == inv && slot.getContainerSlot() == invSlot) {
                    result.add(slot.index);
                    break;
                }
            }
        }
        return result;
    }
}
