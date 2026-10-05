package com.dumaru.afkfishing.gui;

import com.dumaru.afkfishing.AfkConfig;
import com.dumaru.afkfishing.FishingController;
import com.dumaru.afkfishing.common.AutoEater;
import com.dumaru.afkfishing.farm.Crops;
import com.dumaru.afkfishing.farm.FarmController;
import com.dumaru.afkfishing.farm.FarmData;
import com.dumaru.afkfishing.farm.FarmScanner;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntFunction;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.jetbrains.annotations.Nullable;

/** 낚시 · 농사 · 농장 지정 · 공통 탭으로 나뉜 설정 화면. */
public class AfkScreen extends Screen {
    private enum Tab {
        FISH("낚시"), FARM("농사"), SETUP("농장 지정"), COMMON("공통");

        final String label;

        Tab(String label) {
            this.label = label;
        }
    }

    private static final int COL_W = 150;
    private static final int ROW_H = 22;
    private static final int BTN_H = 20;
    private static final int CROPS_PER_PAGE = 5;
    private static Tab lastTab = Tab.FISH;

    @Nullable
    private final Screen parent;
    /** 화면을 열 때 바라보고 있던 블록 (모서리·상자 지정용) */
    @Nullable
    private final BlockPos looked;
    private Tab tab = lastTab;
    private Button toggleButton;
    private int infoY;
    private int cropPage;

    public AfkScreen(@Nullable Screen parent) {
        super(Component.literal("AFK 자동화"));
        this.parent = parent;
        HitResult hit = Minecraft.getInstance().hitResult;
        this.looked = hit instanceof BlockHitResult b && hit.getType() == HitResult.Type.BLOCK ? b.getBlockPos() : null;
    }

    @Override
    protected void init() {
        int tabW = 74;
        int tabsLeft = width / 2 - (tabW * Tab.values().length + 4 * (Tab.values().length - 1)) / 2;
        for (int i = 0; i < Tab.values().length; i++) {
            Tab t = Tab.values()[i];
            Button b = addRenderableWidget(Button.builder(Component.literal(t == tab ? "[" + t.label + "]" : t.label), btn -> {
                tab = t;
                lastTab = t;
                rebuildWidgets();
            }).bounds(tabsLeft + i * (tabW + 4), 6, tabW, BTN_H).build());
            b.active = t != tab;
        }
        int left = width / 2 - COL_W - 5;
        int right = width / 2 + 5;
        int y = 32;
        switch (tab) {
            case FISH -> y = initFish(left, right, y);
            case FARM -> y = initFarm(left, right, y);
            case SETUP -> y = initSetup(left, right, y);
            case COMMON -> y = initCommon(left, right, y);
        }
        y += 4;
        addRenderableWidget(Button.builder(Component.literal("통계 초기화"), b -> {
            FishingController.INSTANCE.resetStats();
            FarmController.INSTANCE.resetStats();
        }).bounds(left, y, COL_W, BTN_H).build());
        addRenderableWidget(Button.builder(Component.literal("완료"), b -> onClose()).bounds(right, y, COL_W, BTN_H).build());
        infoY = y + ROW_H + 2;
    }

    // ---- 낚시 탭 ----

