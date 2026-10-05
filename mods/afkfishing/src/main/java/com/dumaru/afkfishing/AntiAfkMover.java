package com.dumaru.afkfishing;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.Input;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * AFK 킥 방지를 위해 주기적으로 실제로 한 걸음 이동했다가 원래 위치로 돌아온다.
 * 키 입력을 흉내 내지 않고 MovementInputUpdateEvent에서 Input 값을 덮어쓰므로 창 포커스와 무관하다.
 */
public final class AntiAfkMover {
    public enum Result { RUNNING, DONE, FAILED }

    private enum Phase { NONE, OUT, RETURN, SETTLE, JUMP, WALK }

    private static final int JITTER_TICKS = 30 * 20;
    private static final int RETURN_TIMEOUT = 60;
    private static final int SETTLE_TICKS = 6;
    private static final double ARRIVE_DIST = 0.05;
    private static final double MAX_DEVIATION = 1.5;
    private static final double PROBE_DIST = 0.6;

    private final Random random;
    private int ticksUntilMove;
    private Phase phase = Phase.NONE;
    private int phaseTicks;
    private Vec3 origin = Vec3.ZERO;
    private Vec3 walkTarget = Vec3.ZERO;
    private float outForward;
    private float outLeft;
    private String failReason = "";

    AntiAfkMover(Random random) {
        this.random = random;
    }

    public void reset() {
        phase = Phase.NONE;
        scheduleNext();
    }

    public void cancel() {
        phase = Phase.NONE;
    }

    /**
     * 목표 지점으로 걸어가도록 입력을 덮어쓴다 (침낭 회수 후 복귀용). tick()은 쓰지 않고,
     * 도착 판정과 cancel()은 호출하는 쪽이 한다. 매 틱 호출해서 목표를 갱신해도 된다.
     */
    public void walkTo(Vec3 target) {
        walkTarget = target;
        phase = Phase.WALK;
    }

    public void countdown() {
        if (ticksUntilMove > 0) {
            ticksUntilMove--;
        }
    }

    public boolean isDue() {
        return ticksUntilMove <= 0;
    }

    public int secondsUntilMove() {
        return ticksUntilMove / 20;
    }

    public String failReason() {
        return failReason;
    }

    private void scheduleNext() {
        int base = AfkConfig.ANTI_AFK_INTERVAL_SECONDS.get() * 20;
        ticksUntilMove = Math.max(20 * 30, base + random.nextInt(JITTER_TICKS * 2 + 1) - JITTER_TICKS);
    }

    /** 이동을 시작한다. 안전한 방향이 없으면 제자리 점프로 대체한다. */
    public boolean begin(LocalPlayer player, float yaw) {
        if (!player.onGround() || player.isInWater()) {
            scheduleNext(); // 이상한 상태에서는 이번 이동을 건너뛴다
            return false;
        }
        player.setYRot(yaw);
        origin = player.position();
        phaseTicks = 0;

        AfkConfig.MovePattern pattern = AfkConfig.MOVE_PATTERN.get();
        if (pattern != AfkConfig.MovePattern.JUMP) {
            for (float[] dir : candidateDirections(pattern)) {
                if (isSafeStep(player, dir[0], dir[1])) {
                    outForward = dir[0];
                    outLeft = dir[1];
                    phase = Phase.OUT;
                    return true;
                }
            }
        }
        phase = Phase.JUMP;
        return true;
    }

    /** 설정한 패턴의 방향을 먼저 시도하고, 막혀 있으면 다른 축도 시도한다. 뒤쪽(물 반대편)이 우선. */
    private List<float[]> candidateDirections(AfkConfig.MovePattern pattern) {
        List<float[]> backForth = List.of(new float[]{-1, 0}, new float[]{1, 0});
        List<float[]> sideways = random.nextBoolean()
                ? List.of(new float[]{0, 1}, new float[]{0, -1})
                : List.of(new float[]{0, -1}, new float[]{0, 1});
        List<float[]> result = new ArrayList<>();
        if (pattern == AfkConfig.MovePattern.BACK_FORTH) {
            result.addAll(backForth);
            result.addAll(sideways);
        } else {
            result.addAll(sideways);
            result.addAll(backForth);
        }
        return result;
    }

