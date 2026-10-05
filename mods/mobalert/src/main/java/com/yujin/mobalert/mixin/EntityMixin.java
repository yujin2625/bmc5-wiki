package com.yujin.mobalert.mixin;

import com.yujin.mobalert.MobAlertConfig;
import com.yujin.mobalert.MobAlertTracker;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 강조 중인 엔티티의 외곽선 색을 설정한 색으로 바꾼다. */
@Mixin(Entity.class)
public abstract class EntityMixin {
    @Inject(method = "getTeamColor", at = @At("HEAD"), cancellable = true)
    private void mobalert$glowColor(CallbackInfoReturnable<Integer> cir) {
        if (MobAlertTracker.isHighlighted((Entity) (Object) this)) {
            cir.setReturnValue(MobAlertConfig.get().glowColor.rgb);
        }
    }
}
