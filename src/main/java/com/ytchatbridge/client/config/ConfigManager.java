package com.ytchatbridge.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class ConfigManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final ConfigManager INSTANCE = new ConfigManager();

    public static ConfigManager get() { return INSTANCE; }

    public static final class DefaultColors {
        public String owner = "#FFD54F";
        @SerializedName("moderater") // keep misspelling for compatibility
        public String moderater = "#42A5F5";
        public String member = "#66BB6A";
        public String user = "#E0E0E0";
    }

    public static final class Data {
        public String provider = "innertube"; // or "wsBridge"
        public DefaultColors defaultColors = new DefaultColors();
        public String prefix = "[YTChat]";
        public String language = "en_us";
        public int maxLineLength = 256;
        public int globalThrottleMsgPerMin = 120;
        public List<String> filters = new ArrayList<>();
        public boolean logToFile = false;
        public String wsEndpoint = "ws://localhost:8787";
    }

    private Data data = new Data();

    private ConfigManager() {}

    public synchronized void reload() {
        Path cfg = getConfigPath();
        try {
            if (Files.notExists(cfg)) {
                save();
                return;
            }
            try (Reader r = Files.newBufferedReader(cfg, StandardCharsets.UTF_8)) {
                Data loaded = GSON.fromJson(r, Data.class);
                if (loaded != null) data = loaded;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public synchronized void save() {
        Path cfg = getConfigPath();
        try {
            Files.createDirectories(cfg.getParent());
            try (Writer w = Files.newBufferedWriter(cfg, StandardCharsets.UTF_8)) {
                GSON.toJson(data, w);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public synchronized Data data() { return data; }

    private Path getConfigPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("ytchat.json");
    }
}
