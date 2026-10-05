package com.dumaru.afkfishing.common;

import com.dumaru.afkfishing.AfkConfig;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.function.ToDoubleFunction;
import net.minecraft.client.player.Input;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CactusBlock;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 블록 단위 A* 길찾기와 경로 따라가기.
 * 노드는 "밟고 서는 블록"이다. 농경지는 점프·낙하로 착지하면 망가지므로 농경지 위로 0.5칸 넘게 떨어지는 길은 만들지 않고,
 * 점프는 한 칸 오를 때만 한다. 입력은 MovementInputUpdateEvent에서 덮어쓰므로 창 포커스와 무관하다.
 */
public final class Navigator {
    public static final Navigator INSTANCE = new Navigator();

    public enum Result { RUNNING, ARRIVED, FAILED }

    /** 목적지 조건. ground = 밟고 설 블록, standY = 섰을 때 발 높이. */
    public interface Goal {
        boolean isGoal(BlockPos ground, double standY);

        double estimate(BlockPos ground);
    }

    private static final int MAX_NODES = 12000;
    private static final int MAX_RANGE = 96;
    private static final int STUCK_TICKS = 40;
    private static final int MAX_REPLANS = 3;
    private static final double MAX_DROP = 3.2;

    private Goal goal;
    private Vec3 preciseEnd;
    private double precision;
    private List<Vec3> waypoints;
    private int index;
    private boolean active;
    private boolean precisePhase;
    private int stuckTicks;
    private double bestRemaining;
    private int replans;
    private int ticks;
    private int timeoutTicks;
    private String failReason = "";
    private final java.util.Random random = new java.util.Random();
    private double ramp = 1.0;
    private double tripSpeed = 1.0;
    private float walkPitch = 20f;

    // applyInput에서 쓸 이번 틱 입력
    private double moveX;
    private double moveZ;
    private double moveSpeed;
    private boolean moveJump;

    private Navigator() {
    }

    // ---- 목적지 조건 ----

    /** 눈 위치에서 target 블록 중심까지 reach 이내로 닿는 자리. */
    public static Goal reach(BlockPos target, double reach) {
        Vec3 c = Vec3.atCenterOf(target);
        return goal((g, sy) -> new Vec3(g.getX() + 0.5, sy + 1.62, g.getZ() + 0.5).distanceTo(c) <= reach,
                g -> Math.max(0, Vec3.atCenterOf(g).distanceTo(c) - reach));
    }

    /** point와 수평 거리 radius 이내, 높이 차 0.7 이내인 자리. */
    public static Goal near(Vec3 point, double radius) {
        return goal((g, sy) -> Util.horizontalDistance(new Vec3(g.getX() + 0.5, sy, g.getZ() + 0.5), point) <= radius
                        && Math.abs(sy - point.y) < 0.7,
                g -> Math.max(0, Vec3.atBottomCenterOf(g.above()).distanceTo(point) - radius));
    }

    public static Goal goal(GoalTest test, ToDoubleFunction<BlockPos> estimate) {
        return new Goal() {
            @Override
            public boolean isGoal(BlockPos ground, double standY) {
                return test.test(ground, standY);
            }

            @Override
            public double estimate(BlockPos ground) {
                return estimate.applyAsDouble(ground);
            }
        };
    }

    @FunctionalInterface
    public interface GoalTest {
        boolean test(BlockPos ground, double standY);
    }

    // ---- 실행 ----

    /**
     * 길찾기를 시작한다. preciseEnd가 있으면 경로 끝에서 그 좌표까지 precision 이내로 정밀하게 다가간다.
     */
    public void start(Goal goal, Vec3 preciseEnd, double precision, int timeoutTicks) {
        this.goal = goal;
        this.preciseEnd = preciseEnd;
        this.precision = precision;
        this.timeoutTicks = timeoutTicks;
        this.waypoints = null;
        this.index = 0;
        this.precisePhase = false;
        this.replans = 0;
        this.ticks = 0;
        this.failReason = "";
        this.active = true;
        this.ramp = AfkConfig.HUMAN_WALK.get() ? 0.35 : 1.0;
        this.tripSpeed = 0.9 + random.nextDouble() * 0.1;
        this.walkPitch = 12f + random.nextFloat() * 16f;
        resetProgress();
        clearMove();
    }

