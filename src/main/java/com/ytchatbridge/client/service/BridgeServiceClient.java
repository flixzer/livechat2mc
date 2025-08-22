package com.ytchatbridge.client.service;

import com.ytchatbridge.client.bridge.ChatProvider;
import com.ytchatbridge.client.bridge.ProviderStatus;
import com.ytchatbridge.client.bridge.innertube.InnertubeProvider;
import com.ytchatbridge.client.config.ConfigManager;
import com.ytchatbridge.client.model.ChatMessage;

import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.text.Style;
import net.minecraft.text.TextColor;
import net.minecraft.util.Formatting;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.regex.Pattern;

public final class BridgeServiceClient implements ChatProvider.Listener {
    private static final BridgeServiceClient INSTANCE = new BridgeServiceClient();
    public static BridgeServiceClient get() { return INSTANCE; }

    private final ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "YTChat-Bridge"); t.setDaemon(true); return t;
    });

    private final ArrayDeque<String> recentVideoIds = new ArrayDeque<>(20);
    private final List<Pattern> compiledFilters = new ArrayList<>();
    private volatile ChatProvider provider;
    private volatile int tokens;
    private volatile long bucketTs = System.currentTimeMillis();
    private volatile long sessionStartTs = 0L; // to filter old backlog messages

    private BridgeServiceClient() {
        rebuildFilters();
        exec.scheduleAtFixedRate(this::refill, 1, 1, TimeUnit.SECONDS);
    }

    private void refill() {
        int perMin = Math.max(1, ConfigManager.get().data().globalThrottleMsgPerMin);
        int perSec = Math.max(1, perMin / 60);
        tokens = Math.min(tokens + perSec, perMin);
    }

    public synchronized void startSession(String videoOrUrl) {
        stopSession();
    // Provider locked to innertube for now (wsBridge disabled)
    provider = new InnertubeProvider();
        provider.setListener(this);
        provider.start(videoOrUrl);
    sessionStartTs = System.currentTimeMillis();
        String vid = extractId(videoOrUrl);
        if (!vid.isEmpty()) {
            if (recentVideoIds.contains(vid)) recentVideoIds.remove(vid);
            recentVideoIds.addFirst(vid);
            while (recentVideoIds.size() > 20) recentVideoIds.removeLast();
        }
    infoToHud("[YTChat] started " + vid);
    }

    public synchronized void stopSession() {
    if (provider != null) { provider.stop(); provider = null; infoToHud("[YTChat] stopped"); }
    }

    public ProviderStatus getStatus() {
        return provider == null ? new ProviderStatus("none", false, "", 0, 0, "") : provider.status();
    }

    public List<String> recentVideos() { return new ArrayList<>(recentVideoIds); }

    @Override
    public void onMessage(ChatMessage msg) {
    // Skip backlog: only show messages timestamped after session start (allow small clock skew)
    if (sessionStartTs > 0 && msg.timestamp + 5000 < sessionStartTs) return;
        if (!permit()) return;
        if (isFiltered(msg.message)) return;
        String sanitized = sanitize(msg.message);
        if (sanitized.isEmpty()) return;
        var cfg = ConfigManager.get().data();

    String prefixRaw = cfg.prefix == null ? "[YTChat]" : cfg.prefix;
    // Support color codes in prefix: &<0-9a-fk-or>
    Text prefixText = parseColoredPrefix(prefixRaw + " ");

        int rgb = colorFor(msg.role, cfg);
        Text name = Text.literal(msg.displayName).setStyle(Style.EMPTY.withColor(TextColor.fromRgb(rgb)));
        Text colon = Text.literal(": ").formatted(Formatting.GRAY);
        String body = sanitized.length() > cfg.maxLineLength ? sanitized.substring(0, cfg.maxLineLength) + "…" : sanitized;
        Text message = Text.literal(body);

        Text finalText = Text.empty().append(prefixText).append(name).append(colon).append(message);
        MinecraftClient.getInstance().inGameHud.getChatHud().addMessage(finalText);
    }

    @Override public void onInfo(String info) { infoToHud("[YTChat] " + info); }
    @Override public void onError(String error) { infoToHud("[YTChat][Error] " + error); }

    private void infoToHud(String s) {
        MinecraftClient.getInstance().inGameHud.getChatHud().addMessage(Text.literal(s).formatted(Formatting.DARK_GRAY));
    }

    private boolean permit() {
        int perMin = Math.max(1, ConfigManager.get().data().globalThrottleMsgPerMin);
        long now = System.currentTimeMillis();
        if (now - bucketTs > 60_000) { tokens = perMin; bucketTs = now; }
        if (tokens <= 0) return false;
        tokens--;
        return true;
    }

    private boolean isFiltered(String s) {
        for (Pattern p : compiledFilters) if (p.matcher(s).find()) return true;
        return false;
    }

    public void rebuildFilters() {
        compiledFilters.clear();
        for (String f : ConfigManager.get().data().filters) {
            try { compiledFilters.add(Pattern.compile(f)); } catch (Exception ignored) {}
        }
    }

    private static int colorFor(ChatMessage.Role role, ConfigManager.Data cfg) {
        try {
            String hex = switch (role) {
                case OWNER -> cfg.defaultColors.owner;
                case MODERATOR -> cfg.defaultColors.moderater;
                case MEMBER -> cfg.defaultColors.member;
                default -> cfg.defaultColors.user;
            };
            if (hex != null && hex.startsWith("#")) {
                return Integer.parseInt(hex.substring(1), 16);
            }
        } catch (Exception ignored) {}
        return 0xE0E0E0;
    }

    private static String sanitize(String s) {
        return s == null ? "" : s.replaceAll("§.", "").replaceAll("\\p{Cntrl}", "").trim();
    }

    private static net.minecraft.text.MutableText parseColoredPrefix(String raw) {
        net.minecraft.text.MutableText out = Text.empty();
        Formatting current = Formatting.GRAY;
        StringBuilder acc = new StringBuilder();
        for (int i=0;i<raw.length();i++) {
            char c = raw.charAt(i);
            if (c == '&' && i+1 < raw.length()) {
                // flush existing
                if (acc.length()>0) { out.append(Text.literal(acc.toString()).formatted(current)); acc.setLength(0); }
                char code = Character.toLowerCase(raw.charAt(++i));
                Formatting f = formattingFromCode(code);
                if (f != null) current = f; else acc.append('&').append(code); // unknown -> keep literal
            } else acc.append(c);
        }
        if (acc.length()>0) out.append(Text.literal(acc.toString()).formatted(current));
        return out;
    }
    private static Formatting formattingFromCode(char c) {
        return switch (c) {
            case '0' -> Formatting.BLACK;
            case '1' -> Formatting.DARK_BLUE;
            case '2' -> Formatting.DARK_GREEN;
            case '3' -> Formatting.DARK_AQUA;
            case '4' -> Formatting.DARK_RED;
            case '5' -> Formatting.DARK_PURPLE;
            case '6' -> Formatting.GOLD;
            case '7' -> Formatting.GRAY;
            case '8' -> Formatting.DARK_GRAY;
            case '9' -> Formatting.BLUE;
            case 'a' -> Formatting.GREEN;
            case 'b' -> Formatting.AQUA;
            case 'c' -> Formatting.RED;
            case 'd' -> Formatting.LIGHT_PURPLE;
            case 'e' -> Formatting.YELLOW;
            case 'f' -> Formatting.WHITE;
            case 'r' -> Formatting.RESET;
            default -> null;
        };
    }

    private static String extractId(String v) {
        if (v == null) return "";
        if (v.contains("v=")) {
            int i = v.indexOf("v=");
            String id = v.substring(i + 2);
            int amp = id.indexOf('&');
            return amp > 0 ? id.substring(0, amp) : id;
        }
        if (v.contains("youtu.be/")) {
            String id = v.substring(v.indexOf("youtu.be/") + 9);
            int q = id.indexOf('?');
            return q > 0 ? id.substring(0, q) : id;
        }
        return v;
    }
}
