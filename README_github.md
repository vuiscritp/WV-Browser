# 🌙 MoonLite Browser

<p align="center">
  <img src="app/src/main/res/drawable/ic_launcher.png" width="128" alt="MoonLite Browser">
</p>

<p align="center">
  <strong>A lightweight, controllable Android WebView browser.</strong><br>
  Automate, inspect, and control Android WebView directly from a terminal or PC.
</p>

<p align="center">
  <img src="https://img.shields.io/badge/version-1.17.1-blue.svg" alt="Version 1.17.1">
  <img src="https://img.shields.io/badge/Android-7.0%2B-green.svg" alt="Android 7.0+">
  <img src="https://img.shields.io/badge/minSdk-24-orange.svg" alt="Minimum SDK 24">
  <img src="https://img.shields.io/badge/engine-Android%20WebView%2FChromium-purple.svg" alt="Android WebView / Chromium">
  <img src="https://img.shields.io/badge/API-HTTP%20%2B%20JSON-lightgrey.svg" alt="HTTP + JSON API">
</p>

---

## 📖 Overview

MoonLite Browser is a lightweight Android browser built on the system's Android WebView / Chromium engine.

Unlike a conventional mobile browser, MoonLite is designed to expose browser functionality through a small local HTTP control API.

This makes it possible to control a browser running on Android from:

- Android Terminal
- `curl`
- Python
- PC via ADB
- automation scripts
- custom clients

MoonLite is **not intended to be a 1:1 Playwright replacement**.

Instead, it provides a practical Android-native browser automation layer with:

- multiple tabs
- navigation
- DOM interaction
- JavaScript execution
- HTML/text extraction
- screenshots
- file uploads
- cookies and session management
- User-Agent and fingerprint emulation
- timezone/locale/device emulation
- userscripts
- CSS injection
- built-in ad blocking
- translation
- application-wide proxy configuration
- live console/network streams
- live MJPEG screenshots
- remote DevTools in debug builds

---

## ✨ Features

### 🌐 Browser

- Android WebView / Chromium based
- Multiple tabs
- Background/headless tab operation
- Navigation
- Back / Forward / Reload
- Search
- DOM interaction
- JavaScript execution
- Screenshot capture
- HTML extraction
- Text scraping
- Element queries
- File upload
- Cookie import/export

### 🤖 Automation

Supported operations include:

`navigate` · `search` · `click` · `fill` · `hover` · `select` · `key` · `scroll` · `wait_for_selector` · `eval`

Example:

```json
{
  "selector": "input[name=q]",
  "text": "MoonLite Browser"
}
```

### 🧩 Browser Emulation

MoonLite can modify several browser-visible properties:

- User-Agent
- Client Hints
- `navigator.userAgentData`
- `navigator.platform`
- locale
- timezone
- screen dimensions
- device pixel ratio
- hardware concurrency
- device memory
- geolocation

Built-in User-Agent presets include:

- `chrome_mobile`
- `chrome_desktop`
- `firefox_mobile`
- `firefox_desktop`
- `safari_mobile`
- `safari_desktop`
- `duckduckgo_mobile`
- `moonlite_default`

> Emulation is designed for compatibility and testing. It should not be considered a guarantee of complete browser fingerprint anonymity.

---

## 🛡️ Security

The control API listens only on:

```text
127.0.0.1:8848
```

Every endpoint except `/status` requires:

```http
Authorization: Bearer YOUR_TOKEN
```

The authentication token is generated using `SecureRandom` and stored in the application's private storage.

This is important because Android loopback is not automatically a security boundary. A local process may still access `127.0.0.1`.

Therefore MoonLite requires authentication even though the server is not exposed directly to the LAN.

---

## 🏗️ Architecture

