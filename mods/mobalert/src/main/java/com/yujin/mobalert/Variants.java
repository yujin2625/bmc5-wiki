package com.yujin.mobalert;

import com.yujin.mobalert.mixin.EntityAccessor;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 한 생물 종류 안의 품종/변종 (예: Dragon Mounts 의 dmr:dragon → "Nether Dragon").
 * <p>
 * 엔티티의 종류 이름이 기본 이름과 다른 번역 키(예: dmr.dragon_breed.nether)를 쓰면 변종으로 보고,
 * 같은 접두사(dmr.dragon_breed.)를 가진 번역 키들을 그 생물의 변종 목록으로 쓴다.
 * 감시 키 형식: "dmr:dragon#dmr.dragon_breed.nether"
 */
public final class Variants {
    public static final char SEP = '#';
    private static final int MAX_VARIANTS = 64;

    private static final Map<EntityType<?>, List<String>> CACHE = new HashMap<>();

    private Variants() {}

    public static void reset() { CACHE.clear(); }

    public static Component typeName(Entity e) {
        try {
            return ((EntityAccessor) e).mobalert$getTypeName();
        } catch (Throwable t) {
            return e.getType().getDescription();
        }
    }

    /** "네더 드래곤 (Nether Dragon)" 처럼 두 언어 이름. 품종이 없으면 생물 이름. */
    public static net.minecraft.network.chat.MutableComponent bothNames(Entity e) {
        String vk = variantKey(e);
        return vk != null ? MobNames.both(vk, typeName(e).getString()) : MobNames.both(e.getType());
    }

    /** 감시 키("type" 또는 "type#variant")의 두 언어 이름. */
    public static net.minecraft.network.chat.MutableComponent bothNames(String watchKey) {
        int i = watchKey.indexOf(SEP);
        if (i > 0) {
            String vk = watchKey.substring(i + 1);
            return MobNames.both(vk, vk.substring(vk.lastIndexOf('.') + 1));
        }
        var id = net.minecraft.resources.ResourceLocation.tryParse(watchKey);
        var type = id == null ? null : net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getOptional(id).orElse(null);
        return type != null ? MobNames.both(type) : Component.literal(watchKey);
    }

    /** 이 엔티티가 변종 이름을 쓰면 그 번역 키, 아니면 null. */
    @Nullable
    public static String variantKey(Entity e) {
        try {
            if (typeName(e).getContents() instanceof TranslatableContents tc) {
                String key = tc.getKey();
                if (!key.equals(e.getType().getDescriptionId()) && key.indexOf('.') > 0) return key;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    public static String prefixOf(String variantKey) {
        return variantKey.substring(0, variantKey.lastIndexOf('.') + 1);
    }

    /** 엔티티의 감시 키: 변종이 있으면 "type#variant", 없으면 "type". */
    public static String watchKey(Entity e) {
        String id = MobAlertTracker.idOf(e.getType()).toString();
        String vk = variantKey(e);
        return vk == null ? id : id + SEP + vk;
    }

    /** 월드에서 변종을 발견하면 접두사를 기억해 둔다 (설정 파일에 저장되어 다음에도 목록에 나온다). */
    public static void discover(Entity e) {
        MobAlertConfig cfg = MobAlertConfig.get();
        String id = MobAlertTracker.idOf(e.getType()).toString();
        if (cfg.variantPrefixes.containsKey(id)) return;
        String vk = variantKey(e);
        if (vk == null) return;
        cfg.variantPrefixes.put(id, prefixOf(vk));
        CACHE.remove(e.getType());
        MobAlertConfig.save();
    }

    /** GUI 목록용: 이 생물의 변종 번역 키들. 변종이 없으면 빈 목록. */
    public static List<String> variantsOf(EntityType<?> type) {
        List<String> cached = CACHE.get(type);
        if (cached != null) return cached;
        String id = MobAlertTracker.idOf(type).toString();
        String prefix = MobAlertConfig.get().variantPrefixes.get(id);
        if (prefix == null) {
            // 아직 월드에서 본 적이 없으면 미리보기 엔티티로 추측해 본다. (월드 밖이면 다음에 다시 시도)
            Entity preview = EntityIcons.preview(type);
            if (preview == null) return List.of();
            String vk = variantKey(preview);
            if (vk == null) {
                CACHE.put(type, List.of());
                return List.of();
            }
            prefix = prefixOf(vk);
        }
        List<String> keys = MobNames.childKeys(prefix);
        if (keys.size() < 2 || keys.size() > MAX_VARIANTS) keys = List.of();
        CACHE.put(type, keys);
        return keys;
    }
}
