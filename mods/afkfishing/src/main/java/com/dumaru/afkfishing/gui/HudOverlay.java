package com.dumaru.afkfishing.gui;

import com.dumaru.afkfishing.AfkConfig;
import com.dumaru.afkfishing.FishingController;
import com.dumaru.afkfishing.common.InvUtil;
import com.dumaru.afkfishing.farm.Crops;
import com.dumaru.afkfishing.farm.FarmController;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.LayeredDraw;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;

public class HudOverlay implements LayeredDraw.Layer {
    private static final int PADDING = 3;
    private static final int LINE_HEIGHT = 10;

    private final List<String> lines = new ArrayList<>();
    private final List<Integer> colors = new ArrayList<>();

    @Override
    public void render(GuiGraphics g, DeltaTracker deltaTracker) {
        Minecraft mc = Minecraft.getInstance();
        FishingController fish = FishingController.INSTANCE;
        FarmController farm = FarmController.INSTANCE;
        if ((!fish.isRunning() && !farm.isRunning()) || !AfkConfig.SHOW_HUD.get() || mc.player == null || mc.options.hideGui) {
            return;
        }
        lines.clear();
        colors.clear();
        if (farm.isRunning()) {
            farmLines(mc.player, farm);
            if (fish.isEmbedded()) {
                add("낚시: " + fish.state().label + " · 잡은 수 " + fish.catches(), 0x55FFFF);
            }
        } else {
            fishLines(mc, fish);
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

    private void add(String text, int color) {
        lines.add(text);
        colors.add(color);
    }

    private void farmLines(LocalPlayer player, FarmController farm) {
        add("자동 농사 · " + farm.stateLabel(), 0x55FF55);
        int[] counts = farm.selectedCounts();
        add("다 자람 " + counts[0] + " / " + counts[1] + " · 경과 " + formatDuration(farm.elapsedMillis() / 1000), 0xFFFFFF);
        int total = 0;
        StringBuilder top = new StringBuilder();
        int shown = 0;
        for (Map.Entry<String, Integer> e : farm.harvested().entrySet()) {
            total += e.getValue();
            if (shown++ < 3) {
                if (!top.isEmpty()) {
                    top.append(", ");
                }
                top.append(Crops.displayName(e.getKey())).append(" ").append(e.getValue());
            }
        }
        add("수확 " + total + (top.isEmpty() ? "" : " (" + top + ")") + " · 심음 " + farm.planted(), 0xFFFFFF);
        add("다음 정리 " + formatDuration(farm.secondsUntilDeposit()) + " · 빈칸 " + InvUtil.freeSlots(player)
                + " · 넣음 " + farm.deposited(), 0xFFFFFF);
        add("배고픔 " + player.getFoodData().getFoodLevel() + "/20", player.getFoodData().getFoodLevel() <= AfkConfig.EAT_BELOW.get()
                ? 0xFFAA55 : 0xFFFFFF);
        String sleep = farm.sleepStatus(player);
        if (sleep != null) {
            add("침낭 수면: " + sleep, 0xFFFFFF);
        }
        if (!farm.lastMessage().isEmpty()) {
            add("최근: " + farm.lastMessage(), 0xAAAAAA);
        }
    }

    private void fishLines(Minecraft mc, FishingController fc) {
        String label = fc.depositLabel().isEmpty() ? fc.state().label : fc.depositLabel();
        add("AFK 낚시 · " + label, 0x55FFFF);
        add("잡은 수 " + fc.catches() + " · 경과 " + formatDuration(fc.elapsedMillis() / 1000), 0xFFFFFF);
        Boolean openWater = fc.openWater();
        if (fc.isStarcatcher()) {
            add("스타캐쳐 낚싯대 · 미니게임 자동", 0xFFDD55);
        } else if (openWater == null) {
            add("탁 트인 물: 확인 중", 0xAAAAAA);
        } else if (openWater) {
            add("탁 트인 물: O (보물 나옴)", 0x55FF55);
        } else {
            add("탁 트인 물: X (보물 안 나옴)", 0xFF5555);
            add("  " + fc.openWaterReason(), 0xFF5555);
        }
        ItemStack held = mc.player.getMainHandItem();
        if (FishingController.isRod(held) && held.isDamageableItem()) {
            add("내구도 " + (held.getMaxDamage() - held.getDamageValue()) + "/" + held.getMaxDamage(), 0xFFFFFF);
        }
        String lure = fc.lureStatus(mc.player);
        if (lure != null) {
            add("바늘: " + lure, 0xFFFFFF);
        }
        if (!fc.lastReelReason().isEmpty()) {
            add("마지막 회수: " + fc.lastReelReason(), 0xFFFFFF);
        }
        String sleep = fc.sleepStatus(mc.player);
        if (sleep != null) {
            add("침낭 수면: " + sleep, 0xFFFFFF);
        }
        long untilDeposit = fc.secondsUntilDeposit(mc.player);
        if (untilDeposit >= 0) {
            add("다음 정리 " + formatDuration(untilDeposit) + " · 빈칸 " + InvUtil.freeSlots(mc.player), 0xFFFFFF);
        }
        if (AfkConfig.ANTI_AFK.get()) {
            add("다음 이동 " + formatDuration(fc.mover().secondsUntilMove()), 0xFFFFFF);
        }
    }

    static String formatDuration(long totalSeconds) {
        long h = totalSeconds / 3600;
        long m = (totalSeconds % 3600) / 60;
        long s = totalSeconds % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, s) : String.format("%d:%02d", m, s);
    }
}
