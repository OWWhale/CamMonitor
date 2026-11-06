# Changelog

All notable changes to this project are documented here.
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/);
this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.0.8] - 2026-11-06

### Added

- **Multi-lens switching.** Phones with several cameras can switch between
  0.5x / 1x / 2x and the front camera from the viewer while streaming. All
  lenses share one video track, so switching never renegotiates the session.
- **Simplified Chinese** resources (`values-b+zh+Hans`), sitting next to
  Traditional Chinese. Language picker now offers system default, Simplified,
  Traditional, English and Japanese.
- Log viewer: level filter and one-tap zip export.
- `CHANGELOG.md`, `.gitattributes`, and a GitHub Actions build check.

### Changed

- **One sensor at a time.** The camera no longer opens every lens concurrently
  (CameraX only supports two anyway); it keeps a single `VideoSource`/track and
  swaps the sensor on demand.
- **Black screen now locks.** The old behaviour only dimmed the panel and any
  tap woke the camera phone. It now switches the display off *and* locks touch
  input in one step; swipe up to unlock. A 300ms gesture gate and a 3s cooldown
  after unlocking keep stray touches from re-locking or unlocking by accident.
- **Screen stays on while streaming**, so the preview no longer dies mid-session;
  turning the display off is explicit.
- No-viewer standby shortened from 60s to 15s.
- Default resource set is English again, matching upstream.

### Fixed

- **Resolution collapse in portrait.** `CameraX` sizes are sensor-space (always
  landscape); swapping width and height for portrait made every request fall
  back to 192x108 while the log still claimed 720p.
- Dead PeerConnection rebuilds: concurrent pipeline rebuilds now go through a
  guard, and a surface/first-frame watchdog reports a silent bind instead of
  waiting forever.
- Concurrent ECDH handshakes exhausted Keystore operation handles, producing
  repeated `Invalid operation handle` errors.
- The CPU wake lock was acquired for 24h and expired silently, which looked
  like the service randomly freezing. It now lives with the service.
- Camera recovery restarts with exponential backoff instead of a tight loop.
- `zh-*` locales no longer collapse to Traditional Chinese, so a zh-CN device
  reaches the Simplified resources.

## [1.0.7] - 2026-11-05

Upstream baseline. See the git history before this branch.
