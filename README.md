# MoonLite Browser

MoonLite là trình duyệt Android nhẹ dựa trên **WebView/Chromium của hệ thống**, được thiết kế để chạy lâu ở background và điều khiển trực tiếp từ **terminal trên điện thoại** hoặc từ **PC qua ADB**.

Mục tiêu của project không phải clone Playwright 1:1 mà là cung cấp một lớp điều khiển HTTP nhỏ, ổn định và thực dụng trên Android:

- nhiều tab, chạy headless khi Activity không mở;
- `navigate`, `search`, `click`, `fill`, `hover`, `select`, `key`, `scroll`;
- JavaScript evaluation (`/eval`);
- scrape HTML/text/elements;
- screenshot PNG;
- upload file vào `<input type=file>`;
- cookie/session import/export;
- UA + Client Hints + `navigator.userAgentData`;
- locale/timezone/hardware/geolocation emulation;
- userscript/CSS injection;
- adblock nhỏ built-in;
- translation không cần API key;
- proxy toàn app qua AndroidX WebKit ProxyController;
- remote DevTools trong **debug build**;
- local control server trên `127.0.0.1:8848`.

## Điểm quan trọng của bản này

Bản này đã sửa các lỗi nguy hiểm có thể làm mất session, làm rò quyền điều khiển browser hoặc gây OOM/crash:

1. **Control API có Bearer token ngẫu nhiên theo từng installation.** Loopback không còn được coi là ranh giới bảo mật.
2. **Incognito không còn tự xóa cookie theo host khi đóng tab.** Android WebView dùng cookie store chung trong process; xóa theo host có thể logout tab thường.
3. **Document-start fingerprint scripts được quản lý bằng `ScriptHandler` và remove trước khi thay persona.** UA cũ không còn tích lũy với UA mới.
4. **Emulation được đăng ký ở document-start** và vẫn được áp ngay vào document hiện tại.
5. **Timezone spoof tính offset theo từng `Date`, có tính DST**, thay vì đóng băng offset của thời điểm hiện tại.
6. **Lifecycle tab/WebView được serialize tốt hơn**, và việc đóng tab chờ per-WebView lock trước khi `destroy()`.
7. **Screenshot có giới hạn kích thước/pixel** để tránh tạo bitmap khổng lồ.
8. **Upload có giới hạn body/base64/decoded bytes** trước khi ghi file.
9. **`/emulate` validate locale, timezone, tọa độ, accuracy, CPU và memory.**
10. **`/useragent` thực sự đổi preset**, đồng thời reject preset không tồn tại.
11. **Remote WebView debugging chỉ bật mặc định ở debug build**, thay vì luôn bật ở release.
12. **Android backup bị tắt** để secret API token không nằm trong dữ liệu backup của app.

> Lưu ý: `/eval`, `/cookies`, `/upload`, `/navigate` và `/proxy` là các quyền rất mạnh. Token chỉ bảo vệ control API; nó không biến browser thành sandbox an toàn trước chính người sở hữu token.

---

# 1. Mô hình điều khiển

```text
┌──────────────────────┐
│ Terminal trên Android│
│ curl / Python / C    │
└──────────┬───────────┘
           │ HTTP + Bearer token
           ▼
┌────────────────────────────┐
│ 127.0.0.1:8848             │
│ MoonLite ControlServer     │
└──────────┬─────────────────┘
           │
           ▼
┌────────────────────────────┐
│ MoonliteService             │
│ TabManager + WebView/Chromium│
└────────────────────────────┘

PC:
PC curl/python → adb forward tcp:8848 tcp:8848 → Android 127.0.0.1:8848
```

Server **chỉ bind `127.0.0.1`**. PC truy cập thông qua ADB forwarding, không mở port HTTP ra Wi-Fi/LAN.

## Vì sao vẫn cần token khi bind localhost?

Một process khác trên Android cũng có thể mở kết nối tới `127.0.0.1`. Ngoài ra:

```bash
adb forward tcp:8848 tcp:8848
```

làm control socket xuất hiện ở phía PC đã được ADB authorize. Vì API có quyền đọc cookie, chạy JavaScript và điều khiển navigation, loopback một mình không đủ để bảo vệ API.

---

# 2. Lấy API token

Token được tạo bằng `SecureRandom`, dài 32 byte và lưu trong private app storage qua `SharedPreferences`.

### Trên điện thoại

Mở MoonLite → drawer/menu → **API token** → **Sao chép**.

