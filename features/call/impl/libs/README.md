# Fork Element Call bundle

`element-call-embedded-plus.aar` is the fork's phone-voice build of Element Call,
bundled here instead of the stock upstream `io.element.android:element-call-embedded`
(which does not understand the `?phoneVoiceLayout=true` widget parameter this app
sends).

It is a pure-assets AAR: `assets/element-call/` is served to the in-call WebView via
`WebViewAssetLoader` (see `DefaultCallWidgetProvider` / `WebViewWidgetMessageInterceptor`).

## Rebuilding the AAR

From the Element Call fork (`element-call-experimental-plus`):

```sh
# 1. Build the embedded web bundle (relative base for asset loading)
corepack pnpm install
corepack pnpm build:embedded

# 2. Copy the bundle into the AAR project's assets
rm -rf embedded/android/lib/src/main/assets/element-call
mkdir -p embedded/android/lib/src/main/assets/element-call
cp -R dist/* embedded/android/lib/src/main/assets/element-call/

# 3. Build the AAR
#    EC_VERSION is a cosmetic label only (flatDir resolves the AAR by file name,
#    not version). Keep it on the upstream Element Call base tag this fork
#    tracks, currently v0.20.3.
cd embedded/android
EC_VERSION=0.20.3-plus ./gradlew :lib:assembleRelease

# 4. Drop it here
cp lib/build/outputs/aar/lib-release.aar \
   <element-x>/features/call/impl/libs/element-call-embedded-plus.aar
```

The `pnpm build:embedded` step needs a machine with enough RAM (the Vite build sets
`--max-old-space-size=16384`).

Wired up via `flatDir` in `settings.gradle.kts` and
`implementation(":element-call-embedded-plus@aar")` in this module's `build.gradle.kts`.
