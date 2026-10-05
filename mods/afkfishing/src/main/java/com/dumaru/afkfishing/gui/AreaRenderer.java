package com.dumaru.afkfishing.gui;

import com.dumaru.afkfishing.AfkConfig;
import com.dumaru.afkfishing.farm.FarmController;
import com.dumaru.afkfishing.farm.FarmData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/** 농장 범위(초록), 상자(노랑), 낚시 자리(파랑)를 월드에 테두리로 그린다. */
public final class AreaRenderer {
    private AreaRenderer() {
    }

    public static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || !AfkConfig.FARM_SHOW_AREA.get()) {
            return;
        }
        if (!(mc.screen instanceof AfkScreen) && !FarmController.INSTANCE.isRunning()
                && !com.dumaru.afkfishing.FishingController.INSTANCE.isRunning()) {
            return;
        }
        FarmData data = FarmData.current();
        Vec3 cam = event.getCamera().getPosition();
        PoseStack pose = event.getPoseStack();
        pose.pushPose();
        pose.translate(-cam.x, -cam.y, -cam.z);
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        if (data.corner1 != null) {
            box(pose, lines, new AABB(FarmData.pos(data.corner1)).inflate(0.01), 0.3f, 1f, 0.3f);
        }
        if (data.corner2 != null) {
            box(pose, lines, new AABB(FarmData.pos(data.corner2)).inflate(0.01), 0.3f, 1f, 0.3f);
        }
        if (data.hasArea()) {
            box(pose, lines, data.areaBox(), 0.2f, 0.9f, 0.2f);
        }
        for (FarmData.ChestEntry chest : data.chests) {
            box(pose, lines, new AABB(chest.pos()).inflate(0.02), 1f, 0.85f, 0.1f);
        }
        if (data.fishChest != null) {
            box(pose, lines, new AABB(FarmData.pos(data.fishChest)).inflate(0.03), 0.2f, 0.9f, 0.9f);
        }
        if (data.tackleBox != null) {
            box(pose, lines, new AABB(FarmData.pos(data.tackleBox)).inflate(0.03), 0.8f, 0.4f, 1f);
        }
        if (data.fishSpot != null) {
            Vec3 p = data.fishSpot.pos();
            box(pose, lines, new AABB(p.x - 0.3, p.y, p.z - 0.3, p.x + 0.3, p.y + 1.8, p.z + 0.3), 0.3f, 0.6f, 1f);
        }
        pose.popPose();
        buffers.endBatch(RenderType.lines());
    }

    private static void box(PoseStack pose, VertexConsumer lines, AABB box, float r, float g, float b) {
        LevelRenderer.renderLineBox(pose, lines, box, r, g, b, 1f);
    }
}
