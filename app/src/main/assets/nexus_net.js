/* nexus_net.js — ALL networking for Nexus lives in this file.
 *
 * Rules of this file:
 *   - It NEVER touches the DOM (no document / getElementById / classList).
 *   - It talks to the UI only through events: NexusNet.subscribe(fn(type, data)).
 *   - Every download run has an "epoch". NexusNet.cancel() bumps it, aborts the
 *     in-flight fetch and tells the native side to drop its HTTP read, so a
 *     toggle / mode switch stops the old downloads immediately.
 *
 * Events (type, data):
 *   'item'          { id, state }   state: 'downloading' | 'ready' | 'failed' | 'idle'
 *   'list-changed'  { kind }        'payloads' | 'exploits'  -> UI should re-render that list
 *   'status'        { code, ... }   syncing | busy | offline | done | allcached | failed | item-error
 *   'run-end'       { summary }     a payload run finished (or was cancelled)
 *
 * Uses the global StorageEngine from index.html (only at call time).
 */
(function (root) {
  'use strict';

  var CLOUD_URL = 'https://raw.githubusercontent.com/PixelPop25/Nexus-Payloads/main/';

  // Built-in copy of official_repos.json. Used when the remote copy has never been fetched.
  var DEFAULT_OFFICIAL = {"version": 1, "updated": "2026-10-19", "note": "Official payload repos. Host this next to manifest.json in the Nexus-Payloads repo to update the list without a new APK. 'id' must stay stable (it is the cache key). 'asset' is a glob matched against GitHub release asset names.", "repos": [{"id": "rel_kstuff_lite_echo_", "name": "Kstuff Lite (Echo)", "repo": "EchoStretch/kstuff-lite", "asset": "kstuff.elf"}, {"id": "rel_kstuff_lite_drakmor_", "name": "Kstuff Lite (Drakmor)", "repo": "drakmor/kstuff-lite", "asset": "kstuff.elf"}, {"id": "rel_shadowmountplus", "name": "ShadowMountPlus", "repo": "drakmor/ShadowMountPlus", "asset": "*ShadowMount*.elf"}, {"id": "rel_ftp_server", "name": "FTP Server", "repo": "ps5-payload-dev/ftpsrv", "asset": "*.elf"}, {"id": "rel_ps5_upload", "name": "PS5 Upload", "repo": "phantomptr/ps5upload", "asset": "*.elf"}, {"id": "rel_nanodns", "name": "NanoDNS", "repo": "drakmor/nanoDNS", "asset": "nanodns.elf"}, {"id": "rel_payload_manager", "name": "Payload Manager", "repo": "itsPLK/ps5-payload-manager", "asset": "pldmgr*.elf"}, {"id": "rel_cheatrunner", "name": "CheatRunner", "repo": "notmaj0r/CheatRunner", "asset": "*.elf"}, {"id": "rel_onionhen", "name": "OnionHEN", "repo": "aydencharles/onionHEN", "asset": "OnionHEN.elf"}, {"id": "rel_etahen", "name": "EtaHEN", "repo": "etaHEN/etaHEN", "asset": "*.bin"}, {"id": "rel_bfpilot", "name": "BFPilot", "repo": "ItsBlurf/BFpilot", "asset": "*.elf"}, {"id": "rel_ps_patch_system", "name": "PS Patch System", "repo": "illusionyy/ps-patch-system", "asset": "*.elf"}, {"id": "rel_webfile_manager", "name": "WebFile Manager", "repo": "owendswang/ps5-web-file-manager", "asset": "*.elf"}, {"id": "rel_fan_control", "name": "Fan Control", "repo": "owendswang/ps5-fan-control", "asset": "ps5-fan-control.elf"}, {"id": "rel_fan_target", "name": "Fan Target", "repo": "drakmor/fan_target", "asset": "fan_target*.elf"}, {"id": "rel_linux_loader", "name": "Linux Loader", "repo": "ps5-linux/ps5-linux-loader", "asset": "*linux*.elf"}, {"id": "rel_game_compressor", "name": "Game Compressor", "repo": "juma-sayeh/PS5-Game-Compressor", "asset": "game-compressor.elf"}, {"id": "rel_lz4_compression", "name": "LZ4 Compression", "repo": "theghostonline/PS5-LZ4-Compression", "asset": "lz4-compression.elf"}, {"id": "rel_unrar", "name": "UnRAR", "repo": "bizkut/unrar-ps5", "asset": "unrar*.elf"}, {"id": "rel_pkg_manager", "name": "PKG Manager", "repo": "itsPLK/ps5-pkg-manager", "asset": "*.elf"}, {"id": "rel_elf_loader", "name": "ELF Loader", "repo": "ps5-payload-dev/elfldr", "asset": "*.elf"}]};

  var listeners = new Set();
  function emit(type, data) {
    listeners.forEach(function (fn) { try { fn(type, data || {}); } catch (e) { console.warn('NexusNet listener', e); } });
  }

  // ── helpers ──────────────────────────────────────────────────────────
  var relId = function (n) { return 'rel_' + String(n).toLowerCase().replace(/[^a-z0-9]+/g, '_'); };
  var itemId = function (it) { return it.id || it.file || it.name; };
  var keyOf = function (it) { return 'cloud_cache_' + it.id; };
  var globRe = function (g) {
    return new RegExp('^' + String(g).trim().replace(/[.+^${}()|[\]\\]/g, '\\$&').replace(/\*/g, '.*').replace(/\?/g, '.') + '$', 'i');
  };
  var sleep0 = function () { return new Promise(function (r) { setTimeout(r, 0); }); };
  function shortMsg(e) {
    if (e && e.name === 'AbortError') return 'timed out';
    var m = String((e && e.message) || e || 'error').replace(/^Error:\s*/, '');
    return m.length > 60 ? m.slice(0, 57) + '...' : m;
  }
  async function readJSON(key, dflt) {
    try { var raw = await StorageEngine.getItem(key); return raw ? JSON.parse(raw) : dflt; }
    catch (e) { return dflt; }
  }
  function b64ToText(b64) {
    return new TextDecoder().decode(Uint8Array.from(atob(b64), function (c) { return c.charCodeAt(0); }));
  }
  function bufToB64(buf) {
    var u8 = new Uint8Array(buf), chunk = 0x8000, s = '';
    for (var i = 0; i < u8.length; i += chunk) s += String.fromCharCode.apply(null, u8.subarray(i, Math.min(i + chunk, u8.length)));
    return btoa(s);
  }

  // ── cancellation state ───────────────────────────────────────────────
  var epoch = 0;          // bumped by cancel(); every running loop checks it
  var current = null;     // the payload run in flight: { mode, epoch, promise }
  var ctrl = null;        // AbortController of the in-flight JS fetch (payload runs only)
  var downloading = {};   // id -> true while that item is actively downloading
  var exploitsRun = null;

  // ── low-level http ───────────────────────────────────────────────────
  async function fetchJSON(url, ms, link) {
    var c = new AbortController();
    if (link) ctrl = c;
    var t = setTimeout(function () { c.abort(); }, ms || 12000);
    try {
      var r = await fetch(url, { signal: c.signal, cache: 'no-store' });
      if (!r.ok) throw new Error('HTTP ' + r.status);
      return await r.json();
    } finally { clearTimeout(t); if (link && ctrl === c) ctrl = null; }
  }
  async function fetchB64(url, ms, link) {
    var c = new AbortController();
    if (link) ctrl = c;
    var t = setTimeout(function () { c.abort(); }, ms || 120000);
    try {
      var r = await fetch(url, { signal: c.signal });
      if (!r.ok) throw new Error('HTTP ' + r.status);
      return bufToB64(await r.arrayBuffer());
    } finally { clearTimeout(t); if (link && ctrl === c) ctrl = null; }
  }

  // Native background-thread download (GitHub release assets redirect to hosts without CORS)
  var waiters = {}, hSeq = 0;
  root.__nativeHttpCb = function (id, result) {
    var w = waiters[id];
    if (!w) return;
    delete waiters[id];
    if (typeof result === 'string' && result.indexOf('ERR:') === 0) w.reject(new Error(result.slice(4)));
    else w.resolve(result);
  };
  function nativeGet(url) {
    return new Promise(function (resolve, reject) {
      if (!(root.AndroidNativeHttp && typeof root.AndroidNativeHttp.getB64Async === 'function')) {
        fetchB64(url, 180000, true).then(resolve, reject);   // desktop / browser fallback
        return;
      }
      var id = 'h' + (++hSeq);
      var t = setTimeout(function () {
        if (waiters[id]) { delete waiters[id]; reject(new Error('timeout')); }
      }, 240000);
      waiters[id] = {
        resolve: function (v) { clearTimeout(t); resolve(v); },
        reject: function (e) { clearTimeout(t); reject(e); }
      };
      try { root.AndroidNativeHttp.getB64Async(url, id); }
      catch (e) { clearTimeout(t); delete waiters[id]; reject(e); }
    });
  }

  // ── item lists (no network: built from cache / built-in defaults) ────
  async function nexusList() {
    var man = await readJSON('nexus_cloud_manifest', null);
    return ((man && man.payloads) || []).map(function (p) {
      return { id: itemId(p), name: p.name || p.file || p.id, file: p.file, kind: 'nexus' };
    });
  }
  async function officialList() {
    var man = await readJSON('nexus_official_manifest', null);
    var repos = (man && Array.isArray(man.repos) && man.repos.length) ? man.repos : DEFAULT_OFFICIAL.repos;
    var out = repos.filter(function (r) { return r && r.name && (r.repo || r.url); }).map(function (r) {
      return { id: r.id || relId(r.name), name: r.name, repo: r.repo, asset: r.asset || '*.elf', url: r.url || null, kind: 'official' };
    });
    // user-added custom sources (older builds also copied the official ones in here -> skip those)
    var skip = new Set(out.map(function (o) { return o.name; }));
    DEFAULT_OFFICIAL.repos.forEach(function (r) { skip.add(r.name); });
    var custom = await readJSON('nexus_sources', []);
    custom.forEach(function (s) {
      if (s && s.name && !skip.has(s.name)) {
        out.push({ id: relId(s.name), name: s.name, repo: s.repo, asset: s.asset || '*.elf', url: s.url || null, kind: 'custom' });
      }
    });
    return out;
  }
  function listItems(mode) { return mode === 'official' ? officialList() : nexusList(); }

  async function refreshOfficialManifest(link) {
    try {
      var m = await fetchJSON(CLOUD_URL + 'official_repos.json', 8000, link);
      if (m && Array.isArray(m.repos) && m.repos.length) {
        await StorageEngine.setItem('nexus_official_manifest', JSON.stringify(m));
        return true;
      }
    } catch (e) { /* keep cached / built-in list */ }
    return false;
  }

  // ── GitHub release resolution ────────────────────────────────────────
  async function resolveRelease(it) {
    if (it.url) return { url: it.url, tag: it.tag || '' };
    var info = JSON.parse(b64ToText(await nativeGet('https://api.github.com/repos/' + it.repo + '/releases?per_page=5')));
    var re = globRe(it.asset || '*.elf');
    for (var i = 0; i < (Array.isArray(info) ? info.length : 0); i++) {
      var a = (info[i].assets || []).find(function (x) { return re.test(x.name); });
      if (a) return { url: a.browser_download_url, tag: info[i].tag_name || info[i].name || '' };
    }
    throw new Error('no file matches ' + (it.asset || '*.elf'));
  }
  async function saveRelease(it, tag) {
    var rels = await readJSON('nexus_releases', []);
    rels = rels.filter(function (x) { return x.id !== it.id; });
    rels.push({ id: it.id, name: it.name, tag: tag });
    await StorageEngine.setItem('nexus_releases', JSON.stringify(rels));
  }

  // ── one item ─────────────────────────────────────────────────────────
  async function downloadOne(it, have, mode, my, sum) {
    var alive = function () { return my === epoch; };
    var mark = function (state) {
      if (!alive()) return;
      downloading[it.id] = true;
      emit('item', { id: it.id, state: state });
    };
    try {
      var b64, tag = '';
      if (!have) mark('downloading');
      if (mode === 'nexus') {
        b64 = await fetchB64(CLOUD_URL + it.file, 180000, true);
      } else {
        var rel = await resolveRelease(it);
        tag = rel.tag;
        if (have) {                                   // "Update All": skip when the tag is unchanged
          var cur = (await readJSON('nexus_releases', [])).find(function (x) { return x.id === it.id; });
          if (cur && cur.tag === tag) return;
          mark('downloading');
        }
        b64 = await nativeGet(rel.url);
      }
      if (!b64 || b64.length < 8) throw new Error('empty download');
      await StorageEngine.setItem(keyOf(it), b64);    // a finished file is always kept, even if the run was cancelled
      if (mode !== 'nexus') await saveRelease(it, tag);
      sum.downloaded++;
      emit('item', { id: it.id, state: 'ready' });
    } catch (e) {
      if (!alive()) return;                           // cancelled: cancel() already reset the spinner
      sum.failed++;
      emit('item', { id: it.id, state: have ? 'ready' : 'failed' });
      emit('status', { code: 'item-error', name: it.name, message: shortMsg(e) });
    } finally {
      delete downloading[it.id];
    }
  }

  // ── one payload run (mode: 'nexus' | 'official') ─────────────────────
  async function runPayloads(mode, opts, my) {
    var alive = function () { return my === epoch; };
    var sum = { mode: mode, total: 0, missing: 0, downloaded: 0, failed: 0, offline: false, cancelled: false };
    try {
      if (typeof navigator !== 'undefined' && navigator.onLine === false) { sum.offline = true; return sum; }
      if (mode === 'nexus') {
        var haveManifest = !!(await readJSON('nexus_cloud_manifest', null));
        if (opts.refresh || !haveManifest) {
          try {
            var man = await fetchJSON(CLOUD_URL + 'manifest.json', 12000, true);
            if (!alive()) { sum.cancelled = true; return sum; }
            await StorageEngine.setItem('nexus_cloud_manifest', JSON.stringify(man));
            emit('list-changed', { kind: 'payloads' });
          } catch (e) {
            if (!alive()) { sum.cancelled = true; return sum; }
            sum.offline = true;
            if (!haveManifest) return sum;            // nothing cached and nothing fetched
          }
        }
      } else {
        var haveOff = !!(await readJSON('nexus_official_manifest', null));
        if (opts.refresh || !haveOff) {
          var got = await refreshOfficialManifest(true);
          if (!alive()) { sum.cancelled = true; return sum; }
          if (got) emit('list-changed', { kind: 'payloads' });
        }
      }

      var items = await listItems(mode);
      sum.total = items.length;
      var todo = [];
      for (var i = 0; i < items.length; i++) {
        var have = await StorageEngine.has(keyOf(items[i]));
        if (!have || (opts.force && mode !== 'nexus')) todo.push({ it: items[i], have: have });
      }
      sum.missing = todo.filter(function (t) { return !t.have; }).length;

      for (var k = 0; k < todo.length; k++) {
        if (!alive()) { sum.cancelled = true; break; }
        await downloadOne(todo[k].it, todo[k].have, mode, my, sum);
        if (sum.failed >= 3 && sum.downloaded === 0) { sum.offline = true; break; }   // network is down: stop spamming errors
        await sleep0();                               // let the UI breathe between files
      }
      if (!alive()) sum.cancelled = true;
    } catch (e) {
      if (!alive()) sum.cancelled = true;
      else { console.warn('payload run failed', e); sum.failed++; }
    } finally {
      emit('run-end', { summary: sum });
    }
    return sum;
  }

  // ── public: payloads ─────────────────────────────────────────────────
  function cancel() {
    epoch++;
    current = null;
    if (ctrl) { try { ctrl.abort(); } catch (e) {} ctrl = null; }
    try { if (root.AndroidNativeHttp && typeof root.AndroidNativeHttp.cancelAll === 'function') root.AndroidNativeHttp.cancelAll(); } catch (e) {}
    Object.keys(downloading).forEach(function (id) { delete downloading[id]; emit('item', { id: id, state: 'idle' }); });
  }

  // opts: { refresh: re-fetch the manifest, force: re-check release tags of already cached items }
  function syncPayloads(mode, opts) {
    opts = opts || {};
    if (current) {
      if (current.mode === mode) return current.promise;   // same source already running: join it
      cancel();                                            // different source: stop the old one first
    }
    var my = epoch;
    var run = { mode: mode, epoch: my };
    run.promise = runPayloads(mode, opts, my).catch(function (e) { console.warn(e); return null; })
      .then(function (s) { if (current === run) current = null; return s; });
    current = run;
    return run.promise;
  }

  // ── public: exploits (independent of payload cancel) ─────────────────
  function syncExploits() {
    if (exploitsRun) return exploitsRun;
    exploitsRun = (async function () {
      var sum = { downloaded: 0, failed: 0, offline: false };
      try {
        var man = await fetchJSON(CLOUD_URL + 'manifest.json', 12000, false);
        await StorageEngine.setItem('nexus_cloud_manifest', JSON.stringify(man));
        emit('list-changed', { kind: 'exploits' });
        var items = [].concat(man.luac0re || [], man.yarp2jb || [], man.lua || [], man.y2jb || []);
        for (var i = 0; i < items.length; i++) {
          var key = 'cloud_cache_' + itemId(items[i]);
          if (await StorageEngine.has(key)) continue;
          try {
            await StorageEngine.setItem(key, await fetchB64(CLOUD_URL + items[i].file, 120000, false));
            sum.downloaded++;
          } catch (e) { sum.failed++; }
          await sleep0();
        }
      } catch (e) { sum.offline = true; }
      return sum;
    })().then(function (s) { exploitsRun = null; return s; }, function () { exploitsRun = null; return null; });
    return exploitsRun;
  }

  // Sync button: exploits first, then the active payload source
  async function syncAll(opts) {
    opts = opts || {};
    var mode = opts.mode || 'nexus';
    if (typeof navigator !== 'undefined' && navigator.onLine === false) { emit('status', { code: 'offline' }); return null; }
    if (current && current.mode === mode) emit('status', { code: 'busy' });
    else emit('status', { code: 'syncing' });
    var ex = await syncExploits();
    var sum = await syncPayloads(mode, { refresh: true });
    if (!sum || sum.cancelled) return sum;
    if (sum.offline && (!ex || ex.offline)) emit('status', { code: 'offline' });
    else if (sum.failed > 0) emit('status', { code: 'failed', failed: sum.failed });
    else if (sum.downloaded > 0 || (ex && ex.downloaded > 0)) emit('status', { code: 'done' });
    else emit('status', { code: 'allcached' });
    return sum;
  }

  root.NexusNet = {
    CLOUD_URL: CLOUD_URL,
    subscribe: function (fn) { listeners.add(fn); return function () { listeners.delete(fn); }; },
    cancel: cancel,
    syncPayloads: syncPayloads,
    syncExploits: syncExploits,
    syncAll: syncAll,
    listItems: listItems,
    isDownloading: function (id) { return !!downloading[id]; },
    isBusy: function () { return !!current; },
    refreshOfficialManifest: function () { return refreshOfficialManifest(false); }
  };
})(window);
