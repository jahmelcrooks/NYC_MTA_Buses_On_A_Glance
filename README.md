# 🚌 NYC Buses On A Glance (Wear OS)

A 100% standalone **Wear OS** Android application designed for quick, seamless access to live **NYC MTA Bus** arrival schedules using on-watch GPS.

---

## 🌟 Key Features

**v1.10:** Settings screen (gear button next to refresh) for the search radius and API key; crown / rotating bezel scrolling (enabled explicitly, not yet tested on a watch).

* **⌚ Standalone watch app:** Runs on the watch itself with no companion phone (`standalone="true"`). The watch needs its own Wi-Fi or LTE for live data.
* **📍 On-watch location:** Searches within a radius you pick in Settings (0.25, 0.5, 0.75 or 1 mi; default 0.5 mi). Use a small radius in dense Manhattan, a bigger one where stops are sparse. It asks for a low-power fix first and only turns on high-accuracy GPS if that doesn't answer in time.
* **⚡ Opens instantly:** Shows your last results right away (if under 30 minutes old, with departed buses removed) while it refreshes in the background.
* **🚌 Live arrivals:** Tap a route card for each direction's next buses, minutes away, clock time, and the nearest stop that serves it. Each route and direction uses only its nearest stop, so you never see the same bus twice.
* **⭐ Star a route + direction + stop:** Tap the star next to a direction. Starred buses sort to the top and appear **live** on the tile and watch face (see below).
* **⚠️ Service alerts:** Routes with a detour or delay show a ⚠ and the alert text when expanded. *Unverified:* parsed from MTA's SIRI alert section, which hasn't been checked against a live response yet; if the format differs you simply see no alerts.
* **🧩 Tile:** Next arrivals for up to 3 starred buses, refreshed about every minute. Tap to open the app.
* **🕐 Watch-face complication:** Next arrival of your first starred bus (e.g. `B41  3m`), updated about every 5 minutes. Tap to open the app.
* **🌙 Ambient mode:** A dim, plain-text view of the next bus for your first few directions. Auto-refresh pauses while the screen is dimmed.
* **🎨 Pastel MTA design:** MTA-inspired colors over OLED-friendly true black.

The tile and complication need no location: a star remembers its stop, so they ask MTA for that stop directly.

---

## 🛠️ Tech Stack & Architecture

* **UI:** Jetpack Compose for Wear OS (`androidx.wear.compose.material`)
* **Architecture:** Clean Architecture + AndroidViewModel + Kotlin Flows & StateFlow
* **Networking:** Retrofit2 + Kotlinx Serialization (Parsing MTA SIRI & Discovery APIs)
* **Location:** Google Play Services `FusedLocationProviderClient`
* **Widgets:** Wear OS Tile Service (`androidx.wear.protolayout`) & Watch Face Complications

---

## 🔑 Setup Instructions

### 1. Prerequisites
* Android Studio Ladybug (or newer)
* Wear OS device or emulator running Wear OS 3.0+ (API 30+)
* MTA Bus Time API Key (Get a free API key at [MTA Bus Time Developer Portal](https://bustime.mta.info/wiki/Developers/Index/))

### 2. Provide your API key (pick one)

**A. At build time.** Create `local.properties` in the project root:

```properties
mta.api.key=YOUR_MTA_BUS_TIME_API_KEY
```

**B. On the watch, no rebuild.** If the app has no key it shows an **Enter API key** button (you type it with the watch keyboard). Or push it over adb, which is much easier than typing:

```bash
adb shell am start -n com.dngro.transit/.presentation.MainActivity --es mta_api_key YOUR_KEY
```

A key entered on the watch overrides the built-in one. It is stored in the app's private storage and excluded from backups.

> ⚠️ **Note:** `local.properties` is ignored by `.gitignore`, so the key is never committed to Git. But a build-time key **is compiled into the APK** (`BuildConfig`), where anyone with the file can extract it. For a build you share, leave the key out and let each person use option B with their own free key.

### 3. Build & Run
Open the project in **Android Studio**, select your Wear OS device or emulator, and click **Run ▶**.

---

## ⚖️ Disclaimer

Unofficial app, not affiliated with or endorsed by the MTA. Transit data comes from the MTA Bus Time API and is subject to MTA's terms of use.

---

## 📄 License

MIT. See [LICENSE](LICENSE).
