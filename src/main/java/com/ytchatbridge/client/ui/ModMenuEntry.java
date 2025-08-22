package com.ytchatbridge.client.ui;

import com.ytchatbridge.client.commands.YTClientCommands;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

// Defensive ModMenu integration: only implements API if ModMenu classes are present.
// Avoid hard compile dependency by using reflection fallback if class not found at runtime.
public class ModMenuEntry implements com.terraformersmc.modmenu.api.ModMenuApi {
    @Override
    public com.terraformersmc.modmenu.api.ConfigScreenFactory<?> getModConfigScreenFactory() {
        return parent -> {
            // If cloth-config missing, just show a tiny info screen.
            if (!FabricLoader.getInstance().isModLoaded("cloth-config")) {
                return new net.minecraft.client.gui.screen.ConfirmScreen(
                        (accepted) -> MinecraftClient.getInstance().setScreen(parent),
                        Text.literal("YTChat Config"),
                        Text.literal("Cloth Config not installed."));
            }
            // Reuse builder from command utility
            try {
                java.lang.reflect.Method m = YTClientCommands.class.getDeclaredMethod("buildConfigScreen", Screen.class);
                m.setAccessible(true);
                return (Screen) m.invoke(null, parent);
            } catch (Exception e) {
                return new net.minecraft.client.gui.screen.ConfirmScreen(
                        (accepted) -> MinecraftClient.getInstance().setScreen(parent),
                        Text.literal("YTChat Error"),
                        Text.literal("Failed to open config: " + e.getClass().getSimpleName()));
            }
        };
    }
}
