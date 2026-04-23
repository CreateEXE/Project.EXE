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
- `build.gradle.kts`, `settings.gradle.kts`, `gradle/libs.versions.toml` — Gradle + version catalog
- `server.js` — Replit-only status page served on port 5000
