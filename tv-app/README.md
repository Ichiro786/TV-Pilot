# TV Pilot TV

TV Pilot running **on the TV itself**: press the remote's Google Assistant mic button and TV Pilot listens, understands, and drives the TV for you. Your phone isn't needed. The phone app (in the repo root) stays a companion.

Plain Java, built with `build.sh` (no Gradle). Package `dev.syed.tvpilot.tv`.

## What it does
- Takes over the remote's **Assistant / mic button** (optional, on by default) with a frosted glass voice bar.
- **Hybrid brain:** a decision model first (Liquid **d1-ultra**, or your own self-hosted d1), and Gemini vision only when it needs to look at the screen.
- **Conversation:** ask it questions and it answers (Gemini), speaking out loud if you set up a voice server.
- Drives the TV through its own ADB on `127.0.0.1:5555`: keys, deep links, app launches, on-screen picking.
- **Phone setup page:** open `http://<tv-ip>:7913` on your phone to paste keys and links instead of typing them with the remote.

## Setup
Everything starts **blank**. Nothing is baked in, so add your own:
| Setting | What it's for |
|---|---|
| Gemini API key | Speech to words, chat, and screen vision |
| Liquid API key | d1-ultra decisions (optional) |
| Your server link + key | Optional self-hosted d1 (`:8080`), Parakeet speech-to-text (`:8081`), and Phonon voice (`:8082`). Paste any one port's link; `https://8080-host…` style links swap the leading port, others get `:port`. |

Without your own server, speech goes to Gemini and replies only show on screen.

## Build
```sh
MIN_SDK=30 ./build.sh   # Android 11 TVs (e.g. TCL)
MIN_SDK=34 ./build.sh   # Android 14 TVs (e.g. Sony Bravia)
```
Needs the Android SDK (build-tools + a platform `android.jar`) and JDK 17. Point `ANDROID_SDK` at your SDK (default `~/Android/Sdk`). The first build creates its own signing key (`tvpilottv.keystore`, git-ignored).