    public boolean isActive() {
        return active;
    }

    public String failReason() {
        return failReason;
    }

    public void cancel() {
        if (active) {
            stopSprint();
        }
        active = false;
        clearMove();
    }

    public Result tick(LocalPlayer player) {
        if (!active) {
            return Result.FAILED;
        }
        if (++ticks > timeoutTicks) {
            return fail("이동 시간 초과");
        }
        if (player.isInWater() || player.isInLava()) {
            return fail("이동 중 물/용암에 빠짐");
        }
        if (waypoints == null) {
            String error = plan(player);
            if (error != null) {
                return fail(error);
            }
        }
        Vec3 pos = player.position();
        clearMove();
        boolean human = AfkConfig.HUMAN_WALK.get();
        boolean last;
        double speed;
        double targetDist;

        if (!precisePhase && index < waypoints.size()) {
            Vec3 wp = waypoints.get(index);
            double dh = Util.horizontalDistance(pos, wp);
            last = index == waypoints.size() - 1;
            // 사람처럼 걸을 때는 꺾이는 지점을 조금 일찍 넘겨서 모서리를 둥글게 돈다
            double pass = last ? 0.25 : (human ? 0.45 : 0.35);
            if (dh < pass && Math.abs(pos.y - wp.y) < 0.6) {
                index++;
                resetProgress();
                if (index >= waypoints.size()) {
                    if (preciseEnd == null) {
                        return arrive();
                    }
                    precisePhase = true;
                }
                return Result.RUNNING;
            }
            moveX = wp.x - pos.x;
            moveZ = wp.z - pos.z;
            speed = last ? Math.max(0.2, Math.min(1.0, dh / 0.5)) : 1.0;
            moveJump = wp.y - pos.y > 0.55 && dh < 1.5 && player.onGround();
            trackProgress(dh + Math.abs(wp.y - pos.y));
            targetDist = dh;
        } else if (preciseEnd != null) {
            precisePhase = true;
            last = true;
            double d = Util.horizontalDistance(pos, preciseEnd);
            if (d < precision) {
                return arrive();
            }
            moveX = preciseEnd.x - pos.x;
            moveZ = preciseEnd.z - pos.z;
            speed = Math.max(0.15, Math.min(1.0, d / 0.35));
            trackProgress(d);
            targetDist = d;
        } else {
            return arrive();
        }

        if (human) {
            // 출발할 때 천천히 가속, 이번 이동의 걸음 빠르기는 조금씩 다르게
            ramp = Math.min(1.0, ramp + 0.12);
            speed = Math.min(speed, ramp) * tripSpeed;
            // 가는 방향을 바라본다. 바로 앞(정밀하게 자리 잡는 중)이면 돌지 않고 게걸음으로 맞춘다.
            if (!last || targetDist > 0.7) {
                float yaw = (float) (Math.toDegrees(Math.atan2(moveZ, moveX)) - 90.0);
                Look.INSTANCE.setAngles(player, yaw, walkPitch, 2.5f);
            }
            float err = Look.INSTANCE.yawError(player);
            if (err > 100) {
                speed *= 0.25; // 거의 뒤돌아야 하면 먼저 몸을 돌린다
            } else if (err > 50) {
                speed *= 0.6;
            }
            boolean sprint = AfkConfig.SPRINT_LONG.get() && !last && !moveJump && err < 15
                    && remainingDistance(pos) > 10 && player.getFoodData().getFoodLevel() > 6;
            player.setSprinting(sprint);
        } else {
            player.setSprinting(false);
        }
        moveSpeed = speed;

        if (stuckTicks > STUCK_TICKS) {
            if (precisePhase && preciseEnd != null && Util.horizontalDistance(pos, preciseEnd) < 0.4) {
                return arrive(); // 정밀 접근에서 조금 모자라도 충분히 가까우면 도착으로 본다
            }
            if (++replans > MAX_REPLANS) {
                return fail("길이 막혀서 더 갈 수 없음");
            }
            waypoints = null;
            precisePhase = false;
            index = 0;
            resetProgress();
        }
        return Result.RUNNING;
    }

    private double remainingDistance(Vec3 pos) {
        double total = 0;
        Vec3 prev = pos;
        for (int i = index; i < waypoints.size(); i++) {
            total += Util.horizontalDistance(prev, waypoints.get(i));
            prev = waypoints.get(i);
        }
        return total;
    }