```text
┌─────────────────────────────┐
│       Terminal / PC         │
│                             │
│ curl / Python / custom app  │
└──────────────┬──────────────┘
               │
               │ HTTP
               │ Bearer Token
               ▼
┌─────────────────────────────┐
│ 127.0.0.1:8848              │
│                             │
│ MoonLite ControlServer      │
└──────────────┬──────────────┘
               │
               ▼
┌─────────────────────────────┐
│      MoonliteService        │
│                             │
│ TabManager                  │
│ BrowserActions              │
│ FingerprintSync             │
│ UserScriptManager           │
│ TranslateManager            │
└──────────────┬──────────────┘
               │
               ▼
┌─────────────────────────────┐
│ Android WebView / Chromium  │
└─────────────────────────────┘
```

When controlling MoonLite from a PC, ADB forwarding can be used:

```text
PC
 │
 │ adb forward
 ▼
Android
 │
 ▼
127.0.0.1:8848
```

---

## 📦 Version

**Current version:** `1.17.1`

| Component | Version |
|---|---:|
| MoonLite | 1.17.1 |
| Version Code | 11701 |
| Android min SDK | 24 |
| Android target SDK | 34 |
| Compile SDK | 34 |
| Kotlin | 1.9.22 |
| Android Gradle Plugin | 8.3.2 |
| Gradle | 8.5 |
| AndroidX WebKit | 1.11.0 |
| NanoHTTPD | 2.3.1 |

### Release status

**1.17.1**

- Stable source build
- Local HTTP control API
- Background browser service
- Multi-tab support
- Browser emulation
- Automation endpoints

---

## 🚀 Installation

### Android

Build or obtain the APK and install it:

```bash
adb install app-debug.apk
```

Start MoonLite:

```bash
adb shell am start -n com.moonlite.browser/.MainActivity
```

---

## 🔌 Connect from PC

Forward the local control port:

```bash
adb forward tcp:8848 tcp:8848
```

The browser API is now available locally on the PC at:

```text
http://127.0.0.1:8848
```

Check the server:

```bash
curl http://127.0.0.1:8848/status
```

Expected response:

```json
{
  "status": "ok",
  "auth": "bearer"
}
```

`/status` intentionally does not expose the authentication token.

---

## 🔑 Authentication

MoonLite uses a per-installation Bearer token.

HTTP header:

```http
Authorization: Bearer YOUR_TOKEN
```

Example:

```bash
export MOONLITE_TOKEN="YOUR_TOKEN"
export BASE="http://127.0.0.1:8848"

curl \
  -H "Authorization: Bearer $MOONLITE_TOKEN" \
  "$BASE/tabs"
```

### ⚠️ Protect your token

Never put the token inside:

- URLs
- query parameters
- HTML
- logs
- Git repositories
- public source code

---

# 🧪 API

All API endpoints require the Bearer token unless explicitly stated otherwise.

**Base URL:**

```text
http://127.0.0.1:8848
```

## GET Endpoints

### `GET /status`

Returns basic server status.

**Authentication:** None

```bash
curl http://127.0.0.1:8848/status
```

### `GET /health`

Returns runtime information such as:

- uptime
- tab count
- memory information

```bash
curl \
  -H "Authorization: Bearer $MOONLITE_TOKEN" \
  "$BASE/health"
```

### `GET /tabs`

Returns currently available tabs.

```bash
curl \
  -H "Authorization: Bearer $MOONLITE_TOKEN" \
  "$BASE/tabs"
```

### `GET /scrape`

Scrapes information from the active page.

```bash
curl \
  -H "Authorization: Bearer $MOONLITE_TOKEN" \
  "$BASE/scrape"
```

### `GET /html`

Returns the page HTML.

```bash
curl \
  -H "Authorization: Bearer $MOONLITE_TOKEN" \
  "$BASE/html"
```

### `GET /exists`

Checks whether an element exists.

```text
/exists?selector=.login-button
```

### `GET /attribute`

Reads an element property/attribute.

```text
/attribute?selector=img&name=src
```

### `GET /elements`

Queries multiple elements.

```text
/elements?selector=.product&limit=20
```

### `GET /console`

Reads recent console events.

```bash
curl \
  -H "Authorization: Bearer $MOONLITE_TOKEN" \
  "$BASE/console"
```

### `GET /network`

Reads recent network events.

```bash
curl \
  -H "Authorization: Bearer $MOONLITE_TOKEN" \
  "$BASE/network"
```

