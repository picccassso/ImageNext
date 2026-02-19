# ImageNext

<img src="ImageNext_icon.png" width="120"/>

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

## Nextcloud Recommendation (Thumbnails)

For best thumbnail generation performance in ImageNext, use the **Nextcloud Preview Generator** app on your server and pre-generate previews after bulk uploads.

Typical commands:

```
sudo -u www-data php /var/www/nextcloud/occ files:scan --all
sudo -u www-data php /var/www/nextcloud/occ preview:generate-all --workers=4
sudo -u www-data php /var/www/nextcloud/occ preview:pre-generate
```

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

## Recent Changes

**2026-02-19**

- Thumbnail loading is now fast — previews are fetched using Nextcloud file IDs instead of paths, which fixed repeated HTTP 400 failures that were forcing slow local fallback processing. 100 thumbnails now load in ~4.7s instead of ~50s.
- macOS `._*` sidecar files are filtered out and no longer appear in the media timeline.
- Pull-to-refresh is disabled while a sync is already running to prevent accidental re-indexing churn.
- Timeline sort order is now stable when multiple items share the same timestamp, so the grid no longer jumps around after a sync.
- Backing out of the fullscreen viewer now returns you to the same position in the Photos grid.
- Added custom app launcher icon.

## License

MIT — see [LICENSE](LICENSE.md)
