const http = require('http');
const fs = require('fs');
const path = require('path');

const PORT = 5000;
const HOST = '0.0.0.0';

function readSafe(p) {
  try { return fs.readFileSync(p, 'utf8'); } catch { return ''; }
}

const manifest = readSafe(path.join(__dirname, 'app/src/main/AndroidManifest.xml'));
const appGradle = readSafe(path.join(__dirname, 'app/build.gradle.kts'));

const versionMatch = appGradle.match(/versionName\s*=\s*"([^"]+)"/);
const minSdkMatch = appGradle.match(/minSdk\s*=\s*(\d+)/);
const targetSdkMatch = appGradle.match(/targetSdk\s*=\s*(\d+)/);
const appIdMatch = appGradle.match(/applicationId\s*=\s*"([^"]+)"/);

const html = `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8" />
<meta name="viewport" content="width=device-width,initial-scale=1" />
<title>ProjectEXE — Android Build Workspace</title>
<style>
  :root { color-scheme: dark; }
  body { margin:0; font-family: ui-sans-serif, system-ui, -apple-system, "Segoe UI", Roboto, sans-serif;
         background:#0b0f17; color:#e6edf3; }
  .wrap { max-width: 880px; margin: 0 auto; padding: 48px 24px; }
  h1 { font-size: 28px; margin: 0 0 8px; }
  .sub { color:#8b949e; margin-bottom: 32px; }
  .card { background:#11161f; border:1px solid #222b3a; border-radius:12px;
          padding:20px 24px; margin-bottom:18px; }
  .grid { display:grid; grid-template-columns: 200px 1fr; gap:8px 16px; font-size:14px; }
  .grid div:nth-child(odd) { color:#8b949e; }
  code { background:#0b0f17; padding:2px 6px; border-radius:6px; border:1px solid #222b3a; }
  pre  { background:#0b0f17; padding:14px 16px; border-radius:8px; border:1px solid #222b3a;
         overflow:auto; font-size:13px; line-height:1.45; }
  .pill { display:inline-block; padding:3px 10px; background:#1f6feb22;
          color:#79c0ff; border:1px solid #1f6feb55; border-radius:999px; font-size:12px; }
  a { color:#79c0ff; }
</style>
</head>
<body>
  <div class="wrap">
    <span class="pill">Native Android Project</span>
    <h1>ProjectEXE</h1>
    <p class="sub">This repository is a native Android (Kotlin + Gradle) application.
       It does not run as a web server, so the preview pane shows this status page instead.</p>

    <div class="card">
      <h3 style="margin-top:0">Project info</h3>
      <div class="grid">
        <div>Application ID</div><div><code>${appIdMatch ? appIdMatch[1] : 'unknown'}</code></div>
        <div>Version</div>       <div><code>${versionMatch ? versionMatch[1] : 'unknown'}</code></div>
        <div>min SDK</div>       <div><code>${minSdkMatch ? minSdkMatch[1] : '?'}</code></div>
        <div>target SDK</div>    <div><code>${targetSdkMatch ? targetSdkMatch[1] : '?'}</code></div>
        <div>Build system</div>  <div><code>Gradle 8.11.1</code> (Kotlin DSL)</div>
        <div>Java</div>          <div><code>GraalVM 22.3.1 (JDK 19)</code></div>
      </div>
    </div>

    <div class="card">
      <h3 style="margin-top:0">Build the APK</h3>
      <p>The Android SDK is not installed in this Replit container, so a full APK build
         must be done locally (Android Studio or the Android command-line tools).</p>
      <pre># From the project root, with Android SDK installed locally:
echo "sdk.dir=/path/to/Android/Sdk" &gt; local.properties
./gradlew :app:assembleDebug
# APK output: app/build/outputs/apk/debug/app-debug.apk</pre>
    </div>

    <div class="card">
      <h3 style="margin-top:0">Optional API key</h3>
      <p>Add an OpenRouter key to <code>local.properties</code> to enable AI features:</p>
      <pre>OPENROUTER_API_KEY=sk-or-...
OPENROUTER_MODEL=meta-llama/llama-3.1-8b-instruct:free</pre>
    </div>
  </div>
</body>
</html>`;

const server = http.createServer((req, res) => {
  res.writeHead(200, {
    'Content-Type': 'text/html; charset=utf-8',
    'Cache-Control': 'no-store, no-cache, must-revalidate',
  });
  res.end(html);
});

server.listen(PORT, HOST, () => {
  console.log(`ProjectEXE info server listening on http://${HOST}:${PORT}`);
});
