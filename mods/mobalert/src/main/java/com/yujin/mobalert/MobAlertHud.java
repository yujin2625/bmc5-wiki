package com.yujin.mobalert;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.List;

/** 화면 가장자리에 주변 감시 대상 목록(방향 화살표 + 거리)을 그린다. */
public final class MobAlertHud {
    private static final int MAX_LINES = 8;
    private static final int LINE_H = 18;
    private static final int PAD = 4;

    private MobAlertHud() {}

    public static void render(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        MobAlertConfig cfg = MobAlertConfig.get();
        if (!cfg.enabled || !cfg.hud || mc.player == null || mc.options.hideGui || mc.getDebugOverlay().showDebugScreen()) return;

        List<Entity> list = MobAlertTracker.nearby();
        if (list.isEmpty()) return;

        Font font = mc.font;
        float pt = delta.getGameTimeDeltaPartialTick(false);
        int shown = Math.min(list.size(), MAX_LINES);
        List<Line> lines = new ArrayList<>(shown + 1);
        int maxTextW = 0;
        for (int i = 0; i < shown; i++) {
            Entity e = list.get(i);
            if (e.isRemoved()) continue;
            int dist = (int) Math.round(e.distanceTo(mc.player));
            int dy = (int) Math.round(e.getY() - mc.player.getY());
            String height = Math.abs(dy) >= 3 ? (dy > 0 ? " ▲" : " ▼") + Math.abs(dy) : "";
            Component text = Component.literal(MobAlertTracker.relativeArrow(mc.player, e, pt) + " ")
                    .append(Variants.typeName(e))
                    .append(Component.literal(" " + dist + "m" + height));
            lines.add(new Line(e, text));
            maxTextW = Math.max(maxTextW, font.width(text));
        }
        if (list.size() > shown) {
            Component more = Component.translatable("mobalert.hud.more", list.size() - shown);
            lines.add(new Line(null, more));
            maxTextW = Math.max(maxTextW, font.width(more));
        }
        if (lines.isEmpty()) return;

        Component header = Component.translatable("mobalert.hud.header", list.size());
        int boxW = Math.max(font.width(header), 18 + maxTextW) + PAD * 2;
        int boxH = 12 + lines.size() * LINE_H + PAD * 2;
        int sw = g.guiWidth(), sh = g.guiHeight();
        int margin = 4;
        int x = switch (cfg.hudPos) {
            case TOP_LEFT, MIDDLE_LEFT, BOTTOM_LEFT -> margin;
            default -> sw - boxW - margin;
        };
        int y = switch (cfg.hudPos) {
            case TOP_LEFT, TOP_RIGHT -> margin;
            case MIDDLE_LEFT, MIDDLE_RIGHT -> (sh - boxH) / 2;
            default -> sh - boxH - 40;
        };

        g.fill(x, y, x + boxW, y + boxH, 0x90000000);
        g.fill(x, y, x + boxW, y + 1, 0xFF000000 | cfg.glowColor.rgb);
        g.drawString(font, header, x + PAD, y + PAD, 0xFF000000 | cfg.glowColor.rgb);
        int ly = y + PAD + 12;
        for (Line line : lines) {
            if (line.entity != null) EntityIcons.render(g, line.entity.getType(), line.entity, x + PAD, ly, 16);
            g.drawString(font, line.text, x + PAD + 18, ly + 4, 0xFFFFFFFF);
            ly += LINE_H;
        }
    }

    private record Line(@org.jetbrains.annotations.Nullable Entity entity, Component text) {}
}
