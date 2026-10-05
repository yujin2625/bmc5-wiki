package com.yujin.mobalert.mixin;

import com.yujin.mobalert.MobAlertTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 감시 대상 엔티티에 (클라이언트에서만) 발광 외곽선을 켠다. 벽 너머로도 보인다. */
@Mixin(Minecraft.class)
public class MinecraftMixin {
    @Inject(method = "shouldEntityAppearGlowing", at = @At("HEAD"), cancellable = true)
    private void mobalert$forceGlow(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (MobAlertTracker.isHighlighted(entity)) cir.setReturnValue(true);
    }
}
