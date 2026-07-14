# Element X Android, experiments fork

> **Like Element X, but better.**

This branch carries experimental UX and platform-stability changes layered on
top of [upstream `main`](https://github.com/element-hq/element-x-android).
Everything is opt-in or behind a Labs toggle, so the rest of the app behaves
exactly like the official Element X release until the switch is flipped.

> Russian version: [EXPERIMENTS.ru.md](EXPERIMENTS.ru.md)


## ⚠️ Disclaimer

* **Unofficial.** Not affiliated with, endorsed by, or coordinated with
  Element Hq Ltd. or the Element Call team.
* **Experimental.** Builds are produced from a moving branch; expect rough
  edges.
* **As is, at your own risk.** No warranty, no support contract, no SLA.
  Backups and testing are on you.
* **Don't kick us.** This fork exists so we can test ideas safely without
  bothering upstream maintainers, so please don't open issues against
  `element-hq/element-x-android` for changes that originated here.

## What's in this build (Element X v26.05.2 base)

This fork tracks upstream Element X (currently **v26.05.2**) and adds the
features below. Each one is optional: where it changes existing UI it sits
behind a Labs toggle, so with the toggles off the app behaves exactly like
official Element X.

* **Phone-style calls** (Labs → "Phone-style calls", on by default). Voice calls
  look like a real phone: a big avatar, an mm:ss timer and four iOS-style buttons.
  Voice and video buttons sit in every room header, group or DM, and there is no
  lobby screen to tap through. Synthesised ringback, earpiece-first audio and
  proper group-call ringing make a call feel like WhatsApp or Signal rather than
  a meeting grid. Turn it off for stock Element Call.

* **mxtr anti-censorship proxy** (Settings → Anti-censorship proxy, off by
  default). An in-app proxy that tunnels your Matrix traffic through your own
  server, so the app keeps working on networks that block homeservers by DPI, with
  no separate VPN. It uses an OS-assigned local port and falls straight back to a
  direct connection when off. Settings in English and Russian.

* **Multi-select messages** (Labs → "Multi-select messages", off by default).
  Long-press a message, then drag up or down to sweep a whole range; the list
  scrolls itself at the edges. Bulk copy, forward or delete up to 30 at once.
  System events and deleted messages stay out of the selection, and it survives a
  rotation (upstream issue #6737).

* **Bulk image / video picker** (Labs → "Bulk image / video picker", on by
  default). Attach several photos or videos in one trip to the gallery, up to 30,
  the way WhatsApp and Telegram do it. The caption goes on the first one.

* **Pin favourites at the top** (Labs → "Pin favourites at the top", on by
  default). Your favourite rooms get their own section at the top of the chat
  list, so the ones you actually use stay in reach.

* **Copy Matrix ID from settings** (Labs → "Copy Matrix ID from settings", on by
  default). A copy button next to your Matrix ID in settings, so you can share it
  in one tap.

* **Hide deleted messages** (Settings → Advanced settings, off by default). Drops
  the "message deleted" placeholders from the timeline so a tidied-up chat reads
  cleanly.

* **Link previews** (Settings → Advanced settings, shown in unencrypted rooms only
  by default). A message that contains a link gets a preview card (site name,
  title, description and image) fetched through your homeserver, with a setting to
  show previews never, only in unencrypted rooms, or always. Encrypted rooms never
  fetch a preview unless you pick "always", and only homeserver-proxied images are
  loaded so a preview does not leak your IP to the linked site. YouTube links get a
  thumbnail card with a play button; tapping it opens the video in the YouTube app
  for now. Inline playback in the timeline (WhatsApp/Telegram style) is a
  work in progress and temporarily disabled while the embedded player is fixed.

## How this fork differs from upstream

All of the call-related changes below are gated behind the
**Labs → "Phone-style calls"** toggle (on by default). Disable it to fall
back to upstream Element Call behaviour with no visual or functional
difference.

### Call experience

1. **Calls look like a phone, not a meeting grid.** A voice call renders as a
   classic phone screen: a centred avatar, an mm:ss timer underneath, and four
   large iOS Phone style buttons across the bottom (microphone, speaker, camera,
   hang up), instead of upstream's participant grid with a large remote tile and
   small bottom controls.

2. **Ringback that cuts out the instant the call connects.** Outgoing voice calls
   play a classic two-tone dial tone (440 + 480 Hz) generated with the Web Audio
   API rather than looping the upstream mp3, and it stops the moment the peer
   joins, without the lag of waiting for the LiveKit room to register them.

3. **You can tell it is actually ringing.** The timer slot, empty in upstream while
   the caller waits, now shows a "Ringing…" / "Соединение…" line with a soft
   opacity pulse that cross-fades into the running mm:ss timer once the peer joins.

4. **One tap to call from any room.** Every room header carries both a voice and a
   video button, groups included. Upstream shows only a video button in groups and
   a voice button only in DMs.

5. **No lobby to tap through.** Tapping a header button drops you straight into the
   call, the WhatsApp and Signal pattern, instead of upstream's lobby preview with
   a "Join" button on every non-DM call.

6. **Group voice calls actually ring.** Upstream's `StartNewCall` intent set
   neither `waitForCallPickup` nor a ring-type RTC notification, so the caller
   heard silence and receivers got a mute banner. The fork forwards the same flags
   upstream uses for DM voice (`waitForCallPickup`, `sendNotificationType=ring`,
   `autoLeave`), so a group voice call rings like a 1-on-1 one.

7. **The right incoming-call prompt for group voice.** With the RTC notification
   event pinned to "audio", the receiver gets a "Decline / Answer" pair instead of
   upstream's "Decline / Video" fallback for groups.

8. **No leftover ringtone or "Calling…" badge.** The phone-style layer suppresses
   upstream's lobby ringtone, large "Calling…" header and earpiece overlay, since
   its own UI already conveys the same thing.

### Audio system

9. **VoIP audio focus claim on call start.** Upstream relies on the
    system to route focus to us when the call activity opens, which fails
    on Pixel / AOSP off the back of an incoming-call notification and
    leaves the call silent. The fork explicitly claims VoIP audio focus,
    fixing the silent-incoming-call path.

10. **Music auto-resume after the call.** Upstream requests
    `AUDIOFOCUS_GAIN` (durable), which sends `AUDIOFOCUS_LOSS` to other
    apps and leaves Spotify, YouTube Music or podcast clients paused
    after the call ends. The fork uses `AUDIOFOCUS_GAIN_TRANSIENT`, the
    iOS Phone / WhatsApp behaviour, so the players resume on their own.

11. **Earpiece-first routing for voice.** Upstream starts a voice call
    on the loudspeaker. The fork prioritises the built-in earpiece for
    audio-only calls, the way a native dialer does. External devices
    (Bluetooth, wired headsets) keep their priority either way.

12. **Audible call-stream volume floor.** Upstream leaves the call stream
    silent if the user had it turned all the way down before the call.
    The fork bumps it to a usable level at call start while respecting
    higher user-set volumes.

### Compatibility

13. **`Promise.withResolvers` polyfill.** The embedded Element Call
    bundle uses `Promise.withResolvers`, available from Chromium 119,
    which is missing on devices like Huawei phones without Google
    Services that remain on Chromium 114 or earlier. The fork ships an
    ESM polyfill so the bundle boots cleanly on those WebViews.

14. **CSS Grid `min/max/minmax` fallback.** The bundle uses Grid with
    `min/max/minmax` (Chromium 79+), which the fork backs up with a
    two-step CSS fallback so the call screen renders on older WebViews.

### Timeline card

15. **Receiver-side call duration.** The duration of a connected call
    is now persisted on the receiving device too, not just on the
    caller's. Both ends now show the same number of seconds.

16. **No duplicate timeline message.** Upstream renders both a
    "voice call no answer" text message and a structured call card. The
    fork drops the text message and keeps only the card.

### Build and packaging

17. **Side-by-side install.** The release build uses applicationId
    `io.element.android.x.plusng` so the fork installs cleanly next to
    the official Element X without overwriting it. The package id changed
    from an earlier `.plus`, so if you had that build, uninstall it first
    - the new one cannot upgrade in place.

18. **`-PdisableR8` for low-RAM build hosts.** R8 stays enabled in CI
    (16 GB RAM) for the production size win, but local builds on hosts
    with under ~10 GB can pass `-PdisableR8` to skip the step rather
    than running out of memory.

### CI

19. **Manual dispatch only.** The build workflow runs on
    `workflow_dispatch` rather than on every push, avoiding wasted CI
    minutes and a release per commit on a fast-moving branch.

20. **Self-pruning releases and runs.** The workflow keeps at most three
    releases (the current build plus two previous, for rollback) and
    deletes older completed workflow runs as part of every successful
    build.

## Known limitations

Items the fork knows about but has not addressed yet. Pull requests and
feedback welcome.

* **Timeline card does not distinguish answered, declined and missed
  calls.** Connected calls show a duration, but a call that was
  declined or never picked up looks the same as a generic call entry
  with no extra signal. Native dialers use a coloured icon (red for
  missed, neutral for answered) plus "no answer" / "declined" text.
  We plan to wire that state through from the call lifecycle into the
  card composable.
* **First moment of ringback can leak through the loudspeaker.** The
  Web Audio context inside the WebView starts emitting before
  Android's `setCommunicationDevice` finishes pinning the route to the
  earpiece, so on some devices the very first ~200 ms of dial tone is
  routed to the speaker before the route flips. Cleanest fix is a
  native `ToneGenerator(STREAM_VOICE_CALL)` ringback, which we are
  holding off on so the Web Audio path stays cross-platform.

## Building

```bash
./gradlew :app:assembleFdroidRelease -PdisableR8 --no-daemon
```

`-PdisableR8` is required on hosts with less than ~16 GB RAM. The bundled
Element Call assets are committed under `app/src/main/assets/element-call/`,
so no separate element-call build is needed for a routine APK build.

## How to opt out of an experiment

Open Settings → Advanced settings → Labs and flip the relevant toggle. The
change applies to the next call placed; in-progress calls are unaffected.

## License

Same AGPL-3.0-only / LicenseRef-Element-Commercial dual licensing as
upstream. See the `LICENSE-*` files in this repository.
