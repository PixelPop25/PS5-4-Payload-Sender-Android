# Nexus (PS5 Payload Sender) — Android

Android companion for sending PS5 payloads (Y2JB / LuaC0re / ELF) and optionally
hosting WebKit Autoloader pages on the LAN.

## Features (v1.0.13+)

- **Sync / Map / Save** payload workflow
- Sync order: **Nexus-Payloads first** (Y2JB + LuaC0re exploits), then official GitHub overlay
- Non-blocking sync (background HTTP)
- Left-edge **host** control (LAN file server for WebKit pages)
- Bundled WebKit host pages under `app/src/main/assets/webkit/` (offline)
- Android nav bar visible (not forced immersive fullscreen)

## WebKit pages — why only HTML?

Upstream itsPLK **release host** embeds a zip with exactly two standalone pages:

| File | Chain |
|------|--------|
| `index.html` / `relapse.html` | **Relapse** (default) |
| `poops.html` | **Poops** |

There are **no separate `.js` files** in that package on purpose. The build
(`ps5-webkit-remote-loader` → `build_standalone` → `build_host`) **inlines**
all scripts (offsets, ROP worker, `poops.js` / `relapse.js`, `autoload.js`,
`elfldr-check.js`) into each HTML file (~6 MB each).

The public git repo is small (~1.4 MB of source blobs). Exploit JS lives in the
`ps5-webkit-remote-loader` submodule and is only present after a full release
build — not as loose files in the host zip.

**Relapse is present** — it is `index.html` (and the explicit alias `relapse.html`).
Poops is `poops.html`. Relapse is unsupported on firmwares **9.05** and **11.40**
(upstream routes those to Poops).

### Bundled assets note

The large HTML pages are **not** committed here (GitHub blob size / clone weight).
They are produced from the official host package:

```text
https://github.com/itsPLK/ps5-webkit-autoloader/releases
→ webkit-autoloader-host_v*.py → EMBEDDED_ZIP_B64 → index.html + poops.html
```

Copy extracted files into `app/src/main/assets/webkit/` before building a release
APK that includes offline WebKit hosting. See `third_party/README-webkit.md`.

## Build

```bash
# Android SDK 34 + JDK 17
./gradlew assembleDebug
```

## Related

- Payloads: https://github.com/PixelPop25/Nexus-Payloads
- WebKit Autoloader: https://github.com/itsPLK/ps5-webkit-autoloader
