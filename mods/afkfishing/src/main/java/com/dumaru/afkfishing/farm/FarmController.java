package com.dumaru.afkfishing.farm;

import com.dumaru.afkfishing.AfkConfig;
import com.dumaru.afkfishing.AntiAfkMover;
import com.dumaru.afkfishing.FishingController;
import com.dumaru.afkfishing.common.AutoEater;
import com.dumaru.afkfishing.common.InvUtil;
import com.dumaru.afkfishing.common.Navigator;
import com.dumaru.afkfishing.common.SleepModule;
import com.dumaru.afkfishing.common.Util;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.FishingRodItem;
import net.minecraft.world.item.HoeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BonemealableBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * 자동 농사 상태 머신. 지정한 범위에서 다 자란 작물을 찾아 걸어가 수확하고, 빈 농경지에 다시 심고,
 * 떨어진 아이템을 줍고, 주기적으로 작물별 상자에 정리한다. 할 일이 없으면 낚시하며 기다리고, 밤에는 침낭에서 잔다.
 * 낚시 모드처럼 마우스·키 입력 대신 gameMode를 직접 호출하므로 창 포커스와 무관하다.
 */
public final class FarmController {
    public static final FarmController INSTANCE = new FarmController();
    public static final String PREFIX = "[자동 농사]";

    public enum State {
        IDLE("정지"),
        SCAN("범위 확인 중"),
        PLAN("다음 작업 고르는 중"),
        MOVE("이동 중"),
        HARVEST("수확 중"),
        BREAK("수확 중 (부수기)"),
        PLANT("심는 중"),
        BONEMEAL("뼛가루 주는 중"),
        COLLECT("아이템 줍는 중"),
        DEPOSIT_MOVE("상자로 이동 중"),
        DEPOSIT("상자에 정리 중"),
        EAT("먹는 중"),
        SLEEP_MOVE("잘 자리로 이동 중"),
        SLEEP("수면"),
        WAIT("다 자라길 기다리는 중"),
        ANTI_AFK("AFK 방지 이동"),
        FISH_MOVE("낚시 자리로 이동 중"),
        FISHING("기다리며 낚시 중");

        public final String label;

        State(String label) {
            this.label = label;
        }
    }

    private enum TaskType { HARVEST, BREAK, PLANT, BONEMEAL, COLLECT }

    private record Task(TaskType type, String cropId, BlockPos pos, ItemEntity item) {
    }

