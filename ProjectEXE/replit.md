# ProjectEXE

Native Android (Kotlin + Gradle) application.

## Replit setup

This repo is a mobile app, not a web app. There is no server-side or browser
frontend, so the Replit preview pane shows a small status page describing the
project (`server.js`, port 5000) instead.

- Java: GraalVM CE 22.3.1 (JDK 19) installed via Replit module `java-graalvm22.3`
- Node.js 20 installed only to run the status page
- Workflow `Start application`: `node server.js` on port 5000 (webview)

## Building the APK

The Android SDK is not available in this Replit container. Build locally:

```
echo "sdk.dir=/path/to/Android/Sdk" > local.properties
./gradlew :app:assembleDebug
# -> app/build/outputs/apk/debug/app-debug.apk
```

Optional config in `local.properties`:

```
OPENROUTER_API_KEY=sk-or-...
OPENROUTER_MODEL=meta-llama/llama-3.1-8b-instruct:free
```

## Layout

- `app/` — Android module (Kotlin sources under `app/src/main/kotlin`)
- `app/src/main/cpp/` — Native llama.cpp wrapper (CMake + JNI). CMake fetches
  `ggerganov/llama.cpp` via FetchContent during NDK build (no committed binaries).
  Requires Android NDK + CMake 3.22.1+ locally; the JNI lib is named `exe_native`.
- `build.gradle.kts`, `settings.gradle.kts`, `gradle/libs.versions.toml` — Gradle + version catalog
- `server.js` — Replit-only status page served on port 5000

## App architecture (post-Wave-1)

- `ai/engine/` — Engine abstraction: `LlmEngine`, `EngineRouter` (auto/online/offline),
  `OnlineEngine` (OpenRouter), `OfflineEngine` (llama.cpp via `local/LlamaCpp.kt`).
- `ai/tools/` — Tool / function-calling framework. `ToolRegistry` exposes tools to the
  online model; offline path skips tools (small local models hallucinate the schema).
  Tools: `get_connectivity_status`, `get_weather` (Open-Meteo), `compose_email`
  (mailto), `create_calendar_event` (CalendarContract INSERT), `set_reminder`
  (AlarmManager + `util/ReminderReceiver`), `start_focus_session`
  (`focus/FocusService`), `system_setting` (read/write whitelisted system settings),
  `open_settings_screen` (deep-link to Android Settings panes), `scan_for_threats`
  (heuristic permission audit of installed apps).
- `settings/SettingsActivity` — In-app settings: engine mode, OpenRouter key/model
  override, tools toggle, GGUF picker (SAF), context-window slider, runtime tool
  permissions, user name, response length.
- `assets/web/vrm_renderer.html` — CSS-only "pet" renderer (placeholder for VRM).
  Has chat input, expression/animation hooks, and an on-screen close button that
  calls `EXEBridge.requestClose` → stops `OverlayService`.

User config (`local.properties` or in-app Settings) controls API keys and model.
Permissions like `WRITE_SETTINGS`, `POST_NOTIFICATIONS`, `READ_CALENDAR`,
`ACCESS_COARSE_LOCATION`, `SCHEDULE_EXACT_ALARM`, and overlay are all requested
on demand from the Settings screen.
