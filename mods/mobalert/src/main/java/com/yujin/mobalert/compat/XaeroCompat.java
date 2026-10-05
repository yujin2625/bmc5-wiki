package com.yujin.mobalert.compat;

import com.yujin.mobalert.MobAlert;
import com.yujin.mobalert.MobAlertConfig;
import com.yujin.mobalert.Variants;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import xaero.common.minimap.waypoints.Waypoint;
import xaero.hud.minimap.BuiltInHudModules;
import xaero.hud.minimap.module.MinimapSession;
import xaero.hud.minimap.waypoint.thirdparty.ThirdPartyWaypoints;
import xaero.hud.minimap.world.container.MinimapWorldContainer;
import xaero.hud.minimap.world.container.MinimapWorldRootContainer;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Xaero's Minimap 의 "서드파티 웨이포인트" API 로 주변 감시 대상을 미니맵/월드맵/월드 안 마커로 표시한다.
 * 이 클래스는 Xaero 가 설치돼 있을 때만 로드된다 ({@link MapMarkers} 참고).
 */
final class XaeroCompat {
    private static final ResourceLocation ORIGIN = ResourceLocation.fromNamespaceAndPath(MobAlert.MODID, "nearby");
    private static final int MAX_WAYPOINTS = 32;

    @Nullable private static ThirdPartyWaypoints current;

    private XaeroCompat() {}

    static void update(List<Entity> nearby) {
        ThirdPartyWaypoints wps = waypointsForCurrentDimension();
        if (wps != current) {
            clear(); // 차원/서버가 바뀌면 이전 마커 정리
            current = wps;
        }
        if (wps == null) return;

        MobAlertConfig cfg = MobAlertConfig.get();
        int color = xaeroColor(cfg.glowColor);
        Set<String> keep = new HashSet<>();
        for (int i = 0; i < nearby.size() && i < MAX_WAYPOINTS; i++) {
            Entity e = nearby.get(i);
            if (e.isRemoved()) continue;
            String id = "e" + e.getId();
            keep.add(id);
            String name = Variants.typeName(e).getString();
            int x = e.getBlockX(), y = e.getBlockY(), z = e.getBlockZ();
            Waypoint wp = wps.get(id);
            if (wp == null) {
                wp = new Waypoint(x, y, z, name, symbol(name), color);
                wp.setTemporary(true);
                wps.add(id, wp);
            } else {
                wp.setX(x);
                wp.setY(y);
                wp.setZ(z);
                wp.setColor(color);
                if (!name.equals(wp.getName())) {
                    wp.setName(name);
                    wp.setSymbol(symbol(name));
                }
            }
        }
        List<String> stale = new ArrayList<>();
        for (Object id : wps.getIds()) if (!keep.contains((String) id)) stale.add((String) id);
        for (String id : stale) wps.remove(id);
    }

    static void clear() {
        if (current != null) {
            current.clear();
            current = null;
        }
    }

    @Nullable
    private static ThirdPartyWaypoints waypointsForCurrentDimension() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return null;
        if (!(BuiltInHudModules.MINIMAP.getCurrentSession() instanceof MinimapSession session)) return null;
        MinimapWorldRootContainer root = session.getWorldManager().getAutoRootContainer();
        if (root == null) return null;
        String dimDir = root.getSession().getDimensionHelper().getDimensionDirectoryName(mc.level.dimension());
        MinimapWorldContainer container = root.addSubContainer(root.getPath().resolve(dimDir));
        return container.getThirdPartyWaypointManager().get(ORIGIN);
    }

    /** 웨이포인트 아이콘 글자: 이름 첫 글자. */
    private static String symbol(String name) {
        return name.isEmpty() ? "!" : name.substring(0, name.offsetByCodePoints(0, 1)).toUpperCase();
    }

    /** Xaero 색 번호 (마인크래프트 채팅 색 순서). */
    private static int xaeroColor(MobAlertConfig.GlowColor c) {
        return switch (c) {
            case GOLD -> 6;
            case RED -> 12;
            case GREEN -> 10;
            case AQUA -> 11;
            case BLUE -> 9;
            case PINK -> 13;
            case WHITE -> 15;
        };
    }
}