Không ghi token vào URL/query string. Luôn gửi bằng header:

```http
Authorization: Bearer YOUR_TOKEN
```

### Debug build + ADB

Nếu đang dùng debug build, có thể đọc token từ private storage bằng:

```bash
adb shell run-as com.moonlite.browser cat shared_prefs/moonlite.xml
```

Không nên đưa file prefs hoặc token lên Git.

---

# 3. Build

## GitHub Actions

Workflow `.github/workflows/build.yml` tự cài Gradle + Android SDK và build APK.

Sau khi push repository:

1. mở **Actions**;
2. chọn workflow build;
3. chờ job hoàn thành;
4. tải artifact `moonlite-debug-apk`.

## Local

Nếu repository có đầy đủ Gradle wrapper:

```bash
./gradlew assembleDebug
```

Nếu source archive thiếu `gradle/wrapper/gradle-wrapper.jar`, chạy từ máy đã cài Gradle:

```bash
gradle wrapper --gradle-version 8.5
./gradlew assembleDebug
```

Project hiện dùng:

- Android Gradle Plugin `8.3.2`
- Gradle `8.5`
- Kotlin `1.9.22`
- compileSdk `34`
- minSdk `24`
- AndroidX WebKit `1.11.0`

---

# 4. Cài trên Android + kết nối PC

```bash
adb install app-debug.apk
adb shell am start -n com.moonlite.browser/.MainActivity
adb forward tcp:8848 tcp:8848
```

Kiểm tra server:

```bash
curl http://127.0.0.1:8848/status
```

Kết quả không chứa token:

```json
{"status":"ok","auth":"bearer"}
```

Test endpoint có bảo vệ:

```bash
curl -i http://127.0.0.1:8848/tabs
```

Sẽ nhận `401 Unauthorized` nếu thiếu token.

Sau khi lấy token:

```bash
export MOONLITE_TOKEN='YOUR_TOKEN'
export BASE='http://127.0.0.1:8848'

curl -H "Authorization: Bearer $MOONLITE_TOKEN" "$BASE/tabs"
```

---

# 5. Python

```python
import requests

BASE = "http://127.0.0.1:8848"
TOKEN = "YOUR_TOKEN"
HEADERS = {"Authorization": f"Bearer {TOKEN}"}

r = requests.post(
    f"{BASE}/navigate",
    headers=HEADERS,
    json={"url": "https://example.com"},
    timeout=15,
)
print(r.json())
```

Khuyến nghị luôn dùng `timeout` ở client vì navigation/scraping có thể mất vài giây.

---

# 6. Endpoint API

Tất cả endpoint dưới đây, ngoại trừ `/status`, yêu cầu:

```http
Authorization: Bearer YOUR_TOKEN
```

Request JSON dùng `Content-Type: application/json`.

## Status

| Endpoint | Method | Ghi chú |
|---|---|---|
| `/status` | GET | public, không trả secret |
| `/health` | GET | uptime, số tab, memory |

## Navigation

| Endpoint | Method | Body |
|---|---|---|
| `/navigate` | POST | `{"url":"https://..."}` |
| `/search` | POST | `{"query":"hello"}` |
| `/back` | POST | — |
| `/forward` | POST | — |
| `/reload` | POST | — |

Navigation đợi `onPageFinished` hoặc timeout, sau đó best-effort chờ DOM ổn định. Đây không phải `networkidle` thật của Playwright vì WebView không expose API public cho trạng thái toàn bộ request đang bay.

## Đọc trang

| Endpoint | Method | Ghi chú |
|---|---|---|
| `/scrape` | GET | URL, title, text, links |
| `/html` | GET | outer HTML, có giới hạn kích thước |
| `/exists?selector=` | GET | kiểm tra selector |
| `/attribute?selector=&name=` | GET | đọc property/attribute |
| `/elements?selector=&limit=` | GET | nhiều phần tử một lần |
| `/console` | GET | console/alert/confirm/prompt log |
| `/network` | GET | network log gần nhất |

## Tương tác

| Endpoint | Method | Body |
|---|---|---|
| `/click` | POST | `{"selector":"button","timeout":5000}` |
| `/fill` | POST | `{"selector":"input","text":"hello"}` |
| `/hover` | POST | `{"selector":"#menu"}` |
| `/select` | POST | `{"selector":"select","value":"vn"}` |
| `/key` | POST | `{"key":"Enter","selector":"input"}` |
| `/scroll` | POST | `{"x":0,"y":800}` hoặc selector |
| `/wait_for_selector` | POST | `{"selector":"...","timeout":5000}` |
| `/eval` | POST | `{"script":"document.title"}` |