### `GET /cookies`

Reads cookies for a URL.

```text
/cookies?url=https://example.com
```

### `GET /cookies/all`

Reads all cookies associated with the specified URL/context.

### `GET /screenshot`

Captures the current page as PNG.

```bash
curl \
  -H "Authorization: Bearer $MOONLITE_TOKEN" \
  "$BASE/screenshot?width=460&height=980"
```

**Default:** `460 × 980`

**Maximum:** `2048 × 4096` and `8,000,000` pixels.

These limits help prevent excessive bitmap allocation and OOM conditions.

### `GET /stream`

Server-Sent Events stream for:

- console
- network

Example:

```bash
curl -N \
  -H "Authorization: Bearer $MOONLITE_TOKEN" \
  "$BASE/stream?types=console,network"
```

### `GET /stream/screenshot`

Live MJPEG screenshot stream.

```bash
curl -N \
  -H "Authorization: Bearer $MOONLITE_TOKEN" \
  "$BASE/stream/screenshot?width=460&height=980&fps=2" \
  | ffplay -f mjpeg -i -
```

**Supported FPS:** `1–5`

Higher frame rates may increase main-thread contention because each frame requires a real WebView measurement/layout/draw operation.

---

## 📮 POST Endpoints

### `POST /navigate`

Navigate to a URL.

```json
{
  "url": "https://example.com"
}
```

Example:

```bash
curl \
  -H "Authorization: Bearer $MOONLITE_TOKEN" \
  -H "Content-Type: application/json" \
  -X POST "$BASE/navigate" \
  -d '{"url":"https://example.com"}'
```

### `POST /search`

Search using the configured search engine.

```json
{
  "query": "MoonLite Browser"
}
```

### `POST /back`

Go back in browser history.

```bash
curl \
  -H "Authorization: Bearer $MOONLITE_TOKEN" \
  -X POST "$BASE/back"
```

### `POST /forward`

Go forward in browser history.

### `POST /reload`

Reload the current page.

### `POST /click`

Click an element.

```json
{
  "selector": "button.login",
  "timeout": 5000
}
```

### `POST /fill`

Fill an input.

```json
{
  "selector": "input[name=username]",
  "text": "hello"
}
```

MoonLite uses the native input setter and dispatches `input` and `change` events to improve compatibility with React/Vue-style controlled inputs.

### `POST /hover`

Hover over an element.

```json
{
  "selector": "#menu"
}
```

### `POST /select`

Select an option.

```json
{
  "selector": "select[name=country]",
  "value": "vn"
}
```

### `POST /key`

Send a keyboard key.

```json
{
  "selector": "input",
  "key": "Enter"
}
```

### `POST /scroll`

Scroll the page.

```json
{
  "x": 0,
  "y": 800
}
```

### `POST /wait_for_selector`

Wait for an element.

```json
{
  "selector": ".content",
  "timeout": 5000
}
```

### `POST /eval`

Execute JavaScript inside the active page.

```json
{
  "script": "document.title"
}
```

Example:

```bash
curl \
  -H "Authorization: Bearer $MOONLITE_TOKEN" \
  -H "Content-Type: application/json" \
  -X POST "$BASE/eval" \
  -d '{"script":"document.title"}'
```

> ⚠️ `/eval` is a highly privileged endpoint. It can execute arbitrary JavaScript inside the active page context.

### `POST /upload`

Upload a file to `<input type="file">`.

```json
{
  "selector": "input[type=file]",
  "filename": "avatar.png",
  "mime": "image/png",
  "content_base64": "..."
}
```

**Current limits:**

| Limit | Value |
|---|---:|
| Request body | 12 MiB |
| Base64 field | 12 MiB |
| Decoded file | 8 MiB |

### `POST /cookies`

Set/import cookies.

```json
{
  "url": "https://example.com",
  "cookies": "session=abc123"
}
```

### `POST /cookies/import`

Import structured cookies.

```json
{
  "cookies": [
    {
      "name": "session",
      "value": "abc123"
    }
  ]
}
```