    /** MovementInputUpdateEvent에서 호출. */
    public void applyInput(Input input, LocalPlayer player) {
        if (!active) {
            return;
        }
        Util.steer(input, player, moveX, moveZ, moveSpeed, moveJump);
    }

    private Result arrive() {
        active = false;
        clearMove();
        stopSprint();
        return Result.ARRIVED;
    }

    private Result fail(String reason) {
        failReason = reason;
        active = false;
        clearMove();
        stopSprint();
        return Result.FAILED;
    }

    private static void stopSprint() {
        LocalPlayer player = net.minecraft.client.Minecraft.getInstance().player;
        if (player != null) {
            player.setSprinting(false);
        }
    }

    private void clearMove() {
        moveX = 0;
        moveZ = 0;
        moveSpeed = 0;
        moveJump = false;
    }

    private void resetProgress() {
        stuckTicks = 0;
        bestRemaining = Double.MAX_VALUE;
    }

    private void trackProgress(double remaining) {
        if (remaining < bestRemaining - 0.05) {
            bestRemaining = remaining;
            stuckTicks = 0;
        } else {
            stuckTicks++;
        }
    }

    // ---- A* ----

    private static final class Node {
        final BlockPos ground;
        final double standY;
        final double g;
        final double f;
        final Node parent;

        Node(BlockPos ground, double standY, double g, double f, Node parent) {
            this.ground = ground;
            this.standY = standY;
            this.g = g;
            this.f = f;
            this.parent = parent;
        }
    }

