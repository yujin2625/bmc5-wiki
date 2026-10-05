package com.yujin.mobalert.mixin;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Entity.class)
public interface EntityAccessor {
    /** 커스텀 이름(이름표)과 무관한 종류 이름. 모드 생물은 여기서 품종/변종 이름을 돌려주기도 한다. */
    @Invoker("getTypeName")
    Component mobalert$getTypeName();
}
