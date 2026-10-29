# Phase 3 — tests to run at the real Node

Everything in Phase 3 was built away from the Node and tested only against a fake
Node on the PC. This is the list that proves it on real hardware. Work top to
bottom; each test says what to do, what should happen, and what to note if it
doesn't. Tick the box when it passes.

Best done on the **S25+** (the real target) — repeat the ★ tests on the A73 if
time allows.

---

## 0. Setup (5 minutes, once)

1. **Install the latest build** from Android Studio (Run ▶ on the phone).
2. **Open Logcat** in Android Studio, select the phone, and paste this filter:
   ```
   package:io.github.eladimany.spindle tag~:Node|MediaHttpServer
   ```
   Keep it open during every test — most "what should happen" lines below are
   log lines. If something goes wrong, **copy the Logcat lines from around that
   moment** and send them to Claude; that's the single most useful thing.
3. **Write down the Node's IP and MAC.** Open this in any browser on the same WiFi:
   `http://<node-ip>:11000/SyncStatus` — the `mac="…"` attribute is the MAC.
   (The Node's IP is shown in the BluOS app's player settings, or in your
   router's list of connected devices.) Last known: `10.0.0.9`, MAC
   `90:56:82:98:B4:42`.
4. Have the **BluOS Controller app** installed on another device (or the same
   phone) — several tests use it.
5. The phone must be on the **same WiFi as the Node** (not the guest network —
   the guest WiFi blocks devices from talking to each other).

---

## 1. Seek sync — the "alien noise" glitch ★

**Background.** On 2026-09-23, switching to the Node mid-song produced a garbled
noise, then the song restarted from 0:00 while the app showed +8 s. Cause (best
explanation): Spindle sent "seek" a split second after "play", before the Node
could seek. Fixed: the seek now waits until the Node reports the stream as
seekable. These tests confirm it.

- [x] **1a. Switch mid-song, 5 times.** Play a song on the phone for ~30 s, then
      Now Playing → "Playing on" → pick the Node.
      **Expect:** no noise; the Node starts at about the same spot (within ~2–3 s —
      the Node always lands ~2 s past the requested second); the app's time
      matches what you hear. You may hear the first ~1 s of the song before it
      jumps — that's expected (see note below).
      **Log:** `Seek to 30s held until the Node's stream is seekable`, then
      `Stream seekable — sending held seek to 30s`.
      **Repeat** with an MP3 and a FLAC (and a 24-bit FLAC if you have one).
- [x] **1b. Seek while playing.** On the Node, drag the seek bar forward (e.g. to
      2:00), then backward (e.g. to 0:40), then near the end.
      **Expect:** audio jumps within ~2 s each time, no noise; the slider doesn't
      snap back to the old position and then jump again.
- [x] **1c. Seek while paused.** Pause, drag to a new spot, press play.
      **Expect:** resumes from the new spot.
- [x] **1d. Seek to ~5 s before the end.** **Expect:** the song ends and the next
      one starts by itself (auto-advance), no double-skip.
- [ ] **1e. Compare with the BluOS app.** During any of the above, open the BluOS
      app's now-playing view: its time should match Spindle's within ~2 s.

**If you hear the noise again:** note the time, copy the Logcat lines that say
`GET /t/… range=…` around it (they show every piece of the file the Node asked
for), and send them.

**Note — the brief start-of-song blip:** the Node can't be told "start at 0:30"
in one command (only "play" then "seek"), so ~1 s of the song's start may be
audible when switching mid-song. Optional experiment for Claude to try with you
at the Node: whether `/Play?url=…&seek=30` in a single request works (not in
the documented API).

---

## 2. Someone else takes over the Node ★

> 2026-09-25 (A73): took over with Spotify — Spindle stepped back and showed paused. ✔

- [ ] **2a. Normal playback stays ours.** Play from Spindle to the Node and let it
      run 30 s. **Expect:** keeps playing (no sudden pause after ~10 s).
      **If it pauses by itself after ~10 s:** that's the one risky assumption in
      this whole phase (the Node echoing our stream URL) — report immediately
      with the Logcat line `Node taken over (…)`.
- [ ] **2b. BluOS app plays something else** (radio / TIDAL / anything) mid-song.
      **Expect:** Spindle shows **paused** at the position it reached, does NOT
      skip to the next song, and doesn't stop the other music.
      **Log:** `Node taken over (state=… service=…) — stepping back`.
- [ ] **2c. Take it back.** Now press play in Spindle.
      **Expect:** the Node switches back to your song at about the same position.
- [ ] **2d. Physical input.** If you have Bluetooth-to-Node or an analog/optical
      input: start it mid-song. **Expect:** same as 2b.
- [ ] **2e. Stop from the BluOS app** mid-song.
      **Expect:** Spindle goes idle, does NOT advance to the next song.
- [ ] **2f. Pause/resume from the BluOS app** mid-song.
      **Expect:** Spindle shows paused, then playing, at the right time. (This is
      also the first real check of the Node's `pause` state — note whether
      Spindle's play/pause button follows.)

---

## 3. Screen off + battery locks ★

- [x] **3a. Locks held while streaming.** With music playing on the Node, run in
      Android Studio's **Terminal** tab (phone plugged in):
      ```
      & "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" shell dumpsys power | findstr Spindle
      & "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" shell dumpsys wifi | findstr Spindle
      ```
      **Expect:** both show `Spindle:NodeStreaming`. Pause → run again → gone
      (only history lines with "REL" remain).
- [ ] **3b. Whole album, screen off.** Start an album on the Node, turn the screen
      off, leave the phone alone (not charging, if possible) for the whole album.
      **Expect:** every song plays and advances with no gaps longer than the
      usual ~1 s between songs. Do this on the **S25+** (Samsung is the most
      aggressive about killing background apps). If it stops, note the song and
      the time.

---

## 4. The phone changes network ★

- [ ] **4a. WiFi off/on mid-song.** Pull down quick settings → WiFi off, wait
      ~5 s → WiFi on.
      **Expect:** after WiFi reconnects, the song continues from about where it
      stopped (within a few seconds).
      **Log:** `Media server on … stopped listening — restarting` (or
      `Phone IP changed …`).
- [ ] **4b. The phone gets a different IP mid-song.** See **How to change the
      phone's IP** below. **Expect:** same as 4a.
      **Log:** `Phone IP changed <old> -> <new> — restarting media server`.
- [ ] **4c. IP change while paused.** Pause, change the phone's IP, press play.
      **Expect:** resumes at the paused spot.

### How to change the phone's IP (Samsung, no router access needed)

Android can give the phone a fixed address on one WiFi network; switching
between automatic and fixed forces a reconnect with a new IP.

1. Note the phone's current IP: Settings → Connections → Wi-Fi → ⚙ next to your
   network → the IP address shown there.
2. While a song is playing on the Node: same screen → **View more** →
   **IP settings** → change **DHCP** to **Static**.
3. In **IP address**, change only the last number to a free one — e.g. if the
   phone is `10.0.0.23`, try `10.0.0.123`. Leave gateway / DNS as filled in.
   Tap **Save**. The WiFi reconnects with the new IP.
4. **Afterwards, set IP settings back to DHCP** (step 2) so the phone behaves
   normally again.

(A quick "is that address free?" check: pick something unusual like `.123` or
`.201`. If WiFi fails to connect, just pick another number.)

---

## 5. The Node disappears and comes back ★

- [x] **5a. Power-cycle the Node mid-song.** Unplug its power, wait ~10 s, plug
      back in.
      **Expect:** Spindle shows buffering within ~10 s, does not crash; when the
      Node has booted (~1 min), the song resumes by itself at about the spot
      where it cut out.
      **Log:** `Node unreachable — waiting for it (resume=<song>)` →
      `Node reachable again (ourStreamSurvived=false, …)`.
- [ ] **5b. Press pause while the Node is off.** Unplug the Node, press pause in
      Spindle within a few seconds, plug the Node back in.
      **Expect:** no crash; Spindle stays paused (doesn't start playing by itself
      when the Node returns); pressing play resumes at the paused spot.
- [ ] **5c. Change volume while the Node is off.** **Expect:** no crash.
- [ ] **5d. Long outage (optional, 10+ min).** Unplug the Node for over 10 minutes.
      **Expect:** after 10 min Spindle switches from buffering to **paused**;
      plugging the Node back in does NOT start music by itself.

---

## 6. The Node gets a different IP

This is the hardest one to arrange — it needs the router. Spindle should find the
Node again by its MAC (from step 0) and carry on.

- [ ] **6a.** With a song playing from Spindle to the Node, change the Node's IP
      (below). **Expect:** Spindle shows buffering, then within ~1–2 minutes of
      the Node coming back on its new IP, playback resumes.
      **Log:** `Node found at <new-ip>:11000 (was <old-ip>:11000)` then
      `Node reachable again …`.
      Note: Spindle must have the **Nearby devices** permission (Android asks the
      first time you open "Playing on"); without it, it can't search.

### How to change the Node's IP

The Node gets its address from your router, so the change is made there:

1. Open your router's admin page (usually `http://10.0.0.138`, `http://10.0.0.1`
   or `http://192.168.1.1` — printed on the router's label, along with the login).
2. Find **DHCP / LAN settings → Address reservation** (also called "Static
   lease", "DHCP reservation" or "IP binding").
3. Add (or edit) a reservation for the Node's **MAC address** from step 0, with
   an unused IP — e.g. if the Node is `10.0.0.9`, use `10.0.0.99`. Save.
4. Start a song from Spindle to the Node, then **unplug the Node's power for
   ~10 s and plug it back in**. It boots up with the new IP.
5. **Afterwards:** delete the reservation (or set it back to the old IP) and
   power-cycle the Node again, so everything else that knows the Node's old
   address keeps working.

If the router has no reservation feature, don't fight it — tell Claude the router
model and we'll find another way (or skip 6a; the logic is small and was reviewed).

---

## 7. What to send back

For each unticked box: the test number, what happened instead, and the Logcat
lines from around that moment. Also mention which phone and which file type
(MP3 / FLAC / hi-res FLAC) you used.

---

## Results — 2026-09-25, A73 at the real Node

Passed: 1a (switch at 0:41 → Node started ~0:43, no noise, times match), 1b, 1c, 1d,
2 (Spotify takeover), 3a, 5a (with the fixes below). Still to do: 1e, 3b (S25+, whole
album, screen off), 4, 5b–5d, 6.

Found and fixed at the Node:
- **Seek while paused started playback** — `/Play?seek` also plays. The seek is now
  kept and sent with the next play.
- **Seek near the end, then the song ended → player vanished.** The Node only reports
  `secs` on changes, so its last `secs` was 6 s short and the natural end read as a
  user's stop. The end is now judged from the interpolated position.
- **The Node sends `pause` then `stop` when it loses power.** Spindle used to go Idle
  (player gone, nothing to resume). A mid-track stop now shows the song paused where it
  stopped; play replays from there.
- Node `pause` statuses now update the position (it showed the last *stream* secs).
- Seen for the first time: the Node reports `connecting` for ~0.1 s after each /Play.

---

## Results — 2026-10-22, A73 at the real Node (0.9.6-beta)

Node "Living Room HI-Fi" at 10.0.0.2, wired to ImanyFiber. The BluOS app's actions were
sent from the PC with the same HTTP commands (/Pause, /Play, /Stop, /Play?url=radio).

Passed: natural ends (incl. a file whose length the Node misreads: 234 s reported as
872 s → now ends correctly); **3b-style screen-off run** on the A73 (battery Optimized,
3 songs, heartbeats every 30 s with no gaps, every end advanced); **4** (the phone hopped
to ImanyHome by itself → "can't reach this phone" + paused; hopped back → resumed by
itself); **1e** (Node 140 s vs Spindle 2:19); **2a**; **2b** (SomaFM radio from "the BluOS
app" → paused, no skip, radio left alone); **2c** (play takes it back); **2e** (stop
mid-song → paused where it stopped, no skip; play continues); **2f** (pause/resume from
outside → Spindle follows).

Found and fixed at the Node: stuck at the end of songs whose length the Node misreads
(the user's "6:00 of 6:15"); a network change during an outage resumed from 0:00; "take it
back" resumed ~10 s early (last reported secs instead of the real position).

Still open: 2d (physical input), 5b–5d (Node unplugged), 6 (Node IP change), and
everything on the user's own phones below ("New in 0.9.6" N1–N8, and C, D, E).

Later the same evening, with the fake Node: **Node turned off → back to the phone** (30 s
after it stops answering, or at once on play — even 0.4 s after "power off"). The Play on
sheet now searches 10 s, then shows ↻ (mockup option A).

---

## New in 0.9.8 — to check at the Node (2026-10-29)

**Build: `Spindle-0.9.8-beta.apk`** (also has everything from 0.9.7 below). Fixes from the
S25+ playback log of 2026-10-22.

- [ ] **Q1. Every song advances.** A long session at the Node, screen locked (as N1). **Expect:**
      no song ends in "paused" — on 10-22 one 3:00 song paused at its end and play replayed it.
- [ ] **Q2. Other WiFi, at once.** If the phone joins ImanyHome mid-song: **Expect:** "<Node>
      can't reach this phone…" within ~10 s (it never showed on 10-22), paused; back on
      ImanyFiber it continues by itself. Best fix: forget ImanyHome on the phone.
- [ ] **Q3. Car Bluetooth.** Getting into the car while music plays on the phone: does it move
      to the car, or pause / go back to the phone speaker? (The log shows the car connecting
      and dropping within 3 s.)

## New in 0.9.7 — to check on your phone (2026-10-29)

**Build: `Spindle-0.9.7-beta.apk`** (release, installs over 0.9.x, keeps everything).

- [ ] **P1. No "Choose a device to connect" dialog.** Open Play on (tab title pill or
      mini-player). **Expect:** one permission prompt the first time ("Nearby devices"),
      then the Node list. No system device picker. Note the phone's Android version.
- [ ] **P2. Output pill on every tab.** With nothing playing, tap "This phone ▾" under a
      tab's title → pick the Node → play a song. **Expect:** it plays on the Node; the pill
      shows the Node's name with a glow.
- [ ] **P3. BOOST on the player.** Tap BOOST under the artist → BOOSTED + a short hint. Let the
      song play to the end. **Expect:** still boosted afterwards (it's for the next smart shuffle).
- [ ] **P4. More like this.** In the queue, swipe a song right. **Expect:** "N songs like it
      play next", a "✦ LIKE …" label, tinted rows with a reason; Undo puts the queue back.
      Long-press a row → boost. Note whether the picks feel similar.
- [ ] **P5. Playlist length.** Describe sheet → Tracks / Time + slider. Try "45 min" and "30
      songs"; also type a length in the request ("a 2-hour …"). **Expect:** the draft matches.
- [ ] **P6. Better AI picks.** "energetic songs for a run" (and 2–3 more moods). **Expect:**
      takes a few seconds longer than before; the songs fit the mood. Note any misfits.

## New in 0.9.6 — to check on your phone (2026-10-22)

**Build: `Spindle-0.9.6-beta.apk`** (release, installs over 0.9.x and keeps playlists,
history and settings). Release builds now keep a small **playback log**: if something goes
wrong, note the time and plug the phone into the PC — Claude pulls
`/sdcard/Android/data/io.github.eladimany.spindle/files/playback-log.txt`.

- [ ] **N1. Long session at the Node, screen locked** (S26+ / S25+), 30+ min. **Expect:**
      every song ends and the next starts — including ones that used to freeze ~15 s before
      the end.
- [ ] **N2. Node turned off.** Turn the Node off while paused, press play. **Expect:** plays
      on the phone at once, message "<Node> isn't answering — switched to this phone".
      While playing: after ~30 s it switches to the phone, paused.
- [ ] **N3. Phone on the other WiFi.** If the phone joins ImanyHome mid-song: **Expect:**
      paused + "<Node> can't reach this phone…"; back on ImanyFiber it continues by itself.
- [ ] **N4. Play on sheet.** Open it: "Searching…" for ~10 s, then ↻. With the Node off:
      "No players found on this WiFi…". Turn the Node on, tap ↻: it appears.
- [ ] **N5. Bluetooth.** Playing on the phone, connect earbuds: **Expect:** sound moves to
      them. Playing on the Node, connect earbuds: switches to the phone, through them.
      Disconnect: back to the speaker, paused.
- [ ] **N6. Queue kept.** Shuffle, play a few songs, close the app (swipe it away) or wait
      a day, reopen. **Expect:** same queue, same song, paused where you left it.
- [ ] **N7. Gemini playlists** (C5 below). If Google is busy it now retries by itself; a
      message appears only if it stays overloaded.
- [ ] **N8. Takeover position.** Play something else on the Node from the BluOS app, then
      press play in Spindle: **Expect:** back to your song where it was (not ~10 s earlier).

---

## Still to do — updated 2026-10-18 (sections C–E still apply; A/B partly done 2026-10-22)

Everything that still needs a real phone, in the order to run it. Tick the boxes here
as you go. **Build: `Spindle-0.9.4-beta.apk`** (release). It installs over 0.9.x on the
S25+ and keeps playlists, history and settings. Release builds have no Logcat output — if
something fails, note what you saw and when; Claude can give you a debug build for a
second run with logs.

### A. S25+ at the Node — the screen-off fix (most important)

Background: on 0.9.0-beta, after ~10 min (2–3 songs) with the screen locked, the song
stuck on its last ~3 s until the screen was unlocked; then resume / skip / previous played
the "alien" noise. Cause: Android saw Spindle's media session as idle during Node playback
and froze the app. Fixed with `SessionPlayer` (0.9.1-beta on) — never tested at the Node.

- [ ] **A1. (= 3b) Whole album, screen locked 20–30 min.** Spindle's battery usage on
      **Unrestricted** (Settings → Apps → Spindle → Battery).
      **Expect:** every song plays to the end and the next one starts; no stall on the last
      seconds, no noise. **If it stalls:** note the song and time, then unlock and note
      whether pressing play/next makes noise.
- [ ] **A2. Same as A1 with battery on "Optimized"** (optional, after A1 passes) — shows
      whether the fix alone is enough for people who never change that setting.
- [ ] **A3. Lock screen + notification while playing on the Node.**
      **Expect:** song title, artist and artwork; play/pause, next and previous all work;
      previous restarts the song if more than 5 s in, otherwise goes back a song.
- [ ] **A4. Headphone / Bluetooth media buttons** (if a headset is connected to the phone
      while the Node plays): play/pause and next work.

### B. The rest of the Node checklist (sections above)

- [ ] **4a–4c — WiFi off/on and IP change.** The recovery code changed on 2026-09-25
      (a mid-track stop is now Paused, not Idle) and hasn't been run since.
- [ ] **2a, 2c–2f** — stays ours / take it back / physical input / stop and pause from the
      BluOS app.
- [ ] **5b–5d** — pause or change volume while the Node is off; 10-minute outage.
- [ ] **1e** — times match the BluOS app.
- [ ] **6a** — the Node gets a different IP (needs the router; skip if awkward).

### C. S25+ — AI playlists with a free Gemini key

The S25 family can't use Gemini Nano from apps (Android answers "status 0"), so Spindle
offers your own free key from Google AI Studio. Needs internet. Nothing here can cost
money as long as billing stays off in Google.

- [ ] **C1. The card.** Playlists → "Make a playlist" shows **Build a playlist** and the
      link **"Add a free Gemini key to describe playlists in words"**. Tap the link.
      **Expect:** AI settings: "On this phone · Not available (status 0)", and a **Free
      Gemini key** card with **Get my free key**.
- [ ] **C2. Get the key.** Get my free key → read the 3 steps → **Open Google AI Studio**.
      **Expect:** AI Studio opens already signed in (no password). Accept the terms if
      asked → **Create API key** → if it asks for a project, choose a **new** one →
      **Copy** the key.
- [ ] **C3. Coming back.** Switch back to Spindle (Back, or the recent-apps button).
      **Expect:** "Found your key" with the key masked (`AIza••••••••xxxx`), "Checking
      with Google…", then **"Key works"**. Android may show a small "Spindle pasted from
      your clipboard" note — that's normal. Tap **Use this key**.
      **If it says "Paste your key" instead:** the key wasn't on the clipboard — paste it
      into the box (long-press → Paste) and tap Check key. Tell Claude which happened.
- [ ] **C4. Connected.** **Expect:** the card shows **Connected**, the masked key,
      Change key / Remove, and "What leaves your phone".
- [ ] **C5. Make a playlist from words.** Back to Playlists. **Expect:** the card now has
      the "Say the vibe" prompt and idea chips. Try 3–4 requests, e.g. "chill 90s rock for
      driving", "upbeat songs for a workout", "something mellow for a rainy evening",
      and one in Hebrew.
      **Expect:** a working row, then an AI draft within ~5–15 s; the "Spindle heard"
      chips (genres / decades) make sense for the request. Note for each: time taken and
      whether the picks fit.
- [ ] **C6. Leave the screen while it works.** Send a request and switch tabs right away.
      **Expect:** the draft is still ready when you come back to Playlists.
- [ ] **C7. No internet.** Airplane mode → send a request.
      **Expect:** "Couldn't reach Gemini. Check the internet connection." — no crash.
- [ ] **C8. Wrong key.** AI settings → Change key → type a few wrong characters at the end
      of the key → Check key.
      **Expect:** "Google says this key doesn't work." The saved key stays connected
      (close the sheet without saving).
- [ ] **C9. Remove.** AI settings → Remove. **Expect:** back to "Get my free key"; the
      Playlists card falls back to Build a playlist + the link.
      Then add the key again (C2–C3) so it's set up for normal use.

### D. S26+ — Gemini Nano download (if you still have access to it)

- [ ] **D1. Progress.** AI settings → **Download on-device AI**.
      **Expect:** a progress bar with a percentage in the status badge ("Downloading 26%")
      and "312 MB of 1.2 GB" under the bar. If Android doesn't report the size, the bar
      moves back and forth and the text says "312 MB so far". Note which one you see.
- [ ] **D2. Leave and come back.** During the download, leave AI settings (or close
      Spindle completely and reopen it). **Expect:** the progress picks up again — not a
      bare "Downloading…" with nothing moving.
- [ ] **D3. Stop.** Tap **Stop download**. Note what happens, because only the phone can
      tell us: either "Download stopped…" with the Download button back (Android dropped
      it), or "Android is finishing the download by itself…" with the progress still
      moving (Android continues anyway — the model belongs to Android, not Spindle, and
      apps have no way to delete it).
- [ ] **D4. Finished.** When the download completes: "On this phone · Ready", and the
      Playlists card has the "Say the vibe" prompt. There is **no** Free Gemini key card
      on this phone (Nano is used instead).
- [ ] **D5. A playlist from words**, as in C5 — note speed and quality vs. the S25+.

### E. Fix track info (new in 0.9.4-beta)

Tracks whose file has no artist tag. Spindle reads "Artist - Title" from the title or file
name, optionally checks with MusicBrainz, and saves the fix in Spindle only — **music files
are never changed**. Already tested on the A73 without internet (notice, review, swap,
untick, Apply, hand edit, undo, Manage folders entry, offline message). Still to test:

On the **S25+** (real library, no internet needed for E1–E4):
- [ ] **E1. Upgrade keeps everything.** After installing 0.9.4 over 0.9.3: playlists,
      listening history and settings are all still there.
- [ ] **E2. The notice.** Tracks tab → "N tracks have no artist" above the list. Note N.
      ✕ hides it; it comes back only if more untagged tracks appear.
- [ ] **E3. The review list.** Tracks → Review (or Folders → ⚙ Manage folders → Fix track
      info). For 10 or so rows, check the artist/title split is right. Pink rows ("Which
      side is the artist?") — is the guess usually right or usually backwards? Rows read
      from a file name start unticked. Note any title that split wrongly (e.g. a song
      with " - " in its name).
- [ ] **E4. Apply** the ones that look right. **Expect:** they leave the list; the Artists
      tab shows them under the right artist (joining it if it already existed); Albums
      shows them under the folder's name as album (MusicBrainz fills the real album).

With **internet** (S26+ or S25+):
- [ ] **E5. Check with MusicBrainz.** On the review screen → Check with MusicBrainz first →
      Look up. **Expect:** progress "MusicBrainz: n of N" (~1–2 s per track), rows gain
      album · year · genre chips; a reversed split says "Turned around…". You can leave
      the screen meanwhile. Note any wrong album or year (the rule picks the earliest
      release — remasters and compilations should not win).
- [ ] **E6. Library-wide fill-in.** In the same sheet, tick "Also N tracks missing year or
      genre" (only shown if there are such tracks). Note N and how long it took; then
      Apply and check a few tracks' year/genre in Smart shuffle / AI playlists chips.
- [ ] **E7. Hand edit + look up.** Long-press any track → **Track info** → change a field →
      "Look up the rest on MusicBrainz" fills empty fields → Save. Reopen: values kept.
      **Use the file's info** puts the file's own info back.
- [ ] **E8. No internet during a lookup.** Airplane mode, start a lookup. **Expect:**
      "Couldn't reach MusicBrainz…" — no crash, and the Check button is offered again.

### What to send back

For each unticked box: the code (A1, C3, …), what happened instead, and the time. For A1
also the file type (MP3 / FLAC / hi-res FLAC). Screenshots help for anything in C and D.
