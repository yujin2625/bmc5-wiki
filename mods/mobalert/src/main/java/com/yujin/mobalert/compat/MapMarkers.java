package com.yujin.mobalert.compat;

import com.yujin.mobalert.MobAlert;
import net.minecraft.world.entity.Entity;
import net.neoforged.fml.ModList;

import java.util.List;

/** 지도 모드 연동 진입점. 지도 모드가 없거나 버전이 맞지 않으면 조용히 꺼진다. */
public final class MapMarkers {
    private static final boolean XAERO = ModList.get().isLoaded("xaerominimap");
    private static boolean broken;

    private MapMarkers() {}

    public static boolean available() { return XAERO && !broken; }

    public static void update(List<Entity> nearby) {
        if (!available()) return;
        try {
            XaeroCompat.update(nearby);
        } catch (Throwable t) {
            disable(t);
        }
    }

    public static void clear() {
        if (!available()) return;
        try {
            XaeroCompat.clear();
        } catch (Throwable t) {
            disable(t);
        }
    }

    private static void disable(Throwable t) {
        broken = true;
        MobAlert.LOGGER.warn("Xaero's Minimap integration failed; map markers disabled", t);
    }
}