Ví dụ:

```bash
curl -H "Authorization: Bearer $MOONLITE_TOKEN" \
  -H 'Content-Type: application/json' \
  -X POST "$BASE/eval" \
  -d '{"script":"document.title"}'
```

`/eval` là endpoint mạnh nhất. JavaScript chạy trong page context của tab active.

## Upload

```bash
curl -H "Authorization: Bearer $MOONLITE_TOKEN" \
  -H 'Content-Type: application/json' \
  -X POST "$BASE/upload" \
  -d "{\
    \"selector\":\"input[type=file]\",\
    \"filename\":\"avatar.png\",\
    \"mime\":\"image/png\",\
    \"content_base64\":\"$(base64 -w0 avatar.png)\"\
  }"
```

Giới hạn hiện tại:

- request body: `12 MiB`;
- decoded upload: `8 MiB`;
- base64 upload field: `12 MiB`.

## Screenshot

```bash
curl -H "Authorization: Bearer $MOONLITE_TOKEN" \
  "$BASE/screenshot?width=460&height=980"
```

Giới hạn:

- width tối đa `2048`;
- height tối đa `4096`;
- tối đa `8,000,000` pixel.

Giới hạn này ngăn request kiểu `10000x10000` tạo bitmap hàng trăm MB và làm app OOM.

---

# 7. Cookie / session

| Endpoint | Method |
|---|---|
| `/cookies?url=` | GET |
| `/cookies` | POST `{"url":"...","cookies":"a=1; b=2"}` |
| `/cookies/all?url=` | GET |
| `/cookies/import` | POST `{"cookies":[...]}` |

Các endpoint cookie cần token vì chúng có thể chứa session đăng nhập.

---

# 8. Incognito: giới hạn thực tế của Android WebView

`/tabs/new` hỗ trợ:

```json
{"url":"https://example.com","incognito":true}
```

Nhưng **không được coi `incognito=true` là profile/cookie isolation hoàn chỉnh**.

`android.webkit.CookieManager` và nhiều storage thành phần WebView dùng chung trong process. Vì vậy bản này **không còn xóa cookie theo host khi đóng incognito**, bởi cách đó có thể phá session của tab thường:

```text
Tab thường → login example.com
Tab incognito → example.com
Đóng incognito
→ xóa cookie example.com
→ tab thường bị logout
```

Bản sửa ưu tiên **không phá session** hơn là giả vờ cung cấp isolation mà WebView không hỗ trợ.

Nếu cần profile isolation thực sự, kiến trúc cần tách storage/process hoặc dùng engine hỗ trợ browser contexts.

---

# 9. User-Agent và fingerprint

Preset:

```text
chrome_mobile
chrome_desktop
firefox_mobile
firefox_desktop
safari_mobile
safari_desktop
duckduckgo_mobile
moonlite_default
```

Ví dụ:

```bash
curl -H "Authorization: Bearer $MOONLITE_TOKEN" \
  -H 'Content-Type: application/json' \
  -X POST "$BASE/useragent" \
  -d '{"preset":"chrome_desktop"}'
```

Bản sửa quản lý `DocumentStartScript` bằng `ScriptHandler`:

```text
preset A
  ↓ remove script A
preset B
  ↓ add script B
reload
  ↓ chỉ chạy B
```

Không còn tình trạng A + B cùng chạy và cùng override `navigator`.

### Giới hạn

WebView vẫn là Chromium. Firefox/Safari preset chỉ là emulation; một site fingerprinting chuyên sâu vẫn có thể phát hiện khác biệt qua WebGL, canvas, audio, timing, network stack, TLS và các đặc trưng Chromium khác.

---

# 10. Runtime emulation

```bash
curl -H "Authorization: Bearer $MOONLITE_TOKEN" \
  -H 'Content-Type: application/json' \
  -X POST "$BASE/emulate" \
  -d '{
    "locale":"en-US",
    "timezone":"America/New_York",
    "latitude":40.7128,
    "longitude":-74.0060,
    "accuracy":20,
    "hardwareConcurrency":8,
    "deviceMemory":8
  }'
```

Các giá trị được validate:

