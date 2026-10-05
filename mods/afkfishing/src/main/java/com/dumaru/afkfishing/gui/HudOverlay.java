package com.dumaru.afkfishing.gui;

import com.dumaru.afkfishing.AfkConfig;
import com.dumaru.afkfishing.FishingController;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.LayeredDraw;
import net.minecraft.world.item.FishingRodItem;
import net.minecraft.world.item.ItemStack;

public class HudOverlay implements LayeredDraw.Layer {
    private static final int PADDING = 3;
    private static final int LINE_HEIGHT = 10;

    @Override
    public void render(GuiGraphics g, DeltaTracker deltaTracker) {
        Minecraft mc = Minecraft.getInstance();
        FishingController fc = FishingController.INSTANCE;
        if (!fc.isRunning() || !AfkConfig.SHOW_HUD.get() || mc.player == null || mc.options.hideGui) {
            return;
        }

        List<String> lines = new ArrayList<>();
        List<Integer> colors = new ArrayList<>();
        lines.add("AFK 낚시 · " + fc.state().label);
        colors.add(0x55FFFF);
        lines.add("잡은 수 " + fc.catches() + " · 경과 " + formatDuration(fc.elapsedMillis() / 1000));
        colors.add(0xFFFFFF);
        Boolean openWater = fc.openWater();
        if (openWater == null) {
            lines.add("탁 트인 물: 확인 중");
            colors.add(0xAAAAAA);
        } else if (openWater) {
            lines.add("탁 트인 물: O (보물 나옴)");
            colors.add(0x55FF55);
        } else {
            lines.add("탁 트인 물: X (보물 안 나옴)");
            colors.add(0xFF5555);
            lines.add("  " + fc.openWaterReason());
            colors.add(0xFF5555);
        }
        ItemStack held = mc.player.getMainHandItem();
        if (held.getItem() instanceof FishingRodItem && held.isDamageableItem()) {
            lines.add("내구도 " + (held.getMaxDamage() - held.getDamageValue()) + "/" + held.getMaxDamage());
            colors.add(0xFFFFFF);
        }
        String lure = fc.lureStatus(mc.player);
        if (lure != null) {
            lines.add("바늘: " + lure);
            colors.add(0xFFFFFF);
        }
        if (!fc.lastReelReason().isEmpty()) {
            lines.add("마지막 회수: " + fc.lastReelReason());
            colors.add(0xFFFFFF);
        }
        String sleep = fc.sleepStatus(mc.player);
        if (sleep != null) {
            lines.add("침낭 수면: " + sleep);
            colors.add(0xFFFFFF);
        }
        if (AfkConfig.ANTI_AFK.get()) {
            lines.add("다음 이동 " + formatDuration(fc.mover().secondsUntilMove()));
            colors.add(0xFFFFFF);
        }

        int width = 0;
        for (String line : lines) {
            width = Math.max(width, mc.font.width(line));
        }
        int x = 4;
        int y = g.guiHeight() / 2 - (lines.size() * LINE_HEIGHT) / 2;
        g.fill(x - PADDING, y - PADDING, x + width + PADDING, y + lines.size() * LINE_HEIGHT + PADDING - 2, 0x90000000);
        for (int i = 0; i < lines.size(); i++) {
            g.drawString(mc.font, lines.get(i), x, y + i * LINE_HEIGHT, colors.get(i));
        }
    }

    static String formatDuration(long totalSeconds) {
        long h = totalSeconds / 3600;
        long m = (totalSeconds % 3600) / 60;
        long s = totalSeconds % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, s) : String.format("%d:%02d", m, s);
    }
}