> ⚠️ Cookies may contain authentication credentials. Protect the API token and cookie data accordingly.

### `POST /emulate`

Configure browser/device emulation.

Possible categories include:

- locale
- timezone
- geolocation
- accuracy
- CPU
- memory
- screen

Input is validated before being applied.

### `POST /useragent`

Change the active User-Agent preset.

```json
{
  "preset": "chrome_mobile"
}
```

Unknown presets are rejected.

### `POST /proxy`

Configure the application-wide WebView proxy.

> The AndroidX WebKit `ProxyController` operates at application/WebView level, not as a per-request HTTP proxy abstraction.

### `POST /adblock`

Enable or configure the built-in lightweight ad blocker.

### `POST /userscript`

Manage userscript/CSS injection.

Userscripts are injected at document start where supported by the implementation.

### `POST /translate`

Translate the current page.

```json
{
  "target": "vi"
}
```

MoonLite does not require a Google API key for the current translation implementation.

> ⚠️ The translation implementation relies on an unofficial Google Translate endpoint. It may change, become rate-limited, or stop working without notice.

---

## 🐍 Python Example

```python
import requests

BASE = "http://127.0.0.1:8848"
TOKEN = "YOUR_TOKEN"

headers = {
    "Authorization": f"Bearer {TOKEN}"
}

# Open a page
response = requests.post(
    f"{BASE}/navigate",
    headers=headers,
    json={
        "url": "https://example.com"
    },
    timeout=15
)

print(response.json())

# Execute JavaScript
response = requests.post(
    f"{BASE}/eval",
    headers=headers,
    json={
        "script": "document.title"
    },
    timeout=15
)

print(response.json())
```

---

## 🖥️ Terminal Example

```bash
export BASE="http://127.0.0.1:8848"
export MOONLITE_TOKEN="YOUR_TOKEN"

curl \
  -H "Authorization: Bearer $MOONLITE_TOKEN" \
  -H "Content-Type: application/json" \
  -X POST "$BASE/navigate" \
  -d '{"url":"https://example.com"}'

curl \
  -H "Authorization: Bearer $MOONLITE_TOKEN" \
  "$BASE/scrape"

curl \
  -H "Authorization: Bearer $MOONLITE_TOKEN" \
  -H "Content-Type: application/json" \
  -X POST "$BASE/eval" \
  -d '{"script":"document.title"}'
```

---

## 🔐 Security Model

MoonLite intentionally treats the control API as a privileged interface.

The following endpoints should be considered highly sensitive:

- `/eval`
- `/cookies`
- `/cookies/all`
- `/cookies/import`
- `/upload`
- `/navigate`
- `/proxy`
- `/useragent`
- `/emulate`

The authentication token protects the API, but it does **not** turn the browser into a security sandbox.

In particular:

- Anyone possessing the token can control the browser.
- `/eval` can execute JavaScript in the active page.
- Cookies can contain login sessions.
- Navigation can access arbitrary URLs.
- File upload can interact with pages containing file inputs.
- Proxy configuration affects WebView networking.
- A compromised Android process with sufficient privileges may still interact with the application environment.

Treat the MoonLite token similarly to a privileged API credential.

---

## 🕵️ Incognito Limitations

MoonLite supports an `incognito` tab mode, but Android WebView does not provide browser-context isolation equivalent to Playwright.

WebView storage can be shared within the process.

Therefore MoonLite deliberately avoids destructive behavior such as:

```text
close incognito
       ↓
delete cookies for host
       ↓
normal tab gets logged out
```

This means:

> `incognito=true` should not be interpreted as complete cookie/storage isolation.

True browser-context isolation would require a different architecture, such as separate storage/process contexts or an engine that natively supports isolated browser contexts.

---

## 📸 Screenshots

Add project screenshots here:

```md
![MoonLite Browser](docs/images/home.png)
![Browser Tabs](docs/images/tabs.png)
![Settings](docs/images/settings.png)
![Automation API](docs/images/api.png)
```

Recommended repository structure:

```text
docs/
└── images/
    ├── home.png
    ├── tabs.png
    ├── settings.png
    └── api.png
```

> The current source archive contains the application icon but does not include full UI screenshots, so screenshots should be added separately rather than pretending they are included in this release.

---

## 🛠️ Building From Source

### Requirements

Recommended environment:

- Android Studio
- JDK 17
- Android SDK 34
- Gradle 8.5

### Build debug APK

```bash
./gradlew assembleDebug
```

Windows:

```bat
gradlew.bat assembleDebug
```

Output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

---

## ⚙️ Project Configuration

Important Gradle configuration:

```kotlin
compileSdk = 34
minSdk = 24
targetSdk = 34

versionCode = 11701
versionName = "1.17.1"
```

### Main dependencies

- AndroidX Core
- AndroidX AppCompat
- Material Components
- ConstraintLayout
- DrawerLayout
- AndroidX WebKit
- NanoHTTPD
- `org.json`

---

## 🧱 Project Structure

```text
MoonLite/
├── app/
│   ├── src/
│   │   └── main/
│   │       ├── java/
│   │       │   └── com/moonlite/browser/
│   │       │       ├── MainActivity.kt
│   │       │       ├── MoonliteService.kt
│   │       │       ├── ControlServer.kt
│   │       │       ├── TabManager.kt
│   │       │       ├── BrowserActions.kt
│   │       │       ├── FingerprintSync.kt
│   │       │       ├── EmulationProfile.kt
│   │       │       ├── UaPresets.kt
│   │       │       ├── UserScriptManager.kt
│   │       │       ├── TranslateManager.kt
│   │       │       ├── AdBlockList.kt
│   │       │       └── ...
│   │       ├── res/
│   │       └── AndroidManifest.xml
│   └── build.gradle
│
├── .github/
│   └── workflows/
│       └── build.yml
│
├── build.gradle
├── settings.gradle
├── gradle.properties
└── README.md
```

---

## 🧪 Debugging

MoonLite includes startup diagnostics.

The application can write:

```text
startup_crash.log
```

Useful ADB commands:

```bash
adb logcat -c

adb logcat -v time | grep -E \
"MoonLiteStartup|AndroidRuntime|MoonliteService|MoonLite"
```

When investigating a startup crash, look for the last:

```text
SERVICE:*
MAIN:*
```

checkpoint before:

```text
UNCAUGHT_EXCEPTION
```

---

## 🩹 Known Limitations

MoonLite is built on Android WebView, so it intentionally inherits some platform limitations.

### Network idle

MoonLite cannot provide Playwright's exact `networkidle` behavior because stock Android WebView does not expose a complete public API for tracking all in-flight network activity.

Navigation therefore uses:

```text
onPageFinished
    +
DOM stability polling
    +
timeout
```

rather than pretending this is a true network-idle detector.

### Proxy

The proxy controller operates at the WebView/application level.

It is not equivalent to having a fully programmable per-request proxy layer.

### Fingerprinting

User-Agent and browser-visible values can be synchronized, but:

> No WebView-based fingerprint spoofing system can guarantee complete anti-fingerprinting behavior.

### Translation

The current translation implementation uses an unofficial Google Translate endpoint and may stop functioning if the upstream service changes.

### Incognito

Incognito does not provide Playwright-style isolated browser contexts.

### Background execution

Android may still reclaim resources under memory pressure or terminate applications under system conditions.

Foreground service usage improves persistence but cannot override the Android operating system.

---

## 📋 API Quick Reference

