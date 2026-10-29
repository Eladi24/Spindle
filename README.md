# <img src=".github/assets/spindle-logo.svg" alt="Spindle logo" width="44" align="top"> Spindle

**Your own music, on your hi-fi, at full quality: straight from your Android phone.**

Spindle is a music player for the files on your phone. It plays them on the phone itself, or streams them over WiFi to a network player such as a Bluesound Node *(works with BluOS)*, untouched, bit for bit.

---

## The problem

You have a carefully built library of FLACs and MP3s on your phone, and a good hi-fi streamer in the living room. Getting one to play on the other is surprisingly hard:

- **Bluetooth** re-compresses everything, so hi-res files lose what made them hi-res.
- **Streamer apps** are built for streaming services and network drives, not for the music in your pocket.
- **Syncing to a NAS** just to play a song you already own is a chore.

## How Spindle solves it

Spindle treats the phone as a **library and remote control**, and the streamer as the **player**.

When you pick your streamer as the output, Spindle runs a small web server on your phone and hands the streamer a private link to the original file. The streamer fetches the file and decodes it itself, so nothing is re-encoded, down-sampled or squeezed through Bluetooth. Spindle keeps the queue, the controls and the volume in sync from your phone.

It's built to survive real homes: it reconnects when the WiFi drops, follows your phone to a new IP address, tells you when your phone has wandered onto a WiFi the streamer can't reach (and carries on by itself when it's back), moves the music back to your phone if the streamer is switched off, and steps back politely when someone else starts playing something on the streamer.

## What's new here ✨

**🔊 Streaming**
- One tap moves playback between the phone, Bluetooth earbuds and your streamer, and it picks up where you were. Connect your headphones and playback follows them.
- Your queue survives: close the app or come back tomorrow, and it's waiting where you left it.
- Original files, original quality: FLAC and hi-res go through untouched.
- Finds players on your network automatically, or connect by IP. Pick your streamer from any tab, even before choosing a song.

**🔀 Smart shuffle**
- Learns from what you play to the end and what you skip, all on your phone.
- Keeps the same artist or album from playing twice in a row, and brings back songs you haven't heard in months.
- Boost a song from the player to hear it sooner. In the queue, swipe a song right and the next 8 songs become ones like it.

**🪄 Playlists that build themselves**
- *"Mellow 90s for a rainy evening"*: describe a playlist in words, set its length in songs or minutes, and **Gemini Nano** turns it into one, **running entirely on the phone**. No account, no cloud, nothing leaves your device. *(Beta, on phones whose Android offers it to apps, such as the Galaxy S26 series and Pixel 9 and newer.)*
- Phone without Gemini Nano? Add your own **free Gemini key** from Google AI Studio in a few taps. Your request, your library's genre and decade names and the artist and title of the songs it picks from are sent; with no billing on the key, nothing can ever be charged.
- No on-device AI? Pick an era, genres and a length, and Spindle builds it from your tags.
- Every draft shows what Spindle understood, and you can adjust it before saving.

**🏷️ Tidy track info**
- Finds tracks with no artist and suggests the fix from the title or file name ("Deep Purple - Highway Star").
- Optionally checks them with **MusicBrainz** and fills in the album, year and genre, after you ask, never by itself.
- Fixes live in Spindle only: your music files are never changed.

**🎨 Feels good to use**
- A dark, glassy design with a floating, frosted nav bar and a full-screen Now Playing view.
- Fast with thousands of tracks: A–Z fast scroll, M3U import/export, lock-screen and headset controls.

## Built with

| | |
|---|---|
| **UI** | Jetpack Compose, Material 3, [Haze](https://github.com/chrisbanes/haze) (real blur) |
| **Playback** | Media3 / ExoPlayer, MediaSession |
| **Streaming** | [Ktor](https://ktor.io) (HTTP server with range requests, and the client for the player's API), Android NSD for discovery |
| **AI** | ML Kit GenAI Prompt API (Gemini Nano on the device), or the Gemini API with your own free key |
| **Metadata** | [MusicBrainz](https://musicbrainz.org) web service |
| **Data** | Room, DataStore, Paging 3, Kotlin Coroutines & Flow |
| **Plumbing** | Hilt, Coil 3, Navigation Compose |

Written in Kotlin. Needs Android 8.0 (API 26) or newer.

## Try it

```bash
git clone git@github.com:Eladi24/Spindle.git
cd Spindle
./gradlew installDebug   # with a phone connected over USB
```

Streaming needs a real phone on the same WiFi as your player; the Android emulator can't be reached from your home network.

## Status 🚧

Spindle is in beta (0.9.8). Local playback, streaming and smart shuffle work day to day; AI playlists and track-info fixes are new and still being tested on real phones.

---

*Spindle is an independent project and isn't affiliated with Lenbrook, Bluesound or BluOS.*
