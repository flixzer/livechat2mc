<<<<<<< HEAD
# YTChat Bridge (Client)

**CLIENT-ONLY** Fabric mod for Minecraft **1.21.8**. Mirrors YouTube Live Chat into the **local** chat HUD using Innertube.  
No packets to the server. Not a server mod. Do **not** install on dedicated servers.

## Build & Run
- Ensure Java 21 and Gradle are installed.
- `./gradlew build`
- `./gradlew runClient`

## Commands (client-only)
- `/ytchat start <video_or_url>` — Start pulling live chat (local HUD only).
- `/ytchat stop` — Stop.
- `/ytchat status` — Provider info, msgs/min, lag, last error.
- `/ytchat setcolor <owner|moderator|member|user> <#RRGGBB>` — Set role color.
- `/ytchat setprefix <text>` — Set local prefix (`[YTChat]` default).
- `/ytchat throttle <msgsPerMin>` — Rate-limit local display.
- `/ytchat filter add <regex>` — Add filter.
- `/ytchat filter remove <id>` — Remove filter by index.
- `/ytchat filter list` — List filters.

## Config
- File: `config/ytchat.json`
- Mod Menu + Cloth Config integration available if both mods are present.

## Innertube Notes
- The provider fetches the watch page, extracts **INNERTUBE_API_KEY** and **INNERTUBE_CONTEXT**, then polls `youtubei/v1/live_chat/get_live_chat` with continuations.
- Polling is done off-thread via `ScheduledExecutorService` (no render/main blocking).

## Client-only Guarantee
- `fabric.mod.json` has `environment: client`.
- Only `client` & `modmenu` entrypoints are registered.
- No packets or networking to the server are used.
=======
# livechat2mc
LiveChat2MC connects your Minecraft chat with live messages from YouTube streams. Instead of switching screens, you can see the chat directly in-game while you play.
>>>>>>> 87c77273a9cc05079459a19f176af7d1d3952df8