    private static final TagKey<Item> LURE_ITEMS =
            TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath("hybrid_aquatic", "lure_items"));
    private static final double REACH = 4.0;
    private static final int RESCAN_MS = 3000;
    private static final long FAIL_COOLDOWN_MS = 2 * 60 * 1000;
    private static final long FISH_RETRY_MS = 5 * 60 * 1000;
    private static final long CHESTS_FULL_RETRY_MS = 2 * 60 * 1000;
    private static final int ACTION_TIMEOUT = 30;
    private static final int BREAK_TIMEOUT = 120;
    private static final int OPEN_TIMEOUT = 40;
    private static final int LEARN_TICKS = 40;
    private static final int MAX_BONEMEAL_PER_CROP = 8;

    private final Random random = new Random();
    private final FarmScanner scanner = new FarmScanner();
    private final SleepModule sleep = new SleepModule(PREFIX);
    private final AutoEater eater = new AutoEater();
    private final AntiAfkMover mover = new AntiAfkMover(random);

    private State state = State.IDLE;
    private int stateTicks;
    private int actionTick = -1;
    private FarmData data;
    private Task task;
    private float lastHealth;
    private boolean scanRequested;
    private boolean noFoodWarned;

    // 수확 판단
    private boolean harvestRound;
    private long ripeSince;
    private final Map<Long, Long> failedUntil = new HashMap<>();
    private final Map<Long, Integer> bonemealUses = new HashMap<>();
    private final Set<Integer> failedItems = new HashSet<>();

    // 상자 정리
    private long nextDepositAt;
    private long lastDepositAttempt;
    private boolean chestsFullWarned;
    private final List<FarmData.ChestEntry> depositQueue = new ArrayList<>();
    private FarmData.ChestEntry depositChest;
    private final List<Integer> depositSlots = new ArrayList<>();
    private int depositPending = -1;
    private Item depositPendingItem;
    private int depositPendingCount;
    private boolean depositLeftovers;
    private boolean depositedBeforeSleep;
    private Map<Item, Integer> startCounts = new HashMap<>();

    // 낚시하며 기다리기
    private long fishRetryAt;

    // 수확물 학습: 수확 직후 근처에 떨어진 아이템을 그 작물의 수확물로 기억한다.
    private String learnCrop;
    private BlockPos learnPos;
    private int learnTicks;
    private Set<Integer> learnKnownEntities = new HashSet<>();
    private Map<Item, Integer> learnInventory;
    private final Map<Item, String> productCache = new HashMap<>();

    // 통계
    private long startedAtMillis;
    private final Map<String, Integer> harvested = new LinkedHashMap<>();
    private int planted;
    private int deposited;
    private String lastMessage = "";
    private long lastSaveAt;

    private FarmController() {
    }

    // ---- 조회 (GUI / HUD) ----

    public boolean isRunning() {
        return state != State.IDLE;
    }

    public State state() {
        return state;
    }

    public String stateLabel() {
        if (state == State.SLEEP && sleep.isActive()) {
            return sleep.label();
        }
        if (task != null && (state == State.MOVE || state == State.HARVEST || state == State.BREAK || state == State.PLANT)) {
            return state.label + " · " + (task.cropId != null ? Crops.displayName(task.cropId) : "");
        }
        return state.label;
    }

    public FarmScanner.Snapshot snapshot() {
        return scanner.snapshot();
    }

    public boolean isScanning() {
        return scanner.isScanning();
    }

    public void requestScan() {
        scanRequested = true;
    }

    /** 설정 화면에서 농장 설정을 바꿨을 때. */
    public void onDataChanged() {
        if (isRunning() && data != null) {
            rebuildProductCache();
        }
    }

    public AntiAfkMover mover() {
        return mover;
    }

    public long elapsedMillis() {
        return isRunning() ? System.currentTimeMillis() - startedAtMillis : 0;
    }

    public Map<String, Integer> harvested() {
        return harvested;
    }

    public int planted() {
        return planted;
    }

    public int deposited() {
        return deposited;
    }

    public String lastMessage() {
        return lastMessage;
    }

    public long secondsUntilDeposit() {
        return Math.max(0, (nextDepositAt - System.currentTimeMillis()) / 1000);
    }

    public String sleepStatus(LocalPlayer player) {
        return sleep.status(player);
    }

    /** 선택한 작물의 (다 자람, 전체) 개수. */
    public int[] selectedCounts() {
        FarmData d = FarmData.current();
        int ripe = 0;
        int total = 0;
        for (Map.Entry<String, FarmScanner.Count> e : scanner.snapshot().counts().entrySet()) {
            if (d.selectedCrops.contains(e.getKey())) {
                ripe += e.getValue().ripe();
                total += e.getValue().total();
            }
        }
        return new int[]{ripe, total};
    }

    public void resetStats() {
        harvested.clear();
        planted = 0;
        deposited = 0;
        startedAtMillis = System.currentTimeMillis();
    }

    // ---- 시작 / 정지 ----

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
        data = FarmData.current();
        if (!data.hasArea()) {
            notify(player, "농장 범위를 먼저 지정하세요 (설정 화면 > 농장 지정 탭).", ChatFormatting.RED);
            return;
        }
        if (data.volume() > FarmScanner.MAX_VOLUME) {
            notify(player, "농장 범위가 너무 큽니다 (최대 " + FarmScanner.MAX_VOLUME + "칸).", ChatFormatting.RED);
            return;
        }
        if (data.selectedCrops.isEmpty()) {
            notify(player, "수확할 작물을 하나 이상 고르세요 (설정 화면 > 농장 지정 탭).", ChatFormatting.RED);
            return;
        }
        if (FishingController.INSTANCE.isRunning()) {
            FishingController.INSTANCE.stop("자동 농사 시작");
        }
        if (data.chests.isEmpty()) {
            notify(player, "지정한 상자가 없어서 수확물을 정리하지 않습니다.", ChatFormatting.GOLD);
        }
        lastHealth = player.getHealth();
        harvestRound = false;
        ripeSince = 0;
        failedUntil.clear();
        bonemealUses.clear();
        failedItems.clear();
        nextDepositAt = System.currentTimeMillis() + AfkConfig.FARM_DEPOSIT_MINUTES.get() * 60_000L;
        lastDepositAttempt = 0;
        chestsFullWarned = false;
        depositedBeforeSleep = false;
        fishRetryAt = 0;
        startCounts = countInventory(player);
        rebuildProductCache();
        sleep.reset();
        mover.reset();
        task = null;
        learnCrop = null;
        startedAtMillis = System.currentTimeMillis();
        lastSaveAt = startedAtMillis;
        lastMessage = "";
        scanner.start(data);
        setState(State.SCAN);
        notify(player, "자동 농사 시작", ChatFormatting.GREEN);
    }

    public void stop(String reason) {
        if (!isRunning()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        Navigator.INSTANCE.cancel();
        sleep.cancel();
        eater.cancel(mc);
        mover.cancel();
        FishingController.INSTANCE.stopEmbedded();
        LocalPlayer player = mc.player;
        if (player != null && mc.gameMode != null) {
            if (state == State.BREAK) {
                mc.gameMode.stopDestroyBlock();
            }
            if (state == State.DEPOSIT && player.containerMenu != player.inventoryMenu) {
                player.closeContainer();
            }
        }
        state = State.IDLE;
        task = null;
        lastMessage = reason;
        if (data != null) {
            data.save();
        }
        if (player != null) {
            notify(player, "자동 농사 정지: " + reason, ChatFormatting.YELLOW);
            mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING.value(), 1.0f));
        }
    }

    /** 접속 종료 직전. */
    public void onDisconnect() {
        if (isRunning()) {
            Navigator.INSTANCE.cancel();
            sleep.cancel();
            eater.cancel(Minecraft.getInstance());
            state = State.IDLE;
            if (data != null) {
                data.save();
            }
        }
        scanner.clear();
    }

    // ---- 틱 ----

    public void tick(Minecraft mc) {
        LocalPlayer player = mc.player;
        if (!isRunning()) {
            if (scanRequested && player != null && mc.level != null) {
                FarmData d = FarmData.current();
                if (!scanner.isScanning()) {
                    scanner.start(d);
                }
                if (!scanner.isScanning() || scanner.tick(mc.level, d)) {
                    scanRequested = false;
                }
            }
            return;
        }
        if (player == null || mc.gameMode == null || mc.level == null) {
            stop("월드 없음");
            return;
        }
        if (FarmData.current() != data) {
            stop("다른 서버/차원으로 이동");
            return;
        }
        if (!checkSafety(player)) {
            return;
        }
        if (!scanner.isScanning() && System.currentTimeMillis() - scanner.snapshot().time() > RESCAN_MS) {
            scanner.start(data);
        }
        scanner.tick(mc.level, data);
        sleep.countdown();
        tickLearning(player);
        if (System.currentTimeMillis() - lastSaveAt > 5 * 60_000L) {
            lastSaveAt = System.currentTimeMillis();
            data.save();
        }
        stateTicks++;

        switch (state) {
            case SCAN -> {
                if (scanner.snapshot().time() > 0) {
                    setState(State.PLAN);
                }
            }
            case PLAN -> plan(mc, player);
            case MOVE -> tickMove(player);
            case HARVEST -> tickHarvest(mc, player);
            case BREAK -> tickBreak(mc, player);
            case PLANT -> tickPlant(mc, player);
            case BONEMEAL -> tickBonemeal(mc, player);
            case COLLECT -> tickCollect(player);
            case DEPOSIT_MOVE -> tickDepositMove(mc, player);
            case DEPOSIT -> tickDeposit(mc, player);
            case EAT -> {
                if (eater.tick(mc, player, this::isCropProduct) != AutoEater.Result.RUNNING) {
                    setState(State.PLAN);
                }
            }
            case SLEEP -> {
                SleepModule.Result result = sleep.tick(mc, player);
                if (result == SleepModule.Result.ABORT) {
                    stop(sleep.failReason());
                } else if (result != SleepModule.Result.RUNNING) {
                    depositedBeforeSleep = false;
                    setState(State.PLAN);
                }
            }
            case SLEEP_MOVE -> {
                Navigator.Result r = Navigator.INSTANCE.tick(player);
                if (r != Navigator.Result.RUNNING) {
                    beginSleep(player); // 못 가도 지금 자리 근처에서 시도
                }
            }
            case WAIT -> tickWait(player);
            case ANTI_AFK -> {
                if (mover.tick(player) != AntiAfkMover.Result.RUNNING) {
                    setState(State.PLAN);
                }
            }
            case FISH_MOVE -> tickFishMove(player);
            case FISHING -> tickFishing(player);
            default -> {
            }
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
        // 농장에서 너무 멀어지면 (텔레포트 등) 정지
        boolean nearFarm = data.areaBox().inflate(48).contains(player.position());
        boolean nearFish = data.fishSpot != null && data.fishSpot.pos().distanceTo(player.position()) < 48;
        if (!nearFarm && !nearFish && state != State.SLEEP && state != State.SLEEP_MOVE) {
            stop("농장에서 너무 멀어짐");
            return false;
        }
        return true;
    }

    private void setState(State next) {
        state = next;
        stateTicks = 0;
        actionTick = -1;
    }

    // ---- 다음 작업 고르기 ----

    private void plan(Minecraft mc, LocalPlayer player) {
        task = null;
        if (player.containerMenu != player.inventoryMenu) {
            return; // 사용자가 창을 열어 두었으면 기다린다
        }
        if (eater.needsEat(player, this::isCropProduct)) {
            eater.begin(player);
            setState(State.EAT);
            return;
        }
        if (AfkConfig.FOOD_MODE.get() != AutoEater.FoodMode.OFF
                && player.getFoodData().getFoodLevel() <= AfkConfig.EAT_BELOW.get()
                && AutoEater.findFood(player, this::isCropProduct) < 0 && !noFoodWarned) {
            noFoodWarned = true;
            notify(player, "배가 고픈데 먹을 음식이 없습니다. 수확할 때마다 배고픔이 줄어드니 음식을 챙겨 주세요.", ChatFormatting.GOLD);
        } else if (player.getFoodData().getFoodLevel() > AfkConfig.EAT_BELOW.get()) {
            noFoodWarned = false;
        }
        if (sleep.shouldSleep(player)) {
            if (AfkConfig.FARM_DEPOSIT_BEFORE_SLEEP.get() && !depositedBeforeSleep) {
                depositedBeforeSleep = true;
                if (beginDeposit(player)) {
                    return;
                }
            }
            FarmData.FishSpot spot = data.fishSpot;
            if (spot != null && spot.pos().distanceTo(player.position()) > 2) {
                Navigator.INSTANCE.start(Navigator.near(spot.pos(), 0.6), spot.pos(), 0.3, 1200);
                setState(State.SLEEP_MOVE);
                return;
            }
            beginSleep(player);
            return;
        }
        if (depositDue(player) && beginDeposit(player)) {
            return;
        }
        if (harvestDue()) {
            Task next = nextHarvestTask(player);
            if (next != null) {
                beginTask(player, next);
                return;
            }
            harvestRound = false;
            ripeSince = 0;
        }
        if (AfkConfig.FARM_REPLANT.get()) {
            Task next = nextPlantTask(player);
            if (next != null) {
                beginTask(player, next);
                return;
            }
        }
        if (AfkConfig.FARM_COLLECT_ITEMS.get() && InvUtil.freeSlots(player) > 0) {
            ItemEntity item = nearestItem(player);
            if (item != null) {
                beginTask(player, new Task(TaskType.COLLECT, null, item.blockPosition(), item));
                return;
            }
        }
        if (AfkConfig.FARM_BONEMEAL.get()) {
            Task next = nextBonemealTask(player);
            if (next != null) {
                beginTask(player, next);
                return;
            }
        }
        if (AfkConfig.FARM_WAIT_FISHING.get() && data.fishSpot != null && System.currentTimeMillis() >= fishRetryAt
                && InvUtil.find(player, s -> s.getItem() instanceof FishingRodItem) >= 0) {
            FarmData.FishSpot spot = data.fishSpot;
            Navigator.INSTANCE.start(Navigator.near(spot.pos(), 0.6), spot.pos(), 0.08, 1200);
            setState(State.FISH_MOVE);
            return;
        }
        setState(State.WAIT);
    }

    /** 낚시·대기 중에 일하러 가야 하는지 (뼛가루는 제외: 그것 때문에 낚시를 끊지 않는다). */
    private boolean hasWork(LocalPlayer player) {
        if (eater.needsEat(player, this::isCropProduct) || sleep.shouldSleep(player) || depositDue(player)) {
            return true;
        }
        if (harvestDue() && nextHarvestTask(player) != null) {
            return true;
        }
        if (AfkConfig.FARM_REPLANT.get() && nextPlantTask(player) != null) {
            return true;
        }
        return AfkConfig.FARM_COLLECT_ITEMS.get() && InvUtil.freeSlots(player) > 0 && nearestItem(player) != null;
    }

    private boolean harvestDue() {
        if (harvestRound) {
            return true;
        }
        int[] counts = selectedCounts();
        int ripe = counts[0];
        int total = counts[1];
        long now = System.currentTimeMillis();
        if (ripe == 0) {
            ripeSince = 0;
            return false;
        }
        if (ripeSince == 0) {
            ripeSince = now;
        }
        int percent = AfkConfig.FARM_MIN_RIPE_PERCENT.get();
        boolean due = ripe >= AfkConfig.FARM_MIN_RIPE.get()
                || (percent > 0 && total > 0 && ripe * 100 >= percent * total)
                || now - ripeSince >= AfkConfig.FARM_MAX_WAIT_MINUTES.get() * 60_000L;
        if (due) {
            harvestRound = true;
        }
        return due;
    }

    private boolean isFailed(BlockPos pos) {
        Long until = failedUntil.get(pos.asLong());
        return until != null && until > System.currentTimeMillis();
    }

    private void markFailed(BlockPos pos) {
        failedUntil.put(pos.asLong(), System.currentTimeMillis() + FAIL_COOLDOWN_MS);
    }

    private Task nextHarvestTask(LocalPlayer player) {
        Level level = player.level();
        Vec3 from = player.position();
        Crops.CropAt best = null;
        double bestDist = Double.MAX_VALUE;
        for (Crops.CropAt c : scanner.snapshot().crops()) {
            if (!c.ripe() || !data.selectedCrops.contains(c.id()) || isFailed(c.actionPos())) {
                continue;
            }
            double d = Vec3.atCenterOf(c.actionPos()).distanceToSqr(from);
            if (d >= bestDist) {
                continue;
            }
            // 스캔 이후 바뀌었을 수 있으니 지금 상태를 다시 본다 (괭이 범위 수확으로 이미 거둔 경우 등)
            Crops.CropAt live = Crops.classify(level, c.pos(), level.getBlockState(c.pos()));
            if (live == null || !live.ripe()) {
                continue;
            }
            best = live;
            bestDist = d;
        }
        if (best == null) {
            return null;
        }
        TaskType type = best.kind() == Crops.Kind.GOURD || best.kind() == Crops.Kind.STACK ? TaskType.BREAK : TaskType.HARVEST;
        return new Task(type, best.id(), best.actionPos(), null);
    }

    private Task nextPlantTask(LocalPlayer player) {
        Level level = player.level();
        Vec3 from = player.position();
        BlockPos best = null;
        String bestCrop = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos soil : scanner.snapshot().emptySoil()) {
            if (isFailed(soil)) {
                continue;
            }
            String crop = plantFor(level, soil);
            if (crop == null) {
                continue;
            }
            double d = Vec3.atCenterOf(soil).distanceToSqr(from);
            if (d < bestDist && level.getBlockState(soil.above()).isAir() && Crops.isSoil(level.getBlockState(soil))) {
                best = soil;
                bestCrop = crop;
                bestDist = d;
            }
        }
        return best == null ? null : new Task(TaskType.PLANT, bestCrop, best, null);
    }

    /** 이 흙에 심을 작물. 그 자리에 있던 작물(기억) → 기본 작물 순. 씨앗이 없으면 null. */
    private String plantFor(Level level, BlockPos soil) {
        String remembered = data.plantMemory.get(FarmData.posKey(soil));
        String crop = remembered != null ? remembered : data.defaultPlant;
        if (crop == null || !data.selectedCrops.contains(crop)) {
            return null;
        }
        boolean soulSand = level.getBlockState(soil).is(Blocks.SOUL_SAND);
        boolean wart = crop.equals(Crops.id(Blocks.NETHER_WART));
        if (soulSand != wart) {
            return null; // 네더 사마귀는 영혼 모래에만, 나머지는 농경지에만
        }
        ItemStack seed = Crops.seedOf(level, crop);
        if (seed.isEmpty() || InvUtil.count(Minecraft.getInstance().player, seed.getItem()) == 0) {
            return null;
        }
        return crop;
    }

    private Task nextBonemealTask(LocalPlayer player) {
        if (InvUtil.count(player, Items.BONE_MEAL) <= AfkConfig.FARM_BONEMEAL_KEEP.get()) {
            return null;
        }
        Level level = player.level();
        Vec3 from = player.position();
        Crops.CropAt best = null;
        double bestDist = Double.MAX_VALUE;
        for (Crops.CropAt c : scanner.snapshot().crops()) {
            if (c.ripe() || c.kind() != Crops.Kind.AGE || !data.selectedCrops.contains(c.id()) || isFailed(c.pos())) {
                continue;
            }
            double d = Vec3.atCenterOf(c.pos()).distanceToSqr(from);
            if (d >= bestDist) {
                continue;
            }
            BlockState state = level.getBlockState(c.pos());
            if (state.getBlock() instanceof BonemealableBlock b && b.isValidBonemealTarget(level, c.pos(), state)) {
                best = c;
                bestDist = d;
            }
        }
        return best == null ? null : new Task(TaskType.BONEMEAL, best.id(), best.pos(), null);
    }

    private ItemEntity nearestItem(LocalPlayer player) {
        AABB box = data.areaBox().inflate(1.5, 1, 1.5);
        ItemEntity best = null;
        double bestDist = Double.MAX_VALUE;
        for (ItemEntity item : player.level().getEntitiesOfClass(ItemEntity.class, box,
                e -> e.isAlive() && !failedItems.contains(e.getId()) && e.onGround())) {
            double d = item.distanceToSqr(player);
            if (d < bestDist) {
                bestDist = d;
                best = item;
            }
        }
        return best;
    }

    // ---- 작업 실행 ----

    private void beginTask(LocalPlayer player, Task next) {
        task = next;
        if (next.type == TaskType.COLLECT) {
            Navigator.INSTANCE.start(Navigator.near(next.item.position(), 0.6), null, 0, 300);
            setState(State.COLLECT);
            return;
        }
        if (inReach(player, next.pos)) {
            setState(actionState(next.type));
            return;
        }
        Navigator.INSTANCE.start(Navigator.reach(next.pos, REACH - 0.3), null, 0, 600);
        setState(State.MOVE);
    }

    private static State actionState(TaskType type) {
        return switch (type) {
            case HARVEST -> State.HARVEST;
            case BREAK -> State.BREAK;
            case PLANT -> State.PLANT;
            case BONEMEAL -> State.BONEMEAL;
            case COLLECT -> State.COLLECT;
        };
    }

    private static boolean inReach(LocalPlayer player, BlockPos pos) {
        return player.getEyePosition().distanceTo(Vec3.atCenterOf(pos)) <= REACH;
    }

    private void tickMove(LocalPlayer player) {
        Navigator.Result r = Navigator.INSTANCE.tick(player);
        if (r == Navigator.Result.ARRIVED) {
            setState(actionState(task.type));
        } else if (r == Navigator.Result.FAILED) {
            markFailed(task.pos);
            lastMessage = Crops.displayName(task.cropId) + " " + task.pos.toShortString() + ": " + Navigator.INSTANCE.failReason();
            setState(State.PLAN);
        }
    }

    private void tickHarvest(Minecraft mc, LocalPlayer player) {
        Level level = player.level();
        Crops.CropAt live = Crops.classify(level, task.pos, level.getBlockState(task.pos));
        if (live == null || !live.ripe()) {
            if (actionTick >= 0) {
                countHarvest(task.cropId);
            }
            setState(State.PLAN); // 거뒀거나 그사이 다른 이유로 바뀜
            return;
        }
        if (stateTicks > ACTION_TIMEOUT) {
            markFailed(task.pos);
            lastMessage = Crops.displayName(task.cropId) + " 수확 실패 " + task.pos.toShortString();
            setState(State.PLAN);
            return;
        }
        if (!holdHarvestTool(mc, player)) {
            return; // 도구를 손에 드는 중
        }
        if (actionTick < 0 || stateTicks - actionTick >= 10) {
            Direction face = Util.faceToward(player, task.pos);
            Vec3 hit = Vec3.atCenterOf(task.pos);
            Util.lookAt(player, hit);
            if (actionTick < 0) {
                startLearning(player, task.cropId, task.pos);
            }
            InteractionResult result = mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND,
                    new BlockHitResult(hit, face, task.pos, false));
            if (result.shouldSwing()) {
                player.swing(InteractionHand.MAIN_HAND);
            }
            actionTick = stateTicks;
        }
    }

    /** 수확할 때 들 도구: 괭이(옵션) → 빈손 → 그대로. 들 수 있으면 true. */
    private boolean holdHarvestTool(Minecraft mc, LocalPlayer player) {
        int minDur = AfkConfig.FARM_TOOL_MIN_DURABILITY.get();
        if (AfkConfig.FARM_USE_HOE.get()
                && InvUtil.find(player, s -> s.getItem() instanceof HoeItem && InvUtil.hasDurability(s, minDur)) >= 0) {
            return InvUtil.ensureInHand(mc, player, s -> s.getItem() instanceof HoeItem && InvUtil.hasDurability(s, minDur));
        }
        ItemStack held = player.getMainHandItem();
        // 블록·씨앗을 들고 우클릭하면 엉뚱한 데 놓일 수 있으니 되도록 빈손으로
        if (held.getItem() instanceof BlockItem || held.is(Items.BONE_MEAL)) {
            InvUtil.selectEmptyHand(player);
        }
        return true;
    }

    private void tickBreak(Minecraft mc, LocalPlayer player) {
        Level level = player.level();
        BlockState state = level.getBlockState(task.pos);
        boolean stillThere = !state.isAir() && Crops.id(state.getBlock()).equals(task.cropId);
        if (!stillThere) {
            mc.gameMode.stopDestroyBlock();
            if (actionTick >= 0) {
                countHarvest(task.cropId);
            }
            setState(State.PLAN);
            return;
        }
        if (stateTicks > BREAK_TIMEOUT) {
            mc.gameMode.stopDestroyBlock();
            markFailed(task.pos);
            lastMessage = Crops.displayName(task.cropId) + " 부수기 실패 " + task.pos.toShortString();
            setState(State.PLAN);
            return;
        }
        if (actionTick < 0) {
            startLearning(player, task.cropId, task.pos);
            actionTick = stateTicks;
        }
        // 이 블록을 가장 빨리 부수는 도구 (수박·호박은 도끼, 대나무는 칼 등)
        int tool = InvUtil.bestToolSlot(player, state, AfkConfig.FARM_TOOL_MIN_DURABILITY.get());
        if (tool >= 0) {
            Item want = player.getInventory().getItem(tool).getItem();
            if (!player.getMainHandItem().is(want) && !InvUtil.ensureInHand(mc, player, s -> s.is(want))) {
                return;
            }
        }
        Direction face = Util.faceToward(player, task.pos);
        Util.lookAt(player, Vec3.atCenterOf(task.pos));
        mc.gameMode.continueDestroyBlock(task.pos, face);
        player.swing(InteractionHand.MAIN_HAND);
    }

    private void tickPlant(Minecraft mc, LocalPlayer player) {
        Level level = player.level();
        BlockPos soil = task.pos;
        if (!level.getBlockState(soil.above()).isAir()) {
            if (stateTicks > 1) {
                planted++;
            }
            setState(State.PLAN);
            return;
        }
        if (stateTicks > ACTION_TIMEOUT) {
            markFailed(soil);
            setState(State.PLAN);
            return;
        }
        ItemStack seed = Crops.seedOf(level, task.cropId);
        if (seed.isEmpty()) {
            markFailed(soil);
            setState(State.PLAN);
            return;
        }
        if (!InvUtil.ensureInHand(mc, player, s -> s.is(seed.getItem()))) {
            if (InvUtil.count(player, seed.getItem()) == 0) {
                setState(State.PLAN);
            }
            return;
        }
        if (stateTicks % 10 == 1) {
            Vec3 hit = new Vec3(soil.getX() + 0.5, soil.getY() + 1.0, soil.getZ() + 0.5);
            Util.lookAt(player, hit);
            InteractionResult result = mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND,
                    new BlockHitResult(hit, Direction.UP, soil, false));
            if (result.shouldSwing()) {
                player.swing(InteractionHand.MAIN_HAND);
            }
        }
    }

    private void tickBonemeal(Minecraft mc, LocalPlayer player) {
        if (stateTicks > ACTION_TIMEOUT) {
            markFailed(task.pos);
            setState(State.PLAN);
            return;
        }
        if (!InvUtil.ensureInHand(mc, player, s -> s.is(Items.BONE_MEAL))) {
            if (InvUtil.count(player, Items.BONE_MEAL) == 0) {
                setState(State.PLAN);
            }
            return;
        }
        Vec3 hit = Vec3.atCenterOf(task.pos);
        Util.lookAt(player, hit);
        InteractionResult result = mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND,
                new BlockHitResult(hit, Util.faceToward(player, task.pos), task.pos, false));
        if (result.shouldSwing()) {
            player.swing(InteractionHand.MAIN_HAND);
        }
        int uses = bonemealUses.merge(task.pos.asLong(), 1, Integer::sum);
        if (uses >= MAX_BONEMEAL_PER_CROP) {
            markFailed(task.pos);
        }
        setState(State.PLAN);
    }

    private void tickCollect(LocalPlayer player) {
        ItemEntity item = task.item;
        if (!item.isAlive()) {
            Navigator.INSTANCE.cancel();
            setState(State.PLAN); // 주웠음
            return;
        }
        if (stateTicks > 400) {
            Navigator.INSTANCE.cancel();
            failedItems.add(item.getId());
            setState(State.PLAN);
            return;
        }
        if (Navigator.INSTANCE.isActive()) {
            Navigator.Result r = Navigator.INSTANCE.tick(player);
            if (r == Navigator.Result.FAILED) {
                failedItems.add(item.getId());
                setState(State.PLAN);
            } else if (r == Navigator.Result.ARRIVED) {
                actionTick = stateTicks;
            }
            return;
        }
        // 도착했는데 아직 안 주워짐: 아이템이 움직였으면 다시 쫓아가고, 오래 안 주워지면 포기
        if (item.distanceTo(player) > 1.5 && stateTicks % 20 == 0) {
            Navigator.INSTANCE.start(Navigator.near(item.position(), 0.6), null, 0, 200);
        } else if (actionTick >= 0 && stateTicks - actionTick > 60) {
            failedItems.add(item.getId());
            setState(State.PLAN);
        }
    }

    private void countHarvest(String cropId) {
        harvested.merge(cropId, 1, Integer::sum);
    }

    private void beginSleep(LocalPlayer player) {
        if (data.fishSpot != null && data.fishSpot.pos().distanceTo(player.position()) < 3) {
            player.setYRot(data.fishSpot.yaw); // 물 쪽을 보고 있으면 침낭은 물 반대편을 우선한다
        }
        sleep.begin(null, data.areaBox().inflate(1));
        setState(State.SLEEP);
    }

    // ---- 대기 / 낚시 ----

    private void tickWait(LocalPlayer player) {
        if (stateTicks % 20 == 0 && hasWork(player)) {
            setState(State.PLAN);
            return;
        }
        if (stateTicks % 200 == 0 && AfkConfig.FARM_WAIT_FISHING.get() && data.fishSpot != null
                && System.currentTimeMillis() >= fishRetryAt) {
            setState(State.PLAN); // 낚시를 다시 시도할 수 있는지 본다
            return;
        }
        mover.countdown();
        if (AfkConfig.ANTI_AFK.get() && mover.isDue() && mover.begin(player, player.getYRot())) {
            setState(State.ANTI_AFK);
        }
    }

    private void tickFishMove(LocalPlayer player) {
        Navigator.Result r = Navigator.INSTANCE.tick(player);
        if (r == Navigator.Result.ARRIVED) {
            FarmData.FishSpot spot = data.fishSpot;
            player.setYRot(spot.yaw);
            player.setXRot(spot.pitch);
            if (FishingController.INSTANCE.startEmbedded(spot.yaw, spot.pitch)) {
                setState(State.FISHING);
            } else {
                fishRetryAt = System.currentTimeMillis() + FISH_RETRY_MS;
                setState(State.WAIT);
            }
        } else if (r == Navigator.Result.FAILED) {
            fishRetryAt = System.currentTimeMillis() + FISH_RETRY_MS;
            notify(player, "낚시 자리로 가지 못했습니다 (" + Navigator.INSTANCE.failReason() + "). 5분 뒤 다시 시도합니다.",
                    ChatFormatting.GOLD);
            setState(State.PLAN);
        } else if (stateTicks % 20 == 0 && hasWork(player)) {
            Navigator.INSTANCE.cancel();
            setState(State.PLAN);
        }
    }

    private void tickFishing(LocalPlayer player) {
        if (!FishingController.INSTANCE.isRunning()) {
            // 낚시가 스스로 멈춤 (인벤토리 가득 참, 찌가 물에 안 들어감 등)
            fishRetryAt = System.currentTimeMillis() + FISH_RETRY_MS;
            setState(State.PLAN);
            return;
        }
        if (stateTicks % 20 == 0 && hasWork(player)) {
            FishingController.INSTANCE.stopEmbedded();
            setState(State.PLAN);
        }
    }

    // ---- 상자 정리 ----

    private boolean depositDue(LocalPlayer player) {
        if (data.chests.isEmpty()) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (now >= nextDepositAt) {
            return hasDepositable(player);
        }
        return InvUtil.freeSlots(player) <= AfkConfig.FARM_DEPOSIT_FREE_SLOTS.get()
                && now - lastDepositAttempt > CHESTS_FULL_RETRY_MS && hasDepositable(player);
    }

    private boolean hasDepositable(LocalPlayer player) {
        for (FarmData.ChestEntry chest : data.chests) {
            if (!slotsFor(player, chest, -1).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /** 상자 정리를 시작한다. 넣을 게 없으면 false. */
    private boolean beginDeposit(LocalPlayer player) {
        lastDepositAttempt = System.currentTimeMillis();
        depositQueue.clear();
        for (FarmData.ChestEntry chest : data.chests) {
            if (!slotsFor(player, chest, -1).isEmpty()) {
                depositQueue.add(chest);
            }
        }
        // 작물 상자부터, 기타 상자는 나중에
        depositQueue.sort(Comparator.comparing(c -> c.catchAll));
        depositLeftovers = false;
        if (depositQueue.isEmpty()) {
            nextDepositAt = System.currentTimeMillis() + AfkConfig.FARM_DEPOSIT_MINUTES.get() * 60_000L;
            return false;
        }
        nextDepositChest(player);
        return true;
    }

    private void nextDepositChest(LocalPlayer player) {
        // 앞 상자가 가득 차서 못 넣은 게 있으면, 같은 작물의 다른 상자가 큐에 남아 있을 때 거기에 넣는다.
        while (!depositQueue.isEmpty()) {
            FarmData.ChestEntry chest = depositQueue.remove(0);
            if (slotsFor(player, chest, -1).isEmpty()) {
                continue;
            }
            depositChest = chest;
            BlockPos pos = chest.pos();
            if (inReach(player, pos)) {
                setState(State.DEPOSIT_MOVE);
                return;
            }
            Navigator.INSTANCE.start(Navigator.reach(pos, REACH - 0.3), null, 0, 900);
            setState(State.DEPOSIT_MOVE);
            return;
        }
        finishDeposit(player);
    }

    private void finishDeposit(LocalPlayer player) {
        depositChest = null;
        nextDepositAt = System.currentTimeMillis() + AfkConfig.FARM_DEPOSIT_MINUTES.get() * 60_000L;
        if (depositLeftovers || hasDepositable(player)) {
            if (!chestsFullWarned) {
                chestsFullWarned = true;
                notify(player, "상자가 가득 차서 일부 수확물을 넣지 못했습니다.", ChatFormatting.GOLD);
            }
            if (AfkConfig.FARM_STOP_WHEN_CHESTS_FULL.get()) {
                stop("상자가 가득 참");
                return;
            }
        } else {
            chestsFullWarned = false;
        }
        setState(State.PLAN);
    }

    private void tickDepositMove(Minecraft mc, LocalPlayer player) {
        BlockPos pos = depositChest.pos();
        if (Navigator.INSTANCE.isActive()) {
            Navigator.Result r = Navigator.INSTANCE.tick(player);
            if (r == Navigator.Result.FAILED) {
                notify(player, "상자 " + pos.toShortString() + "까지 가지 못했습니다 (" + Navigator.INSTANCE.failReason() + ").",
                        ChatFormatting.GOLD);
                depositLeftovers = true;
                nextDepositChest(player);
            }
            return;
        }
        // 상자 열기
        Direction face = Util.faceToward(player, pos);
        Vec3 hit = Util.faceCenter(pos, face);
        Util.lookAt(player, hit);
        if (player.getMainHandItem().getItem() instanceof BlockItem) {
            InvUtil.selectEmptyHand(player);
        }
        mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, new BlockHitResult(hit, face, pos, false));
        player.swing(InteractionHand.MAIN_HAND);
        depositSlots.clear();
        depositPending = -1;
        setState(State.DEPOSIT);
    }

    private void tickDeposit(Minecraft mc, LocalPlayer player) {
        AbstractContainerMenu menu = player.containerMenu;
        if (menu == player.inventoryMenu) {
            if (stateTicks > OPEN_TIMEOUT) {
                notify(player, "상자 " + depositChest.pos().toShortString() + "를 열지 못했습니다.", ChatFormatting.GOLD);
                depositLeftovers = true;
                nextDepositChest(player);
            }
            return;
        }
        if (actionTick < 0) {
            actionTick = stateTicks; // 상자가 열린 틱
        }
        // 지난 틱에 옮긴 칸이 그대로면 상자가 가득 찬 것
        if (depositPending >= 0) {
            Slot slot = menu.getSlot(depositPending);
            ItemStack now = slot.getItem();
            if (now.is(depositPendingItem) && now.getCount() >= depositPendingCount) {
                depositLeftovers = true;
                depositSlots.removeIf(i -> menu.getSlot(i).getItem().is(depositPendingItem));
            } else {
                deposited += depositPendingCount - (now.is(depositPendingItem) ? now.getCount() : 0);
            }
            depositPending = -1;
        }
        if (stateTicks - actionTick == 3) {
            depositSlots.addAll(slotsFor(player, depositChest, menu.containerId));
        }
        if (stateTicks - actionTick < 4) {
            return; // 상자 내용이 동기화될 때까지
        }
        if (depositSlots.isEmpty()) {
            player.closeContainer();
            nextDepositChest(player);
            return;
        }
        int menuSlot = depositSlots.remove(0);
        ItemStack stack = menu.getSlot(menuSlot).getItem();
        if (stack.isEmpty()) {
            return;
        }
        depositPending = menuSlot;
        depositPendingItem = stack.getItem();
        depositPendingCount = stack.getCount();
        mc.gameMode.handleInventoryMouseClick(menu.containerId, menuSlot, 0, ClickType.QUICK_MOVE, player);
    }

    /**
     * 이 상자에 넣을 칸 목록. menuId가 -1이면 인벤토리 칸 번호(0~35), 아니면 열린 메뉴의 칸 번호.
     * 남겨 둘 개수(씨앗, 먹을 음식, 시작할 때 갖고 있던 것)를 지키도록 큰 묶음부터 고른다.
     */
    private List<Integer> slotsFor(LocalPlayer player, FarmData.ChestEntry chest, int menuId) {
        Inventory inv = player.getInventory();
        Map<Item, List<int[]>> byItem = new HashMap<>(); // item → [인벤토리 칸, 개수]
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty() || !belongsTo(stack, chest) || isProtected(stack)) {
                continue;
            }
            byItem.computeIfAbsent(stack.getItem(), k -> new ArrayList<>()).add(new int[]{i, stack.getCount()});
        }
        List<Integer> invSlots = new ArrayList<>();
        for (Map.Entry<Item, List<int[]>> e : byItem.entrySet()) {
            Item item = e.getKey();
            int total = InvUtil.count(player, item);
            int keep = keepCount(player, item, chest.catchAll);
            List<int[]> stacks = e.getValue();
            stacks.sort((a, b) -> b[1] - a[1]);
            for (int[] s : stacks) {
                if (total - s[1] >= keep) {
                    invSlots.add(s[0]);
                    total -= s[1];
                }
            }
        }
        if (menuId < 0) {
            return invSlots;
        }
        // 열린 메뉴에서 플레이어 인벤토리 칸 번호로 바꾼다.
        List<Integer> menuSlots = new ArrayList<>();
        AbstractContainerMenu menu = player.containerMenu;
        for (int invSlot : invSlots) {
            for (Slot slot : menu.slots) {
                if (slot.container == inv && slot.getContainerSlot() == invSlot) {
                    menuSlots.add(slot.index);
                    break;
                }
            }
        }
        return menuSlots;
    }

    private boolean belongsTo(ItemStack stack, FarmData.ChestEntry chest) {
        String crop = productCache.get(stack.getItem());
        if (!chest.catchAll) {
            return crop != null && chest.crops.contains(crop);
        }
        // 기타 상자: 작물 상자가 따로 있는 수확물은 거기로 보낸다.
        return crop == null || data.chestsFor(crop).isEmpty();
    }

    /** 절대 상자에 넣지 않는 것: 도구, 침낭, 낚싯바늘, 쓸 뼛가루. */
    private boolean isProtected(ItemStack stack) {
        return stack.isDamageableItem() || stack.is(SleepModule.SLEEPING_BAG_ITEMS) || stack.is(LURE_ITEMS)
                || stack.getItem() instanceof FishingRodItem
                || (stack.is(Items.BONE_MEAL) && AfkConfig.FARM_BONEMEAL.get());
    }

    private int keepCount(LocalPlayer player, Item item, boolean catchAll) {
        int keep = 0;
        if (isSeedOfSelected(item)) {
            keep = Math.max(keep, AfkConfig.FARM_KEEP_SEEDS.get());
        }
        ItemStack probe = new ItemStack(item);
        AutoEater.FoodMode mode = AfkConfig.FOOD_MODE.get();
        if (AutoEater.isSafeFood(probe) && (mode == AutoEater.FoodMode.ANY
                || (mode == AutoEater.FoodMode.NON_CROP && !isCropProduct(probe)))) {
            keep = Math.max(keep, AfkConfig.FOOD_KEEP.get());
        }
        if (catchAll) {
            // 기타 상자에는 이번에 새로 얻은 만큼만 넣는다 (원래 갖고 있던 건 그대로).
            keep = Math.max(keep, startCounts.getOrDefault(item, 0));
        }
        return keep;
    }

    private boolean isSeedOfSelected(Item item) {
        Level level = Minecraft.getInstance().level;
        for (String crop : data.selectedCrops) {
            if (Crops.seedOf(level, crop).is(item)) {
                return true;
            }
        }
        return false;
    }

    /** 농사로 거둔 작물인지 (자동으로 먹기에서 제외할 때 씀). */
    public boolean isCropProduct(ItemStack stack) {
        return data != null && productCache.containsKey(stack.getItem());
    }

    private void rebuildProductCache() {
        productCache.clear();
        Level level = Minecraft.getInstance().level;
        Set<String> crops = new HashSet<>(data.selectedCrops);
        for (FarmData.ChestEntry c : data.chests) {
            crops.addAll(c.crops);
        }
        for (String crop : crops) {
            for (Item item : Crops.knownProducts(crop)) {
                productCache.put(item, crop);
            }
            if (level != null) {
                ItemStack seed = Crops.seedOf(level, crop);
                if (!seed.isEmpty()) {
                    productCache.putIfAbsent(seed.getItem(), crop);
                }
            }
            for (String itemId : data.learnedProducts.getOrDefault(crop, Set.of())) {
                Item item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(itemId));
                if (item != Items.AIR) {
                    productCache.putIfAbsent(item, crop);
                }
            }
            Item blockItem = Crops.block(crop).asItem();
            if (blockItem != Items.AIR && Crops.knownProducts(crop).isEmpty()) {
                productCache.putIfAbsent(blockItem, crop);
            }
        }
    }

    // ---- 수확물 학습 ----

    private void startLearning(LocalPlayer player, String cropId, BlockPos pos) {
        learnCrop = cropId;
        learnPos = pos;
        learnTicks = 0;
        learnKnownEntities = new HashSet<>();
        for (ItemEntity e : player.level().getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(3))) {
            learnKnownEntities.add(e.getId());
        }
        learnInventory = countInventory(player);
    }

    private void tickLearning(LocalPlayer player) {
        if (learnCrop == null) {
            return;
        }
        learnTicks++;
        for (ItemEntity e : player.level().getEntitiesOfClass(ItemEntity.class, new AABB(learnPos).inflate(2.5))) {
            if (learnKnownEntities.add(e.getId())) {
                learnProduct(learnCrop, e.getItem().getItem());
            }
        }
        if (learnTicks == 10) {
            // 바로 인벤토리로 들어오는 경우
            countInventory(player).forEach((item, count) -> {
                if (count > learnInventory.getOrDefault(item, 0)) {
                    learnProduct(learnCrop, item);
                }
            });
        }
        if (learnTicks > LEARN_TICKS) {
            learnCrop = null;
        }
    }

    private void learnProduct(String cropId, Item item) {
        if (productCache.containsKey(item) || isProtected(new ItemStack(item))) {
            return;
        }
        data.learn(cropId, BuiltInRegistries.ITEM.getKey(item).toString());
        productCache.put(item, cropId);
    }

    private static Map<Item, Integer> countInventory(LocalPlayer player) {
        Map<Item, Integer> counts = new HashMap<>();
        Inventory inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty()) {
                counts.merge(stack.getItem(), stack.getCount(), Integer::sum);
            }
        }
        return counts;
    }

    private static void notify(LocalPlayer player, String text, ChatFormatting color) {
        Util.notify(player, PREFIX, text, color);
    }
}
