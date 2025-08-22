package com.ytchatbridge.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.loader.api.FabricLoader;

import com.ytchatbridge.client.commands.YTClientCommands;
import com.ytchatbridge.client.config.ConfigManager;
import com.ytchatbridge.client.service.BridgeServiceClient;

public class YTChatBridgeClient implements ClientModInitializer {
    public static final String MOD_ID = "livechat2mc";

    @Override
    public void onInitializeClient() {
        // Load or create config
        ConfigManager.get().reload();

        // Initialize bridge service (client only)
        BridgeServiceClient.get();

        // Register client commands
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            YTClientCommands.register(dispatcher);
        });

        boolean hasModMenu = FabricLoader.getInstance().isModLoaded("modmenu");
        boolean hasCloth = FabricLoader.getInstance().isModLoaded("cloth-config");
        System.out.println("[YTChat] ModMenu=" + hasModMenu + " ClothConfig=" + hasCloth);
    }
}
