# RFC: Classic 1:1 Audio Call UI

> **Fork-only feature** - not merged to upstream. Maintained on `feature/classic-call` branch.
> See [Patch Surface Inventory](#patch-surface-inventory) for rebase guidance.

## Status

| | |
|---|---|
| **Status** | Phase 1 (skeleton) |
| **Author** | Doverie team |
| **Branch** | `feature/classic-call` |
| **Tracker** | (n/a - internal) |
| **Last updated** | 2026-04-28 |

## 0. Goal & non-goals

**Goal**: when `CallType.RoomCall.isAudioCall == true`, render a classic phone-style UI (avatar(s) + name + timer + mute/speaker/hangup) instead of Element Call's conferencing UI. Both 1:1 and group audio calls. Video calls are unchanged.

**Quality bar - WhatsApp level**. The classic call must feel like a native dialer:
- Earpiece by default; speaker is opt-in
- Visible "Calling…" → "Ringing…" → "Connected" + live timer
- Outgoing ringback tone before connect
- Vibration / haptics on connect, mute toggle, hangup
- Avatar speaking pulse (when feasible via widget API)
- Smooth transitions between phases - no abrupt UI jumps
- Lock-screen / Telecom integration (Android `CallStyle`, ConnectionService)
- Survives rotation, doze, BT (dis)connection without drop or audio glitch

**Avatar layout adapts to member count**:
- 1:1 (2 members) → single large centered avatar
- 3 members → row of three medium avatars
- 4+ members → grid (2×2, 2×3, …)

**Non-goals**:
- Restoring legacy `m.call.*` events
- Replacing Element Call with a native WebRTC stack
- Modifying signaling, e2ee, LiveKit integration
- Self-hosting Element Call (optional, deferred)

## 1. Compatibility matrix

| Slice | Min | Target | Notes |
|---|---|---|---|
| Android | API 24 (7.0) | API 35 | Matches FOSS branch minSdk |
| WebView | Chromium 99+ | latest | Huawei fallback already in place (`e29c940d81`) |
| Element Call | semver-pinned via Element X release | rolling | Risk: widget API breakage |

The classic UI **must not** require API > 24 or Chromium > 99.

## 2. Architecture decisions

### D1. Native overlay over hidden WebView, not an Element Call fork
**Rationale**: Widget API exposes everything we need (`io.element.join`, `im.vector.hangup`, `io.element.device_mute`) - see [`element-call/src/widget.ts`](https://github.com/element-hq/element-call/blob/livekit/src/widget.ts). Widget API is documented MSC2974 contract - stable across Element Call releases. Forking Element Call would add maintenance burden and self-host requirements with no functional gain.

### D2. Changes confined to `:features:call:impl`
**Rationale**: `CallType.RoomCall.isAudioCall: Boolean` is already parameterised. `IncomingCallScreen` is already phone-style. All deltas isolate to a single Gradle module - clean rebases.

### D3. Follow Presenter / View / State / Event pattern (per `AGENTS.md`)
File names:
- `ClassicCallNode.kt` - Appyx Node
- `ClassicCallPresenter.kt` - Molecule presenter
- `ClassicCallView.kt` - stateless Compose
- `ClassicCallState.kt` - immutable data class
- `ClassicCallEvents.kt` - sealed interface
- `ClassicCallStateProvider.kt` - preview states
- `ClassicCallPresenterTest.kt` - Turbine tests

DI: Metro `@Inject` constructor + `@ContributesNode(RoomScope::class)`.

### D4. Hide WebView via `alpha = 0f`, do not destroy
**Rationale**: setting `View.GONE` may pause the WebRTC pipeline. `alpha = 0f` with full size keeps the engine running. Native overlay is rendered in front via Compose `Box`.

**Exception**: `WebChromeClient.onPermissionRequest` for the microphone - the WebView must briefly be visible, OR we auto-grant if Android `RECORD_AUDIO` is already granted.

### D5. Audio routing reuses existing `WebViewAudioManager`
`controls.setAudioDevice(id)` already works through the JS bridge. The native speaker button calls `audioManager.setCommunicationDevice(speaker)` directly. We disable the enforce-loop (`WebViewAudioManager.kt:120-127`) only in `classicMode` to avoid fighting our explicit user actions.

## 3. Module touchpoints

### `features/call/impl/data/`
**`WidgetMessage.kt`** - add to `Action` enum:
```kotlin
@SerialName("io.element.device_mute")
DeviceMute,
```

### `features/call/impl/ui/classic/` *(new directory, fork-only)*
- `ClassicCallNode.kt`
- `ClassicCallPresenter.kt`
- `ClassicCallView.kt`
- `ClassicCallState.kt`
- `ClassicCallEvents.kt`
- `ClassicCallStateProvider.kt`
- `ClassicCallPresenterTest.kt`

### Modified existing files
- `CallScreenView.kt` (~line 115): branch on `state.isAudioCall` - wrap WebView in `Box`, render `ClassicCallView` overlay with `alpha = 0f` on the WebView
- `CallScreenPresenter.kt` (~line 270): add `sendMuteMessage(audio: Boolean)`, `sendJoinMessage()` mirroring existing `sendHangupMessage`
- `WebViewAudioManager.kt` (~line 120): add `var classicMode: Boolean = false`; early-return in `commsDeviceChangedListener` when `classicMode == true`

### Strings (`temporary.xml`)
- `screen_classic_call_state_connecting`
- `screen_classic_call_state_ringing`
- `a11y_classic_call_mute`
- `a11y_classic_call_speaker`
- `a11y_classic_call_hangup`

## 4. Phased delivery

### Phase 0 - Earpiece-default audio routing (~1-2d, **shippable standalone**)

**Why first**: the loudest user complaint is "I cannot listen to a voice call by holding the phone to my ear" - Element Call defaults audio routing to the loudspeaker even for 1:1 voice. This is fixable independently of the classic UI overhaul, ships value immediately.

**What**:
- Introduce a separate priority list for audio-only calls in `WebViewAudioManager` placing `TYPE_BUILTIN_EARPIECE` above `TYPE_BUILTIN_SPEAKER`
- On `onCallStarted()` for `isAudioCall = true`: force-select earpiece as the initial communication device (overriding any auto-selection)
- Keep speaker as an opt-in toggle (existing user-driven path stays intact)
- Video calls keep current behavior (loudspeaker default makes sense for video)
- BT/wired headsets stay highest priority - they always win

**Acceptance**: in a fresh 1:1 voice call with no headset connected, audio comes out of the earpiece. Tapping the in-call speaker button switches to loudspeaker.

### Phase 1 - Skeleton & widget plumbing (~1.5d)
- Add `DeviceMute` action to `WidgetMessage`
- Add `sendMuteMessage`, `sendJoinMessage` to `CallScreenPresenter`
- Scaffold `ClassicCall*` files (Node, Presenter, View, State, Events, StateProvider)
- Stub View with a single Hangup button
- Branch in `CallScreenView` for `isAudioCall`

**Acceptance**: opening a 1:1 audio call shows the stub screen; tap Hangup → call really ends (verify via Element Call console logs).

### Phase 2 - Mute & speaker (~2d)
- Mute toggle through `DeviceMute` widget action
- Speaker toggle through `WebViewAudioManager.setCommunicationDevice`
- Bidirectional state sync: parse `fromWidget DeviceMute` events to keep UI in sync if state changes outside our path
- Set `classicMode = true` to disable enforce-loop

**Acceptance**: mute/unmute works in both directions; speaker toggle does not loop.

### Phase 3 - UI polish (~2d)
- Avatar (large, centered) using Compound `AvatarSize.IncomingCall`
- Name + state text (Connecting / 00:23)
- Timer logic: start on `Action.Join` confirmation, stop on disconnect
- Action buttons styled consistently with `IncomingCallScreen`'s `ActionButton`
- Day/night previews + Compound theme tokens

**Acceptance**: screenshot tests pass for all states; all `@PreviewsDayNight` rendered.

### Phase 4 - Edge cases (~2d)
- **Mic permission flow** (P0 blocker): the WebRTC permission dialog is rendered
  by the WebView. With `alpha=0f` it is invisible and the user cannot grant.
  Strategy: intercept `WebChromeClient.onPermissionRequest`, auto-grant when
  Android `RECORD_AUDIO` is already granted; if not, request the OS permission
  natively first (visible Android dialog) and resolve the WebRTC request from
  its callback. Never show the hidden WebView.
- **Outgoing ringback tone**: while the call is `Connecting` / `Ringing` and
  before `InCall`, play the device's stock ringback through the in-call audio
  stream so the caller hears the standard "phone is ringing" tone (matches
  WhatsApp / native dialer expectations). Stop on `InCall` or hangup.
- **End-of-call screen**: brief "Call lasted m:ss" overlay before dismiss when
  the call ended after a successful connection. Skip on connect failures.
- **Telecom / ConnectionService**: investigate whether Element X already
  registers a `PhoneAccount` and uses `ConnectionService` to integrate with
  the system call UI (lock screen handling, system call log, reject-by-call).
  Document findings; if missing, scope out as Phase 7+.
- **Lifecycle**: rotation, lock screen, doze, return from background
- **Bluetooth headset connect/disconnect mid-call**

**Acceptance**: mic permission works on first install; outgoing ringback plays;
end-of-call line shown; full edge-case test plan green.

### Phase 5 - Tests & rebase guard (~1d)
- `ClassicCallPresenterTest` (Turbine) for every Event
- Screenshot tests for 4 states
- Widget API contract test: mock WebView receives correctly-shaped JSON for HangUp/Mute/Join
- Update [Patch Surface Inventory](#patch-surface-inventory)

**Acceptance**: `./gradlew :features:call:impl:test` green; `./gradlew ktlintFormat` clean.

### Phase 6 - Polish (~1d)
- Vibration on answer/hangup
- Subtle animations (avatar fade-in, button press feedback)
- Localisation: en + ru in `temporary.xml`

**Total**: ~9 working days; ~10 calendar days with 20% buffer.

## 5. Testing strategy

### Unit (Turbine + JUnit)
- `ClassicCallPresenterTest`: each Event emits the expected widget message
- `WidgetMessageTest`: `DeviceMute` serialise/deserialise matches Element Call's expected JSON shape
- `CallScreenPresenterTest`: `sendMuteMessage` / `sendJoinMessage` produce correct JSON

### Screenshot
- 4 states: Connecting / Connected / Muted / Ended
- Day + Night
- Reuse existing screenshot infrastructure (Paparazzi)

### Manual device matrix
| Device | Android | WebView | Smoke |
|---|---|---|---|
| Huawei BTK-AL09 | 9 | <119 (custom) | hangup, mute, speaker, BT |
| Pixel emulator | 14 | latest | full flow + permissions |
| Poco | 16 | latest | full flow + lockscreen |

### Widget API contract (CI guard, optional)
Schedule: weekly check that `element-call/src/widget.ts` `ElementWidgetActions` enum hashes match a known-good baseline. Diff → flag for manual review.

## 6. Patch Surface Inventory

> **Maintainers**: when rebasing onto upstream `develop`, expect conflicts only in these files. If conflicts appear elsewhere, investigate before resolving.

```
features/call/impl/src/main/kotlin/io/element/android/features/call/impl/data/WidgetMessage.kt
  → +1 enum value (DeviceMute)

features/call/impl/src/main/kotlin/io/element/android/features/call/impl/ui/CallScreenView.kt
  → branch on state.isAudioCall (~line 115)

features/call/impl/src/main/kotlin/io/element/android/features/call/impl/ui/CallScreenPresenter.kt
  → +sendMuteMessage, +sendJoinMessage (~line 280)

features/call/impl/src/main/kotlin/io/element/android/features/call/impl/utils/WebViewAudioManager.kt
  → +classicMode flag, early-return in commsDeviceChangedListener (~line 120)

features/call/impl/src/main/kotlin/io/element/android/features/call/impl/ui/classic/  (NEW DIR - fork-only)
  ClassicCallNode.kt
  ClassicCallPresenter.kt
  ClassicCallView.kt
  ClassicCallState.kt
  ClassicCallEvents.kt
  ClassicCallStateProvider.kt

features/call/impl/src/main/res/values/temporary.xml
  → +5 strings (screen_classic_call_*, a11y_classic_call_*)

features/call/impl/src/test/kotlin/io/element/android/features/call/impl/ui/classic/  (NEW DIR - fork-only)
  ClassicCallPresenterTest.kt
  ClassicCallViewTest.kt (screenshot)
```

### Rebase routine
1. `git fetch upstream && git rebase upstream/develop`
2. Inspect conflicts in files listed above only
3. `./gradlew :features:call:impl:test`
4. Manual smoke test: 1:1 audio call from one account to another → answer → hangup → confirm both sides clean up

## 7. Risk register

| # | Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|---|
| R1 | Element Call changes widget API actions (e.g. renames `im.vector.hangup`) | Low | High | API stable since 2023, MSC2974 contract. Pin known-good Element Call URL in Config. CI guard (Phase 5) |
| R2 | Mic permission dialog inside WebView is invisible due to alpha=0 | Medium | High | Phase 4 handles explicitly: temporarily show WebView OR auto-grant via `WebChromeClient.onPermissionRequest` |
| R3 | Audio routing fights between enforce-loop and our button taps | Medium | Medium | `classicMode` flag disables enforce-loop only in classic UI |
| R4 | Activity recreation (rotation) breaks hidden WebView state | Low | High | `ElementCallActivity` already declares `configChanges` - verify in Phase 4 |
| R5 | `Action.Join` does not skip lobby in audio-only mode | Medium | Low | URL params already include `intent=start_call`; our `Action.Join` is supplementary. Fallback: show stock lobby briefly |
| R6 | Video calls accidentally trigger classic UI | Low | High | Strict gate: `isAudioCall == true` only. Group audio is welcome (adaptive avatar layout) |
| R7 | Background audio drops if WebView is GC'd under memory pressure | Medium | High | Foreground service already pinned - verify priority + `WAKE_LOCK` if needed |

## 7+ Future work (not in current scope)

### Granular hangup reasons
Native dialers and modern messengers (WhatsApp / Telegram / Signal) distinguish at least:
- **Connected → Ended** - "Длился 0:43"
- **No answer** - "Нет ответа"
- **Declined** - "Отклонён"
- **Busy** - "Занято"
- **Unreachable / failed** - "Не удалось дозвониться"
- **Cancelled by caller** - silent dismiss

Element Call upstream emits only `Action.Close` with no reason payload. To surface
the full set of reasons, the path is one of:

1. **Subscribe to room timeline / state** in `CallScreenPresenter` and parse
   `m.call.member` (or `org.matrix.msc4143.rtc.member`) membership transitions
   to infer cause. Heaviest, but no upstream changes.
2. **Parse `m.call.hangup` events** (legacy 1:1 protocol) if the room has them.
3. **Petition Element Call upstream** to add a `reason` field to
   `ElementWidgetActions.HangupCall` / `Close`. Cleanest, but lead time is months.

Until we pick one, the classic UI shows a binary "Длился m:ss" or "Нет ответа"
and silently dismisses on user-initiated hangup before connect.

### Speaking-pulse avatar
Animate the active speaker's avatar with a subtle scale/glow pulse when their
audio level rises. Requires Element Call to expose per-participant audio level
events via the widget API (`controls.onSpeakingChanged` or similar) - not yet
in the documented surface.

### Telecom / `ConnectionService` integration
Register a `PhoneAccount` so calls appear in the system call log, the lock
screen shows the canonical fullscreen incoming-call activity, and `CallStyle`
notifications have the answer/decline gestures. Element X may already do
some of this - investigate before duplicating.

## 8. Open questions

1. **Outgoing ringback tone** - Element Call doesn't play one. Add via `RingtoneManager` locally? *(scope: small, deferred to Phase 6)*
2. **Group voice calls** - strictly NOT classic UI? If a 1:1 DM gains a third member mid-call, switch to stock UI or stay? *(decision blocker: clarifies R6)*
3. **Telecom / `ConnectionService`** - does Element X already integrate? Affects lockscreen UX.
4. **End-of-call screen** - "Call lasted 1:23, OK" or just dismiss? Match `IncomingCallScreen` styling.

These do not block Phase 1.

## 9. Glossary

- **Element Call**: open-source SFU-based call frontend (React app), embedded in Element X via WebView
- **LiveKit**: SFU backend used by Element Call
- **Widget API**: postMessage-based RPC between host (Element X) and embedded widget (Element Call), defined in MSC2974
- **`controls.*`**: limited host-injected JS API for audio device routing and PiP, defined in `element-call/src/controls.ts`
