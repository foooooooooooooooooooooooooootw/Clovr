# Build Upgrade Notes

## What changed from the original Clover-dev

### Gradle & build toolchain

| Thing | Before | After | Notes |
|-------|--------|-------|-------|
| Android Gradle Plugin | 4.0.1 | 7.4.2 | Requires JDK 11+ |
| Gradle wrapper | 6.1.1 | 7.5 | Matched to AGP 7.x |
| `compileSdkVersion` | 29 | 34 | |
| `targetSdkVersion` | 29 | 34 | |
| `minSdkVersion` | 17 | 21 | Drops Android 4.x (< 0.5% of devices) |
| Java source compat | 1.8 | 11 | Required by AGP 7 |
| Repository | jcenter + google | mavenCentral + google | jcenter shut down in 2022 |

**JDK requirement:** Install JDK 11 or 17 (both work). You do not need to remove
your existing JDK — Android Studio manages JDK versions per-project. Set
`File → Project Structure → SDK Location → Gradle JDK` to JDK 11 or 17.

**Why AGP 7 not 8?** AGP 8 requires migrating the build scripts to use the new
`namespace` field (done here) and drops several deprecated APIs. AGP 7.4.2 is
a stable middle ground — modern enough to get security fixes and mavenCentral
support, conservative enough that the rest of the codebase doesn't need touching.
You can step to AGP 8 later.

### Repository: jcenter → mavenCentral

jcenter was shut down by JFrog in 2022. All packages have been migrated to
mavenCentral. The root `build.gradle` now uses `mavenCentral()` everywhere.
Some older package coordinates changed slightly when moving to Maven Central —
the versions in `app/build.gradle` have been updated accordingly.

### Sentry removed from build

The original build had hardcoded Sentry DSN tokens pointing to the original
author's private Sentry account. These have been removed. If you want crash
reporting in your fork, create a free Sentry account, get your own DSN, and
uncomment the relevant lines in both `build.gradle` files.

---

## Video playback: ExoPlayer 2.11 → Media3

The original code used `com.google.android.exoplayer:exoplayer-core:2.11.7`.
This API was overhauled and the old classes were deleted:

| Old (removed) | New (Media3) |
|---------------|-------------|
| `ExoPlayerFactory.newSimpleInstance()` | `new ExoPlayer.Builder(context).build()` |
| `SimpleExoPlayer` | `ExoPlayer` |
| `ExtractorMediaSource.Factory` | `MediaItem.fromUri(...)` + `player.setMediaItem(...)` |
| `AudioListener` | `Player.Listener` (unified listener) |
| `exoplayer2.audio.AudioListener` import | removed |
| `com.google.android.exoplayer2.*` | `androidx.media3.*` |

### What this fixes

- **VP9 WebM** files that previously errored or produced a black screen
- **AV1** files (common on some boards)  
- **H.265/HEVC** files (where device supports hardware decode)
- Silent/unexplained video errors — `onPlayerError` now logs
  `error.getErrorCodeName()` so you can actually see what failed
- ExoPlayer 2.11 had a bug where seeking on some MP4s caused a crash;
  Media3 1.3 has this fixed

### The `@UnstableApi` annotation

Media3 marks most of its surface area `@UnstableApi` because the team reserves
the right to change things between releases. The `MultiImageView` class is
annotated with `@UnstableApi` to suppress the resulting lint warnings. This is
the recommended approach per the Media3 migration guide — it does not indicate
anything is actually broken.

---

## Dependency version summary

| Library | Old | New | Reason |
|---------|-----|-----|--------|
| ExoPlayer / Media3 | exoplayer 2.11.7 | media3 1.3.0 | API removed, codec fixes |
| OkHttp | 3.10.0 | 4.12.0 | Security fixes, HTTP/2 improvements |
| Volley | 1.1.1 | 1.2.1 | Bug fixes |
| ORMLite | 4.48 | 5.7 | Bug fixes, no migration needed |
| Gson | 2.8.6 | 2.10.1 | Security fix (CVE-2022-25647) |
| appcompat | 1.1.0 | 1.6.1 | Bug fixes |
| material | 1.1.0 | 1.11.0 | Bug fixes |
| GifDrawable | 1.2.20 | 1.2.28 | Crash fixes |
| jsoup | 1.13.1 | 1.17.2 | Security fixes |
| JUnit | 4.12 | 4.13.2 | Security fix |
| Mockito | 2.27.0 | 5.10.0 | |
