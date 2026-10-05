package com.yujin.mobalert;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/**
 * 대상 머리 위에 "▼ 이름 거리" 마커를 2D 로 그린다.
 * 월드 렌더(셰이더 영향을 받음)가 아니라 HUD 에 그리므로 Iris 셰이더를 켜도 보이고, 벽 너머로도 보인다.
 */
public final class WorldMarkers {
    private static final Matrix4f VIEW = new Matrix4f();
    private static final Matrix4f PROJECTION = new Matrix4f();
    private static Vec3 cameraPos = Vec3.ZERO;
    private static boolean captured;

    private WorldMarkers() {}

    /** 이번 프레임의 카메라 행렬을 기억해 둔다. */
    public static void onRenderStage(RenderLevelStageEvent e) {
        // 어느 단계든 같은 행렬이다. (렌더링 최적화 모드가 일부 단계를 건너뛸 수 있어 단계는 따지지 않는다)
        VIEW.set(e.getModelViewMatrix());
        PROJECTION.set(e.getProjectionMatrix());
        cameraPos = e.getCamera().getPosition();
        captured = true;
    }

    public static void render(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        MobAlertConfig cfg = MobAlertConfig.get();
        if (!captured || !cfg.enabled || !cfg.marker || mc.player == null || mc.options.hideGui) return;

        Font font = mc.font;
        float pt = delta.getGameTimeDeltaPartialTick(false);
        int color = 0xFF000000 | cfg.glowColor.rgb;
        Matrix4f viewProj = new Matrix4f(PROJECTION).mul(VIEW);
        int sw = g.guiWidth(), sh = g.guiHeight();

        for (Entity e : MobAlertTracker.nearby()) {
            if (e.isRemoved()) continue;
            Vec3 p = e.getPosition(pt).add(0, e.getBbHeight() + 0.5, 0).subtract(cameraPos);
            Vector4f clip = viewProj.transform(new Vector4f((float) p.x, (float) p.y, (float) p.z, 1f));
            if (clip.w <= 0.05f) continue; // 카메라 뒤
            float sx = (clip.x / clip.w * 0.5f + 0.5f) * sw;
            float sy = (0.5f - clip.y / clip.w * 0.5f) * sh;
            if (sx < -50 || sx > sw + 50 || sy < -20 || sy > sh + 20) continue;

            int dist = (int) Math.round(e.distanceTo(mc.player));
            Component label = Component.empty().append(Variants.typeName(e)).append(" " + dist + "m");
            int x = Math.round(sx), y = Math.round(sy);
            int w = font.width(label);

            g.fill(x - w / 2 - 2, y - 20, x + w / 2 + 2, y - 9, 0x80000000);
            g.drawString(font, label, x - w / 2, y - 18, 0xFFFFFFFF);
            g.drawCenteredString(font, "▼", x, y - 8, color);
        }
    }
}
