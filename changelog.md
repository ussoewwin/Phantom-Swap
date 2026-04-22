# Changelog

Notable changes to **Phantom Swap** (no-root virtual memory helper) and the related **Phantom Swap Root** experiment. Entries are grouped by release date; older items from the same development push are merged into one section where they shipped together.

The format loosely follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

---

## [Unreleased]

### Changed

- **Battery optimization UX:** Removed the automatic battery-optimization exclusion prompt from `MainActivity` (`checkBatteryOptimization()` flow and related settings intent). The app no longer opens the system exclusion request dialog on launch.
- **Power-management strategy:** Focus shifted to service-side resilience instead of launch-time permission prompting (foreground service + wake lock + watchdog + periodic health checks remain active).

### Notes

- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` is still declared in `AndroidManifest.xml`; if this UX is intentionally retired, the permission can be removed in a follow-up cleanup.

### Planned / discussed (not shipped as product requirements)

- Google Play distribution and pricing experiments (one-time, ads, freemium).
- Further store listing assets and policies review.

---

## 2026-04-15

### Fixed

- **Swap file location (FUSE / Doze):** Creation could stall or the process could be killed after long idle periods when the file lived under `getExternalFilesDir()` (FUSE-backed app external storage). The swap file is now created under **`context.filesDir`** (app-private internal storage) with the same 1 MB streaming write strategy, improving completion reliability on affected devices (e.g. UMIDIGI A7 Pro 64 GB eMMC class hardware).

### Changed

- **Build output:** Debug APK for the no-root app documented at ~8.0 MB; artifact name **`PhantomSwap-debug.apk`** (path under `app/build/outputs/apk/debug/` in local dev trees).

### Technical notes

- Foreground service + coroutines on I/O, optional wake lock, progress broadcasts for UI remain the pattern for long-running allocation.

---

## 2026-04-12

### Added

- **No-root app (Phantom Swap):** Kotlin app with swap-sized backing file, foreground service, seek/slider up to **8 GB**, RAM readouts, create/delete flow, progress UI (e.g. `ProgressBar` + notification copy such as written MB totals).
- **Root variant (separate repo / tree):** **`AndroidRootSwap`** (or equivalent) as an isolated project: kernel-driven swap via `swapon` / `swapoff`, status from **`/proc/swaps`**, no long-lived “holder” foreground service required for the mapping trick used in no-root mode.
- **Toolchain alignment:** Build stabilization for **AGP 9.1.x**, **Gradle 9.3.x**, and **JDK 21** (Kotlin plugin in `app/build.gradle.kts`, current `compileSdk` style).

### Changed

- **I/O strategy (no-root):** Replaced **4 KB page-by-page** `MappedByteBuffer` touching (very slow on large files, heavy on FUSE) with **1 MB `FileOutputStream` block writes** for practical creation times.
- **Branding:** App name **Phantom Swap**; English **`strings.xml`**; Gradle **`archivesBaseName`** / output **`PhantomSwap-debug.apk`** and **`PhantomSwapRoot-debug.apk`** for the root build where configured.
- **Identity:** Package / namespace migration from placeholder **`com.example.myswapapp`** to **`com.phantom.swap`**, and root app from **`com.example.myrootswapapp`** to **`com.phantom.swaproot`**, including source tree layout.
- **Concurrency model:** Heavy work runs on **background dispatchers** inside the **foreground service** so the UI thread stays responsive (foreground avoids process kill; worker threads are not “background app” in the Android scheduling sense).

### Fixed

- **8 GB creation crash / reset (no-root):** **`FileChannel.map`** is limited by **`Int.MAX_VALUE`** (~2 GB per map). Implemented **chunked mapping** (e.g. ~2 GB segments kept in a list) so 4–8 GB files can be mapped without VM blow-ups.
- **Root command hangs:** Shell pipelines could **deadlock** on full stdout/stderr buffers; root-side commands were hardened by discarding or redirecting output (e.g. `> /dev/null 2>&1` pattern) where appropriate.

### Documentation / product context

- **No-root vs kernel swap:** No-root mode may perform **large sequential writes** so the OS backs the file as usable memory pressure; real swap under root is **kernel-managed** and typically **writes as needed**, with different flash wear trade-offs.
- **Versus some store apps:** Some competitors may rely more on **sparse allocation** (fast, large logical size, little immediate physical write); early Phantom Swap prototypes leaned toward **full physical backing** for predictable behavior at the cost of time and wear — later 1 MB streaming balanced speed and behavior.

### Assets

- Launcher icon direction: **neon-blue infinity arrow** concept; mipmaps / adaptive (round) launcher integration as implemented in-project.

---

## Earlier than 2026-04-12 (discovery only)

These items are **research / architecture notes**, not versioned app releases:

- Explored feasibility of **SWAP-style behavior without root** (`Runtime.exec`, `mkswap` / `swapon` availability varies by **SELinux** and OEM).
- Confirmed **sideload** install path (unknown-sources flow); real `swapon` from a normal app remains **device-dependent**.
- Root bring-up notes for **UMIDIGI A7 Pro** class devices (bootloader unlock, Magisk-patched `boot.img`, **`vbmeta` / verity** cautions) documented for the root fork only; follow current Magisk/OEM guides before flashing.

---

## Links

- Repository: [Phantom-Swap](https://github.com/ussoewwin/Phantom-Swap)
- Releases: installable APKs when published appear under **Releases**.