- latitude: `-90..90`;
- longitude: `-180..180`;
- accuracy: `> 0`;
- hardwareConcurrency: `1..256`;
- deviceMemory: `1..1024`;
- timezone phải tồn tại trong IANA timezone database của Android;
- locale phải có dạng locale hợp lệ cơ bản.

Timezone offset được tính theo **Date đang được hỏi**, nên DST thay đổi theo ngày thay vì dùng một offset cố định của thời điểm hiện tại.

Đây vẫn là JS-layer emulation, không phải kernel/network-level spoofing.

---

# 11. Proxy

```bash
curl -H "Authorization: Bearer $MOONLITE_TOKEN" \
  -H 'Content-Type: application/json' \
  -X POST "$BASE/proxy" \
  -d '{"host":"1.2.3.4","port":8080,"scheme":"socks5"}'
```

Xóa:

```bash
curl -H "Authorization: Bearer $MOONLITE_TOKEN" \
  -H 'Content-Type: application/json' \
  -X POST "$BASE/proxy" \
  -d '{"clear":true}'
```

Proxy hiện áp dụng **toàn app**, không phải từng tab. Đây là giới hạn của WebView/ProxyController.

---

# 12. DevTools

Remote WebView debugging chỉ được bật mặc định trong debug build:

```kotlin
WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
```

Trong debug build có thể dùng Chrome desktop:

```text
chrome://inspect
```

Kết nối Android bằng USB debugging.

Release build không tự bật WebView debugging.

---

# 13. Security model

### Đã làm

- control server bind loopback;
- Bearer token ngẫu nhiên 32 byte;
- so sánh token bằng constant-time `MessageDigest.isEqual`;
- `/status` không trả token;
- token không được đưa vào URL;
- body request có giới hạn theo `Content-Length`;
- upload có giới hạn riêng;
- screenshot có giới hạn pixel;
- `/emulate` có validation;
- remote WebView debugging không tự bật trong release;
- Android backup tắt để tránh backup secret token;
- đóng WebView được serialize với request lock để giảm race `destroy()` vs evaluate/draw.

### Không nên làm

Không mở server ra `0.0.0.0` chỉ để PC truy cập qua Wi-Fi. Nếu cần điều khiển từ PC, dùng:

```bash
adb forward tcp:8848 tcp:8848
```

Không commit token vào source hoặc shell script public.

Không đặt token vào:

```text
http://127.0.0.1:8848/eval?token=...
```

vì query string dễ xuất hiện trong log/history/proxy tooling.

---

# 14. Những giới hạn WebView cố hữu

MoonLite không tuyên bố những khả năng mà WebView không cung cấp:

| Có | Giới hạn |
|---|---|
| JS eval | Không có Playwright BrowserContext isolation hoàn chỉnh |
| Screenshot | Không có PDF public API đơn giản |
| Cookies | Cookie store không tách per-tab |
| UA + Client Hints | Chromium vẫn là engine thật bên dưới |
| Locale/timezone/geolocation JS | Không thay đổi kernel/network fingerprint |
| Proxy | Toàn app, không per-tab |
| Open shadow root | Không xuyên closed shadow root |
| Same-origin iframe | Không xuyên cross-origin iframe |
| Network log | Không phải full DevTools Network interception |
| Background tabs | Vẫn phụ thuộc Android WebView/OEM memory policy |
| Service restart | `START_STICKY` không vượt qua Force Stop |

---

# 15. Troubleshooting

### API trả 401

Lấy token trong MoonLite → API token → Sao chép, sau đó:

```bash
curl -H "Authorization: Bearer YOUR_TOKEN" http://127.0.0.1:8848/tabs
```

### PC không kết nối được

Kiểm tra:

```bash
adb devices
adb forward tcp:8848 tcp:8848
curl http://127.0.0.1:8848/status
```

### Activity đóng nhưng browser vẫn chạy

Đây là thiết kế. `MoonliteService` là foreground service và `stopWithTask=false`.

### Browser bị Android/OEM kill

Android vẫn có quyền kill process khi thiếu RAM hoặc khi người dùng force-stop. Không có app Android nào có thể bảo đảm service sống sau Force Stop.

### Incognito làm tôi kỳ vọng cookie isolation

Không có isolation hoàn chỉnh trên một process WebView. Xem mục **Incognito: giới hạn thực tế** ở trên.

### Fingerprint vẫn bị phát hiện

Đây là expected limitation của WebView. MoonLite chỉ đồng bộ các lớp UA/Client Hints/JS mà WebView public API cho phép; không biến Chromium thành Firefox/Safari thật.

