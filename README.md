# ImageNext

An Android app for browsing and backing up your photos and videos to a Nextcloud server.

## Features

- **Nextcloud login** — Browser Login Flow v2 with app-password fallback.
- **Folder selection** — Pick which Nextcloud folders to sync from.
- **Photo and video timeline** — Grid view grouped by date with paging for large libraries. Videos show a play badge overlay.
- **Fullscreen viewer** — Swipe between photos and videos. Pinch to zoom on images, in-app video playback with seek, sound toggle, and auto-hiding controls.
- **Smart albums** — Recents, Photos, and Videos albums that update automatically. You can also create your own albums manually.
- **Backup uploads** — Back up your local photos and videos to Nextcloud. Configurable schedule, network and power policies, flat or year/month folder structure, and optional mirror-delete.
- **Offline thumbnails** — Background sync indexes metadata and caches thumbnails. Falls back to local frame extraction for videos when the server doesn't provide previews.
- **App lock** — PIN or biometric lock for the app.
- **Security** — Credentials stored in Android Keystore. HTTPS only. No analytics, no telemetry.

## Known Issues / Work in Progress

- Auto sync still needs work and is being actively worked on.
- Some animations are a bit rough and need smoothing out.
- Thumbnail loading on first sync is slow, this is being worked on.
- The general UI and overall look of the app is still being worked on.

## Requirements

- Android 9+ (API 28)
- A Nextcloud server

## Build

```
./gradlew assembleDebug
```

## Project Structure

```
app/          — App shell, navigation, theme
core/model    — Domain types
core/common   — Shared utilities
core/network  — Nextcloud auth and WebDAV client
core/database — Room database, entities, DAOs
core/security — Keystore credential vault, session management
core/data     — Repository implementations
core/sync     — WorkManager background sync and backup upload pipeline
feature/      — UI features (onboarding, folders, photos, viewer, albums, settings)
```

## License

TBD
