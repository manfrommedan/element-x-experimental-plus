# phone-layer

Pluggable fork layer that brands the app as **Element X+** and bundles the
patched Element Call WebView for offline-friendly classic phone-style call UX.

The presence of this directory is the marker that activates the layer in the
build, mirroring how upstream's `enterprise/` directory toggles the enterprise
build:

```kotlin
// plugins/src/main/kotlin/PhoneLayer.kt
val isPhoneLayerBuild = File("phone-layer/README.md").exists()
```

Drop the `phone-layer/` directory from the working tree and the build produces
a stock Element X APK with no trace of the fork. Keep it and the build adds a
`plus` product flavor that wires in the brand assets. The embedded Element Call
bundle itself is delivered by the `element-call-embedded-plus` AAR in
`features/call/impl/libs`.

## Layout

| Path | What it is |
| --- | --- |
| `brand/` | App icon, adaptive-icon background, brand strings (`Element X+`). |
| `docs/` | Fork-specific docs: `EXPERIMENTS.md`, `classic-call-rfc.md`, ru variants. |

The phone-style call code itself (`CallSummary`, `CallSummaryStore`,
`LocalPhoneVoiceLayout`, the audio routing tweaks, the URL params) lives
inline in the `features/call/` and `features/messages/` modules. There is
no separate `app/src/plus/` overlay.

## Building

```bash
# fork build, Element X+
./gradlew :app:assembleFdroidPlusRelease -PdisableR8 --no-daemon

# vanilla build, stock Element X without the fork
./gradlew :app:assembleFdroidRelease -PdisableR8 --no-daemon
```

## How to opt out at runtime

Settings, Advanced, Labs, "Phone-style calls". Disabling the toggle inside a
plus build falls back to upstream Element Call behaviour for the next call
placed.
