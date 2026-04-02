# 🚴 VeloSpeedometer

Audio cycling speedometer for Android. Announces speed and stats by voice — no need to look at the screen.

## Features

| Feature | Detail |
|---|---|
| **Speed announcement** | Triggered when speed changes by ≥ N km/h (configurable) |
| **Average speed** | Rolling window (e.g. last 10 min), announced on a separate timer |
| **Distance** | Accumulated since ride start, announced with avg |
| **EMA filter** | Smooths GPS noise (α = 0.3 default) |
| **Foreground service** | Survives screen-off; shows live stats in notification |
| **Notification actions** | 📢 Announce now · ■ Stop — no unlock needed |
| **Volume key shortcuts** | Vol↓ ×2 → announce now · Vol↑ ×2 → start/stop |

## Settings

| Setting | Default | Description |
|---|---|---|
| Speed change threshold | 5 km/h | Min change since last announce to trigger voice |
| Min time between alerts | 10 s | Debounce — prevents rapid-fire announcements |
| Avg calculation period | 10 min | Rolling window for average speed |
| Avg announcement interval | 2 min | How often average+distance is spoken |
| EMA alpha | 0.3 | Smoothing factor (0.05 = smooth, 1.0 = raw) |

## Build

### Local
```bash
./gradlew assembleDebug
# APK → app/build/outputs/apk/debug/app-debug.apk
```

### GitHub Actions
Push to `main` — workflow builds both debug and release (unsigned) APKs,  
available under **Actions → Build APK → Artifacts**.

#### Signed release (optional)
Add these secrets to your repo (Settings → Secrets → Actions):

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | `base64 -w0 your.keystore` |
| `KEYSTORE_PASS` | keystore password |
| `KEY_ALIAS` | key alias |
| `KEY_PASS` | key password |

Then uncomment the signing steps in `.github/workflows/build.yml`.

## Requirements

- Android 7.0+ (API 24)
- GPS / Fine location permission
- TTS engine (pre-installed on all standard Android devices)