    private int initFish(int left, int right, int y) {
        toggleButton = addRenderableWidget(Button.builder(fishToggleLabel(), b -> {
            FishingController.INSTANCE.toggle();
            b.setMessage(fishToggleLabel());
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
                v -> Component.literal("낚싯대 최소 내구도: " + v)));
        y += ROW_H;
        addRenderableWidget(new IntSlider(left, y, AfkConfig.ANTI_AFK_INTERVAL_SECONDS, 60, 840, 30,
                v -> Component.literal("이동 주기: " + HudOverlay.formatDuration(v) + " ±30초")));
        addRenderableWidget(new IntSlider(right, y, AfkConfig.MOVE_TICKS, 2, 10, 1, v -> secs("이동 거리", v)));
        y += ROW_H + 4;
        addRenderableWidget(toggle(left, y, "자동 낚싯대 교체", AfkConfig.AUTO_SWAP_ROD));
        addRenderableWidget(toggle(right, y, "가득 차면 정지", AfkConfig.STOP_WHEN_FULL));
        y += ROW_H;
        addRenderableWidget(toggle(left, y, "바늘 소진 시 정지", AfkConfig.STOP_WHEN_LURE_GONE));
        addRenderableWidget(toggle(right, y, "AFK 방지 이동", AfkConfig.ANTI_AFK));
        y += ROW_H;
        addRenderableWidget(CycleButton.<AfkConfig.MovePattern>builder(p -> Component.literal(p.label))
                .withValues(AfkConfig.MovePattern.values())
                .withInitialValue(AfkConfig.MOVE_PATTERN.get())
                .create(left, y, COL_W, BTN_H, Component.literal("이동 패턴"), (b, v) -> AfkConfig.MOVE_PATTERN.set(v)));
        return y + ROW_H;
    }

    // ---- 농사 탭 ----

    private int initFarm(int left, int right, int y) {
        toggleButton = addRenderableWidget(Button.builder(farmToggleLabel(), b -> {
            FarmController.INSTANCE.toggle();
            b.setMessage(farmToggleLabel());
        }).bounds(left, y, COL_W * 2 + 10, BTN_H).build());
        y += ROW_H + 4;
        addRenderableWidget(new IntSlider(left, y, AfkConfig.FARM_MIN_RIPE, 1, 64, 1,
                v -> Component.literal("모이면 수확: " + v + "개")));
        addRenderableWidget(new IntSlider(right, y, AfkConfig.FARM_MIN_RIPE_PERCENT, 0, 100, 5,
                v -> Component.literal(v == 0 ? "비율 기준: 안 씀" : "또는 " + v + "% 이상 자라면")));
        y += ROW_H;
        addRenderableWidget(new IntSlider(left, y, AfkConfig.FARM_MAX_WAIT_MINUTES, 1, 60, 1,
                v -> Component.literal("최대 대기: " + v + "분")));
        addRenderableWidget(new IntSlider(right, y, AfkConfig.FARM_DEPOSIT_MINUTES, 1, 60, 1,
                v -> Component.literal("상자 정리 주기: " + v + "분")));
        y += ROW_H;
        addRenderableWidget(new IntSlider(left, y, AfkConfig.FARM_DEPOSIT_FREE_SLOTS, 0, 20, 1,
                v -> Component.literal("빈칸 " + v + "개 이하면 정리")));
        addRenderableWidget(new IntSlider(right, y, AfkConfig.FARM_KEEP_SEEDS, 0, 64, 1,
                v -> Component.literal("남길 씨앗: " + v + "개")));
        y += ROW_H;
        addRenderableWidget(new IntSlider(left, y, AfkConfig.FARM_TOOL_MIN_DURABILITY, 0, 64, 1,
                v -> Component.literal("도구 최소 내구도: " + v)));
        addRenderableWidget(new IntSlider(right, y, AfkConfig.FARM_BONEMEAL_KEEP, 0, 64, 1,
                v -> Component.literal("남길 뼛가루: " + v + "개")));
        y += ROW_H + 4;
        addRenderableWidget(toggle(left, y, "괭이로 수확", AfkConfig.FARM_USE_HOE));
        addRenderableWidget(toggle(right, y, "빈 농경지 다시 심기", AfkConfig.FARM_REPLANT));
        y += ROW_H;
        addRenderableWidget(toggle(left, y, "뼛가루 사용", AfkConfig.FARM_BONEMEAL));
        addRenderableWidget(toggle(right, y, "떨어진 아이템 줍기", AfkConfig.FARM_COLLECT_ITEMS));
        y += ROW_H;
        addRenderableWidget(toggle(left, y, "자기 전에 정리", AfkConfig.FARM_DEPOSIT_BEFORE_SLEEP));
        addRenderableWidget(toggle(right, y, "상자 가득 차면 정지", AfkConfig.FARM_STOP_WHEN_CHESTS_FULL));
        y += ROW_H;
        addRenderableWidget(toggle(left, y, "기다리며 낚시", AfkConfig.FARM_WAIT_FISHING));
        addRenderableWidget(toggle(right, y, "농경지 피해서 다니기", AfkConfig.FARM_AVOID_FARMLAND));
        y += ROW_H;
        addRenderableWidget(toggle(left, y, "범위 테두리 표시", AfkConfig.FARM_SHOW_AREA));
        addRenderableWidget(toggle(right, y, "AFK 방지 이동", AfkConfig.ANTI_AFK));
        return y + ROW_H;
    }

    // ---- 농장 지정 탭 ----

    private int initSetup(int left, int right, int y) {
        FarmData data = FarmData.current();
        Minecraft mc = Minecraft.getInstance();
        boolean hasLooked = looked != null;
        boolean lookedIsContainer = hasLooked && mc.level != null && mc.level.getBlockEntity(looked) != null;
        y += 12; // 바라보는 블록 안내 줄

        Button c1 = addRenderableWidget(Button.builder(Component.literal("바라보는 블록 = 모서리 1"), b -> {
            data.corner1 = FarmData.arr(looked);
            changed(data);
        }).bounds(left, y, COL_W, BTN_H).build());
        Button c2 = addRenderableWidget(Button.builder(Component.literal("바라보는 블록 = 모서리 2"), b -> {
            data.corner2 = FarmData.arr(looked);
            changed(data);
        }).bounds(right, y, COL_W, BTN_H).build());
        c1.active = hasLooked;
        c2.active = hasLooked;
        y += ROW_H;

        Button other = addRenderableWidget(Button.builder(Component.literal(
                lookedIsContainer && data.chestAt(looked) != null && data.chestAt(looked).catchAll
                        ? "이 상자: 기타 상자 해제" : "바라보는 상자 = 기타 상자"), b -> {
            FarmData.ChestEntry entry = data.addChest(looked);
            entry.catchAll = !entry.catchAll;
            cleanupChests(data);
            changed(data);
        }).bounds(left, y, COL_W, BTN_H).build());
        other.active = lookedIsContainer;
        Button fishSpot = addRenderableWidget(Button.builder(Component.literal("지금 위치·방향 = 낚시 자리"), b -> {
            FarmData.FishSpot spot = new FarmData.FishSpot();
            spot.x = mc.player.getX();
            spot.y = mc.player.getY();
            spot.z = mc.player.getZ();
            spot.yaw = mc.player.getYRot();
            spot.pitch = mc.player.getXRot();
            data.fishSpot = spot;
            changed(data);
        }).bounds(right, y, COL_W, BTN_H).build());
        fishSpot.active = mc.player != null;
        y += ROW_H;

        addRenderableWidget(Button.builder(Component.literal(FarmController.INSTANCE.isScanning() ? "스캔 중..." : "범위 다시 스캔"),
                b -> {
                    FarmController.INSTANCE.requestScan();
                    b.setMessage(Component.literal("스캔 중..."));
                }).bounds(left, y, COL_W, BTN_H).build());
        // 기억이 없는 빈 농경지에 심을 기본 작물
        List<String> plantable = new ArrayList<>();
        plantable.add("");
        for (String id : data.selectedCrops) {
            if (mc.level != null && !Crops.seedOf(mc.level, id).isEmpty()) {
                plantable.add(id);
            }
        }
        String currentDefault = data.defaultPlant != null && plantable.contains(data.defaultPlant) ? data.defaultPlant : "";
        addRenderableWidget(CycleButton.<String>builder(id -> Component.literal(id.isEmpty() ? "심지 않음" : Crops.displayName(id)))
                .withValues(plantable)
                .withInitialValue(currentDefault)
                .create(right, y, COL_W, BTN_H, Component.literal("기본 작물"), (b, v) -> {
                    data.defaultPlant = v.isEmpty() ? null : v;
                    changed(data);
                }));
        y += ROW_H + 4;

        // 작물 목록: [선택 토글] [+상자] [상자 비우기]
        List<String> crops = cropList(data);
        int pages = Math.max(1, (crops.size() + CROPS_PER_PAGE - 1) / CROPS_PER_PAGE);
        cropPage = Math.min(cropPage, pages - 1);
        Map<String, FarmScanner.Count> counts = FarmController.INSTANCE.snapshot().counts();
        int nameW = COL_W + 40;
        int smallW = (COL_W * 2 + 10 - nameW - 8) / 2;
        for (int i = cropPage * CROPS_PER_PAGE; i < Math.min(crops.size(), (cropPage + 1) * CROPS_PER_PAGE); i++) {
            String id = crops.get(i);
            boolean selected = data.selectedCrops.contains(id);
            FarmScanner.Count count = counts.get(id);
            String label = (selected ? "✔ " : "✘ ") + Crops.displayName(id)
                    + (count != null ? " (" + count.ripe() + "/" + count.total() + ")" : "");
            addRenderableWidget(Button.builder(Component.literal(label), b -> {
                if (!data.selectedCrops.remove(id)) {
                    data.selectedCrops.add(id);
                }
                changed(data);
            }).bounds(left, y, nameW, BTN_H).build());
            int chestCount = data.chestsFor(id).size();
            Button add = addRenderableWidget(Button.builder(Component.literal("+상자 (" + chestCount + ")"), b -> {
                FarmData.ChestEntry entry = data.addChest(looked);
                entry.crops.add(id);
                entry.catchAll = false;
                changed(data);
            }).bounds(left + nameW + 4, y, smallW, BTN_H).build());
            add.active = lookedIsContainer;
            Button clear = addRenderableWidget(Button.builder(Component.literal("상자 비우기"), b -> {
                for (FarmData.ChestEntry c : data.chests) {
                    c.crops.remove(id);
                }
                cleanupChests(data);
                changed(data);
            }).bounds(left + nameW + 8 + smallW, y, smallW, BTN_H).build());
            clear.active = chestCount > 0;
            y += ROW_H;
        }
        if (crops.isEmpty()) {
            y += ROW_H; // 안내 문구 자리
        }
        Button prev = addRenderableWidget(Button.builder(Component.literal("◀"), b -> {
            cropPage--;
            rebuildWidgets();
        }).bounds(left, y, 40, BTN_H).build());
        prev.active = cropPage > 0;
        Button next = addRenderableWidget(Button.builder(Component.literal("▶"), b -> {
            cropPage++;
            rebuildWidgets();
        }).bounds(right + COL_W - 40, y, 40, BTN_H).build());
        next.active = cropPage < pages - 1;
        Button unregister = addRenderableWidget(Button.builder(Component.literal("바라보는 상자 등록 해제"), b -> {
            data.chests.removeIf(c -> c.pos().equals(looked));
            changed(data);
        }).bounds(left + 44, y, COL_W * 2 + 10 - 88, BTN_H).build());
        unregister.active = hasLooked && data.chestAt(looked) != null;
        return y + ROW_H;
    }

    /** 스캔에서 찾은 작물 + 이미 고른 작물. */
    private static List<String> cropList(FarmData data) {
        Set<String> ids = new LinkedHashSet<>(FarmController.INSTANCE.snapshot().counts().keySet());
        ids.addAll(data.selectedCrops);
        return new ArrayList<>(ids);
    }

    private static void cleanupChests(FarmData data) {
        data.chests.removeIf(c -> !c.catchAll && c.crops.isEmpty());
    }

    private void changed(FarmData data) {
        data.save();
        FarmController.INSTANCE.onDataChanged();
        rebuildWidgets();
    }

    // ---- 공통 탭 ----

    private int initCommon(int left, int right, int y) {
        addRenderableWidget(toggle(left, y, "밤에 침낭으로 자기", AfkConfig.SLEEP_AT_NIGHT));
        addRenderableWidget(new IntSlider(right, y, AfkConfig.BAG_SEARCH_RADIUS, 1, 10, 1,
                v -> Component.literal("침낭 자리 반경: " + v + "칸")));
        y += ROW_H;
        addRenderableWidget(CycleButton.<AutoEater.FoodMode>builder(m -> Component.literal(m.label))
                .withValues(AutoEater.FoodMode.values())
                .withInitialValue(AfkConfig.FOOD_MODE.get())
                .create(left, y, COL_W, BTN_H, Component.literal("자동으로 먹기"), (b, v) -> AfkConfig.FOOD_MODE.set(v)));
        addRenderableWidget(new IntSlider(right, y, AfkConfig.EAT_BELOW, 1, 19, 1,
                v -> Component.literal("배고픔 " + v + " 이하면 먹기")));
        y += ROW_H;
        addRenderableWidget(new IntSlider(left, y, AfkConfig.FOOD_KEEP, 0, 64, 1,
                v -> Component.literal("남길 음식: " + v + "개")));
        addRenderableWidget(toggle(right, y, "피격 시 정지", AfkConfig.STOP_ON_DAMAGE));
        y += ROW_H;
        addRenderableWidget(toggle(left, y, "HUD 표시", AfkConfig.SHOW_HUD));
        return y + ROW_H;
    }

    // ---- 공통 ----

    @Override
    public void tick() {
        if (toggleButton != null) {
            toggleButton.setMessage(tab == Tab.FISH ? fishToggleLabel() : farmToggleLabel());
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        int y = infoY;
        switch (tab) {
            case FISH -> renderFishInfo(g, y);
            case FARM -> renderFarmInfo(g, y);
            case SETUP -> renderSetupInfo(g);
            case COMMON -> g.drawCenteredString(font, "자동으로 먹기: 작물 제외 = 농사로 거둔 작물은 먹지 않음. 효과가 붙은 음식은 먹지 않음",
                    width / 2, y, 0xAAAAAA);
        }
    }

    private void renderFishInfo(GuiGraphics g, int y) {
        FishingController fc = FishingController.INSTANCE;
        String summary = "잡은 수 " + fc.catches();
        if (!fc.isRunning() && !fc.lastMessage().isEmpty()) {
            summary += " · 마지막 정지 사유: " + fc.lastMessage();
        }
        g.drawCenteredString(font, summary, width / 2, y, 0xAAAAAA);
        y += 12;
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
            if (++shown % 3 == 0) {
                g.drawCenteredString(font, line.toString(), width / 2, y, 0xDDDDDD);
                line.setLength(0);
                y += 10;
            }
        }
        if (!line.isEmpty() && y <= height - 10) {
            g.drawCenteredString(font, line.toString(), width / 2, y, 0xDDDDDD);
        }
    }

    private void renderFarmInfo(GuiGraphics g, int y) {
        FarmController fc = FarmController.INSTANCE;
        String summary = "상태: " + fc.stateLabel() + " · 심음 " + fc.planted() + " · 상자에 넣음 " + fc.deposited();
        if (!fc.isRunning() && !fc.lastMessage().isEmpty()) {
            summary += " · 마지막: " + fc.lastMessage();
        }
        g.drawCenteredString(font, summary, width / 2, y, 0xAAAAAA);
        y += 12;
        StringBuilder line = new StringBuilder();
        for (Map.Entry<String, Integer> e : fc.harvested().entrySet()) {
            if (!line.isEmpty()) {
                line.append("   ");
            }
            line.append(Crops.displayName(e.getKey())).append(" x").append(e.getValue());
        }
        if (!line.isEmpty() && y <= height - 10) {
            g.drawCenteredString(font, "수확: " + line, width / 2, y, 0xDDDDDD);
        }
    }

    private void renderSetupInfo(GuiGraphics g) {
        FarmData data = FarmData.current();
        Minecraft mc = Minecraft.getInstance();
        String lookedText = looked == null ? "블록을 바라본 채로 J를 눌러 이 화면을 열면 모서리·상자를 지정할 수 있습니다"
                : "바라보는 블록: " + looked.toShortString() + " (" + mc.level.getBlockState(looked).getBlock().getName().getString() + ")";
        g.drawCenteredString(font, lookedText, width / 2, 32, looked == null ? 0xFFAA55 : 0xFFFFFF);
        int y = infoY;
        String area;
        if (data.hasArea()) {
            BlockPos min = data.min();
            BlockPos max = data.max();
            area = "범위 " + min.toShortString() + " ~ " + max.toShortString() + " (" + (max.getX() - min.getX() + 1) + "×"
                    + (max.getZ() - min.getZ() + 1) + ", 높이 " + (max.getY() - min.getY() + 1) + ")";
            if (data.volume() > FarmScanner.MAX_VOLUME) {
                area += " - 너무 큼!";
            }
        } else {
            area = "범위: 모서리 두 곳을 지정하세요 (농경지 높이로 찍으면 됨)";
        }
        g.drawCenteredString(font, area, width / 2, y, 0xAAAAAA);
        y += 10;
        long otherChests = data.chests.stream().filter(c -> c.catchAll).count();
        String extra = "작물 상자 " + data.chests.stream().filter(c -> !c.catchAll).count() + "개 · 기타 상자 " + otherChests + "개 · 낚시 자리 "
                + (data.fishSpot == null ? "없음" : "지정됨");
        g.drawCenteredString(font, extra, width / 2, y, 0xAAAAAA);
        if (cropList(data).isEmpty()) {
            g.drawCenteredString(font, "범위를 지정하고 '범위 다시 스캔'을 누르면 작물 목록이 나옵니다", width / 2, y + 12, 0xFFAA55);
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

    private static Component fishToggleLabel() {
        if (FishingController.INSTANCE.isEmbedded()) {
            return Component.literal("농사 중 기다리며 낚시하는 중 (농사 탭에서 정지)");
        }
        return FishingController.INSTANCE.isRunning()
                ? Component.literal("■ 낚시 정지 (F8)")
                : Component.literal("▶ 낚시 시작 (F8)");
    }

    private static Component farmToggleLabel() {
        return FarmController.INSTANCE.isRunning()
                ? Component.literal("■ 농사 정지 (F9)")
                : Component.literal("▶ 농사 시작 (F9)");
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
            super(x, y, COL_W, BTN_H, Component.empty(),
                    (Math.max(min, Math.min(max, cfg.get())) - min) / (double) (max - min));
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
