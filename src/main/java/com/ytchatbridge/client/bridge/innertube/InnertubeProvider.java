package com.ytchatbridge.client.bridge.innertube;

import com.google.gson.*;
import com.ytchatbridge.client.bridge.ChatProvider;
import com.ytchatbridge.client.bridge.ProviderStatus;
import com.ytchatbridge.client.model.ChatMessage;

import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class InnertubeProvider implements ChatProvider {
    private final ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "YTChat-Innertube");
        t.setDaemon(true);
        return t;
    });
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private volatile Listener listener;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile String continuation = null;
    private volatile String apiKey = null;
    private volatile JsonObject context = null;
    private volatile String channelTitle = "";
    private volatile String lastError = "";
    private volatile long lastLagMs = 0;
    private volatile int msgsInWindow = 0;
    private volatile long windowStart = System.currentTimeMillis();
    private volatile int consecutiveErrors = 0;
    private volatile String lastVideoId = ""; // keep for potential re-bootstrap
    private volatile long startUsec = 0L; // timestampUsec boundary to filter backlog externally
    private volatile int emptyCycles = 0; // consecutive polls with no continuation+no actions
    private static final int EMPTY_CYCLE_END_THRESHOLD = 3;

    private static final Pattern API_KEY_RE = Pattern.compile("\\\"INNERTUBE_API_KEY\\\":\\\"(.*?)\\\"");
    // Old CTX regex caused truncated nested JSON (balanced braces not handled) leading to EOF parse errors.
    // Keep pattern constant commented for reference.
    // private static final Pattern CTX_RE = Pattern.compile("\\\"INNERTUBE_CONTEXT\\\":(\\{.*?\\})[,}]");
    private static final Pattern CONT_RE = Pattern.compile("\\\"continuation\\\":\\\"(.*?)\\\"");

    @Override
    public void start(String videoIdOrUrl) {
        if (running.getAndSet(true)) return;
        lastError = "";
        String vid = normalizeVideoId(videoIdOrUrl);
    lastVideoId = vid;
    startUsec = System.currentTimeMillis() * 1000L;
        exec.execute(() -> initialFetch(vid));
    }

    @Override
    public void stop() {
        running.set(false);
        continuation = null;
        apiKey = null;
        context = null;
    emptyCycles = 0;
    }

    @Override
    public ProviderStatus status() {
        int mpm;
        long now = System.currentTimeMillis();
        synchronized (this) {
            if (now - windowStart > 60_000) { windowStart = now; msgsInWindow = 0; }
            mpm = msgsInWindow;
        }
        return new ProviderStatus("innertube", running.get(), channelTitle, mpm, lastLagMs, lastError);
    }

    @Override
    public void setListener(Listener listener) { this.listener = listener; }

    private void initialFetch(String videoId) {
        try {
            String watchUrl = "https://www.youtube.com/watch?v=" + videoId + "&bp=wgUCEAE%3D";
            HttpRequest req = HttpRequest.newBuilder(URI.create(watchUrl))
                    .GET()
                    .timeout(Duration.ofSeconds(15))
                    .header("User-Agent", ua())
                    .build();
            long t0 = System.currentTimeMillis();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            lastLagMs = System.currentTimeMillis() - t0;
            String html = resp.body();

            Matcher mKey = API_KEY_RE.matcher(html);
            Matcher mCont = CONT_RE.matcher(html);
            if (!mKey.find() || !mCont.find()) {
                fail("Failed to parse watch page for apiKey/continuation" );
                return;
            }
            apiKey = mKey.group(1);
            continuation = mCont.group(1);

            String ctxJson = extractInnertubeContext(html);
            if (ctxJson == null) {
                fail("Failed to locate INNERTUBE_CONTEXT JSON");
                return;
            }
            try {
                context = JsonParser.parseString(unescapeJson(ctxJson)).getAsJsonObject();
            } catch (JsonParseException ex) {
                fail("Context parse error: " + shortMsg(ex.getMessage()));
                return;
            }
            info("Server Started");
            schedulePoll(0);
        } catch (Exception e) {
            fail(e.getMessage());
        }
    }

    private void schedulePoll(long delayMs) {
        if (!running.get()) return;
        exec.schedule(this::pollOnce, Math.max(0, delayMs), TimeUnit.MILLISECONDS);
    }

    private void pollOnce() {
        if (!running.get() || apiKey == null || continuation == null || context == null) return;
        try {
            JsonObject payload = new JsonObject();
            payload.add("context", context.deepCopy());
            payload.addProperty("continuation", continuation);

            HttpRequest req = HttpRequest.newBuilder(URI.create("https://www.youtube.com/youtubei/v1/live_chat/get_live_chat?key=" + apiKey))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .header("User-Agent", ua())
                    .POST(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8))
                    .build();
            long t0 = System.currentTimeMillis();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            lastLagMs = System.currentTimeMillis() - t0;
            String bodyStr = resp.body();
            JsonObject body;
            try {
                body = JsonParser.parseString(bodyStr).getAsJsonObject();
            } catch (JsonParseException ex) {
                consecutiveErrors++;
                lastError = "parse(" + ex.getClass().getSimpleName() + "): " + shortMsg(ex.getMessage());
                // Log a small snippet once per error wave
                if (consecutiveErrors <= 3 && listener != null) {
                    listener.onError("Innertube JSON parse failed (#" + consecutiveErrors + "): " + shortMsg(ex.getMessage()) + " snippet=" + snippet(bodyStr));
                }
                // After several consecutive parse errors, attempt a re-bootstrap
                if (consecutiveErrors >= 5) {
                    info("Rebootstrapping after repeated parse errors");
                    consecutiveErrors = 0;
                    initialFetch(lastVideoId);
                } else {
                    schedulePoll(2000);
                }
                return;
            }
            consecutiveErrors = 0; // reset on success

            Long timeoutMs = 1500L;
            JsonArray actions = null;
            boolean advancedContinuation = false;
            try {
                JsonObject liveCont = body.getAsJsonObject("continuationContents")
                    .getAsJsonObject("liveChatContinuation");
                if (liveCont.has("timeoutMs")) timeoutMs = liveCont.get("timeoutMs").getAsLong();
                JsonArray conts = liveCont.getAsJsonArray("continuations");
                if (conts != null && conts.size() > 0) {
                    JsonObject c0 = conts.get(0).getAsJsonObject();
                    if (c0.has("invalidationContinuationData")) {
                        continuation = c0.getAsJsonObject("invalidationContinuationData").get("continuation").getAsString();
                        advancedContinuation = true;
                    } else if (c0.has("timedContinuationData")) {
                        continuation = c0.getAsJsonObject("timedContinuationData").get("continuation").getAsString();
                        advancedContinuation = true;
                    }
                }
                actions = liveCont.getAsJsonArray("actions");
            } catch (Exception ignore) { }

            // Detect live ended: several cycles with no continuation advance and no actions
            if (!advancedContinuation && (actions == null || actions.size() == 0)) {
                emptyCycles++;
                if (emptyCycles >= EMPTY_CYCLE_END_THRESHOLD) {
                    endStream("no more live chat updates");
                    return; // stop polling
                }
            } else {
                emptyCycles = 0;
            }

            if (actions != null) {
                for (JsonElement el : actions) {
                    try {
                        JsonObject a = el.getAsJsonObject();
                        JsonObject addChatItem = a.has("addChatItemAction") ? a.getAsJsonObject("addChatItemAction") : null;
                        if (addChatItem == null) continue;
                        JsonObject item = addChatItem.getAsJsonObject("item");
                        if (item == null) continue;
                        JsonObject renderer = item.has("liveChatTextMessageRenderer") ? item.getAsJsonObject("liveChatTextMessageRenderer") : null;
                        if (renderer == null) continue;

                        String name = deepText(renderer.getAsJsonObject("authorName"));
                        String msg = deepRuns(renderer.getAsJsonObject("message"));
                        long tsUsec = renderer.has("timestampUsec") ? renderer.get("timestampUsec").getAsLong() : (System.currentTimeMillis() * 1000L);
                        // Convert to ms for ChatMessage; BridgeServiceClient compares with sessionStartTs (ms)
                        long ts = tsUsec / 1000L;
                        // If this is clearly older than start boundary minus small tolerance, skip sending (extra guard)
                        if (startUsec > 0 && tsUsec + 5_000_000L < startUsec) continue;
                        ChatMessage.Role role = roleFromBadges(renderer.getAsJsonArray("authorBadges"));

                        if (listener != null) {
                            listener.onMessage(new ChatMessage(name, msg, role, ts));
                            synchronized (this) { msgsInWindow++; }
                        }
                    } catch (Exception ignore) { }
                }
            }
            schedulePoll(timeoutMs != null ? timeoutMs : 1500L);
        } catch (Exception e) {
            lastError = e.getMessage();
            schedulePoll(2000);
        }
    }

    private static String deepText(JsonObject obj) {
        if (obj == null) return "";
        if (obj.has("simpleText")) return obj.get("simpleText").getAsString();
        return "";
    }
    private static String deepRuns(JsonObject obj) {
        if (obj == null) return "";
        try {
            StringBuilder sb = new StringBuilder();
            for (JsonElement e : obj.getAsJsonArray("runs")) {
                JsonObject r = e.getAsJsonObject();
                if (r.has("text")) sb.append(r.get("text").getAsString());
            }
            return sb.toString();
        } catch (Exception e) { return ""; }
    }

    private static ChatMessage.Role roleFromBadges(JsonArray badges) {
        if (badges == null) return ChatMessage.Role.USER;
        for (JsonElement el : badges) {
            try {
                JsonObject b = el.getAsJsonObject().getAsJsonObject("metadataBadgeRenderer");
                String style = b.get("style").getAsString();
                if (style.contains("OWNER")) return ChatMessage.Role.OWNER;
                if (style.contains("MODERATOR")) return ChatMessage.Role.MODERATOR;
                if (style.contains("MEMBER")) return ChatMessage.Role.MEMBER;
            } catch (Exception ignore) { }
        }
        return ChatMessage.Role.USER;
    }

    private static String normalizeVideoId(String v) {
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

    private static String ua() {
        return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";
    }

    private static String unescapeJson(String s) {
        // Unescape common escaped quotes (\") produced in embedded JSON blobs
        return s.replace("\\\"", "\"");
    }

    private static String extractInnertubeContext(String html) {
        String marker = "\"INNERTUBE_CONTEXT\":";
        int idx = html.indexOf(marker);
        if (idx < 0) return null;
        int i = idx + marker.length();
        // Skip whitespace
        while (i < html.length() && Character.isWhitespace(html.charAt(i))) i++;
        if (i >= html.length() || html.charAt(i) != '{') return null;
        int start = i;
        int depth = 0;
        boolean inString = false;
        for (; i < html.length(); i++) {
            char c = html.charAt(i);
            if (c == '"') {
                // Count backslashes to determine if escaped
                int bs = 0; int j = i - 1; while (j >= 0 && html.charAt(j) == '\\') { bs++; j--; }
                if (bs % 2 == 0) inString = !inString;
            }
            if (inString) continue;
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return html.substring(start, i + 1);
                }
            }
        }
        return null; // Unbalanced
    }

    private void info(String s) { if (listener != null) listener.onInfo(s); }
    private void fail(String s) { lastError = s; if (listener != null) listener.onError(s); }
    private void endStream(String reason) {
        running.set(false);
        if (listener != null) {
            listener.onInfo("Live ended: " + reason);
            listener.onInfo("Stopped");
        }
    }

    private static String shortMsg(String m) {
        if (m == null) return "";
        return m.length() > 120 ? m.substring(0, 117) + "..." : m;
    }
    private static String snippet(String body) {
        if (body == null) return "<null>";
        String trimmed = body.replaceAll("\\s+", " ");
        return trimmed.length() > 180 ? trimmed.substring(0, 177) + "..." : trimmed;
    }
}
