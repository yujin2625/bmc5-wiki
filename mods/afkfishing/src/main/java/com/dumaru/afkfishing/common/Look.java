package com.dumaru.afkfishing.common;

import com.dumaru.afkfishing.AfkConfig;
import java.util.Random;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * 시선(카메라) 회전을 사람처럼 부드럽게 한다. 목표 각도만 정해 두면 매 프레임 조금씩 돌아간다.
 * 멀리 돌 때는 빠르게, 가까워지면 천천히(감속) 돌고, 목표에 약간의 오차를 둔다.
 * 자동화 쪽은 목표를 정한 뒤 aligned()가 될 때까지 기다렸다가 행동한다.
 * 옵션을 끄면 예전처럼 한 번에 돌아간다.
 */
public final class Look {
    public static final Look INSTANCE = new Look();

    private static final float MIN_SPEED = 25f;   // 도/초: 끝에서 너무 느려지지 않게
    private static final float EASE_RATE = 9f;    // 클수록 빨리 따라감
    private static final float RENOISE_DEG = 8f;  // 목표가 이만큼 바뀌면 오차를 새로 뽑는다

    private final Random random = new Random();
    private boolean active;
    private float baseYaw;
    private float basePitch;
    private float targetYaw;
    private float targetPitch;
    private float speedScale = 1f;
    private long lastNanos;

    private Look() {
    }

    public boolean isActive() {
        return active;
    }

    /** 자동화가 끝나면 시선을 놓아 준다 (마우스로 다시 돌릴 수 있게). */
    public void release() {
        active = false;
    }

    /** 플레이어 눈에서 point를 보도록. noise = 목표 오차(도). */
    public void lookAt(LocalPlayer player, Vec3 point, float noise) {
        Vec3 eye = player.getEyePosition();
        double dx = point.x - eye.x;
        double dy = point.y - eye.y;
        double dz = point.z - eye.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, horizontal));
        setAngles(player, yaw, pitch, noise);
    }

    public void setAngles(LocalPlayer player, float yaw, float pitch, float noise) {
        boolean fresh = !active
                || Math.abs(Mth.wrapDegrees(yaw - baseYaw)) > RENOISE_DEG
                || Math.abs(pitch - basePitch) > RENOISE_DEG;
        baseYaw = yaw;
        basePitch = pitch;
        if (fresh) {
            targetYaw = yaw + (float) random.nextGaussian() * noise;
            targetPitch = pitch + (float) random.nextGaussian() * noise * 0.6f;
            // 회전마다 빠르기를 조금씩 다르게
            speedScale = 0.8f + random.nextFloat() * 0.4f;
        } else {
            // 목표가 조금씩 움직일 때(걸으며 앞 보기 등)는 오차를 유지한 채 따라간다
            targetYaw = yaw + (targetYaw - baseYaw);
            targetPitch = pitch + (targetPitch - basePitch);
        }
        targetPitch = Mth.clamp(targetPitch, -90f, 90f);
        if (!active) {
            lastNanos = System.nanoTime();
        }
        active = true;
        if (!AfkConfig.HUMAN_LOOK.get()) {
            player.setYRot(yaw);
            player.setXRot(Mth.clamp(pitch, -90f, 90f));
            targetYaw = yaw;
            targetPitch = Mth.clamp(pitch, -90f, 90f);
        }
    }

    /** 목표 각도까지 tolerance(도) 안으로 돌았는지. */
    public boolean aligned(LocalPlayer player, float tolerance) {
        if (!active) {
            return true;
        }
        return Math.abs(Mth.wrapDegrees(targetYaw - player.getYRot())) <= tolerance
                && Math.abs(targetPitch - player.getXRot()) <= tolerance;
    }

    /** 지금 바라보는 방향과 목표 방향(수평)의 차이. */
    public float yawError(LocalPlayer player) {
        return active ? Math.abs(Mth.wrapDegrees(targetYaw - player.getYRot())) : 0f;
    }

    /** 매 프레임(그리고 매 틱) 호출. 지난 호출 이후 흐른 시간만큼 돈다. */
    public void update() {
        long now = System.nanoTime();
        float dt = Math.min(0.1f, (now - lastNanos) / 1.0e9f);
        lastNanos = now;
        LocalPlayer player = Minecraft.getInstance().player;
        if (!active || player == null || dt <= 0) {
            return;
        }
        if (!AfkConfig.HUMAN_LOOK.get()) {
            player.setYRot(targetYaw);
            player.setXRot(targetPitch);
            active = false;
            return;
        }
        float maxSpeed = AfkConfig.LOOK_SPEED.get() * speedScale;
        float yawErr = Mth.wrapDegrees(targetYaw - player.getYRot());
        float pitchErr = targetPitch - player.getXRot();
        player.setYRot(player.getYRot() + step(yawErr, dt, maxSpeed));
        player.setXRot(Mth.clamp(player.getXRot() + step(pitchErr, dt, maxSpeed * 0.8f), -90f, 90f));
        if (Math.abs(yawErr) < 0.05f && Math.abs(pitchErr) < 0.05f) {
            active = false; // 다 돌았으면 놓아 준다 (기다리는 동안 마우스로 둘러볼 수 있게)
        }
    }

    public static float yawTo(LocalPlayer player, Vec3 point) {
        Vec3 eye = player.getEyePosition();
        return (float) (Math.toDegrees(Math.atan2(point.z - eye.z, point.x - eye.x)) - 90.0);
    }

    public static float pitchTo(LocalPlayer player, Vec3 point) {
        Vec3 eye = player.getEyePosition();
        double dx = point.x - eye.x;
        double dz = point.z - eye.z;
        return (float) -Math.toDegrees(Math.atan2(point.y - eye.y, Math.sqrt(dx * dx + dz * dz)));
    }

    private static float step(float error, float dt, float maxSpeed) {
        float abs = Math.abs(error);
        if (abs < 0.05f) {
            return error;
        }
        // 지수 감속 + 최대·최소 속도
        float eased = abs * (1f - (float) Math.exp(-dt * EASE_RATE));
        float move = Mth.clamp(eased, Math.min(abs, MIN_SPEED * dt), maxSpeed * dt);
        return Math.signum(error) * move;
    }
}
