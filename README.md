# Android.EXE

A native Kotlin Android desktop-pet app. Your AI companion lives in a floating overlay, renders a VRM/GLB avatar via Three.js + @pixiv/three-vrm in a transparent WebView, and reacts to what you're doing using a local GGUF model via llama.cpp.

---

## Requirements

| Tool | Version |
|------|---------|
| Android Studio | Hedgehog or newer |
| Android NDK | r26c or newer |
| CMake | 3.22.1+ |
| Min Android SDK | 26 (Android 8) |
| Target SDK | 34 |

---

## First-time setup (critical — do this before opening Android Studio)

### 1. Clone with the llama.cpp submodule

```bash
git clone --recurse-submodules https://github.com/YOUR_USERNAME/AndroidEXE.git
```

Or if you already cloned without `--recurse-submodules`:

```bash
git submodule update --init --recursive
```

This populates `app/src/main/cpp/llama.cpp/` (~200 MB). The CMake build compiles llama.cpp directly from source — no prebuilt `.so` needed.

### 2. local.properties

Android Studio creates this automatically, but confirm it points to your SDK:

```
sdk.dir=/Users/YOU/Library/Android/sdk
```

### 3. NDK

In Android Studio: **File → Project Structure → SDK Location → NDK Location**  
Set it to your NDK path, e.g. `/Users/YOU/Library/Android/sdk/ndk/26.3.11579264`

---

## Build

1. Open the `AndroidEXE` folder in Android Studio
2. Wait for Gradle sync to finish
3. **Build → Make Project** (first build is slow — compiling llama.cpp takes 5–10 min)
4. Run on a physical device (emulators don't support `SYSTEM_ALERT_WINDOW` overlay properly)

---

## First run — setup order

1. **Grant "Display over other apps"** — the app prompts you, or go to Settings → Apps → Android.EXE → Display over other apps
2. **Grant Accessibility** — tap "Open Accessibility Settings" → Android.EXE → Enable
3. **Pick your avatar** — tap "Choose Avatar File", pick your `.vrm`, `.glb`, or `.gltf`
4. **Pick your GGUF model** — tap "Choose GGUF Model" (any GGUF, 3B–13B recommended for phone RAM)
5. **Tap "Start Pet"**

The avatar appears in the bottom-right corner. Drag it anywhere. Double-tap to cycle between bust / full-body / face-cam framing.

---

## Architecture

```
MainActivity          ← setup UI, file picking, permission checks
PetForegroundService  ← orchestrates everything, stays alive in background
  ├─ PetOverlayManager     ← SYSTEM_ALERT_WINDOW floating view
  │    └─ AvatarWebView    ← transparent WebView running Three.js + three-vrm
  ├─ LlamaBridge           ← JNI wrapper around llama.cpp (real inference)
  ├─ PetReactionEngine     ← builds prompts, parses emotion tags from LLM output
  └─ PetDatabase (Room)    ← pet profile, personality, memory, interaction history

PetAccessibilityService   ← reads screen content, emits ScreenContext events
```

### Renderer (assets/avatar_renderer.html)

Loaded once inside AvatarWebView. Uses:
- **three.js r165** — 3D engine
- **@pixiv/three-vrm 2.1.2** — official VRM loader from pixiv (handles VRM 0.0 and VRM 1.0)
- **GLTFLoader** — plain GLB/GLTF support

Kotlin ↔ JS communication:
- **Kotlin → JS**: `webView.evaluateJavascript("AvatarAPI.loadModel(…)", null)`
- **JS → Kotlin**: `window.AndroidBridge.onModelLoaded(name)` via `@JavascriptInterface`

### LLM (llama.cpp)

`app/src/main/cpp/llama_jni.cpp` is a real JNI bridge — no stubs. It:
- Loads any GGUF file via `llama_load_model_from_file`
- Runs inference with greedy sampling + top-k/top-p + repetition penalty
- Streams tokens back via a `TokenCallback` interface
- Clears KV cache after each response

---

## Offline use

The avatar renderer fetches Three.js and @pixiv/three-vrm from jsDelivr CDN. To run fully offline:

1. Download these two files:
   - `https://cdn.jsdelivr.net/npm/three@0.165.0/build/three.module.js`
   - `https://cdn.jsdelivr.net/npm/@pixiv/three-vrm@2.1.2/lib/three-vrm.module.min.js`
   - `https://cdn.jsdelivr.net/npm/three@0.165.0/examples/jsm/loaders/GLTFLoader.js`
   - `https://cdn.jsdelivr.net/npm/three@0.165.0/examples/jsm/controls/OrbitControls.js`
2. Place them in `app/src/main/assets/js/`
3. Update the `importmap` in `avatar_renderer.html` to use relative paths:
   ```json
   {
     "imports": {
       "three": "./js/three.module.js",
       "@pixiv/three-vrm": "./js/three-vrm.module.min.js"
     }
   }
   ```
   And update the `GLTFLoader` / `OrbitControls` import paths similarly.

---

## Troubleshooting

| Symptom | Fix |
|---------|-----|
| Avatar doesn't appear | Check overlay permission; check logcat for `AvatarWebView` errors |
| Grey mesh renders instead of textured avatar | Normal for first few seconds while textures upload to GPU |
| "File not found" when loading avatar | The file is copied to app-private storage — check that copy succeeded in logcat |
| LLM says "model not loaded" | Ensure the GGUF path is set in DB and the service was restarted after picking the file |
| App crashes immediately on start | Usually overlay permission denied — check `Settings.canDrawOverlays()` in logcat |
| Build fails: "llama.h not found" | Run `git submodule update --init --recursive` |

---

## Adding personality / memory

Edit `PersonalityTraits` via the Room DB directly (use Database Inspector in Android Studio), or extend `MainActivity` with a personality editor screen. The `coreQuirk` and `speechStyle` fields are injected verbatim into the system prompt.