    /** 경로를 계산해 waypoints에 넣는다. 실패하면 이유를 돌려준다. */
    private String plan(LocalPlayer player) {
        Level level = player.level();
        BlockPos start = startGround(level, player);
        if (start == null) {
            return "현재 위치에서 출발할 수 없음";
        }
        double startY = start.getY() + Math.max(0, standTop(level, start, true));
        Map<Long, Double> topCache = new HashMap<>();
        Map<Long, Double> bestG = new HashMap<>();
        PriorityQueue<Node> open = new PriorityQueue<>((a, b) -> Double.compare(a.f, b.f));
        open.add(new Node(start, startY, 0, goal.estimate(start), null));
        bestG.put(start.asLong(), 0.0);
        boolean avoidFarmland = AfkConfig.FARM_AVOID_FARMLAND.get();
        int expanded = 0;
        while (!open.isEmpty()) {
            Node node = open.poll();
            Double known = bestG.get(node.ground.asLong());
            if (known != null && known < node.g - 1.0E-6) {
                continue;
            }
            if (goal.isGoal(node.ground, node.standY)) {
                buildWaypoints(node, level, avoidFarmland);
                return null;
            }
            if (++expanded > MAX_NODES) {
                break;
            }
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) {
                        continue;
                    }
                    Node next = step(level, node, dx, dz, topCache, avoidFarmland);
                    if (next == null) {
                        continue;
                    }
                    if (Math.abs(next.ground.getX() - start.getX()) > MAX_RANGE
                            || Math.abs(next.ground.getZ() - start.getZ()) > MAX_RANGE) {
                        continue;
                    }
                    Double prev = bestG.get(next.ground.asLong());
                    if (prev == null || next.g < prev - 1.0E-6) {
                        bestG.put(next.ground.asLong(), next.g);
                        open.add(next);
                    }
                }
            }
        }
        return "목적지까지 갈 수 있는 길을 찾지 못함";
    }

    private Node step(Level level, Node from, int dx, int dz, Map<Long, Double> cache, boolean avoidFarmland) {
        BlockPos g = from.ground;
        boolean diagonal = dx != 0 && dz != 0;
        for (int dy = 1; dy >= -3; dy--) {
            BlockPos n = g.offset(dx, dy, dz);
            double top = cachedTop(level, n, cache);
            if (Double.isNaN(top)) {
                continue;
            }
            double standY = n.getY() + top;
            double rise = standY - from.standY;
            if (rise > 1.25) {
                return null; // 이 열의 가장 높은 땅도 못 올라감
            }
            if (diagonal && Math.abs(rise) > 0.6) {
                return null;
            }
            if (rise > 0.6 && !clear(level, g.above(3))) {
                return null; // 점프할 머리 공간 없음
            }
            if (rise < -0.6) {
                double drop = -rise;
                if (drop > MAX_DROP) {
                    return null;
                }
                if (level.getBlockState(n).getBlock() instanceof FarmBlock && drop > 0.5) {
                    return null; // 농경지에 떨어지면 흙으로 변한다
                }
                for (int y = n.getY() + 3; y <= g.getY() + 2; y++) {
                    if (!clear(level, new BlockPos(n.getX(), y, n.getZ()))) {
                        return null;
                    }
                }
            }
            if (diagonal) {
                int hi = Math.max(g.getY(), n.getY());
                if (!clear(level, new BlockPos(g.getX() + dx, hi + 1, g.getZ())) || !clear(level, new BlockPos(g.getX() + dx, hi + 2, g.getZ()))
                        || !clear(level, new BlockPos(g.getX(), hi + 1, g.getZ() + dz)) || !clear(level, new BlockPos(g.getX(), hi + 2, g.getZ() + dz))) {
                    return null;
                }
            }
            double cost = diagonal ? 1.414 : 1.0;
            if (rise > 0.6) {
                cost += 1.0;
            } else if (rise < -0.6) {
                cost += 0.5 * -rise;
            }
            if (avoidFarmland && level.getBlockState(n).getBlock() instanceof FarmBlock) {
                cost += 2.0;
            }
            double gCost = from.g + cost;
            return new Node(n, standY, gCost, gCost + goal.estimate(n), from);
        }
        return null;
    }

    private void buildWaypoints(Node end, Level level, boolean avoidFarmland) {
        List<Node> nodes = new ArrayList<>();
        for (Node n = end; n != null; n = n.parent) {
            nodes.add(n);
        }
        Collections.reverse(nodes);
        List<Vec3> list = new ArrayList<>();
        if (nodes.size() > 1) {
            if (AfkConfig.HUMAN_WALK.get() && replans == 0) { // 막혀서 다시 찾는 중이면 지름길 없이 칸 단위로
                // 줄 당기기: 곧게 갈 수 있는 가장 먼 지점까지 한 번에 간다. 꺾이는 지점은 칸 안에서 조금씩 비튼다.
                int a = 0;
                int lastIndex = nodes.size() - 1;
                while (a < lastIndex) {
                    int next = a + 1;
                    for (int j = lastIndex; j > a + 1; j--) {
                        if (straightClear(level, nodes.get(a), nodes.get(j), avoidFarmland)) {
                            next = j;
                            break;
                        }
                    }
                    Node n = nodes.get(next);
                    double jitter = next == lastIndex ? 0 : 0.15;
                    list.add(new Vec3(n.ground.getX() + 0.5 + (random.nextDouble() * 2 - 1) * jitter, n.standY,
                            n.ground.getZ() + 0.5 + (random.nextDouble() * 2 - 1) * jitter));
                    a = next;
                }
            } else {
                // 첫 노드는 지금 서 있는 칸이라 건너뛴다.
                for (int i = 1; i < nodes.size(); i++) {
                    Node n = nodes.get(i);
                    list.add(new Vec3(n.ground.getX() + 0.5, n.standY, n.ground.getZ() + 0.5));
                }
            }
        } else if (preciseEnd != null) {
            precisePhase = true;
        }
        waypoints = list;
        index = 0;
    }

    /**
     * a에서 b까지 같은 높이로 곧게 걸어갈 수 있는지. 0.25칸 간격으로 짚으며 몸 폭(좌우 0.3)까지
     * 밟을 수 있는 같은 높이의 땅인지 본다. 농경지를 피하는 중이면 농경지를 가로지르는 지름길은 쓰지 않는다.
     */
    private static boolean straightClear(Level level, Node a, Node b, boolean avoidFarmland) {
        if (Math.abs(a.standY - b.standY) > 0.01) {
            return false;
        }
        double ax = a.ground.getX() + 0.5;
        double az = a.ground.getZ() + 0.5;
        double dx = b.ground.getX() + 0.5 - ax;
        double dz = b.ground.getZ() + 0.5 - az;
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1.0E-4) {
            return true;
        }
        double px = -dz / len * 0.3;
        double pz = dx / len * 0.3;
        boolean endsOnFarmland = level.getBlockState(a.ground).getBlock() instanceof FarmBlock
                || level.getBlockState(b.ground).getBlock() instanceof FarmBlock;
        int steps = (int) Math.ceil(len / 0.25);
        int y = a.ground.getY();
        for (int i = 0; i <= steps; i++) {
            double t = i / (double) steps;
            for (int side = -1; side <= 1; side++) {
                double x = ax + dx * t + px * side;
                double z = az + dz * t + pz * side;
                BlockPos cell = new BlockPos(net.minecraft.util.Mth.floor(x), y, net.minecraft.util.Mth.floor(z));
                double top = standTop(level, cell, false);
                if (Double.isNaN(top) || Math.abs(cell.getY() + top - a.standY) > 0.01) {
                    return false;
                }
                if (avoidFarmland && !endsOnFarmland && level.getBlockState(cell).getBlock() instanceof FarmBlock) {
                    return false;
                }
            }
        }
        return true;
    }

    private static BlockPos startGround(Level level, LocalPlayer player) {
        BlockPos below = BlockPos.containing(player.getX(), player.getY() - 0.01, player.getZ());
        if (!Double.isNaN(standTop(level, below, true))) {
            return below;
        }
        BlockPos on = player.getOnPos();
        if (!Double.isNaN(standTop(level, on, true))) {
            return on;
        }
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos n = below.relative(dir);
            if (!Double.isNaN(standTop(level, n, true))
                    && player.getBoundingBox().inflate(0.05, 0, 0.05).intersects(n.getX(), n.getY(), n.getZ(), n.getX() + 1, n.getY() + 1.5, n.getZ() + 1)) {
                return n;
            }
        }
        return player.onGround() ? below : null;
    }

    private static double cachedTop(Level level, BlockPos pos, Map<Long, Double> cache) {
        return cache.computeIfAbsent(pos.asLong(), k -> standTop(level, pos, false));
    }

    /**
     * pos를 밟고 설 수 있으면 블록 안에서의 윗면 높이(0~1), 아니면 NaN.
     * 위 두 칸이 비어 있고(액체 없음) 위험한 블록이 없어야 한다.
     */
    public static double standTop(Level level, BlockPos pos, boolean lenient) {
        if (!level.isLoaded(pos)) {
            return Double.NaN;
        }
        BlockState state = level.getBlockState(pos);
        VoxelShape shape = state.getCollisionShape(level, pos);
        if (shape.isEmpty()) {
            return Double.NaN;
        }
        double top = shape.max(Direction.Axis.Y);
        if (top <= 0 || top > 1.0) {
            return Double.NaN;
        }
        if (!clear(level, pos.above()) || !clear(level, pos.above(2))) {
            return Double.NaN;
        }
        if (!lenient && isDangerous(level, pos, state)) {
            return Double.NaN;
        }
        return top;
    }

    /** 충돌 상자가 없고 액체도 없는 칸. */
    public static boolean clear(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.getCollisionShape(level, pos).isEmpty() && state.getFluidState().isEmpty();
    }

    private static boolean isDangerous(Level level, BlockPos ground, BlockState groundState) {
        if (groundState.is(Blocks.MAGMA_BLOCK) || groundState.getBlock() instanceof CampfireBlock) {
            return true;
        }
        BlockPos feet = ground.above();
        BlockState feetState = level.getBlockState(feet);
        if (feetState.getBlock() instanceof SweetBerryBushBlock || feetState.is(Blocks.FIRE) || feetState.is(Blocks.SOUL_FIRE)
                || feetState.is(Blocks.WITHER_ROSE) || feetState.is(Blocks.POWDER_SNOW) || feetState.is(Blocks.COBWEB)) {
            return true;
        }
        // 선인장 옆에 서면 닿아서 피해를 입는다.
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            if (level.getBlockState(feet.relative(dir)).getBlock() instanceof CactusBlock
                    || level.getBlockState(feet.above().relative(dir)).getBlock() instanceof CactusBlock) {
                return true;
            }
            if (!level.getFluidState(feet.relative(dir)).isEmpty() && level.getFluidState(feet.relative(dir)).is(net.minecraft.tags.FluidTags.LAVA)) {
                return true;
            }
        }
        return false;
    }
}
