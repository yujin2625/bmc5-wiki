package com.dumaru.afkfishing.common;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.player.Input;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.phys.Vec3;

/** 여러 모드가 같이 쓰는 작은 도우미들. */
public final class Util {
    private Util() {
    }

    public static void notify(LocalPlayer player, String prefix, String text, ChatFormatting color) {
        // displayClientMessage는 로컬에만 표시되고 서버로 전송되지 않는다.
        player.displayClientMessage(Component.literal(prefix + " " + text).withStyle(color), false);
    }

    public static void lookAt(LocalPlayer player, Vec3 point) {
        Vec3 eye = player.getEyePosition();
        double dx = point.x - eye.x;
        double dy = point.y - eye.y;
        double dz = point.z - eye.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        player.setYRot((float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0));
        player.setXRot((float) -Math.toDegrees(Math.atan2(dy, horizontal)));
    }

    /** UseItemOn 패킷에는 방향이 없으므로, 서버가 방향을 따지는 동작(침대 놓기 등) 전에 회전을 먼저 보낸다. */
    public static void sendRotation(Minecraft mc, LocalPlayer player) {
        mc.getConnection().send(new ServerboundMovePlayerPacket.Rot(player.getYRot(), player.getXRot(), player.onGround()));
    }

    public static double horizontalDistance(Vec3 a, Vec3 b) {
        double dx = a.x - b.x;
        double dz = a.z - b.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** 블록을 클릭할 면: 블록 중심에서 플레이어 눈 쪽을 향하는 면. */
    public static Direction faceToward(LocalPlayer player, BlockPos pos) {
        Vec3 d = player.getEyePosition().subtract(Vec3.atCenterOf(pos));
        return Direction.getNearest(d.x, d.y, d.z);
    }

    /** 블록의 face 면 중앙 좌표. */
    public static Vec3 faceCenter(BlockPos pos, Direction face) {
        return Vec3.atCenterOf(pos).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
    }

    /**
     * 월드 방향 (dx, dz)로 움직이도록 Input을 덮어쓴다. speed는 0~1.
     * 키 입력을 흉내 내지 않으므로 창 포커스와 무관하다.
     */
    public static void steer(Input input, LocalPlayer player, double dx, double dz, double speed, boolean jump) {
        float forward = 0;
        float left = 0;
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist > 1.0E-4 && speed > 0) {
            double yawRad = Math.toRadians(player.getYRot());
            double sin = Math.sin(yawRad);
            double cos = Math.cos(yawRad);
            double f = -dx * sin + dz * cos;
            double l = dx * cos + dz * sin;
            forward = (float) (f / dist * speed);
            left = (float) (l / dist * speed);
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
}
