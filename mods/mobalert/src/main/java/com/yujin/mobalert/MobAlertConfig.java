package com.yujin.mobalert;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** config/mobalert.json 에 저장되는 설정. GUI/명령어 모두 이 객체를 수정한 뒤 save() 한다. */
public class MobAlertConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FMLPaths.CONFIGDIR.get().resolve("mobalert.json");

    public static final int MIN_RADIUS = 8, MAX_RADIUS = 128;
    public static final int MAX_COOLDOWN = 300;

    public enum HudPos { TOP_LEFT, TOP_RIGHT, MIDDLE_LEFT, MIDDLE_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

    public enum GlowColor {
        GOLD(0xFFD700), RED(0xFF4040), GREEN(0x40FF40), AQUA(0x40FFFF), BLUE(0x4080FF), PINK(0xFF66CC), WHITE(0xFFFFFF);
        public final int rgb;
        GlowColor(int rgb) { this.rgb = rgb; }
    }

    public boolean enabled = true;
    public int radius = 48;
    public boolean glow = true;
    public GlowColor glowColor = GlowColor.GOLD;
    public boolean hud = true;
    public HudPos hudPos = HudPos.MIDDLE_RIGHT;
    public boolean toast = true;
    public boolean sound = true;
    public boolean chat = false;
    /** Xaero 미니맵/월드맵에 위치 표시. */
    public boolean map = true;
    /** 머리 위 화면 마커 (셰이더를 써도 보임). */
    public boolean marker = true;
    /** 내 펫을 직접 때리는 공격을 막는다. (웅크리면 허용) */
    public boolean petGuard = true;
    /** 휩쓸기 공격이 내 펫을 칠 것 같으면 막는다. */
    public boolean petGuardSweep = true;
    /** 같은 종류의 몹을 다시 알릴 때까지의 최소 간격(초). */
    public int cooldownSeconds = 30;
    /** 감시 키: "minecraft:panda" (그 생물 전부) 또는 "dmr:dragon#dmr.dragon_breed.nether" (특정 품종만). */
    public Set<String> entities = new LinkedHashSet<>();
    /** 월드에서 발견한 변종 번역 키 접두사 (생물 id → "dmr.dragon_breed."). */
    public Map<String, String> variantPrefixes = new LinkedHashMap<>();

    private static MobAlertConfig instance = new MobAlertConfig();
    /** 품종 단위 규칙이 하나라도 있는 생물 id. */
    private transient Set<String> typesWithVariantRules;

    public static MobAlertConfig get() { return instance; }

    public static void load() {
        if (Files.exists(FILE)) {
            try (Reader r = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
                MobAlertConfig loaded = GSON.fromJson(r, MobAlertConfig.class);
                if (loaded != null) instance = loaded;
            } catch (Exception e) {
                MobAlert.LOGGER.error("Failed to read {}, using defaults", FILE, e);
            }
        }
        instance.sanitize();
        save();
    }

    public static void save() {
        instance.sanitize();
        try {
            Files.createDirectories(FILE.getParent());
            try (Writer w = Files.newBufferedWriter(FILE, StandardCharsets.UTF_8)) {
                GSON.toJson(instance, w);
            }
        } catch (IOException e) {
            MobAlert.LOGGER.error("Failed to write {}", FILE, e);
        }
    }

    private void sanitize() {
        if (entities == null) entities = new LinkedHashSet<>();
        if (variantPrefixes == null) variantPrefixes = new LinkedHashMap<>();
        if (glowColor == null) glowColor = GlowColor.GOLD;
        if (hudPos == null) hudPos = HudPos.MIDDLE_RIGHT;
        radius = Math.clamp(radius, MIN_RADIUS, MAX_RADIUS);
        cooldownSeconds = Math.clamp(cooldownSeconds, 0, MAX_COOLDOWN);
        typesWithVariantRules = null;
    }

    public boolean isWatched(String key) { return entities.contains(key); }

    public void setWatched(String key, boolean watched) {
        if (watched) entities.add(key);
        else entities.remove(key);
        typesWithVariantRules = null;
    }

    public void clearWatched() {
        entities.clear();
        typesWithVariantRules = null;
    }

    /** 이 엔티티가 감시 대상인지: 생물 전체 규칙 또는 품종 규칙에 맞으면 true. */
    public boolean matches(Entity e) {
        String id = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
        if (entities.contains(id)) return true;
        if (typesWithVariantRules == null) {
            Set<String> set = new HashSet<>();
            for (String k : entities) {
                int i = k.indexOf(Variants.SEP);
                if (i > 0) set.add(k.substring(0, i));
            }
            typesWithVariantRules = set;
        }
        if (!typesWithVariantRules.contains(id)) return false;
        String vk = Variants.variantKey(e);
        return vk != null && entities.contains(id + Variants.SEP + vk);
    }
}
