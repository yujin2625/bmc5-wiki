package com.yujin.mobalert;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;

public class MobAlertToast implements Toast {
    private static final ResourceLocation BACKGROUND = ResourceLocation.withDefaultNamespace("toast/recipe");
    private static final long DISPLAY_MS = 5000L;

    private final EntityType<?> type;
    private final Entity entity;
    private final Component title;
    private final Component detail;

    public MobAlertToast(EntityType<?> type, Entity entity, Component name, int count, int distance, Component dir) {
        this.type = type;
        this.entity = entity;
        this.title = count > 1
                ? Component.translatable("mobalert.toast.title_many", name, count)
                : Component.translatable("mobalert.toast.title", name);
        this.detail = Component.translatable("mobalert.toast.detail", distance, dir);
    }

    @Override
    public Visibility render(GuiGraphics g, ToastComponent toasts, long timeSinceVisible) {
        Font font = toasts.getMinecraft().font;
        g.blitSprite(BACKGROUND, 0, 0, width(), height());
        int textX = 30;
        EntityIcons.render(g, type, entity, 5, 6, 20);
        g.drawString(font, title, textX, 7, 0xFFFFD700, false);
        g.drawString(font, detail, textX, 18, 0xFFFFFFFF, false);
        return timeSinceVisible >= DISPLAY_MS * toasts.getNotificationDisplayTimeMultiplier()
                ? Visibility.HIDE : Visibility.SHOW;
    }
}
