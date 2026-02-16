# ImageNext

A modern photo viewer for Nextcloud on Android.

Browse your photos, view them fullscreen, and keep thumbnails cached for offline use. 

## Features

- **Nextcloud login** — Browser Login Flow v2 with app-password fallback.
- **Folder selection** — Pick which Nextcloud folders to sync.
- **Photo timeline** — Grid view grouped by date, with paging for large libraries.
- **Fullscreen viewer** — Swipe between photos, pinch to zoom, adjacent image prefetch.
- **Albums** — Folder-based album grouping.
- **Offline thumbnails** — Background sync indexes metadata and caches thumbnails progressively.
- **Security** — Credentials stored in Android Keystore. No analytics, no telemetry. HTTPS only.

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
core/sync     — WorkManager background sync
feature/      — UI features (onboarding, folders, photos, viewer, albums, settings)
```

## License

TBD
