# PiBrain Chat — Android App

Single-shell Android app containing Claude (claude.ai) and Gemini (gemini.google.com)
as full WebViews, plus a Relay panel for passing text between them.

## What it does

- **CLAUDE tab** — full claude.ai in a WebView, your session, your cookies
- **GEMINI tab** — full gemini.google.com in a WebView, your Google session
- **RELAY tab** — clipboard bridge to copy text between the two AIs + scratch pad

## Build

1. Open in Android Studio
2. Gradle sync
3. Plug in phone (USB debugging on)
4. Press ▶

No API keys. No backend. Log in once inside the app, cookies persist.

## Relay Workflow

1. Long-press → Copy any response from Claude or Gemini
2. Switch to RELAY tab
3. Tap ← From Claude / ← From Gemini
4. Edit/annotate
5. Tap Send to Claude → or Send to Gemini →
6. Switch to that tab, paste

## Stack
- Kotlin
- Android WebView + CookieManager
- Chrome UA spoofing (prevents WebView blocks)
- Material Components
