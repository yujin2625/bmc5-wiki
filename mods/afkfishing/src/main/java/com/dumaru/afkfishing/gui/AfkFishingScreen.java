package com.dumaru.afkfishing.gui;

import com.dumaru.afkfishing.AfkConfig;
import com.dumaru.afkfishing.FishingController;
import java.util.Map;
import java.util.function.IntFunction;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.jetbrains.annotations.Nullable;

public class AfkFishingScreen extends Screen {
    private static final int COL_W = 150;
    private static final int ROW_H = 24;
    private static final int BTN_H = 20;

    @Nullable
    private final Screen parent;
    private Button toggleButton;
    private int statsY;

    public AfkFishingScreen(@Nullable Screen parent) {
        super(Component.literal("AFK 낚시"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int left = width / 2 - COL_W - 5;
        int right = width / 2 + 5;
        int y = 30;

        toggleButton = addRenderableWidget(Button.builder(toggleLabel(), b -> {
            FishingController.INSTANCE.toggle();
            b.setMessage(toggleLabel());
        }).bounds(left, y, COL_W * 2 + 10, BTN_H).build());
        y += ROW_H + 4;

        addRenderableWidget(new IntSlider(left, y, AfkConfig.REEL_DELAY_MIN, 0, 40, 1, v -> secs("회수 반응 최소", v)));
        addRenderableWidget(new IntSlider(right, y, AfkConfig.REEL_DELAY_MAX, 0, 40, 1, v -> secs("회수 반응 최대", v)));
        y += ROW_H;
        addRenderableWidget(new IntSlider(left, y, AfkConfig.RECAST_DELAY_MIN, 5, 100, 1, v -> secs("재투척 최소", v)));
        addRenderableWidget(new IntSlider(right, y, AfkConfig.RECAST_DELAY_MAX, 5, 100, 1, v -> secs("재투척 최대", v)));
        y += ROW_H;
        addRenderableWidget(new IntSlider(left, y, AfkConfig.BITE_TIMEOUT_SECONDS, 30, 300, 5,
                v -> Component.literal("입질 대기 제한: " + v + "초")));
        addRenderableWidget(new IntSlider(right, y, AfkConfig.MIN_DURABILITY, 0, 64, 1,
                v -> Component.literal("최소 내구도: " + v)));
        y += ROW_H;
        addRenderableWidget(new IntSlider(left, y, AfkConfig.ANTI_AFK_INTERVAL_SECONDS, 60, 840, 30,
                v -> Component.literal("이동 주기: " + HudOverlay.formatDuration(v) + " ±30초")));
        addRenderableWidget(new IntSlider(right, y, AfkConfig.MOVE_TICKS, 2, 10, 1, v -> secs("이동 거리", v)));
        y += ROW_H + 4;

        addRenderableWidget(toggle(left, y, "자동 낚싯대 교체", AfkConfig.AUTO_SWAP_ROD));
        addRenderableWidget(toggle(right, y, "가득 차면 정지", AfkConfig.STOP_WHEN_FULL));
        y += ROW_H;
        addRenderableWidget(toggle(left, y, "피격 시 정지", AfkConfig.STOP_ON_DAMAGE));
        addRenderableWidget(toggle(right, y, "HUD 표시", AfkConfig.SHOW_HUD));
        y += ROW_H;
        addRenderableWidget(toggle(left, y, "AFK 방지 이동", AfkConfig.ANTI_AFK));
        addRenderableWidget(CycleButton.<AfkConfig.MovePattern>builder(p -> Component.literal(p.label))
                .withValues(AfkConfig.MovePattern.values())
                .withInitialValue(AfkConfig.MOVE_PATTERN.get())
                .create(right, y, COL_W, BTN_H, Component.literal("이동 패턴"), (b, v) -> AfkConfig.MOVE_PATTERN.set(v)));
        y += ROW_H;
        addRenderableWidget(toggle(left, y, "밤에 침낭으로 자기", AfkConfig.SLEEP_AT_NIGHT));
        addRenderableWidget(toggle(right, y, "바늘 소진 시 정지", AfkConfig.STOP_WHEN_LURE_GONE));
        y += ROW_H;
        addRenderableWidget(new IntSlider(left, y, AfkConfig.BAG_SEARCH_RADIUS, 1, 10, 1,
                v -> Component.literal("침낭 자리 반경: " + v + "칸")));
        y += ROW_H + 4;

        addRenderableWidget(Button.builder(Component.literal("통계 초기화"), b -> FishingController.INSTANCE.resetStats())
                .bounds(left, y, COL_W, BTN_H).build());
        addRenderableWidget(Button.builder(Component.literal("완료"), b -> onClose())
                .bounds(right, y, COL_W, BTN_H).build());
        statsY = y + ROW_H + 2;
    }

    @Override
    public void tick() {
        // F8이나 안전장치로 상태가 바뀔 수 있으므로 버튼 라벨 갱신
        toggleButton.setMessage(toggleLabel());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(font, title, width / 2, 12, 0xFFFFFF);

        FishingController fc = FishingController.INSTANCE;
        String summary = "잡은 수 " + fc.catches();
        if (!fc.isRunning() && !fc.lastMessage().isEmpty()) {
            summary += " · 마지막 정지 사유: " + fc.lastMessage();
        }
        g.drawCenteredString(font, summary, width / 2, statsY, 0xAAAAAA);

        // 획득 아이템 상위 목록 (화면에 들어가는 만큼)
        int y = statsY + 12;
        int shown = 0;
        StringBuilder line = new StringBuilder();
        for (Map.Entry<Item, Integer> e : fc.lootSortedDesc().entrySet()) {
            if (shown >= 6 || y > height - 10) {
                break;
            }
            if (!line.isEmpty()) {
                line.append("   ");
            }
            line.append(e.getKey().getDescription().getString()).append(" x").append(e.getValue());
            shown++;
            if (shown % 3 == 0) {
                g.drawCenteredString(font, line.toString(), width / 2, y, 0xDDDDDD);
                line.setLength(0);
                y += 10;
            }
        }
        if (!line.isEmpty() && y <= height - 10) {
            g.drawCenteredString(font, line.toString(), width / 2, y, 0xDDDDDD);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void removed() {
        AfkConfig.SPEC.save();
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    private static Component toggleLabel() {
        return FishingController.INSTANCE.isRunning()
                ? Component.literal("■ 정지 (F8)")
                : Component.literal("▶ 시작 (F8)");
    }

    private static Component secs(String label, int ticks) {
        return Component.literal(String.format("%s: %.2f초", label, ticks / 20.0));
    }

    private static CycleButton<Boolean> toggle(int x, int y, String label, ModConfigSpec.BooleanValue cfg) {
        return CycleButton.onOffBuilder(cfg.get())
                .create(x, y, COL_W, BTN_H, Component.literal(label), (b, v) -> cfg.set(v));
    }

    private static class IntSlider extends AbstractSliderButton {
        private final ModConfigSpec.IntValue cfg;
        private final int min;
        private final int max;
        private final int step;
        private final IntFunction<Component> label;

        IntSlider(int x, int y, ModConfigSpec.IntValue cfg, int min, int max, int step, IntFunction<Component> label) {
            super(x, y, COL_W, BTN_H, Component.empty(), (cfg.get() - min) / (double) (max - min));
            this.cfg = cfg;
            this.min = min;
            this.max = max;
            this.step = step;
            this.label = label;
            updateMessage();
        }

        private int current() {
            int raw = min + (int) Math.round(value * (max - min));
            int snapped = min + Math.round((raw - min) / (float) step) * step;
            return Math.max(min, Math.min(max, snapped));
        }

        @Override
        protected void updateMessage() {
            if (label != null) {
                setMessage(label.apply(current()));
            }
        }

        @Override
        protected void applyValue() {
            cfg.set(current());
        }
    }
}
