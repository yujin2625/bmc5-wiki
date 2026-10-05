package com.dumaru.afkfishing;

import com.dumaru.afkfishing.common.Look;
import com.dumaru.afkfishing.common.Navigator;
import com.dumaru.afkfishing.farm.FarmController;
import com.dumaru.afkfishing.gui.AfkScreen;
import com.dumaru.afkfishing.gui.AreaRenderer;
import com.dumaru.afkfishing.gui.HudOverlay;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;

// 클라이언트 전용: 레지스트리/네트워크 채널을 등록하지 않으므로 이 모드가 없는 서버에도 접속 가능하다.
@Mod(value = AfkFishing.MODID, dist = Dist.CLIENT)
public class AfkFishing {
    public static final String MODID = "afkfishing";

    public static final KeyMapping KEY_OPEN_GUI = new KeyMapping(
            "key.afkfishing.open_gui", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_J, "key.categories.afkfishing");
    public static final KeyMapping KEY_TOGGLE = new KeyMapping(
            "key.afkfishing.toggle", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F8, "key.categories.afkfishing");
    public static final KeyMapping KEY_FARM_TOGGLE = new KeyMapping(
            "key.afkfishing.farm_toggle", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F9, "key.categories.afkfishing");

    public AfkFishing(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.CLIENT, AfkConfig.SPEC);
        container.registerExtensionPoint(IConfigScreenFactory.class, (c, parent) -> new AfkScreen(parent));

        modBus.addListener(RegisterKeyMappingsEvent.class, e -> {
            e.register(KEY_OPEN_GUI);
            e.register(KEY_TOGGLE);
            e.register(KEY_FARM_TOGGLE);
        });
        modBus.addListener(RegisterGuiLayersEvent.class, e ->
                e.registerAboveAll(ResourceLocation.fromNamespaceAndPath(MODID, "hud"), new HudOverlay()));

        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, e -> onClientTick());
        NeoForge.EVENT_BUS.addListener(MovementInputUpdateEvent.class, e -> {
            // 길찾기 이동이 우선. 아니면 AFK 방지 이동 (낚시·농사 각자 것).
            if (Navigator.INSTANCE.isActive()) {
                Navigator.INSTANCE.applyInput(e.getInput(), Minecraft.getInstance().player);
            } else {
                FishingController.INSTANCE.mover().applyInput(e.getInput());
                FarmController.INSTANCE.mover().applyInput(e.getInput());
            }
        });
        NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.LoggingOut.class, e -> {
            FishingController.INSTANCE.stop("서버 접속 종료");
            FarmController.INSTANCE.onDisconnect();
            Look.INSTANCE.release();
        });
        NeoForge.EVENT_BUS.addListener(RenderLevelStageEvent.class, AreaRenderer::render);
        // 시선 회전은 프레임마다 조금씩 (틱마다 꺾으면 뚝뚝 끊겨 보인다)
        NeoForge.EVENT_BUS.addListener(RenderFrameEvent.Pre.class, e -> Look.INSTANCE.update());
    }

    private static void onClientTick() {
        Minecraft mc = Minecraft.getInstance();
        Look.INSTANCE.update(); // 창이 최소화돼 프레임이 안 돌 때도 돌아가도록
        while (KEY_OPEN_GUI.consumeClick()) {
            if (mc.screen == null) {
                mc.setScreen(new AfkScreen(null));
            }
        }
        while (KEY_TOGGLE.consumeClick()) {
            FishingController.INSTANCE.toggle();
        }
        while (KEY_FARM_TOGGLE.consumeClick()) {
            FarmController.INSTANCE.toggle();
        }
        FarmController.INSTANCE.tick(mc);
        FishingController.INSTANCE.tick(mc);
    }
}
