package com.yujin.mobalert.gui;

import com.yujin.mobalert.EntityIcons;
import com.yujin.mobalert.MobAlertConfig;
import com.yujin.mobalert.MobAlertTracker;
import com.yujin.mobalert.MobNames;
import com.yujin.mobalert.Variants;
import com.yujin.mobalert.compat.MapMarkers;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.ai.attributes.DefaultAttributes;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

/** 설정 화면: 위쪽은 옵션 버튼, 아래쪽은 검색 가능한 생물 목록(클릭으로 알림 켜기/끄기). */
public class MobAlertScreen extends Screen {
    private static final int BTN_H = 20, GAP = 4;

    @Nullable private final Screen parent;
    private final MobAlertConfig cfg = MobAlertConfig.get();
    private final List<Candidate> allCandidates = new ArrayList<>();

    private EditBox search;
    private EntityList list;
    private Filter filter = Filter.MOBS;

    private enum Filter { MOBS, SELECTED, ALL }

    public MobAlertScreen(@Nullable Screen parent) {
        super(Component.translatable("mobalert.screen.title"));
        this.parent = parent;

        List<Candidate> bases = new ArrayList<>();
        Set<String> known = new HashSet<>();
        for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
            if (type == EntityType.PLAYER) continue;
            ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
            // 생물 판정: 능력치(체력 등)가 있거나 MISC 가 아닌 분류. 그 외(보트, 화살 등)는 "모든 엔티티" 보기에서만.
            boolean creature = DefaultAttributes.hasSupplier(type) || type.getCategory() != MobCategory.MISC;
            List<String> variants = creature ? Variants.variantsOf(type) : List.of();
            bases.add(Candidate.base(type, id, creature, variants.size()));
            known.add(id.toString());
            for (String vk : variants) known.add(id.toString() + Variants.SEP + vk);
        }
        bases.sort(Comparator.comparing((Candidate c) -> c.name.toLowerCase(Locale.ROOT)));
        for (Candidate base : bases) {
            allCandidates.add(base);
            if (base.variantCount > 0) {
                for (String vk : Variants.variantsOf(base.type)) allCandidates.add(Candidate.variant(base, vk));
            }
        }
        // 설정 파일/명령어로 추가했지만 목록에 없는 키도 보이게 한다.
        for (String key : cfg.entities) {
            if (!known.contains(key)) allCandidates.add(Candidate.unknown(key));
        }
    }

    @Override
    protected void init() {
        int cols = 4;
        int contentW = Math.min(width - 20, 440);
        int btnW = (contentW - GAP * (cols - 1)) / cols;
        int left = (width - contentW) / 2;
        int y = 22;

        // --- 1행: 전체 켜기, 반경, 쿨다운, 강조 색
        addRenderableWidget(toggle("enabled", cfg.enabled, v -> cfg.enabled = v, col(left, btnW, 0), y, btnW));
        addRenderableWidget(new IntSlider(col(left, btnW, 1), y, btnW, "mobalert.option.radius",
                MobAlertConfig.MIN_RADIUS, MobAlertConfig.MAX_RADIUS, cfg.radius, v -> cfg.radius = v));
        addRenderableWidget(new IntSlider(col(left, btnW, 2), y, btnW, "mobalert.option.cooldown",
                0, 120, Math.min(cfg.cooldownSeconds, 120), v -> cfg.cooldownSeconds = v));
        addRenderableWidget(CycleButton.<MobAlertConfig.GlowColor>builder(c ->
                        Component.translatable("mobalert.color." + c.name().toLowerCase(Locale.ROOT))
                                .withStyle(s -> s.withColor(c.rgb)))
                .withValues(MobAlertConfig.GlowColor.values()).withInitialValue(cfg.glowColor)
                .create(col(left, btnW, 3), y, btnW, BTN_H, Component.translatable("mobalert.option.color"),
                        (b, v) -> cfg.glowColor = v));

        // --- 2행: 발광, 화면 마커, HUD, HUD 위치
        y += BTN_H + GAP;
        addRenderableWidget(toggle("glow", cfg.glow, v -> cfg.glow = v, col(left, btnW, 0), y, btnW));
        addRenderableWidget(toggle("marker", cfg.marker, v -> cfg.marker = v, col(left, btnW, 1), y, btnW));
        addRenderableWidget(toggle("hud", cfg.hud, v -> cfg.hud = v, col(left, btnW, 2), y, btnW));
        addRenderableWidget(CycleButton.<MobAlertConfig.HudPos>builder(p ->
                        Component.translatable("mobalert.hudpos." + p.name().toLowerCase(Locale.ROOT)))
                .withValues(MobAlertConfig.HudPos.values()).withInitialValue(cfg.hudPos)
                .displayOnlyValue()
                .withTooltip(p -> Tooltip.create(Component.translatable("mobalert.option.hudpos")))
                .create(col(left, btnW, 3), y, btnW, BTN_H, Component.translatable("mobalert.option.hudpos"),
                        (b, v) -> cfg.hudPos = v));

        // --- 3행: 팝업, 소리, 채팅, 지도
        y += BTN_H + GAP;
        addRenderableWidget(toggle("toast", cfg.toast, v -> cfg.toast = v, col(left, btnW, 0), y, btnW));
        addRenderableWidget(toggle("sound", cfg.sound, v -> cfg.sound = v, col(left, btnW, 1), y, btnW));
        addRenderableWidget(toggle("chat", cfg.chat, v -> cfg.chat = v, col(left, btnW, 2), y, btnW));
        CycleButton<Boolean> mapButton = toggle("map", cfg.map, v -> cfg.map = v, col(left, btnW, 3), y, btnW);
        if (!MapMarkers.available()) {
            mapButton.active = false;
            mapButton.setTooltip(Tooltip.create(Component.translatable("mobalert.tooltip.map_missing")));
        }
        addRenderableWidget(mapButton);

        // --- 4행: 펫 보호, 휩쓸기 보호, 보기 필터, 전체 해제
        y += BTN_H + GAP;
        addRenderableWidget(toggle("pet", cfg.petGuard, v -> cfg.petGuard = v, col(left, btnW, 0), y, btnW));
        addRenderableWidget(toggle("petsweep", cfg.petGuardSweep, v -> cfg.petGuardSweep = v, col(left, btnW, 1), y, btnW));
        addRenderableWidget(CycleButton.<Filter>builder(f ->
                        Component.translatable("mobalert.screen.filter_" + f.name().toLowerCase(Locale.ROOT)))
                .withValues(Filter.values()).withInitialValue(filter).displayOnlyValue()
                .withTooltip(f -> Tooltip.create(Component.translatable("mobalert.tooltip.filter")))
                .create(col(left, btnW, 2), y, btnW, BTN_H, Component.empty(), (b, v) -> {
                    filter = v;
                    refreshList();
                }));
        addRenderableWidget(Button.builder(Component.translatable("mobalert.screen.clear"), b -> {
            cfg.clearWatched();
            refreshList();
        }).bounds(col(left, btnW, 3), y, btnW, BTN_H).build());

        // --- 5행: 검색창
        y += BTN_H + GAP + 4;
        String prev = search != null ? search.getValue() : "";
        search = new EditBox(font, left + 1, y + 1, contentW - 2, BTN_H - 2, Component.translatable("mobalert.screen.search"));
        search.setHint(Component.translatable("mobalert.screen.search").withStyle(ChatFormatting.DARK_GRAY));
        search.setValue(prev);
        search.setResponder(s -> refreshList());
        addRenderableWidget(search);

        // --- 생물 목록
        y += BTN_H + GAP;
        int listBottom = height - 32;
        list = new EntityList(minecraft, width, listBottom - y, y, contentW);
        addRenderableWidget(list);
        refreshList();

        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
                .bounds(width / 2 - 100, height - 26, 200, BTN_H).build());
    }

    private static int col(int left, int btnW, int i) { return left + i * (btnW + GAP); }

    private CycleButton<Boolean> toggle(String key, boolean initial, Consumer<Boolean> setter, int x, int y, int w) {
        return CycleButton.onOffBuilder(initial)
                .withTooltip(v -> Tooltip.create(Component.translatable("mobalert.tooltip." + key)))
                .create(x, y, w, BTN_H, Component.translatable("mobalert.option." + key), (b, v) -> setter.accept(v));
    }

    private void refreshList() {
        if (list == null) return;
        String q = search == null ? "" : search.getValue().trim().toLowerCase(Locale.ROOT);
        List<Candidate> filtered = new ArrayList<>();
        for (Candidate c : allCandidates) {
            boolean visible = switch (filter) {
                case MOBS -> c.creature;
                case SELECTED -> cfg.isWatched(c.key);
                case ALL -> true;
            };
            if (!visible) continue;
            if (!q.isEmpty() && !c.search.contains(q)) continue;
            filtered.add(c);
        }
        list.setCandidates(filtered);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(font, title, width / 2, 8, 0xFFFFFF);
        Component count = Component.translatable("mobalert.screen.count", cfg.entities.size(), MobAlertTracker.nearby().size());
        g.drawString(font, count, width - font.width(count) - 6, height - 20, 0xA0A0A0);
    }

    @Override
    public void removed() {
        MobAlertConfig.save();
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    /**
     * 목록 한 줄. key = 감시 키 ("minecraft:panda" 또는 "dmr:dragon#dmr.dragon_breed.nether"),
     * name/altName = 현재 언어/다른 언어 이름, search = 한/영/초성/id 검색 문자열.
     */
    private record Candidate(@Nullable EntityType<?> type, String key, @Nullable String parentKey, String name,
                             String altName, String subtitle, String search, boolean creature, int variantCount) {
        static Candidate base(EntityType<?> type, ResourceLocation id, boolean creature, int variantCount) {
            String fb = MobNames.fallbackName(id);
            String sub = variantCount > 0
                    ? id + " · " + Component.translatable("mobalert.screen.all_variants", variantCount).getString()
                    : id.toString();
            return new Candidate(type, id.toString(), null, MobNames.primary(type), MobNames.secondary(type), sub,
                    MobNames.searchText(type.getDescriptionId(), fb, id.toString()), creature, variantCount);
        }

        static Candidate variant(Candidate base, String variantKey) {
            String fb = MobNames.fallbackName(ResourceLocation.withDefaultNamespace(
                    variantKey.substring(variantKey.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT)));
            String key = base.key + Variants.SEP + variantKey;
            String sub = base.key + " · " + Component.translatable("mobalert.screen.variant").getString();
            return new Candidate(base.type, key, base.key, MobNames.primary(variantKey, fb), MobNames.secondary(variantKey, fb),
                    sub, MobNames.searchText(variantKey, fb, key), true, 0);
        }

        static Candidate unknown(String key) {
            return new Candidate(null, key, null, Variants.bothNames(key).getString(), "", key,
                    key.toLowerCase(Locale.ROOT), true, 0);
        }
    }

    // ------------------------------------------------------------------ list

    private class EntityList extends ObjectSelectionList<EntityList.Entry> {
        private final int rowWidth;

        EntityList(Minecraft mc, int width, int height, int y, int rowWidth) {
            super(mc, width, height, y, 24);
            this.rowWidth = rowWidth;
        }

        void setCandidates(List<Candidate> candidates) {
            clearEntries();
            for (Candidate c : candidates) addEntry(new Entry(c));
            setScrollAmount(Mth.clamp(getScrollAmount(), 0, getMaxScroll()));
        }

        @Override
        public int getRowWidth() { return rowWidth; }

        @Override
        protected int getScrollbarPosition() { return (width + rowWidth) / 2 + 4; }

        class Entry extends ObjectSelectionList.Entry<Entry> {
            private final Candidate c;

            Entry(Candidate c) { this.c = c; }

            @Override
            public void render(GuiGraphics g, int index, int top, int left, int width, int height,
                               int mouseX, int mouseY, boolean hovering, float partialTick) {
                boolean on = cfg.isWatched(c.key);
                boolean viaParent = !on && c.parentKey != null && cfg.isWatched(c.parentKey); // 상위 "모든 품종"으로 이미 포함
                int indent = c.parentKey != null ? 14 : 0;
                int color = 0xFF000000 | cfg.glowColor.rgb;
                if (on) g.fill(left, top, left + width, top + height, 0x40000000 | (cfg.glowColor.rgb & 0xFFFFFF));
                else if (hovering) g.fill(left, top, left + width, top + height, 0x20FFFFFF);

                // 체크박스
                int bx = left + 4 + indent, by = top + (height - 12) / 2;
                g.fill(bx, by, bx + 12, by + 12, 0xFF000000);
                g.renderOutline(bx, by, 12, 12, on ? color : 0xFF808080);
                if (on) g.drawString(font, "✔", bx + 2, by + 2, color, false);
                else if (viaParent) g.drawString(font, "✔", bx + 2, by + 2, 0xFF606060, false);

                int x = bx + 17;
                EntityIcons.render(g, c.type, null, x, top + (height - 20) / 2, 20);
                x += 24;
                g.drawString(font, c.name, x, top + 2, on || viaParent ? 0xFFFFFF : 0xC0C0C0);
                if (!c.altName.isEmpty()) {
                    g.drawString(font, c.altName, x + font.width(c.name) + 6, top + 2, 0x909090, false);
                }
                g.drawString(font, c.subtitle, x, top + 11, 0x707070, false);
            }

            @Override
            public boolean mouseClicked(double mouseX, double mouseY, int button) {
                if (button == 0) {
                    cfg.setWatched(c.key, !cfg.isWatched(c.key));
                    if (filter == Filter.SELECTED) refreshList();
                    return true;
                }
                return false;
            }

            @Override
            public Component getNarration() {
                return Component.literal(c.altName.isEmpty() ? c.name : c.name + " " + c.altName);
            }
        }
    }

    // ------------------------------------------------------------------ slider

    private static class IntSlider extends AbstractSliderButton {
        private final String key;
        private final int min, max;
        private final Consumer<Integer> setter;

        IntSlider(int x, int y, int w, String key, int min, int max, int value, Consumer<Integer> setter) {
            super(x, y, w, BTN_H, Component.empty(), (value - min) / (double) (max - min));
            this.key = key;
            this.min = min;
            this.max = max;
            this.setter = setter;
            updateMessage();
        }

        private int intValue() { return (int) Math.round(min + value * (max - min)); }

        @Override
        protected void updateMessage() { setMessage(Component.translatable(key, intValue())); }

        @Override
        protected void applyValue() { setter.accept(intValue()); }
    }
}
