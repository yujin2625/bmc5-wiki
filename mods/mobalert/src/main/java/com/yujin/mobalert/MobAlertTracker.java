package com.yujin.mobalert;

import com.yujin.mobalert.compat.MapMarkers;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 주변의 감시 대상 엔티티를 주기적으로 스캔하고, 새로 나타난 것에 대해 알림을 띄운다. */
public final class MobAlertTracker {
    private static final int SCAN_INTERVAL_TICKS = 5;
    private static final String[] COMPASS = {"north", "northeast", "east", "southeast", "south", "southwest", "west", "northwest"};

    /** 현재 반경 안에 있는 감시 대상 (가까운 순). HUD가 읽는다. */
    private static List<Entity> nearby = List.of();
    /** 발광 강조할 엔티티 id. 렌더 스레드에서 mixin이 조회한다. */
    private static IntSet highlighted = new IntOpenHashSet();
    private static final Map<String, Long> lastAlert = new HashMap<>();
    private static int tickCounter;

    private MobAlertTracker() {}

    public static List<Entity> nearby() { return nearby; }

    public static boolean isHighlighted(Entity entity) {
        MobAlertConfig cfg = MobAlertConfig.get();
        return cfg.enabled && cfg.glow && entity.level().isClientSide() && highlighted.contains(entity.getId());
    }

    public static void reset() {
        MapMarkers.clear();
        nearby = List.of();
        highlighted = new IntOpenHashSet();
        lastAlert.clear();
    }

    static void tick(Minecraft mc) {
        if (++tickCounter % SCAN_INTERVAL_TICKS != 0) return;
        LocalPlayer player = mc.player;
        MobAlertConfig cfg = MobAlertConfig.get();
        if (mc.level != null && tickCounter % 40 == 0) {
            for (Entity e : mc.level.entitiesForRendering()) {
                if (e instanceof LivingEntity && e != player) Variants.discover(e);
            }
        }
        if (player == null || mc.level == null || !cfg.enabled || cfg.entities.isEmpty()) {
            if (!nearby.isEmpty() || !highlighted.isEmpty()) {
                nearby = List.of();
                highlighted = new IntOpenHashSet();
            }
            MapMarkers.clear();
            return;
        }

        double r2 = (double) cfg.radius * cfg.radius;
        List<Entity> found = new ArrayList<>();
        IntSet ids = new IntOpenHashSet();
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e == player || !e.isAlive()) continue;
            if (!cfg.matches(e)) continue;
            if (e.distanceToSqr(player) > r2) continue;
            found.add(e);
            ids.add(e.getId());
        }
        found.sort(Comparator.comparingDouble(e -> e.distanceToSqr(player)));

        // 새로 범위에 들어온 엔티티를 종류(품종 포함)별로 묶는다 (가장 가까운 것 기준).
        Map<String, List<Entity>> newcomers = new LinkedHashMap<>();
        for (Entity e : found) {
            if (!highlighted.contains(e.getId())) newcomers.computeIfAbsent(Variants.watchKey(e), k -> new ArrayList<>()).add(e);
        }

        nearby = List.copyOf(found);
        highlighted = ids;
        if (cfg.map) MapMarkers.update(nearby);
        else MapMarkers.clear();

        long now = System.currentTimeMillis();
        boolean playedSound = false;
        for (Map.Entry<String, List<Entity>> entry : newcomers.entrySet()) {
            Long last = lastAlert.get(entry.getKey());
            if (last != null && now - last < cfg.cooldownSeconds * 1000L) continue;
            lastAlert.put(entry.getKey(), now);
            alert(mc, player, entry.getValue(), cfg, !playedSound);
            playedSound = true;
        }
    }

    private static void alert(Minecraft mc, LocalPlayer player, List<Entity> list, MobAlertConfig cfg, boolean sound) {
        Entity closest = list.getFirst();
        EntityType<?> type = closest.getType();
        int dist = (int) Math.round(closest.distanceTo(player));
        Component name = Variants.typeName(closest);
        Component dir = Component.translatable("mobalert.dir." + compass(player, closest));

        if (cfg.toast) {
            mc.getToasts().addToast(new MobAlertToast(type, closest, name, list.size(), dist, dir));
        }
        if (cfg.chat) {
            MutableComponent msg = Component.literal("[Mob Alert] ").withStyle(ChatFormatting.GOLD)
                    .append(Component.translatable("mobalert.chat.found",
                            Variants.bothNames(closest).withStyle(s -> s.withColor(TextColor.fromRgb(cfg.glowColor.rgb))),
                            list.size(), dist, dir,
                            (int) closest.getX(), (int) closest.getY(), (int) closest.getZ())
                            .withStyle(ChatFormatting.WHITE));
            player.displayClientMessage(msg, false);
        }
        if (cfg.sound && sound) {
            mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_BELL.value(), 1.4f, 1.0f));
        }
    }

    /** 절대 방위(북/북동/...) 키. */
    public static String compass(Entity from, Entity to) {
        double dx = to.getX() - from.getX(), dz = to.getZ() - from.getZ();
        double bearing = Math.toDegrees(Math.atan2(dx, -dz)); // 0 = 북(-Z), 90 = 동(+X)
        int idx = Math.floorMod((int) Math.round(bearing / 45.0), 8);
        return COMPASS[idx];
    }

    /** 플레이어가 바라보는 방향 기준 화살표. */
    public static String relativeArrow(Entity from, Entity to, float partialTick) {
        double dx = to.getX() - from.getX(), dz = to.getZ() - from.getZ();
        double targetYaw = Math.toDegrees(Math.atan2(-dx, dz)); // 마인크래프트 yaw 규약 (0 = 남쪽)
        double rel = Mth.wrapDegrees(targetYaw - from.getViewYRot(partialTick));
        int idx = Math.floorMod((int) Math.round(rel / 45.0), 8);
        return switch (idx) {
            case 0 -> "↑";
            case 1 -> "↗";
            case 2 -> "→";
            case 3 -> "↘";
            case 4 -> "↓";
            case 5 -> "↙";
            case 6 -> "←";
            default -> "↖";
        };
    }

    public static ResourceLocation idOf(EntityType<?> type) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(type);
    }
}
