package com.dumaru.afkfishing.compat;

import com.mojang.logging.LogUtils;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.FishingRodItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;

/**
 * Star Catcher 낚싯대 지원. 컴파일 의존성 없이 리플렉션으로만 접근하므로 모드가 없거나 버전이 달라도 게임이 깨지지 않는다
 * (그 경우 Star Catcher 지원만 꺼진다).
 *
 * Star Catcher 흐름: 낚싯대 사용 → FishingBobEntity 투척. 찌 상태(STATE)가 서버에서 동기화된다
 * (1 날아감, 2 물에 뜸, 3 입질, 4 미니게임 중). 입질 중에 다시 쓰면 서버가 미니게임 화면을 열고,
 * 손잡이가 돌아가는 원판에서 손잡이가 표적(sweet spot)과 겹칠 때 누르면 점수가 오른다.
 * 점수가 다 차면 화면이 스스로 서버에 성공을 보내고 닫힌다.
 */
public final class Starcatcher {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final int STATE_FLYING = 1;
    public static final int STATE_BOBBING = 2;
    public static final int STATE_BITING = 3;
    public static final int STATE_MINIGAME = 4;

    private static final TagKey<Item> RODS =
            TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath("starcatcher", "rods"));
    private static final ResourceLocation BOB_ID = ResourceLocation.fromNamespaceAndPath("starcatcher", "fishing_bob");

    private static boolean initialized;
    private static boolean available;
    private static EntityDataAccessor<Integer> stateAccessor;
    private static Class<?> screenClass;
    private static Method getActiveSweetSpots;
    private static Method getModifiers;
    private static Method inputPressed;
    private static Field handlePos;
    private static Field handleSpeed;
    private static Field currentRotation;
    private static Field partial;
    private static Field hitDelay;
    private static Field progress;
    private static Field hp;
    private static Field spotPos;
    private static Field spotThickness;
    private static Field spotCanHit;
    private static Field spotRemoved;
    private static Method canHitSpot;
    private static Field spotBehaviour;
    private static Field treasureActive;
    private static Field treasureProgress;
    private static final java.util.Random RANDOM = new java.util.Random();
    private static final java.util.Map<Object, Float> aims = new java.util.IdentityHashMap<>();
    private static Screen aimScreen;
    private static boolean holdingForTreasure;

    private Starcatcher() {
    }

    @SuppressWarnings("unchecked")
    private static boolean init() {
        if (initialized) {
            return available;
        }
        initialized = true;
        if (!ModList.get().isLoaded("starcatcher")) {
            return false;
        }
        try {
            Class<?> bob = Class.forName("com.wdiscute.starcatcher.bobentity.FishingBobEntity");
            stateAccessor = (EntityDataAccessor<Integer>) bob.getField("STATE").get(null);
            screenClass = Class.forName("com.wdiscute.starcatcher.minigame.FishingMinigameScreen");
            Class<?> spot = Class.forName("com.wdiscute.starcatcher.minigame.ActiveSweetSpot");
            Class<?> modifier = Class.forName("com.wdiscute.starcatcher.modifiers.minigamemodifiers.AbstractMinigameModifier");
            getActiveSweetSpots = screenClass.getMethod("getActiveSweetSpots");
            getModifiers = screenClass.getMethod("getModifiers");
            inputPressed = screenClass.getMethod("inputPressed");
            handlePos = screenClass.getField("handlePos");
            handleSpeed = screenClass.getField("handleSpeed");
            currentRotation = screenClass.getField("currentRotation");
            partial = screenClass.getField("partial");
            hitDelay = screenClass.getField("hitDelay");
            progress = screenClass.getField("progress");
            hp = screenClass.getField("hp");
            spotPos = spot.getField("pos");
            spotThickness = spot.getField("thickness");
            spotCanHit = spot.getField("canHit");
            spotRemoved = spot.getField("removed");
            canHitSpot = modifier.getMethod("canHitSpot", screenClass, spot);
            spotBehaviour = spot.getField("behaviour");
            treasureActive = screenClass.getField("treasureActive");
            treasureProgress = screenClass.getField("treasureProgress");
            available = true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.warn("[afkfishing] Star Catcher 지원을 켜지 못했습니다 (버전이 다를 수 있음)", e);
            available = false;
        }
        return available;
    }

    public static boolean isAvailable() {
        return init();
    }

    /** Star Catcher 낚싯대인지 (바닐라 낚싯대는 제외). */
    public static boolean isRod(ItemStack stack) {
        return !stack.isEmpty() && !(stack.getItem() instanceof FishingRodItem) && stack.is(RODS) && init();
    }

    /** 플레이어가 던진 Star Catcher 찌. 없으면 null. */
    public static Entity findBob(LocalPlayer player) {
        if (!init()) {
            return null;
        }
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(BOB_ID);
        Entity best = null;
        double bestDist = Double.MAX_VALUE;
        for (Entity e : player.level().getEntities(player, new AABB(player.blockPosition()).inflate(64), e -> e.getType() == type)) {
            Entity owner = e instanceof Projectile p ? p.getOwner() : null;
            if (owner != null && owner != player) {
                continue; // 다른 사람 찌
            }
            double d = e.distanceToSqr(player);
            if (owner == player) {
                d -= 1.0E6; // 주인이 확인된 찌를 우선
            }
            if (d < bestDist) {
                bestDist = d;
                best = e;
            }
        }
        return best;
    }

    /** 찌 상태 (1 날아감, 2 물에 뜸, 3 입질, 4 미니게임). 모르면 0. */
    public static int bobState(Entity bob) {
        if (bob == null || !init()) {
            return 0;
        }
        try {
            return bob.getEntityData().get(stateAccessor);
        } catch (RuntimeException e) {
            return 0;
        }
    }

    public static boolean isMinigame(Screen screen) {
        return screen != null && init() && screenClass.isInstance(screen);
    }

    /** 미니게임 진행도 (0~1). 모르면 -1. */
    public static float progress(Screen screen) {
        if (!isMinigame(screen)) {
            return -1;
        }
        try {
            int max = hp.getInt(screen);
            return max <= 0 ? -1 : progress.getFloat(screen) / max;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return -1;
        }
    }

    /**
     * 미니게임 한 틱 분량을 둔다. 이번 틱부터 다음 틱 사이에 손잡이가 지나갈 표적이 있으면, 손잡이가 노린 지점에 오는
     * 순간(틱 사이의 시점)에 누른 것으로 친다. 눌렀으면 true.
     *
     * human: 표적 정중앙이 아니라 표적 안의 아무 지점을 노린다. missPercent: 표적마다 이 확률로 살짝 바깥을 노려 빗나간다.
     * 보물: 보물 표적을 먼저 맞히고, 보물 게이지가 덜 찼는데 물고기 게이지가 거의 찼으면 일반 표적은 건너뛴다
     * (물고기 게이지가 먼저 다 차면 미니게임이 끝나서 보물을 못 받는다).
     */
    public static boolean playMinigameTick(Screen screen, boolean human, int missPercent) {
        if (!isMinigame(screen)) {
            return false;
        }
        try {
            if (screen != aimScreen) {
                aimScreen = screen;
                aims.clear();
            }
            float handle = handlePos.getFloat(screen);
            float speed = handleSpeed.getFloat(screen);
            int rot = currentRotation.getInt(screen);
            float delay = hitDelay.getFloat(screen);
            if (speed <= 0 || rot == 0) {
                return false;
            }
            // 보물을 노리는 중이면 물고기 게이지를 너무 빨리 채우지 않는다
            boolean chasingTreasure = treasureActive.getBoolean(screen) && treasureProgress.getInt(screen) < 100;
            float fishRatio = progress.getFloat(screen) / Math.max(1, hp.getInt(screen));
            if (!chasingTreasure || fishRatio < 0.4f) {
                holdingForTreasure = false;
            } else if (fishRatio >= 0.8f) {
                holdingForTreasure = true;
            }
            // getHandlePosPrecise() = handlePos + speed * partial * rot + hitDelay * speed * rot
            float base = handle + speed * delay * rot;
            List<?> spots = (List<?>) getActiveSweetSpots.invoke(screen);
            List<?> modifiers = (List<?>) getModifiers.invoke(screen);
            Object bestSpot = null;
            float bestT = Float.MAX_VALUE;
            boolean bestTreasure = false;
            for (Object spot : spots) {
                if (!spotCanHit.getBoolean(spot) || spotRemoved.getBoolean(spot) || !allowed(screen, modifiers, spot)) {
                    continue;
                }
                boolean treasure = isTreasure(spot);
                if (holdingForTreasure && !treasure) {
                    continue;
                }
                float pos = spotPos.getFloat(spot);
                int half = spotThickness.getInt(spot) / 2;
                float offset = aims.computeIfAbsent(spot, k -> chooseAim(half, human, treasure ? 0 : missPercent));
                float ahead = wrap((pos + offset - base) * rot); // 손잡이 진행 방향으로 노린 지점까지 남은 각도
                float t;
                if (ahead / speed < 1.0f) {
                    t = ahead / speed; // 이번 틱 안에 노린 지점을 지나감 → 그 순간에 누름
                } else if (Math.abs(offset) < half && overlaps(base, pos, half)) {
                    t = 0; // 노린 지점은 지났지만 아직 표적 안
                } else {
                    continue;
                }
                // 보물 표적이 먼저, 그다음 먼저 닿는 표적
                if (bestSpot == null || (treasure && !bestTreasure) || (treasure == bestTreasure && t < bestT)) {
                    bestT = t;
                    bestSpot = spot;
                    bestTreasure = treasure;
                }
            }
            if (bestSpot == null) {
                return false;
            }
            float t = Math.max(0, Math.min(0.999f, bestT));
            float offset = aims.getOrDefault(bestSpot, 0f);
            boolean intendedMiss = Math.abs(offset) >= spotThickness.getInt(bestSpot) / 2;
            float precise = base + speed * t * rot;
            if (!intendedMiss && !overlaps(precise, spotPos.getFloat(bestSpot), spotThickness.getInt(bestSpot) / 2)) {
                return false;
            }
            partial.setFloat(screen, t);
            inputPressed.invoke(screen);
            aims.remove(bestSpot); // 다음에 돌아올 때는 새로 노린다
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.warn("[afkfishing] Star Catcher 미니게임 자동 진행 실패", e);
            available = false;
            return false;
        }
    }

    /** 표적 중심에서 얼마나 벗어난 곳을 노릴지. 빗나가기로 했으면 표적 바로 바깥. */
    private static float chooseAim(int half, boolean human, int missPercent) {
        if (missPercent > 0 && RANDOM.nextInt(100) < missPercent) {
            float sign = RANDOM.nextBoolean() ? 1 : -1;
            return sign * (half + 1 + RANDOM.nextFloat() * 3);
        }
        if (!human || half <= 1) {
            return 0;
        }
        return (RANDOM.nextFloat() * 2 - 1) * half * 0.7f;
    }

    private static boolean isTreasure(Object spot) throws ReflectiveOperationException {
        Object behaviour = spotBehaviour.get(spot);
        return behaviour != null && behaviour.getClass().getSimpleName().contains("Treasure");
    }

    private static boolean allowed(Screen screen, List<?> modifiers, Object spot) throws ReflectiveOperationException {
        for (Object m : modifiers) {
            if (!(Boolean) canHitSpot.invoke(m, screen, spot)) {
                return false;
            }
        }
        return true;
    }

    /** FishingMinigameScreen.doDegreesOverlapWithLeeway와 같은 판정. */
    private static boolean overlaps(float a, float b, int leeway) {
        float diff = Math.abs(b - a);
        return diff < leeway || diff > 360.0f - leeway;
    }

    private static float wrap(float degrees) {
        float d = degrees % 360.0f;
        return d < 0 ? d + 360.0f : d;
    }
}