---

# 16. Kiến trúc source

```text
app/src/main/java/com/moonlite/browser/
├── MoonliteService.kt       # foreground engine lifecycle
├── TabManager.kt            # WebView/tab lifecycle + synchronization
├── ControlServer.kt         # authenticated local HTTP API
├── BrowserActions.kt        # navigation/search helpers
├── FingerprintSync.kt       # UA/Client-Hints/document-start spoof
├── EmulationProfile.kt      # locale/timezone/geo/hardware JS emulation
├── UaPresets.kt              # UA persona definitions
├── UserScriptManager.kt     # userscript/CSS registry
├── TranslateManager.kt      # free translation helper
├── AdBlockList.kt            # small built-in blocker
├── AppPrefs.kt               # persistent settings + API token
└── MainActivity.kt           # UI shell only
```

---

# 17. Nguyên tắc phát triển tiếp theo

Nếu tiếp tục mở rộng MoonLite, ưu tiên theo thứ tự:

1. giữ control API **authenticated + loopback-only**;
2. không bao giờ dùng host-wide cookie deletion để giả lập incognito;
3. mọi document-start injection phải giữ `ScriptHandler` để remove được;
4. mọi API nhận dữ liệu lớn phải có giới hạn trước khi decode/allocate;
5. mọi lifecycle `WebView.destroy()` phải serialize với request đang chạy;
6. không bật remote debugging mặc định trong release;
7. không tuyên bố fingerprint/profile isolation mạnh hơn khả năng thực tế của WebView.



## v11 stability notes

- Release builds do **not** enable WebView remote debugging. `chrome://inspect`
  is available only in debug builds.
- The control API remains bound to `127.0.0.1:8848` and requires the generated
  Bearer token for every endpoint except `/status`.
- `/upload` is capped at 8 MiB decoded data and `/screenshot` is capped at
  8,000,000 pixels.
- The `incognito` tab flag is **session-like only**. Android WebView does not
  provide a separate cookie profile per WebView in the same process. MoonLite
  deliberately does not delete cookies when an incognito tab closes, because
  doing so would also delete cookies belonging to normal tabs. Do not use this
  mode when strict cookie isolation is required.
- Fingerprint document-start handlers are removed before a UA/emulation profile
  is replaced, preventing old profiles from accumulating.
- GitHub Actions builds with JDK 17, Android SDK 34, AGP 8.3.2 and Gradle 8.5.


## v12 build-fix note

GitHub Actions v11 logs showed three compile issues: NanoHTTPD 2.3.1 has no `Response.Status.REQUEST_ENTITY_TOO_LARGE`, and Android Gradle Plugin was not generating `BuildConfig`. v12 uses `Response.Status.BAD_REQUEST` for rejected oversized bodies (the JSON still states the size limit) and explicitly enables `buildConfig true`.

## v13 UI/UX overhaul

v13 keeps the v12 browser/control/security architecture and focuses on a compact mobile browser shell:

- Compact tab strip and URL toolbar; the WebView gets more vertical space by default.
- Vector drawable icons are used for toolbar, tabs, settings and navigation controls instead of emoji/raster UI assets.
- URL bar gets a subtle focus animation and keeps the touch target comfortable while reducing visual footprint.
- Tab chips use a light fade/scale entrance animation. The animation can be disabled in Settings.
- A small `+` action lives in the tab strip instead of consuming a full toolbar button.
- The overflow menu contains New Tab, Reload, Incognito Tab and Settings.
- The tab strip collapses while scrolling down and returns when scrolling up, giving the page more usable space.
- Settings is now a dedicated multi-section screen: General, Appearance, Privacy & Security, Fingerprint, Performance, Tabs, Developer and About.
- Settings exposes practical controls for search engine, homepage, compact UI, tab animation, ad blocking, browser persona, locale profile, browsing-data clearing and API token access.
- The compact/comfortable UI preference is shared through `AppPrefs`, so returning to the browser applies the selected density.

### UI asset policy

Prefer this order for new UI assets:

1. Android VectorDrawable XML for icons and simple shapes.
2. XML shape/selector/gradient drawables for backgrounds and states.
3. WebView CSS only for content rendered inside the browser page itself.
4. PIL/raster assets only when a visual genuinely cannot be represented cleanly as a vector/drawable.

Avoid emoji as functional UI icons because their glyph shape varies by Android vendor/font.
