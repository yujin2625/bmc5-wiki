package com.dumaru.afkfishing.mixin;

import com.dumaru.afkfishing.FishingController;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientLevel.class)
public class ClientLevelMixin {
    // 나가기 버튼/게임 종료 시 연결이 끊기기 직전. 찌가 남아 있으면 서버가 낚싯바늘을 돌려주지 않고 찌를 지우므로 먼저 회수한다.
    @Inject(method = "disconnect", at = @At("HEAD"))
    private void afkfishing$reelBeforeDisconnect(CallbackInfo ci) {
        FishingController.INSTANCE.reelInBeforeDisconnect();
    }
}
