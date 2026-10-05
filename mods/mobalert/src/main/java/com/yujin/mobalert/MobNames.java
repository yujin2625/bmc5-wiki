package com.yujin.mobalert;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.entity.EntityType;
import org.jetbrains.annotations.Nullable;

import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/** 현재 언어 설정과 상관없이 한국어/영어 번역을 모두 읽어 이름 표시와 검색에 쓴다. */
public final class MobNames {
    private static final String CHOSEONG = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ";

    @Nullable private static Map<String, String> english, korean;

    private MobNames() {}

    /** 리소스팩이 다시 로드되면 호출한다. */
    public static void reset() {
        english = null;
        korean = null;
    }

    private static void ensureLoaded() {
        if (english != null && korean != null) return;
        ResourceManager rm = Minecraft.getInstance().getResourceManager();
        english = load(rm, "en_us");
        korean = load(rm, "ko_kr");
    }

    private static Map<String, String> load(ResourceManager rm, String lang) {
        Map<String, String> map = new HashMap<>();
        for (String ns : rm.getNamespaces()) {
            List<Resource> stack = rm.getResourceStack(ResourceLocation.fromNamespaceAndPath(ns, "lang/" + lang + ".json"));
            for (Resource r : stack) {
                try (InputStream in = r.open()) {
                    Language.loadFromJson(in, map::put);
                } catch (Exception e) {
                    MobAlert.LOGGER.debug("Failed to read {} lang from {}", lang, ns, e);
                }
            }
        }
        return map;
    }

    // ------------------------------------------------------------ lookups

    /** 번역 키의 영어 이름. 없으면 fallback. */
    public static String english(String key, String fallback) {
        ensureLoaded();
        return english.getOrDefault(key, fallback);
    }

    /** 번역 키의 한국어 이름. 없으면 영어, 그것도 없으면 fallback. */
    public static String korean(String key, String fallback) {
        ensureLoaded();
        String ko = korean.get(key);
        return ko != null ? ko : english(key, fallback);
    }

    public static boolean hasTranslation(String key) {
        ensureLoaded();
        return english.containsKey(key) || korean.containsKey(key);
    }

    /** prefix 바로 아래의 번역 키들 (예: "dmr.dragon_breed." → nether, end, ...). 더 깊은 키는 제외. */
    public static List<String> childKeys(String prefix) {
        ensureLoaded();
        TreeSet<String> keys = new TreeSet<>();
        for (Map<String, String> m : List.of(english, korean)) {
            for (String k : m.keySet()) {
                if (k.length() > prefix.length() && k.startsWith(prefix) && k.indexOf('.', prefix.length()) < 0) keys.add(k);
            }
        }
        return List.copyOf(keys);
    }

    private static boolean uiIsKorean() {
        return Minecraft.getInstance().getLanguageManager().getSelected().startsWith("ko");
    }

    public static String primary(String key, String fallback) {
        return uiIsKorean() ? korean(key, fallback) : english(key, fallback);
    }

    /** 현재 UI 언어가 아닌 쪽 이름 (같으면 빈 문자열). */
    public static String secondary(String key, String fallback) {
        String p = primary(key, fallback);
        String s = uiIsKorean() ? english(key, fallback) : korean(key, fallback);
        return s.equals(p) ? "" : s;
    }

    /** "판다 (Panda)" 형태. 두 이름이 같으면 하나만. */
    public static MutableComponent both(String key, String fallback) {
        MutableComponent c = Component.literal(primary(key, fallback));
        String s = secondary(key, fallback);
        if (!s.isEmpty()) c.append(Component.literal(" (" + s + ")").withStyle(ChatFormatting.GRAY));
        return c;
    }

    /** 검색용 문자열: 한국어 + 영어 + 초성(ㅍㄷ) + 추가 텍스트(id 등), 소문자. */
    public static String searchText(String key, String fallback, String extra) {
        String ko = korean(key, fallback);
        return (ko + "\n" + english(key, fallback) + "\n" + choseong(ko) + "\n" + extra).toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------ entity types

    /** 번역이 없는 모드 생물은 id 경로를 "Dragon" 처럼 다듬어 쓴다. */
    public static String fallbackName(ResourceLocation id) {
        String[] parts = id.getPath().split("[_/]");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
        }
        return sb.toString();
    }

    public static String primary(EntityType<?> type) {
        return primary(type.getDescriptionId(), fallbackName(MobAlertTracker.idOf(type)));
    }

    public static String secondary(EntityType<?> type) {
        return secondary(type.getDescriptionId(), fallbackName(MobAlertTracker.idOf(type)));
    }

    public static MutableComponent both(EntityType<?> type) {
        return both(type.getDescriptionId(), fallbackName(MobAlertTracker.idOf(type)));
    }

    public static String choseong(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (char ch : s.toCharArray()) {
            if (ch >= 0xAC00 && ch <= 0xD7A3) sb.append(CHOSEONG.charAt((ch - 0xAC00) / 588));
            else sb.append(ch);
        }
        return sb.toString();
    }
}
