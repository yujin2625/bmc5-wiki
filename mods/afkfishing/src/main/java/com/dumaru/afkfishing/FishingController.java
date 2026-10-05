package com.dumaru.afkfishing;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.FishingRodItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

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
        MOVING("AFK 방지 이동"),
        SLEEP_PREP("침낭 펼치는 중"),
        SLEEPING("수면 중"),
        BAG_PICKUP("침낭 회수 중"),
        RETURNING("제자리로 복귀 중");

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

    // Comforts 침낭. 서버 설정 autoUse가 켜져 있으면 땅에 쓰면 바로 펼치고 눕는다.
    private static final TagKey<Item> SLEEPING_BAG_ITEMS =
            TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath("comforts", "sleeping_bags"));
    private static final TagKey<Block> SLEEPING_BAG_BLOCKS =
            TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath("comforts", "sleeping_bags"));
    // 바닐라 침대를 쓸 수 있는 밤 시간대 (맑은 날 기준)
    private static final long NIGHT_START = 12542;
    private static final long NIGHT_END = 23460;
    private static final int SLEEP_START_TIMEOUT = 60; // 침낭을 펼친 뒤 눕기를 기다리는 시간 (틱)
    private static final int SLEEP_RETRY_TICKS = 60 * 20; // 잠들지 못했을 때 다시 시도하기까지 (틱)
    private static final int BAG_PICKUP_TIMEOUT = 100;
    private static final int RETURN_TIMEOUT = 300;
    private static final int CHASE_ITEM_TICKS = 120; // 떨어진 침낭 아이템을 쫓아가는 최대 시간 (틱)
    private static final double RETURN_ARRIVE_DIST = 0.08;

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
    // 침낭 수면
    private int sleepRetryIn;
    private boolean bagPlaced;
    private BlockPos bagFoot;
    private BlockPos bagHead;
    private Vec3 fishingSpot;

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
        sleepRetryIn = 0;
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
            case COOLDOWN -> tickCooldown(mc, player);
            case SLEEP_PREP -> tickSleepPrep(mc, player);
            case SLEEPING -> tickSleeping(player);
            case BAG_PICKUP -> tickBagPickup(mc, player);
            case RETURNING -> tickReturning(player);
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
        if (sleepRetryIn > 0) {
            sleepRetryIn--;
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
        if (shouldSleep(mc, player)) {
            useRod(mc, player, "밤이 되어 잠자러 감");
            beginSleep();
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

    private void tickCooldown(Minecraft mc, LocalPlayer player) {
        if (--delay > 0) {
            return;
        }
        if (shouldSleep(mc, player)) {
            beginSleep();
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
        // 낚싯바늘은 낚을 때마다 내구도가 1씩 닳는다 (자석 8, 가시/발광 16).
        String reason = !player.getOffhandItem().isEmpty()
                ? "왼손에 다른 아이템이 있어 낚싯바늘을 끼울 수 없음"
                : "낚싯바늘 소진 - 인벤토리에 예비 " + expectedLure.getDescription().getString() + " 없음";
        if (AfkConfig.STOP_WHEN_LURE_GONE.get()) {
            stop(reason);
            return false;
        }
        notify(player, reason + ". 바늘 없이 계속 낚시합니다.", ChatFormatting.GOLD);
        expectedLure = null;
        return true;
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

    // ---- 밤에 침낭으로 자기 ----

    /** HUD 표시용: 침낭 수면 상태. 옵션이 꺼져 있으면 null. */
    public String sleepStatus(LocalPlayer player) {
        if (!AfkConfig.SLEEP_AT_NIGHT.get()) {
            return null;
        }
        if (state == State.SLEEP_PREP || state == State.SLEEPING || state == State.BAG_PICKUP || state == State.RETURNING) {
            return state.label;
        }
        if (findSleepingBag(player) < 0) {
            return "인벤토리에 침낭 없음";
        }
        if (!player.level().dimensionType().bedWorks()) {
            return "이 차원에서는 잘 수 없음";
        }
        if (sleepRetryIn > 0) {
            return "재시도 " + sleepRetryIn / 20 + "초 후";
        }
        return isNight(player.level()) ? "밤 - 곧 잠" : "밤이 되면 잠";
    }

    private boolean shouldSleep(Minecraft mc, LocalPlayer player) {
        return AfkConfig.SLEEP_AT_NIGHT.get()
                && sleepRetryIn <= 0
                && mc.level.dimensionType().bedWorks() // 네더/엔드에서는 침대가 폭발한다
                && isNight(mc.level)
                && player.onGround()
                && !player.isInWater()
                && findSleepingBag(player) >= 0;
    }

    private static boolean isNight(Level level) {
        long time = level.getDayTime() % 24000L;
        return time >= NIGHT_START && time < NIGHT_END;
    }

    private void beginSleep() {
        bagPlaced = false;
        bagFoot = null;
        bagHead = null;
        setState(State.SLEEP_PREP, 0);
    }

    private void tickSleepPrep(Minecraft mc, LocalPlayer player) {
        if (bagPlaced) {
            if (player.isSleeping()) {
                notify(player, "밤이 되어 침낭에서 잡니다.", ChatFormatting.GRAY);
                setState(State.SLEEPING, 0);
            } else if (stateTicks > SLEEP_START_TIMEOUT) {
                sleepRetryIn = SLEEP_RETRY_TICKS;
                if (findPlacedBag(player.level()) != null) {
                    // 침낭은 펼쳐졌는데 눕지 못했으면 회수하고 낚시로 돌아간다.
                    notify(player, "잠들지 못했습니다. 침낭을 회수하고 1분 뒤 다시 시도합니다.", ChatFormatting.GOLD);
                    setState(State.BAG_PICKUP, 0);
                } else {
                    failSleep(player, "잠들지 못함 (몬스터가 가까이 있거나 잘 수 없는 시간)");
                }
            }
            return;
        }
        if (player.fishing != null) {
            if (stateTicks % 20 == 1) {
                useRod(mc, player, "잠자기 전 회수");
            }
            return;
        }
        if (!ensureSleepingBagInHand(mc, player)) {
            return;
        }
        BagPlacement placement = findBagPlacement(player);
        if (placement == null) {
            failSleep(player, "반경 3칸 안에 침낭을 펼칠 자리가 없음 (나란한 빈칸 2개 + 위 빈 공간 필요)");
            return;
        }
        Direction dir = placement.dir();
        bagFoot = placement.foot();
        bagHead = bagFoot.relative(dir);
        fishingSpot = player.position();

        // 침낭(침대)은 서버가 알고 있는 플레이어 방향으로 머리 쪽이 펼쳐진다.
        // UseItemOn 패킷에는 방향이 없으므로 회전 패킷을 먼저 보낸다.
        BlockPos ground = bagFoot.below();
        Vec3 hit = new Vec3(ground.getX() + 0.5, ground.getY() + 1.0, ground.getZ() + 0.5);
        lookAt(player, hit);
        player.setYRot(dir.toYRot());
        mc.getConnection().send(new ServerboundMovePlayerPacket.Rot(player.getYRot(), player.getXRot(), player.onGround()));
        InteractionResult result = mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND,
                new BlockHitResult(hit, Direction.UP, ground, false));
        if (result.shouldSwing()) {
            player.swing(InteractionHand.MAIN_HAND);
        }
        bagPlaced = true;
        stateTicks = 0;
    }

    private void failSleep(LocalPlayer player, String reason) {
        sleepRetryIn = SLEEP_RETRY_TICKS;
        notify(player, reason + " - 1분 뒤 다시 시도합니다.", ChatFormatting.GOLD);
        setState(State.CASTING, 0);
    }

    private void tickSleeping(LocalPlayer player) {
        if (player.isSleeping()) {
            return;
        }
        // 일어났다. 바닐라는 침대 옆 빈칸에 세우므로 침낭을 걷고 제자리로 돌아가야 한다.
        sleepRetryIn = SLEEP_RETRY_TICKS;
        notify(player, "기상. 침낭을 회수하고 낚시를 이어갑니다.", ChatFormatting.GRAY);
        setState(State.BAG_PICKUP, 0);
    }

    private void tickBagPickup(Minecraft mc, LocalPlayer player) {
        BlockPos target = findPlacedBag(player.level());
        if (target == null) {
            if (stateTicks > 5) {
                setState(State.RETURNING, 0);
            }
            return;
        }
        if (stateTicks > BAG_PICKUP_TIMEOUT) {
            mc.gameMode.stopDestroyBlock();
            stop("침낭을 회수하지 못함 (" + target.toShortString() + ")");
            return;
        }
        // 침낭은 강도 0.1이라 맨손으로도 몇 틱이면 부서진다. 아이템은 머리 쪽에서 떨어진다.
        lookAt(player, Vec3.atBottomCenterOf(target).add(0, 0.1, 0));
        mc.gameMode.continueDestroyBlock(target, Direction.UP);
        player.swing(InteractionHand.MAIN_HAND);
    }

    private void tickReturning(LocalPlayer player) {
        if (player.isInWater() || player.isInLava()) {
            mover.cancel();
            stop("복귀 중 물/용암에 빠짐");
            return;
        }
        player.setSprinting(false);
        // 떨어진 침낭 아이템을 먼저 주우러 가고, 주웠으면 원래 낚시 자리로 간다.
        ItemEntity dropped = stateTicks < CHASE_ITEM_TICKS ? findDroppedBag(player) : null;
        Vec3 target = dropped != null ? dropped.position() : fishingSpot;
        double dx = target.x - player.getX();
        double dz = target.z - player.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dropped == null && dist < RETURN_ARRIVE_DIST) {
            mover.cancel();
            setState(State.CASTING, 0);
            return;
        }
        if (stateTicks > RETURN_TIMEOUT) {
            mover.cancel();
            if (dropped == null && dist < 0.4) {
                setState(State.CASTING, 0);
            } else {
                stop("낚시 자리로 돌아오지 못함");
            }
            return;
        }
        mover.walkTo(target);
    }

    /** 침낭이 있는 슬롯. 선택된 칸 → 핫바 → 메인 인벤토리 순으로 찾고, 없으면 -1. */
    private static int findSleepingBag(LocalPlayer player) {
        Inventory inv = player.getInventory();
        if (inv.getSelected().is(SLEEPING_BAG_ITEMS)) {
            return inv.selected;
        }
        for (int i = 0; i < 36; i++) {
            if (inv.getItem(i).is(SLEEPING_BAG_ITEMS)) {
                return i;
            }
        }
        return -1;
    }

    private boolean ensureSleepingBagInHand(Minecraft mc, LocalPlayer player) {
        Inventory inv = player.getInventory();
        if (inv.getSelected().is(SLEEPING_BAG_ITEMS)) {
            return true;
        }
        int slot = findSleepingBag(player);
        if (slot < 0) {
            failSleep(player, "인벤토리에 침낭 없음");
            return false;
        }
        if (Inventory.isHotbarSlot(slot)) {
            inv.selected = slot;
            return true;
        }
        if (player.containerMenu == player.inventoryMenu) {
            mc.gameMode.handleInventoryMouseClick(player.inventoryMenu.containerId, slot, inv.selected, ClickType.SWAP, player);
        } else if (stateTicks > 100) {
            failSleep(player, "다른 창이 열려 있어 침낭을 꺼내지 못함");
        }
        return false; // 서버 반영 후 다음 틱에 펼친다
    }

    private record BagPlacement(BlockPos foot, Direction dir) {
    }

    /**
     * 침낭을 펼칠 자리(발 쪽 칸 + dir 방향 머리 쪽 칸)를 찾는다. 서버의 침대 사용 거리(수평 3칸, 수직 2칸) 안에서
     * 가까운 자리를 고르고, 거리가 같으면 물 반대편(낚시 방향의 뒤)을 우선한다.
     */
    private BagPlacement findBagPlacement(LocalPlayer player) {
        Level level = player.level();
        BlockPos feet = player.blockPosition();
        Vec3 water = Vec3.directionFromRotation(0, lockedYaw);
        AABB playerBox = player.getBoundingBox();
        BagPlacement best = null;
        double bestScore = Double.MAX_VALUE;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -3; dx <= 3; dx++) {
                for (int dz = -3; dz <= 3; dz++) {
                    BlockPos foot = feet.offset(dx, dy, dz);
                    // 침대 사용 거리: 플레이어 위치와 발 쪽 칸 바닥 중앙의 차이가 수평 3, 수직 2 이하
                    Vec3 footCenter = Vec3.atBottomCenterOf(foot);
                    if (Math.abs(footCenter.x - player.getX()) > 2.9 || Math.abs(footCenter.z - player.getZ()) > 2.9
                            || Math.abs(footCenter.y - player.getY()) > 1.9) {
                        continue;
                    }
                    if (!isBagSpotFree(level, foot)) {
                        continue;
                    }
                    for (Direction dir : Direction.Plane.HORIZONTAL) {
                        BlockPos head = foot.relative(dir);
                        AABB area = new AABB(foot).minmax(new AABB(head));
                        if (area.intersects(playerBox) || !isBagSpotFree(level, head)
                                || !level.getEntitiesOfClass(LivingEntity.class, area, e -> e != player).isEmpty()) {
                            continue;
                        }
                        Vec3 middle = footCenter.add(Vec3.atBottomCenterOf(head)).scale(0.5);
                        Vec3 offset = middle.subtract(player.position());
                        // 가까울수록, 물 반대편일수록 좋은 자리
                        double score = offset.horizontalDistance() + Math.max(0, offset.normalize().dot(water)) * 1.5
                                + Math.abs(dy) * 0.5;
                        if (score < bestScore) {
                            bestScore = score;
                            best = new BagPlacement(foot, dir);
                        }
                    }
                }
            }
        }
        return best;
    }

    /** 빈칸이고(물 없음), 위가 막혀 있지 않고, 발밑에 밟을 수 있는 블록이 있는지. */
    private static boolean isBagSpotFree(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        BlockPos above = pos.above();
        BlockState aboveState = level.getBlockState(above);
        BlockPos below = pos.below();
        BlockState belowState = level.getBlockState(below);
        return state.canBeReplaced() && state.getFluidState().isEmpty()
                && aboveState.getCollisionShape(level, above).isEmpty() && aboveState.getFluidState().isEmpty()
                && !belowState.getCollisionShape(level, below).isEmpty() && belowState.getFluidState().isEmpty();
    }

    /** 펼쳐 둔 침낭 블록 위치 (머리 쪽 우선). 없으면 null. */
    private BlockPos findPlacedBag(Level level) {
        if (bagHead != null && level.getBlockState(bagHead).is(SLEEPING_BAG_BLOCKS)) {
            return bagHead;
        }
        if (bagFoot != null && level.getBlockState(bagFoot).is(SLEEPING_BAG_BLOCKS)) {
            return bagFoot;
        }
        return null;
    }

    private ItemEntity findDroppedBag(LocalPlayer player) {
        if (bagHead == null) {
            return null;
        }
        ItemEntity nearest = null;
        double best = Double.MAX_VALUE;
        for (ItemEntity item : player.level().getEntitiesOfClass(ItemEntity.class, new AABB(bagHead).inflate(3),
                e -> e.isAlive() && e.getItem().is(SLEEPING_BAG_ITEMS))) {
            double d = item.distanceToSqr(player);
            if (d < best) {
                best = d;
                nearest = item;
            }
        }
        return nearest;
    }

    private static void lookAt(LocalPlayer player, Vec3 point) {
        Vec3 eye = player.getEyePosition();
        double dx = point.x - eye.x;
        double dy = point.y - eye.y;
        double dz = point.z - eye.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        player.setYRot((float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0));
        player.setXRot((float) -Math.toDegrees(Math.atan2(dy, horizontal)));
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
