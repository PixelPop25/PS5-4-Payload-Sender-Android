# WebKit Autoloader (bundled)

Host pages shipped under `app/src/main/assets/webkit/` are extracted from the
official itsPLK WebKit Autoloader host package so the app can serve them offline.

Optional git submodule (for updating sources):

```bash
git submodule add https://github.com/itsPLK/ps5-webkit-autoloader.git third_party/ps5-webkit-autoloader
```

Rebuild/refresh bundled pages by extracting `EMBEDDED_ZIP_B64` from the upstream
`webkit-autoloader-host_*.py` release and copying `index.html` + `poops.html`
into `app/src/main/assets/webkit/`.