    /** 해당 방향으로 한 걸음 갔을 때 막히지 않고, 발밑에 땅이 있고, 물/용암이 아닌지 검사한다. */
    private boolean isSafeStep(LocalPlayer player, float forward, float left) {
        Vec3 d = localToWorld(forward, left, player.getYRot()).scale(PROBE_DIST);
        Level level = player.level();
        AABB moved = player.getBoundingBox().move(d.x, 0, d.z);
        if (!level.noCollision(player, moved)) {
            return false;
        }
        if (level.noCollision(player, moved.move(0, -0.2, 0))) {
            return false; // 발밑이 비어 있음 (낭떠러지)
        }
        Vec3 target = player.position().add(d.x, 0, d.z);
        BlockPos feet = BlockPos.containing(target);
        return level.getFluidState(feet).isEmpty() && level.getFluidState(feet.below()).isEmpty();
    }

    public Result tick(LocalPlayer player) {
        if (phase == Phase.NONE) {
            return Result.DONE;
        }
        phaseTicks++;
        player.setSprinting(false);

        if (player.isInWater() || player.isInLava()) {
            return fail("이동 중 물/용암에 빠짐");
        }
        double dist = horizontalDistance(player.position(), origin);
        if (dist > MAX_DEVIATION) {
            return fail("이동 중 원위치에서 너무 멀어짐");
        }

        switch (phase) {
            case OUT -> {
                if (phaseTicks >= AfkConfig.MOVE_TICKS.get()) {
                    nextPhase(Phase.RETURN);
                }
            }
            case RETURN -> {
                if (dist < ARRIVE_DIST) {
                    nextPhase(Phase.SETTLE);
                } else if (phaseTicks > RETURN_TIMEOUT) {
                    if (dist < 0.3) {
                        nextPhase(Phase.SETTLE);
                    } else {
                        return fail("원위치로 돌아오지 못함");
                    }
                }
            }
            case JUMP -> {
                if (phaseTicks > 3 && player.onGround()) {
                    nextPhase(Phase.SETTLE);
                }
            }
            case SETTLE -> {
                if (phaseTicks >= SETTLE_TICKS) {
                    phase = Phase.NONE;
                    scheduleNext();
                    return Result.DONE;
                }
            }
            default -> {
            }
        }
        return Result.RUNNING;
    }

    /** MovementInputUpdateEvent에서 호출. 이동 중일 때만 입력을 덮어쓴다. */
    public void applyInput(Input input) {
        if (phase == Phase.NONE) {
            return;
        }
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        float forward = 0;
        float left = 0;
        boolean jump = false;
        switch (phase) {
            case OUT -> {
                forward = outForward;
                left = outLeft;
            }
            case RETURN, WALK -> {
                Vec3 goal = phase == Phase.WALK ? walkTarget : origin;
                Vec3 pos = player.position();
                double dx = goal.x - pos.x;
                double dz = goal.z - pos.z;
                double dist = Math.sqrt(dx * dx + dz * dz);
                if (dist > 1.0E-4) {
                    // 월드 방향을 플레이어 기준 (앞, 왼쪽) 입력으로 변환. 가까워질수록 입력을 줄여 지나치지 않게 한다.
                    double yawRad = Math.toRadians(player.getYRot());
                    double sin = Math.sin(yawRad);
                    double cos = Math.cos(yawRad);
                    double f = -dx * sin + dz * cos;
                    double l = dx * cos + dz * sin;
                    double scale = Math.max(0.15, Math.min(1.0, dist / 0.35)) / dist;
                    forward = (float) (f * scale);
                    left = (float) (l * scale);
                }
            }
            case JUMP -> jump = phaseTicks <= 1;
            default -> {
            }
        }
        input.forwardImpulse = forward;
        input.leftImpulse = left;
        input.up = forward > 0;
        input.down = forward < 0;
        input.left = left > 0;
        input.right = left < 0;
        input.jumping = jump;
        input.shiftKeyDown = false;
    }

    private void nextPhase(Phase next) {
        phase = next;
        phaseTicks = 0;
    }

    private Result fail(String reason) {
        failReason = reason;
        phase = Phase.NONE;
        return Result.FAILED;
    }

    /** Entity.getInputVector와 같은 회전: (left, forward) → 월드 (x, z). */
    private static Vec3 localToWorld(float forward, float left, float yawDeg) {
        double yawRad = Math.toRadians(yawDeg);
        double sin = Math.sin(yawRad);
        double cos = Math.cos(yawRad);
        return new Vec3(left * cos - forward * sin, 0, forward * cos + left * sin);
    }

    private static double horizontalDistance(Vec3 a, Vec3 b) {
        double dx = a.x - b.x;
        double dz = a.z - b.z;
        return Math.sqrt(dx * dx + dz * dz);
    }
}
