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
