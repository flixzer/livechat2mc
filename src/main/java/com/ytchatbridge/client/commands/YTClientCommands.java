package com.ytchatbridge.client.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

import com.ytchatbridge.client.config.ConfigManager;
import com.ytchatbridge.client.service.BridgeServiceClient;

import java.util.Locale;
import java.util.concurrent.CompletableFuture;

public final class YTClientCommands {
    // Deferred screen open to avoid being overwritten when chat GUI closes after command runs
    private static Screen PENDING_SCREEN;
    static {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (PENDING_SCREEN != null && client.currentScreen == null) {
                client.setScreen(PENDING_SCREEN);
                PENDING_SCREEN = null;
            }
        });
    }
    public static void register(CommandDispatcher<FabricClientCommandSource> d) {
        var root = ClientCommandManager.literal("ytchat");

        // /ytchat start <video_or_url>
        root.then(ClientCommandManager.literal("start")
            .then(ClientCommandManager.argument("video_or_url", StringArgumentType.greedyString())
                .suggests(YTClientCommands::suggestRecent)
                .executes(ctx -> {
                    String v = StringArgumentType.getString(ctx, "video_or_url");
                    BridgeServiceClient.get().startSession(v);
                    feedback(ctx, "Started: " + v);
                    return 1;
                }))
        );

        // /ytchat stop
        root.then(ClientCommandManager.literal("stop").executes(ctx -> {
            BridgeServiceClient.get().stopSession();
            // Service already prints "[YTChat] stopped"; avoid duplicate.
            return 1;
        }));

        // /ytchat status
        root.then(ClientCommandManager.literal("status").executes(ctx -> {
            var st = BridgeServiceClient.get().getStatus();
            feedback(ctx, "Provider=" + st.providerName + " running=" + st.running + " mpm=" + st.msgsPerMin + " lag=" + st.lastLagMs + "ms err=" + st.lastError);
            return 1;
        }));

        // /ytchat setcolor <role> <#hex>
        root.then(ClientCommandManager.literal("setcolor")
            .then(ClientCommandManager.argument("role", StringArgumentType.word())
                .suggests((c,b)->suggestRoles(b))
                .then(ClientCommandManager.argument("hex", StringArgumentType.word())
                    .suggests((c,b)->suggestHex(b))
                    .executes(ctx -> {
                        String role = StringArgumentType.getString(ctx, "role").toLowerCase(Locale.ROOT);
                        String hex = StringArgumentType.getString(ctx, "hex");
                        if (!hex.matches("#(?i)[0-9a-f]{6}")) { feedback(ctx, "Invalid hex"); return 0; }
                        var data = ConfigManager.get().data();
                        switch (role) {
                            case "owner" -> data.defaultColors.owner = hex;
                            case "moderator" -> data.defaultColors.moderater = hex;
                            case "member" -> data.defaultColors.member = hex;
                            case "user" -> data.defaultColors.user = hex;
                            default -> { feedback(ctx, "Unknown role"); return 0; }
                        }
                        ConfigManager.get().save();
                        feedback(ctx, "Color updated");
                        return 1;
                    }))))
        ;

        // /ytchat setprefix <text>
        root.then(ClientCommandManager.literal("setprefix")
            .then(ClientCommandManager.argument("text", StringArgumentType.greedyString())
                .executes(ctx -> {
                    String t = StringArgumentType.getString(ctx, "text");
                    ConfigManager.get().data().prefix = t;
                    ConfigManager.get().save();
                    feedback(ctx, "Prefix set");
                    return 1;
                }))
        );

        // /ytchat throttle <msgsPerMin>
        root.then(ClientCommandManager.literal("throttle")
            .then(ClientCommandManager.argument("rate", IntegerArgumentType.integer(1, 5000))
                .executes(ctx -> {
                    int r = IntegerArgumentType.getInteger(ctx, "rate");
                    ConfigManager.get().data().globalThrottleMsgPerMin = r;
                    ConfigManager.get().save();
                    feedback(ctx, "Throttle set to " + r + " msg/min");
                    return 1;
                }))
        );

        // /ytchat config  (open GUI without needing ModMenu)
        root.then(ClientCommandManager.literal("config").executes(ctx -> {
            if (!FabricLoader.getInstance().isModLoaded("cloth-config")) { feedback(ctx, "Cloth Config not loaded"); return 0; }
            try {
                Screen current = MinecraftClient.getInstance().currentScreen;
                PENDING_SCREEN = buildConfigScreen(current);
                feedback(ctx, "Opened config");
                return 1;
            } catch (Throwable t) {
                feedback(ctx, "Config error: " + t.getClass().getSimpleName());
                return 0;
            }
        }));

        // /ytchat filter add <regex> | remove <id> | list
        var filter = ClientCommandManager.literal("filter");
        filter.then(ClientCommandManager.literal("add")
            .then(ClientCommandManager.argument("regex", StringArgumentType.greedyString())
                .executes(ctx -> {
                    String re = StringArgumentType.getString(ctx, "regex");
                    var data = ConfigManager.get().data();
                    data.filters.add(re);
                    ConfigManager.get().save();
                    com.ytchatbridge.client.service.BridgeServiceClient.get().rebuildFilters();
                    feedback(ctx, "Filter added (#" + (data.filters.size()-1) + ")");
                    return 1;
                }))
        );
        filter.then(ClientCommandManager.literal("remove")
            .then(ClientCommandManager.argument("id", IntegerArgumentType.integer(0))
                .suggests((c,b)->suggestFilterIds(b))
                .executes(ctx -> {
                    int id = IntegerArgumentType.getInteger(ctx, "id");
                    var data = ConfigManager.get().data();
                    if (id < 0 || id >= data.filters.size()) { feedback(ctx, "Invalid id"); return 0; }
                    data.filters.remove(id);
                    ConfigManager.get().save();
                    com.ytchatbridge.client.service.BridgeServiceClient.get().rebuildFilters();
                    feedback(ctx, "Filter removed");
                    return 1;
                }))
        );
        filter.then(ClientCommandManager.literal("list").executes(ctx -> {
            var list = ConfigManager.get().data().filters;
            if (list.isEmpty()) feedback(ctx, "No filters");
            else {
                for (int i=0;i<list.size();i++) {
                    MinecraftClient.getInstance().inGameHud.getChatHud().addMessage(Text.literal("[#"+i+"] " + list.get(i)));
                }
            }
            return 1;
        }));
        root.then(filter);

        d.register(root);
    }

    private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestRoles(SuggestionsBuilder b) {
        for (String r : new String[]{"owner","moderator","member","user"}) b.suggest(r);
        return b.buildFuture();
    }
    private static CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestHex(SuggestionsBuilder b) {
        for (String h : new String[]{"#FFD54F","#42A5F5","#66BB6A","#E0E0E0"}) b.suggest(h);
        return b.buildFuture();
    }
    private static CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestRecent(CommandContext<?> ctx, SuggestionsBuilder b) {
        for (String id : BridgeServiceClient.get().recentVideos()) b.suggest(id);
        return b.buildFuture();
    }
    private static CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestFilterIds(SuggestionsBuilder b) {
        var list = ConfigManager.get().data().filters;
        for (int i=0;i<list.size();i++) b.suggest(Integer.toString(i));
        return b.buildFuture();
    }

    private static void feedback(CommandContext<?> ctx, String s) {
        MinecraftClient.getInstance().inGameHud.getChatHud().addMessage(Text.literal("[YTChat] " + s));
    }

    // Build configuration screen (mirrors old ModMenu screen) – only call when cloth-config is present
    private static Screen buildConfigScreen(Screen parent) {
    var cfg = ConfigManager.get().data();
    var builder = me.shedaniel.clothconfig2.api.ConfigBuilder.create()
        .setParentScreen(parent)
        .setTitle(Text.translatable("livechat2mc.config.title"));
    var eb = builder.entryBuilder();
    var general = builder.getOrCreateCategory(Text.literal("General"));

    // Provider locked to innertube; selector removed.
    general.addEntry(eb.startStrField(Text.translatable("livechat2mc.config.prefix"), cfg.prefix)
        .setTooltip(Text.literal("Use & color codes e.g. &a[YTChat]&r"))
        .setSaveConsumer(v -> cfg.prefix = v)
        .build());

    general.addEntry(eb.startIntField(Text.translatable("livechat2mc.config.throttle"), cfg.globalThrottleMsgPerMin)
        .setMin(1).setMax(5000)
        .setSaveConsumer(v -> cfg.globalThrottleMsgPerMin = v)
        .build());

    var colors = builder.getOrCreateCategory(Text.translatable("livechat2mc.config.colors"));
    colors.addEntry(eb.startStrField(Text.translatable("livechat2mc.config.color.owner"), cfg.defaultColors.owner)
        .setSaveConsumer(v -> cfg.defaultColors.owner = v).build());
    colors.addEntry(eb.startStrField(Text.translatable("livechat2mc.config.color.moderator"), cfg.defaultColors.moderater)
        .setSaveConsumer(v -> cfg.defaultColors.moderater = v).build());
    colors.addEntry(eb.startStrField(Text.translatable("livechat2mc.config.color.member"), cfg.defaultColors.member)
        .setSaveConsumer(v -> cfg.defaultColors.member = v).build());
    colors.addEntry(eb.startStrField(Text.translatable("livechat2mc.config.color.user"), cfg.defaultColors.user)
        .setSaveConsumer(v -> cfg.defaultColors.user = v).build());

    var filtersCsv = String.join(",", cfg.filters);
    colors.addEntry(eb.startStrField(Text.translatable("livechat2mc.config.filters"), filtersCsv)
        .setTooltip(Text.literal("Comma-separated regex list"))
        .setSaveConsumer(v -> {
            cfg.filters.clear();
            if (v != null && !v.isBlank()) {
            for (String s : v.split(",")) cfg.filters.add(s.trim());
            }
        }).build());

    builder.setSavingRunnable(() -> {
        ConfigManager.get().save();
        BridgeServiceClient.get().rebuildFilters();
    });
    return builder.build();
    }
}
