# 📱 BleRemoteControl – Build & Release Guide

## ⚙️ Übersicht
BleRemoteControl ist eine Android-App zum Öffnen und Schließen eines Garagentors via BLE-Verbindung mit einem ESP32-C6-Controller („esp32BtBridge“).

Dieses Dokument beschreibt:
1. 🧰 Lokalen Build & Test
2. 🤖 Automatischen Build über GitHub Actions
3. 🚀 Manuelles Hochladen in den Google Play Store (interner Test-Track)

---

## 🧰 1. Lokaler Build (Android Studio / CLI)

### Voraussetzungen
- Android Studio Ladybug (oder neuer)
- Gradle 8.5+
- JDK 17+
- compileSdk = 36, minSdk = 26 (siehe `build.gradle.kts`)

### Schritte

#### Debug-Build
```bash
./gradlew assembleDebug
