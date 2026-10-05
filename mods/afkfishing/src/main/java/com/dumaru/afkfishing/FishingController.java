package com.dumaru.afkfishing;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.FishingRodItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * 낚시 상태 머신. 매 클라이언트 틱마다 호출되며 마우스 입력 대신 gameMode.useItem()을 직접 호출하므로
 * 창 포커스와 무관하게 동작한다.
 */
public final class FishingController {
    public static final FishingController INSTANCE = new FishingController();

    public enum State {
        IDLE("정지"),
        CASTING("투척"),
        WAITING("입질 대기"),
        REELING("회수 중"),
        COOLDOWN("재투척 대기"),
        MOVING("AFK 방지 이동");

        public final String label;

        State(String label) {
            this.label = label;
        }
    }

    private static final int HOOK_LAND_TIMEOUT = 100; // 찌가 물에 들어가길 기다리는 시간 (틱)
    private static final int MAX_LAND_FAILURES = 5;
    private static final int LOOT_CHECK_DELAY = 40; // 회수 후 아이템이 날아올 시간 (틱)
    private static final int OPEN_WATER_CHECK_INTERVAL = 10;
    private static final int LURE_RETURN_TIMEOUT = 40; // 회수 후 바늘이 왼손으로 돌아오길 기다리는 시간 (틱)
    private static final int OFFHAND_MENU_SLOT_BUTTON = 40; // SWAP 클릭에서 왼손을 뜻하는 버튼 번호
    // Hybrid Aquatic 낚싯바늘. 던질 때 왼손에서 찌로 옮겨지고 회수하면 돌아온다.
    private static final TagKey<Item> LURE_ITEMS =
            TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath("hybrid_aquatic", "lure_items"));

    private final Random random = new Random();
    private final AntiAfkMover mover = new AntiAfkMover(random);

    private State state = State.IDLE;
    private int stateTicks;
    private int delay;
    private int landFailures;
    private boolean hookLanded;
    private String lastReelReason = "";
    // 탁 트인 물 판정. null = 아직 확인 전
    private Boolean openWater;
    private String openWaterReason = "";
    private boolean openWaterWarned;
    // 시작할 때 왼손에 있던 낚싯바늘. null이면 바늘 없이 낚시.
    private Item expectedLure;
    private float lockedYaw;
    private float lockedPitch;
    private float lastHealth;

    // 통계
    private long startedAtMillis;
    private int catches;
    private final Map<Item, Integer> loot = new HashMap<>();
    private Map<Item, Integer> lootSnapshot;
    private int lootCheckIn = -1;
    private String lastMessage = "";

    private FishingController() {
    }

    public AntiAfkMover mover() {
        return mover;
    }

    public boolean isRunning() {
        return state != State.IDLE;
    }

    public State state() {
        return state;
    }

    public int catches() {
        return catches;
    }

    public long elapsedMillis() {
        return isRunning() ? System.currentTimeMillis() - startedAtMillis : 0;
    }

    public String lastReelReason() {
        return lastReelReason;
    }

    public Boolean openWater() {
        return openWater;
    }

    public String openWaterReason() {
        return openWaterReason;
    }

    /** HUD 표시용: 왼손 낚싯바늘 상태. 바늘 없이 낚시 중이면 null. */
    public String lureStatus(LocalPlayer player) {
        if (expectedLure == null) {
            return null;
        }
        ItemStack off = player.getOffhandItem();
        String durability = off.is(expectedLure) && off.isDamageableItem()
                ? (off.getMaxDamage() - off.getDamageValue()) + "/" + off.getMaxDamage()
                : "-";
        int spare = 0;
        Inventory inv = player.getInventory();
        for (int i = 0; i < 36; i++) {
            if (inv.getItem(i).is(expectedLure)) {
                spare += inv.getItem(i).getCount();
            }
        }
        return expectedLure.getDescription().getString() + " " + durability + " · 예비 " + spare;
    }

    public String lastMessage() {
        return lastMessage;
    }

    public Map<Item, Integer> lootSortedDesc() {
        Map<Item, Integer> sorted = new LinkedHashMap<>();
        loot.entrySet().stream()
                .sorted(Map.Entry.<Item, Integer>comparingByValue().reversed())
                .forEach(e -> sorted.put(e.getKey(), e.getValue()));
        return sorted;
    }

    public void resetStats() {
        catches = 0;
        loot.clear();
        startedAtMillis = System.currentTimeMillis();
    }

    public void toggle() {
        if (isRunning()) {
            stop("수동 정지");
        } else {
            start();
        }
    }

    public void start() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || isRunning()) {
            return;
        }
        if (findUsableRod(player) < 0) {
            notify(player, "사용할 수 있는 낚싯대가 없습니다.", ChatFormatting.RED);
            return;
        }
        lockedYaw = player.getYRot();
        lockedPitch = player.getXRot();
        lastHealth = player.getHealth();
        landFailures = 0;
        lastReelReason = "";
        openWater = null;
        openWaterReason = "";
        openWaterWarned = false;
        ItemStack offhand = player.getOffhandItem();
        expectedLure = offhand.is(LURE_ITEMS) ? offhand.getItem() : null;
        startedAtMillis = System.currentTimeMillis();
        mover.reset();
        lastMessage = "";
        setState(State.CASTING, 0);
        notify(player, "AFK 낚시 시작", ChatFormatting.GREEN);
    }

    public void stop(String reason) {
        if (!isRunning()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.player.fishing != null && mc.gameMode != null && mc.getConnection() != null
                && mc.player.isAlive()) {
            // 찌를 남겨 두면 접속 종료 등으로 찌가 사라질 때 낚싯바늘도 같이 사라지므로 먼저 회수한다.
            useRod(mc, mc.player, "정지 시 회수");
        }
        state = State.IDLE;
        mover.cancel();
        lootCheckIn = -1;
        lastMessage = reason;
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) {
            notify(player, "AFK 낚시 정지: " + reason, ChatFormatting.YELLOW);
            Minecraft.getInstance().getSoundManager().play(
                    SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING.value(), 1.0f));
        }
    }

    public void tick(Minecraft mc) {
        if (!isRunning()) {
            return;
        }
        LocalPlayer player = mc.player;
        if (player == null || mc.gameMode == null || mc.level == null) {
            stop("월드 없음");
            return;
        }
        if (!checkSafety(player)) {
            return;
        }
        tickLootCheck(player);
        stateTicks++;

        switch (state) {
            case CASTING -> tickCasting(mc, player);
            case WAITING -> tickWaiting(mc, player);
            case REELING -> tickReeling(mc, player);
            case COOLDOWN -> tickCooldown(player);
            case MOVING -> {
                AntiAfkMover.Result result = mover.tick(player);
                if (result == AntiAfkMover.Result.DONE) {
                    setState(State.CASTING, 0);
                } else if (result == AntiAfkMover.Result.FAILED) {
                    stop(mover.failReason());
                }
            }
            default -> {
            }
        }
        if (state != State.MOVING && state != State.IDLE) {
            mover.countdown();
        }
    }

    private boolean checkSafety(LocalPlayer player) {
        if (player.isDeadOrDying()) {
            stop("사망");
            return false;
        }
        float health = player.getHealth();
        if (AfkConfig.STOP_ON_DAMAGE.get() && health < lastHealth - 0.01f) {
            lastHealth = health;
            stop("피해를 입음");
            return false;
        }
        lastHealth = health;
        if (AfkConfig.STOP_WHEN_FULL.get() && player.getInventory().getFreeSlot() < 0) {
            stop("인벤토리 가득 참");
            return false;
        }
        return true;
    }

    private void tickCasting(Minecraft mc, LocalPlayer player) {
        if (player.fishing != null) {
            // 이전 찌가 아직 사라지지 않음. 너무 오래 남아 있으면 한 번 더 당겨서 회수.
            if (stateTicks > 40) {
                useRod(mc, player, "남아 있던 찌 정리");
                stateTicks = 0;
            }
            return;
        }
        if (!ensureRodInHand(mc, player) || !ensureLureInOffhand(mc, player)) {
            return;
        }
        player.setYRot(lockedYaw);
        player.setXRot(lockedPitch);
        useRod(mc, player, null);
        hookLanded = false;
        setState(State.WAITING, 0);
    }

    private void tickWaiting(Minecraft mc, LocalPlayer player) {
        FishingHook hook = player.fishing;
        if (hook == null) {
            // 서버가 찌를 제거함 (거리 초과, 아이템 변경 등). 다시 던진다.
            if (stateTicks > 20) {
                setState(State.COOLDOWN, randomBetween(AfkConfig.RECAST_DELAY_MIN.get(), AfkConfig.RECAST_DELAY_MAX.get()));
            }
            return;
        }
        if (hook.biting) {
            setState(State.REELING, randomBetween(AfkConfig.REEL_DELAY_MIN.get(), AfkConfig.REEL_DELAY_MAX.get()));
            return;
        }
        if (hook.getHookedIn() != null) {
            recast(mc, player, "찌가 엔티티에 걸림");
            return;
        }
        // 수면에서 출렁이는 찌는 클라이언트에서 잠깐씩 물 밖으로 판정될 수 있으므로,
        // 한 번이라도 물에 들어갔으면 그 투척에서는 물 밖 검사를 하지 않는다.
        if (!hookLanded && hook.isInWater()) {
            hookLanded = true;
            landFailures = 0;
            checkOpenWater(player, hook);
        } else if (hookLanded && stateTicks % OPEN_WATER_CHECK_INTERVAL == 0) {
            checkOpenWater(player, hook);
        }
        if (!hookLanded && stateTicks > HOOK_LAND_TIMEOUT) {
            landFailures++;
            if (landFailures >= MAX_LAND_FAILURES) {
                useRod(mc, player, "찌 물 밖");
                stop("찌가 물에 들어가지 않음");
                return;
            }
            recast(mc, player, "찌 물 밖");
            return;
        }
        if (stateTicks > AfkConfig.BITE_TIMEOUT_SECONDS.get() * 20) {
            recast(mc, player, "입질 대기 시간 초과");
        }
    }

    private void checkOpenWater(LocalPlayer player, FishingHook hook) {
        OpenWaterChecker.Result result = OpenWaterChecker.check(hook.level(), hook.blockPosition());
        openWater = result.ok();
        openWaterReason = result.reason();
        if (!result.ok() && !openWaterWarned) {
            openWaterWarned = true;
            notify(player, "탁 트인 물이 아닙니다. 보물(책 등)이 나오지 않습니다: " + result.reason(), ChatFormatting.GOLD);
        }
    }

    private void tickReeling(Minecraft mc, LocalPlayer player) {
        if (--delay > 0) {
            return;
        }
        if (player.fishing != null) {
            lootSnapshot = countInventory(player.getInventory());
            lootCheckIn = LOOT_CHECK_DELAY;
            useRod(mc, player, "입질");
            catches++;
        }
        setState(State.COOLDOWN, randomBetween(AfkConfig.RECAST_DELAY_MIN.get(), AfkConfig.RECAST_DELAY_MAX.get()));
    }

    private void tickCooldown(LocalPlayer player) {
        if (--delay > 0) {
            return;
        }
        if (AfkConfig.ANTI_AFK.get() && mover.isDue() && mover.begin(player, lockedYaw)) {
            setState(State.MOVING, 0);
            return;
        }
        setState(State.CASTING, 0);
    }

    private void recast(Minecraft mc, LocalPlayer player, String reason) {
        useRod(mc, player, reason);
        setState(State.COOLDOWN, randomBetween(AfkConfig.RECAST_DELAY_MIN.get(), AfkConfig.RECAST_DELAY_MAX.get()));
    }

    private void setState(State next, int delayTicks) {
        state = next;
        stateTicks = 0;
        delay = delayTicks;
    }

    /** reason이 null이 아니면 회수 동작으로 보고 이유를 기록한다 (HUD 표시용). */
    private void useRod(Minecraft mc, LocalPlayer player, String reason) {
        if (reason != null) {
            lastReelReason = reason;
        }
        InteractionResult result = mc.gameMode.useItem(player, InteractionHand.MAIN_HAND);
        if (result.shouldSwing()) {
            player.swing(InteractionHand.MAIN_HAND);
        }
    }

    /** 접속 종료 직전에 호출. 찌가 나가 있으면 회수 패킷을 보내 낚싯바늘을 돌려받는다. */
    public void reelInBeforeDisconnect() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.player.fishing != null && mc.gameMode != null && mc.player.isAlive()) {
            useRod(mc, mc.player, "접속 종료 전 회수");
        }
        state = State.IDLE;
        mover.cancel();
    }

    /**
     * 시작할 때 왼손에 낚싯바늘이 있었다면 투척 전에 다시 왼손에 있는지 확인한다.
     * 인벤토리로 돌아왔으면 왼손으로 옮기고, 끝내 없으면 정지한다.
     */
    private boolean ensureLureInOffhand(Minecraft mc, LocalPlayer player) {
        if (expectedLure == null || player.getOffhandItem().is(expectedLure)) {
            return true;
        }
        Inventory inv = player.getInventory();
        for (int i = 0; i < 36; i++) {
            if (inv.getItem(i).is(expectedLure) && player.containerMenu == player.inventoryMenu
                    && player.getOffhandItem().isEmpty()) {
                int menuSlot = Inventory.isHotbarSlot(i) ? 36 + i : i;
                mc.gameMode.handleInventoryMouseClick(player.inventoryMenu.containerId, menuSlot,
                        OFFHAND_MENU_SLOT_BUTTON, ClickType.SWAP, player);
                notify(player, "낚싯바늘이 인벤토리로 돌아와 있어서 왼손으로 옮겼습니다.", ChatFormatting.GRAY);
                return false; // 서버 반영 후 다음 틱에 투척
            }
        }
        if (stateTicks < LURE_RETURN_TIMEOUT) {
            return false; // 서버에서 돌려주는 중일 수 있으니 잠시 대기
        }
        if (!player.getOffhandItem().isEmpty()) {
            stop("왼손에 다른 아이템이 있어 낚싯바늘을 끼울 수 없음");
        } else {
            // 낚싯바늘은 낚을 때마다 내구도가 1씩 닳는다 (자석 8, 가시/발광 16).
            stop("낚싯바늘 소진 - 인벤토리에 예비 " + expectedLure.getDescription().getString() + " 없음");
        }
        return false;
    }

    // ---- 낚싯대 관리 ----

    private boolean ensureRodInHand(Minecraft mc, LocalPlayer player) {
        Inventory inv = player.getInventory();
        if (isUsableRod(inv.getSelected())) {
            return true;
        }
        int slot = findUsableRod(player);
        if (slot < 0 || !AfkConfig.AUTO_SWAP_ROD.get()) {
            stop("사용할 수 있는 낚싯대 없음 (내구도 부족)");
            return false;
        }
        if (Inventory.isHotbarSlot(slot)) {
            inv.selected = slot;
            return true;
        }
        if (player.containerMenu != player.inventoryMenu) {
            return false; // 다른 컨테이너가 열려 있으면 다음 틱에 다시 시도
        }
        // 인벤토리 메뉴에서 메인 인벤토리 슬롯 9~35는 메뉴 슬롯 번호와 같다. SWAP으로 선택된 핫바 칸과 교환.
        mc.gameMode.handleInventoryMouseClick(player.inventoryMenu.containerId, slot, inv.selected, ClickType.SWAP, player);
        return false; // 서버 반영 후 다음 틱에 투척
    }

    /** 선택된 칸 → 핫바 → 메인 인벤토리 순으로 사용 가능한 낚싯대 슬롯을 찾는다. 없으면 -1. */
    private int findUsableRod(LocalPlayer player) {
        Inventory inv = player.getInventory();
        if (isUsableRod(inv.getSelected())) {
            return inv.selected;
        }
        for (int i = 0; i < 36; i++) {
            if (isUsableRod(inv.getItem(i))) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isRod(ItemStack stack) {
        return stack.getItem() instanceof FishingRodItem;
    }

    private static boolean isUsableRod(ItemStack stack) {
        if (!isRod(stack)) {
            return false;
        }
        if (!stack.isDamageableItem()) {
            return true;
        }
        return stack.getMaxDamage() - stack.getDamageValue() > AfkConfig.MIN_DURABILITY.get();
    }

    // ---- 통계 ----

    private void tickLootCheck(LocalPlayer player) {
        if (lootCheckIn < 0 || --lootCheckIn > 0) {
            return;
        }
        lootCheckIn = -1;
        Map<Item, Integer> now = countInventory(player.getInventory());
        now.forEach((item, count) -> {
            int gained = count - lootSnapshot.getOrDefault(item, 0);
            if (gained > 0) {
                loot.merge(item, gained, Integer::sum);
            }
        });
    }

    private static Map<Item, Integer> countInventory(Inventory inv) {
        Map<Item, Integer> counts = new HashMap<>();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty()) {
                counts.merge(stack.getItem(), stack.getCount(), Integer::sum);
            }
        }
        return counts;
    }

    // ---- 유틸 ----

    private int randomBetween(int a, int b) {
        int lo = Math.min(a, b);
        int hi = Math.max(a, b);
        return lo + random.nextInt(hi - lo + 1);
    }

    private static void notify(LocalPlayer player, String text, ChatFormatting color) {
        // displayClientMessage는 로컬에만 표시되고 서버로 전송되지 않는다.
        player.displayClientMessage(Component.literal("[AFK 낚시] " + text).withStyle(color), false);
    }
}