| Method | Endpoint | Purpose |
|---|---|---|
| GET | `/status` | Server status |
| GET | `/health` | Runtime health |
| GET | `/tabs` | List tabs |
| GET | `/scrape` | Scrape active page |
| GET | `/html` | Get HTML |
| GET | `/exists` | Check element |
| GET | `/attribute` | Read attribute |
| GET | `/elements` | Query elements |
| GET | `/console` | Console log |
| GET | `/network` | Network log |
| GET | `/cookies` | Read cookies |
| GET | `/cookies/all` | Read cookie set |
| GET | `/screenshot` | Capture PNG |
| GET | `/stream` | SSE events |
| GET | `/stream/screenshot` | MJPEG stream |
| POST | `/navigate` | Navigate |
| POST | `/search` | Search |
| POST | `/back` | History back |
| POST | `/forward` | History forward |
| POST | `/reload` | Reload |
| POST | `/click` | Click |
| POST | `/fill` | Fill input |
| POST | `/hover` | Hover |
| POST | `/select` | Select option |
| POST | `/key` | Keyboard input |
| POST | `/scroll` | Scroll |
| POST | `/eval` | Execute JavaScript |
| POST | `/wait_for_selector` | Wait for element |
| POST | `/upload` | Upload file |
| POST | `/cookies` | Set cookies |
| POST | `/cookies/import` | Import cookies |
| POST | `/emulate` | Device emulation |
| POST | `/proxy` | Configure proxy |
| POST | `/adblock` | Ad blocking |
| POST | `/tabs/new` | Create tab |
| POST | `/tabs/close` | Close tab |
| POST | `/tabs/switch` | Switch tab |
| POST | `/useragent` | Change UA |
| POST | `/userscript` | Userscript/CSS |
| POST | `/translate` | Translate page |

---

## 🤝 Contributing

Contributions are welcome.

Before opening a pull request:

1. Keep changes focused.
2. Explain the reason for the change.
3. Test the affected Android/WebView behavior.
4. Do not commit API tokens, cookies, or private credentials.
5. Update the README when adding or changing API endpoints.
6. Preserve the local-only control-server security model unless there is a documented reason to change it.

Suggested workflow:

```bash
git checkout -b feature/my-change

# make changes

./gradlew assembleDebug

git add .
git commit -m "feat: describe the change"

git push origin feature/my-change
```

---

## 🐛 Bug Reports

When reporting a bug, include:

```text
MoonLite version:
Android version:
Device:
WebView version:
Build type:
Steps to reproduce:
Expected behavior:
Actual behavior:
Relevant logcat:
```

For startup crashes, include the relevant:

```text
startup_crash.log
```

and:

```bash
adb logcat
```

output.

### Never include

- API tokens
- cookies
- session credentials
- passwords
- private URLs

---

## 📜 License

**License: Not yet specified.**

The current source archive does not contain a `LICENSE` file, so users and contributors should not assume that the project is MIT, Apache-2.0, GPL, or otherwise open-source licensed merely because the source code is publicly available.

Before publishing the repository, add a license explicitly, for example:

```text
LICENSE
```

and update this section.

If the intended license is MIT, this section can later be changed to:

> MoonLite Browser is released under the MIT License. See `LICENSE` for the full license text.

---

## ⚠️ Disclaimer

MoonLite is provided for development, automation, testing, and research purposes.

The authors are not responsible for:

- misuse of the automation API
- loss of cookies or sessions
- account lockouts
- websites rejecting automated traffic
- third-party service changes
- proxy-related issues
- WebView implementation differences
- data loss caused by incorrect API usage

Users are responsible for complying with:

- applicable laws
- website terms of service
- API terms
- authentication requirements
- privacy requirements
- applicable third-party licenses

---

## 🌙 Why MoonLite?

MoonLite aims to keep the architecture simple:

```text
Android
   +
WebView
   +
Small HTTP API
   +
Terminal
```

No external browser daemon is required.

No Playwright installation is required on the Android device.

No separate Chromium binary is bundled.

The browser engine already provided by Android does the heavy lifting, while MoonLite exposes the parts needed for automation and remote control.

---

## 📌 Project Status

| Property | Value |
|---|---|
| Version | 1.17.1 |
| Platform | Android |
| Engine | Android WebView / Chromium |
| API | Local HTTP + JSON |
| Default address | `127.0.0.1:8848` |
| Authentication | Bearer token |
| Minimum Android | Android 7.0 / API 24 |

---

<p align="center">
  <strong>MoonLite Browser — Lightweight browser control for Android. 🌙</strong>
</p>

<p align="center">
  <sub>Open source project by Vui -vi.</sub>
</p>
