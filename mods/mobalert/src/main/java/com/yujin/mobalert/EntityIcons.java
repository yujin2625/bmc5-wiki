package com.yujin.mobalert;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** GUI 아이콘 자리에 생물의 실제 3D 모델을 그린다. 그릴 수 없으면 스폰 알로 대체한다. */
public final class EntityIcons {
    private static final float BODY_YAW = 150f;   // 살짝 옆을 보는 3/4 각도
    private static final float PITCH_DEG = 10f;

    private static final Map<EntityType<?>, LivingEntity> PREVIEWS = new HashMap<>();
    private static final Set<EntityType<?>> FAILED = new HashSet<>();
    @Nullable private static Level previewLevel;

    private EntityIcons() {}

    /**
     * (x, y)부터 size×size 영역 안에 아이콘을 그린다.
     * @param live 실제 월드의 엔티티가 있으면 그 모습(새끼, 색 변형 등)을 그대로 그린다.
     */
    public static void render(GuiGraphics g, @Nullable EntityType<?> type, @Nullable Entity live, int x, int y, int size) {
        if (type != null && !FAILED.contains(type)) {
            LivingEntity entity = live instanceof LivingEntity le ? le : preview(type);
            if (entity != null && renderModel(g, entity, x, y, size)) return;
        }
        if (type != null) {
            ItemStack egg = spawnEgg(type);
            if (!egg.isEmpty()) {
                g.pose().pushPose();
                g.pose().translate(x, y, 0);
                g.pose().scale(size / 16f, size / 16f, 1f);
                g.renderFakeItem(egg, 0, 0);
                g.pose().popPose();
            }
        }
    }

    public static ItemStack spawnEgg(EntityType<?> type) {
        SpawnEggItem egg = SpawnEggItem.byId(type);
        return egg != null ? new ItemStack(egg) : ItemStack.EMPTY;
    }

    @Nullable
    public static LivingEntity preview(EntityType<?> type) {
        Level level = Minecraft.getInstance().level;
        if (level == null) return null; // 메인 메뉴 등 월드 밖에서는 엔티티를 만들 수 없다
        if (level != previewLevel) {
            PREVIEWS.clear();
            previewLevel = level;
        }
        if (PREVIEWS.containsKey(type)) return PREVIEWS.get(type);
        LivingEntity result = null;
        try {
            if (type.create(level) instanceof LivingEntity le) result = le;
        } catch (Throwable t) {
            MobAlert.LOGGER.debug("Cannot create preview entity for {}", type, t);
            FAILED.add(type);
        }
        PREVIEWS.put(type, result);
        return result;
    }

    /** InventoryScreen.renderEntityInInventory 와 같은 방식이지만, 회전을 고정하고 실패해도 화면 상태를 복구한다. */
    private static boolean renderModel(GuiGraphics g, LivingEntity e, int x, int y, int size) {
        float w = e.getBbWidth(), h = e.getBbHeight();
        float extent = Math.max(Math.max(w, h), 0.3f);
        float scale = size / extent * 0.95f;

        float bodyRot = e.yBodyRot, bodyRotO = e.yBodyRotO, yRot = e.getYRot(), yRotO = e.yRotO;
        float xRot = e.getXRot(), xRotO = e.xRotO, headRot = e.yHeadRot, headRotO = e.yHeadRotO;
        e.yBodyRot = e.yBodyRotO = BODY_YAW;
        e.setYRot(BODY_YAW);
        e.yRotO = BODY_YAW;
        e.setXRot(0);
        e.xRotO = 0;
        e.yHeadRot = e.yHeadRotO = BODY_YAW;

        Quaternionf pitch = new Quaternionf().rotateX(PITCH_DEG * (float) (Math.PI / 180.0));
        Quaternionf pose = new Quaternionf().rotateZ((float) Math.PI).mul(pitch);
        EntityRenderDispatcher dispatcher = Minecraft.getInstance().getEntityRenderDispatcher();

        g.pose().pushPose();
        boolean ok = true;
        try {
            g.pose().translate(x + size / 2f, y + size / 2f, 100.0);
            g.pose().scale(scale, scale, -scale);
            g.pose().translate(0, h / 2f, 0);
            g.pose().mulPose(pose);
            Lighting.setupForEntityInInventory();
            dispatcher.overrideCameraOrientation(pitch.conjugate(new Quaternionf()).rotateY((float) Math.PI));
            dispatcher.setRenderShadow(false);
            RenderSystem.runAsFancy(() ->
                    dispatcher.render(e, 0, 0, 0, 0f, 1f, g.pose(), g.bufferSource(), 0xF000F0));
            g.flush();
        } catch (Throwable t) {
            MobAlert.LOGGER.warn("Failed to render icon for {}, falling back to spawn egg", e.getType(), t);
            FAILED.add(e.getType());
            ok = false;
        } finally {
            dispatcher.setRenderShadow(true);
            g.pose().popPose();
            Lighting.setupFor3DItems();
            e.yBodyRot = bodyRot;
            e.yBodyRotO = bodyRotO;
            e.setYRot(yRot);
            e.yRotO = yRotO;
            e.setXRot(xRot);
            e.xRotO = xRotO;
            e.yHeadRot = headRot;
            e.yHeadRotO = headRotO;
        }
        return ok;
    }
}
