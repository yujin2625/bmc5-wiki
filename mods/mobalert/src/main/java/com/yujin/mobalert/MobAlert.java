package com.yujin.mobalert;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.logging.LogUtils;
import com.yujin.mobalert.gui.MobAlertScreen;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;

@Mod(value = MobAlert.MODID, dist = Dist.CLIENT)
public class MobAlert {
    public static final String MODID = "mobalert";
    public static final Logger LOGGER = LogUtils.getLogger();

    public static final KeyMapping OPEN_KEY = new KeyMapping(
            "key.mobalert.open", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_N, "key.categories.mobalert");

    /** 명령어로 GUI를 열 때 채팅창이 닫힌 다음 틱에 열기 위한 플래그. */
    private static boolean openScreenNextTick;

    public MobAlert(IEventBus modBus, ModContainer container) {
        MobAlertConfig.load();

        modBus.addListener(RegisterKeyMappingsEvent.class, e -> e.register(OPEN_KEY));
        modBus.addListener(RegisterClientReloadListenersEvent.class, e ->
                e.registerReloadListener((ResourceManagerReloadListener) rm -> {
                    MobNames.reset();
                    Variants.reset();
                }));
        modBus.addListener(RegisterGuiLayersEvent.class, e -> {
            e.registerBelowAll(ResourceLocation.fromNamespaceAndPath(MODID, "world_markers"), WorldMarkers::render);
            e.registerAboveAll(ResourceLocation.fromNamespaceAndPath(MODID, "nearby_hud"), MobAlertHud::render);
        });

        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, e -> onClientTick());
        NeoForge.EVENT_BUS.addListener(RenderLevelStageEvent.class, WorldMarkers::onRenderStage);
        NeoForge.EVENT_BUS.addListener(InputEvent.InteractionKeyMappingTriggered.class, PetGuard::onInput);
        NeoForge.EVENT_BUS.addListener(RegisterClientCommandsEvent.class, e -> MobAlertCommands.register(e.getDispatcher()));
        NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.LoggingOut.class, e -> {
            MobAlertTracker.reset();
            Variants.reset();
        });

        container.registerExtensionPoint(IConfigScreenFactory.class, (mod, parent) -> new MobAlertScreen(parent));
    }

    public static void requestOpenScreen() { openScreenNextTick = true; }

    private static void onClientTick() {
        Minecraft mc = Minecraft.getInstance();
        if (openScreenNextTick && mc.screen == null) {
            openScreenNextTick = false;
            mc.setScreen(new MobAlertScreen(null));
        }
        while (OPEN_KEY.consumeClick()) {
            if (mc.screen == null) mc.setScreen(new MobAlertScreen(null));
        }
        MobAlertTracker.tick(mc);
    }
}
