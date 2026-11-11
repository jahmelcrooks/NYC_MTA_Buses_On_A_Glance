# 🚌 NYC Buses On A Glance (Wear OS)

A 100% standalone **Wear OS** Android application designed for quick, seamless access to live **NYC MTA Bus** arrival schedules using on-watch GPS.

---

## 🌟 Key Features

* **⌚ 100% Standalone Watch App:** Runs independently on your Wear OS watch with zero reliance on a companion phone app (`standalone="true"`).
* **📍 On-Watch GPS & Proximity Math:** Uses `FusedLocationProviderClient` with `PRIORITY_HIGH_ACCURACY` to calculate real-time distance from your wrist to nearby stops within a **0.5-mile (805m)** radius.
* **⭐ Persistent Favorites ("Hold to Pin"):** Long-press or tap the star icon (⭐) on any bus route to pin it to the top of your list. Saved to disk in `SharedPreferences` so your favorites persist anywhere you go.
* **⚡ 1-Tap Drop-Down Schedules:** Tap any bus card (**B41**, **B44-SBS**, **M15**) to immediately reveal live arrival ETAs, clock times, and distance to your stop.
* **🎨 Pastel MTA Design System:** Soft MTA Pastel Blue (`#5C9CE6`), Pastel Gold (`#FFD166`), Pastel Green (`#70C1B3`), and SBS Purple (`#B39DDB`) styling over OLED-friendly true black.
* **🧩 Wear OS Tile:** Includes a Glance/ProtoLayout Tile displaying your starred favorite buses right on your watch face.

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

### 2. Configure Local API Keys
Create a `local.properties` file in the root directory (if it doesn't already exist) and add your MTA API Key:

```properties
mta.api.key=YOUR_MTA_BUS_TIME_API_KEY
```

> ⚠️ **Note:** `local.properties` is ignored by `.gitignore` so your private API keys will never be committed to Git.

### 3. Build & Run
Open the project in **Android Studio**, select your Wear OS device or emulator, and click **Run ▶**.

---

## 📄 License

```text
MIT License

Copyright (c) 2025

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction rights, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software...
```
