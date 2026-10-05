package com.dumaru.afkfishing;

import com.dumaru.afkfishing.gui.AfkFishingScreen;
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

    public AfkFishing(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.CLIENT, AfkConfig.SPEC);
        container.registerExtensionPoint(IConfigScreenFactory.class, (c, parent) -> new AfkFishingScreen(parent));

        modBus.addListener(RegisterKeyMappingsEvent.class, e -> {
            e.register(KEY_OPEN_GUI);
            e.register(KEY_TOGGLE);
        });
        modBus.addListener(RegisterGuiLayersEvent.class, e ->
                e.registerAboveAll(ResourceLocation.fromNamespaceAndPath(MODID, "hud"), new HudOverlay()));

        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, e -> onClientTick());
        NeoForge.EVENT_BUS.addListener(MovementInputUpdateEvent.class,
                e -> FishingController.INSTANCE.mover().applyInput(e.getInput()));
        NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.LoggingOut.class,
                e -> FishingController.INSTANCE.stop("서버 접속 종료"));
    }

    private static void onClientTick() {
        Minecraft mc = Minecraft.getInstance();
        while (KEY_OPEN_GUI.consumeClick()) {
            if (mc.screen == null) {
                mc.setScreen(new AfkFishingScreen(null));
            }
        }
        while (KEY_TOGGLE.consumeClick()) {
            FishingController.INSTANCE.toggle();
        }
        FishingController.INSTANCE.tick(mc);
    }
}
