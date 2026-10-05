package com.dumaru.afkfishing.common;

import com.dumaru.afkfishing.AfkConfig;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * 밤에 Comforts 침낭을 펼쳐 자고, 일어나면 침낭을 회수하는 과정. 낚시와 농사 모드가 같이 쓴다.
 * 서버의 Comforts autoUse가 켜져 있으면 침낭을 땅에 쓰는 순간 펼쳐지고 눕는다.
 */
public final class SleepModule {
    public enum Result { RUNNING, DONE, FAILED, ABORT }

    private enum Phase {
        NONE(""), PLAN("침낭 자리 찾는 중"), APPROACH("침낭 자리로 이동"), PLACE("침낭 펼치는 중"),
        WAIT_SLEEP("눕는 중"), SLEEPING("수면 중"), PICKUP("침낭 회수 중"), COLLECT("침낭 줍는 중"), RETURN("제자리로 복귀 중");

        final String label;

        Phase(String label) {
            this.label = label;
        }
    }

    public static final TagKey<Item> SLEEPING_BAG_ITEMS =
            TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath("comforts", "sleeping_bags"));
    private static final TagKey<Block> SLEEPING_BAG_BLOCKS =
            TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath("comforts", "sleeping_bags"));
    // 바닐라 침대를 쓸 수 있는 밤 시간대 (맑은 날 기준)
    private static final long NIGHT_START = 12542;
    private static final long NIGHT_END = 23460;
    private static final int SLEEP_START_TIMEOUT = 60;
    private static final int SLEEP_RETRY_TICKS = 60 * 20;
    private static final int BAG_PICKUP_TIMEOUT = 100;
    private static final int COLLECT_TIMEOUT = 200;
    private static final int MAX_PATH_CANDIDATES = 6;

    private final String prefix;
    private Phase phase = Phase.NONE;
    private int phaseTicks;
    private int retryIn;
    private Vec3 returnSpot;
    private AABB forbidden;
    private BlockPos bagFoot;
    private Direction bagDir;
    private boolean failed;
    private String failReason = "";

    public SleepModule(String prefix) {
        this.prefix = prefix;
    }

    public void reset() {
        phase = Phase.NONE;
        retryIn = 0;
    }

    public boolean isActive() {
        return phase != Phase.NONE;
    }

    public String label() {
        return phase.label;
    }

    public String failReason() {
        return failReason;
    }

    /** 매 틱 호출해서 재시도 대기 시간을 줄인다. */
    public void countdown() {
        if (retryIn > 0) {
            retryIn--;
        }
    }

    public boolean shouldSleep(LocalPlayer player) {
        Level level = player.level();
        return AfkConfig.SLEEP_AT_NIGHT.get()
                && retryIn <= 0
                && level.dimensionType().bedWorks() // 네더/엔드에서는 침대가 폭발한다
                && isNight(level)
                && player.onGround()
                && !player.isInWater()
                && InvUtil.find(player, s -> s.is(SLEEPING_BAG_ITEMS)) >= 0;
    }

    /** HUD 표시용 상태. 옵션이 꺼져 있으면 null. */
    public String status(LocalPlayer player) {
        if (!AfkConfig.SLEEP_AT_NIGHT.get()) {
            return null;
        }
        if (isActive()) {
            return phase.label;
        }
        if (InvUtil.find(player, s -> s.is(SLEEPING_BAG_ITEMS)) < 0) {
            return "인벤토리에 침낭 없음";
        }
        if (!player.level().dimensionType().bedWorks()) {
            return "이 차원에서는 잘 수 없음";
        }
        if (retryIn > 0) {
            return "재시도 " + retryIn / 20 + "초 후";
        }
        return isNight(player.level()) ? "밤 - 곧 잠" : "밤이 되면 잠";
    }

    public static boolean isNight(Level level) {
        long time = level.getDayTime() % 24000L;
        return time >= NIGHT_START && time < NIGHT_END;
    }

    /**
     * 잠자기를 시작한다. returnSpot이 있으면 일어난 뒤 그 자리로 돌아온다(낚시).
     * forbidden 안(농장)에는 침낭을 펼치지 않는다.
     */
    public void begin(Vec3 returnSpot, AABB forbidden) {
        this.returnSpot = returnSpot;
        this.forbidden = forbidden;
        this.failed = false;
        this.failReason = "";
        this.bagFoot = null;
        this.bagDir = null;
        setPhase(Phase.PLAN);
    }

    public void cancel() {
        if (phase != Phase.NONE) {
            Navigator.INSTANCE.cancel();
        }
        phase = Phase.NONE;
    }

    public Result tick(Minecraft mc, LocalPlayer player) {
        phaseTicks++;
        switch (phase) {
            case PLAN -> tickPlan(mc, player);
            case APPROACH -> {
                Navigator.Result r = Navigator.INSTANCE.tick(player);
                if (r == Navigator.Result.ARRIVED) {
                    setPhase(Phase.PLACE);
                } else if (r == Navigator.Result.FAILED) {
                    fail(player, "침낭 자리까지 가지 못함 (" + Navigator.INSTANCE.failReason() + ")");
                }
            }
            case PLACE -> tickPlace(mc, player);
            case WAIT_SLEEP -> {
                if (player.isSleeping()) {
                    Util.notify(player, prefix, "밤이 되어 침낭에서 잡니다.", ChatFormatting.GRAY);
                    setPhase(Phase.SLEEPING);
                } else if (phaseTicks > SLEEP_START_TIMEOUT) {
                    if (findPlacedBag(player.level()) != null) {
                        // 펼쳐졌는데 눕지 못했으면 회수한다.
                        failed = true;
                        failReason = "잠들지 못함";
                        retryIn = SLEEP_RETRY_TICKS;
                        Util.notify(player, prefix, "잠들지 못했습니다. 침낭을 회수하고 1분 뒤 다시 시도합니다.", ChatFormatting.GOLD);
                        setPhase(Phase.PICKUP);
                    } else {
                        fail(player, "잠들지 못함 (몬스터가 가까이 있거나 잘 수 없는 시간)");
                    }
                }
            }
            case SLEEPING -> {
                if (!player.isSleeping()) {
                    retryIn = SLEEP_RETRY_TICKS;
                    Util.notify(player, prefix, "기상. 침낭을 회수합니다.", ChatFormatting.GRAY);
                    setPhase(Phase.PICKUP);
                }
            }
            case PICKUP -> tickPickup(mc, player);
            case COLLECT -> tickCollect(player);
            case RETURN -> {
                Navigator.Result r = Navigator.INSTANCE.tick(player);
                if (r == Navigator.Result.ARRIVED) {
                    return finish();
                } else if (r == Navigator.Result.FAILED) {
                    phase = Phase.NONE;
                    failReason = "제자리로 돌아오지 못함 (" + Navigator.INSTANCE.failReason() + ")";
                    return Result.ABORT;
                }
            }
            default -> {
                return failed ? Result.FAILED : Result.DONE;
            }
        }
        if (phase == Phase.NONE) {
            return failed ? Result.FAILED : Result.DONE;
        }
        return Result.RUNNING;
    }

    private Result finish() {
        phase = Phase.NONE;
        return failed ? Result.FAILED : Result.DONE;
    }

    private void setPhase(Phase next) {
        phase = next;
        phaseTicks = 0;
    }

    private void fail(LocalPlayer player, String reason) {
        failed = true;
        failReason = reason;
        retryIn = SLEEP_RETRY_TICKS;
        Util.notify(player, prefix, reason + " - 1분 뒤 다시 시도합니다.", ChatFormatting.GOLD);
        startReturnOrEnd(player);
    }

    private void startReturnOrEnd(LocalPlayer player) {
        if (returnSpot != null && Util.horizontalDistance(returnSpot, player.position()) > 0.15) {
            Navigator.INSTANCE.start(Navigator.near(returnSpot, 0.6), returnSpot, 0.08, 600);
            setPhase(Phase.RETURN);
        } else {
            phase = Phase.NONE;
        }
    }

    private void tickPlan(Minecraft mc, LocalPlayer player) {
        if (InvUtil.find(player, s -> s.is(SLEEPING_BAG_ITEMS)) < 0) {
            fail(player, "인벤토리에 침낭 없음");
            return;
        }
        List<Placement> candidates = findPlacements(player);
        if (candidates.isEmpty()) {
            fail(player, "반경 " + AfkConfig.BAG_SEARCH_RADIUS.get()
                    + "칸 안에 침낭을 펼칠 자리가 없음 (나란한 빈칸 2개 + 위 빈 공간 필요)");
            return;
        }
        for (int i = 0; i < Math.min(MAX_PATH_CANDIDATES, candidates.size()); i++) {
            Placement p = candidates.get(i);
            if (inSleepRange(player.position(), p.foot)) {
                bagFoot = p.foot;
                bagDir = p.dir;
                setPhase(Phase.PLACE);
                return;
            }
        }
        // 3칸 밖이면 걸어간다. 가장 좋은 자리부터 길을 찾아 본다.
        Placement p = candidates.get(0);
        bagFoot = p.foot;
        bagDir = p.dir;
        BlockPos foot = p.foot;
        BlockPos head = foot.relative(p.dir);
        AABB bagArea = new AABB(foot).minmax(new AABB(head));
        Navigator.INSTANCE.start(Navigator.goal(
                (g, sy) -> inSleepRange(new Vec3(g.getX() + 0.5, sy, g.getZ() + 0.5), foot)
                        && !bagArea.intersects(new AABB(g.above()).inflate(-0.2, 0, -0.2)),
                g -> Math.max(0, Vec3.atCenterOf(g).distanceTo(Vec3.atCenterOf(foot)) - 2.5)), null, 0, 400);
        setPhase(Phase.APPROACH);
    }

    private void tickPlace(Minecraft mc, LocalPlayer player) {
        if (phaseTicks < 4) {
            return; // 멈춰서 서버에 위치가 반영될 때까지 잠깐 대기
        }
        if (!InvUtil.ensureInHand(mc, player, s -> s.is(SLEEPING_BAG_ITEMS))) {
            if (InvUtil.find(player, s -> s.is(SLEEPING_BAG_ITEMS)) < 0 || phaseTicks > 100) {
                fail(player, "침낭을 손에 들지 못함");
            }
            return;
        }
        BlockPos head = bagFoot.relative(bagDir);
        Level level = player.level();
        if (!isBagSpotFree(level, bagFoot) || !isBagSpotFree(level, head)
                || new AABB(bagFoot).minmax(new AABB(head)).intersects(player.getBoundingBox())
                || !inSleepRange(player.position(), bagFoot)) {
            fail(player, "침낭 자리가 막힘");
            return;
        }
        // 침낭(침대)은 서버가 알고 있는 플레이어 방향으로 머리 쪽이 펼쳐진다.
        BlockPos ground = bagFoot.below();
        Vec3 hit = new Vec3(ground.getX() + 0.5, ground.getY() + 1.0, ground.getZ() + 0.5);
        Util.lookAt(player, hit);
        player.setYRot(bagDir.toYRot());
        Util.sendRotation(mc, player);
        InteractionResult result = mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND,
                new BlockHitResult(hit, Direction.UP, ground, false));
        if (result.shouldSwing()) {
            player.swing(InteractionHand.MAIN_HAND);
        }
        setPhase(Phase.WAIT_SLEEP);
    }

    private void tickPickup(Minecraft mc, LocalPlayer player) {
        BlockPos target = findPlacedBag(player.level());
        if (target == null) {
            if (phaseTicks > 5) {
                setPhase(Phase.COLLECT);
            }
            return;
        }
        if (phaseTicks > BAG_PICKUP_TIMEOUT) {
            mc.gameMode.stopDestroyBlock();
            phase = Phase.NONE;
            failReason = "침낭을 회수하지 못함 (" + target.toShortString() + ")";
            failed = true;
            retryIn = SLEEP_RETRY_TICKS;
            Util.notify(player, prefix, failReason, ChatFormatting.RED);
            return;
        }
        // 침낭은 강도 0.1이라 맨손으로도 몇 틱이면 부서진다. 아이템은 머리 쪽에서 떨어진다.
        Util.lookAt(player, Vec3.atBottomCenterOf(target).add(0, 0.1, 0));
        mc.gameMode.continueDestroyBlock(target, Direction.UP);
        player.swing(InteractionHand.MAIN_HAND);
    }

    private void tickCollect(LocalPlayer player) {
        ItemEntity dropped = findDroppedBag(player);
        if (dropped == null || phaseTicks > COLLECT_TIMEOUT) {
            Navigator.INSTANCE.cancel();
            startReturnOrEnd(player);
            return;
        }
        if (!Navigator.INSTANCE.isActive() || phaseTicks % 20 == 0) {
            Navigator.INSTANCE.start(Navigator.near(dropped.position(), 0.8), null, 0, COLLECT_TIMEOUT);
        }
        if (Navigator.INSTANCE.tick(player) == Navigator.Result.FAILED) {
            Util.notify(player, prefix, "떨어진 침낭에 가지 못했습니다. 직접 주워 주세요.", ChatFormatting.GOLD);
            startReturnOrEnd(player);
        }
    }

    // ---- 자리 찾기 ----

    private record Placement(BlockPos foot, Direction dir, double score) {
    }

    /** 설정한 반경 안에서 침낭 자리 후보를 좋은 순서로. 가깝고, 걷지 않아도 되는 자리를 우선한다. */
    private List<Placement> findPlacements(LocalPlayer player) {
        Level level = player.level();
        BlockPos feet = player.blockPosition();
        Vec3 look = Vec3.directionFromRotation(0, player.getYRot());
        AABB playerBox = player.getBoundingBox();
        int radius = AfkConfig.BAG_SEARCH_RADIUS.get();
        List<Placement> result = new ArrayList<>();
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos foot = feet.offset(dx, dy, dz);
                    if (!isBagSpotFree(level, foot)) {
                        continue;
                    }
                    boolean needsWalk = !inSleepRange(player.position(), foot);
                    for (Direction dir : Direction.Plane.HORIZONTAL) {
                        BlockPos head = foot.relative(dir);
                        AABB area = new AABB(foot).minmax(new AABB(head));
                        if (area.intersects(playerBox) || !isBagSpotFree(level, head)
                                || (forbidden != null && forbidden.intersects(area.deflate(0.1)))
                                || !level.getEntitiesOfClass(LivingEntity.class, area, e -> e != player).isEmpty()) {
                            continue;
                        }
                        Vec3 middle = Vec3.atBottomCenterOf(foot).add(Vec3.atBottomCenterOf(head)).scale(0.5);
                        Vec3 offset = middle.subtract(player.position());
                        // 가까울수록, 바라보는 방향(낚시면 물 쪽)의 반대편일수록, 걷지 않아도 될수록 좋은 자리
                        double score = offset.horizontalDistance() + Math.max(0, offset.normalize().dot(look)) * 1.5
                                + Math.abs(dy) * 0.5 + (needsWalk ? 2.0 : 0);
                        result.add(new Placement(foot, dir, score));
                    }
                }
            }
        }
        result.sort(Comparator.comparingDouble(Placement::score));
        return result;
    }

    /** 침낭 발 쪽 칸이 서버의 침대 사용 거리(Comforts: 수평 3, 수직 2) 안에 있는지. 여유를 조금 둔다. */
    private static boolean inSleepRange(Vec3 playerPos, BlockPos foot) {
        Vec3 c = Vec3.atBottomCenterOf(foot);
        return Math.abs(c.x - playerPos.x) <= 2.7 && Math.abs(c.z - playerPos.z) <= 2.7
                && Math.abs(c.y - playerPos.y) <= 1.9;
    }

    /** 빈칸이고(물 없음), 위가 막혀 있지 않고, 발밑이 농경지가 아닌 밟을 수 있는 블록인지. */
    private static boolean isBagSpotFree(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        BlockPos above = pos.above();
        BlockState aboveState = level.getBlockState(above);
        BlockPos below = pos.below();
        BlockState belowState = level.getBlockState(below);
        return state.canBeReplaced() && state.getFluidState().isEmpty()
                && aboveState.getCollisionShape(level, above).isEmpty() && aboveState.getFluidState().isEmpty()
                && !belowState.getCollisionShape(level, below).isEmpty() && belowState.getFluidState().isEmpty()
                && !(belowState.getBlock() instanceof FarmBlock);
    }

    private BlockPos findPlacedBag(Level level) {
        if (bagFoot == null) {
            return null;
        }
        BlockPos head = bagFoot.relative(bagDir);
        if (level.getBlockState(head).is(SLEEPING_BAG_BLOCKS)) {
            return head;
        }
        if (level.getBlockState(bagFoot).is(SLEEPING_BAG_BLOCKS)) {
            return bagFoot;
        }
        return null;
    }

    private ItemEntity findDroppedBag(LocalPlayer player) {
        if (bagFoot == null) {
            return null;
        }
        ItemEntity nearest = null;
        double best = Double.MAX_VALUE;
        for (ItemEntity item : player.level().getEntitiesOfClass(ItemEntity.class, new AABB(bagFoot).inflate(4),
                e -> e.isAlive() && e.getItem().is(SLEEPING_BAG_ITEMS))) {
            double d = item.distanceToSqr(player);
            if (d < best) {
                best = d;
                nearest = item;
            }
        }
        return nearest;
    }
}
