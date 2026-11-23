(function () {
  'use strict';

  const root = window.SubLyter = window.SubLyter || {};
  const diag = root.diagnostics || { logWarn() {}, logError() {} };

  function getBridge() {
    return window.AndroidBridge || window.ExoPlayerManager || null;
  }

  /* ---- Асинхронный RPC для тяжёлых вызовов моста ---- */
  let nextReqId = 1;
  const pendingAsync = new Map();

  window.__onNativeAsyncResult = function (reqId, ok, payload) {
    const entry = pendingAsync.get(reqId);
    if (!entry) return;
    pendingAsync.delete(reqId);
    clearTimeout(entry.timer);
    if (ok) entry.resolve(payload);
    else entry.reject(new Error(payload || 'Native async error'));
  };

  function callNativeAsync(asyncMethod, fallbackSyncMethod, timeoutMs = 15000) {
    const bridge = getBridge();
    if (!bridge) return Promise.resolve(null);
    if (typeof bridge[asyncMethod] === 'function') {
      const reqId = nextReqId++;
      return new Promise((resolve, reject) => {
        const timer = setTimeout(() => {
          pendingAsync.delete(reqId);
          reject(new Error(`Native timeout (${asyncMethod})`));
        }, timeoutMs);
        pendingAsync.set(reqId, { resolve, reject, timer });
        try {
          bridge[asyncMethod](reqId);
        } catch (e) {
          pendingAsync.delete(reqId);
          clearTimeout(timer);
          diag.logError('bridge.' + asyncMethod, e);
          reject(e);
        }
      });
    }
    if (fallbackSyncMethod && typeof bridge[fallbackSyncMethod] === 'function') {
      try {
        return Promise.resolve(bridge[fallbackSyncMethod]());
      } catch (e) {
        diag.logWarn('bridge.' + fallbackSyncMethod, e);
        return Promise.resolve(null);
      }
    }
    return Promise.resolve(null);
  }

  function b64ToBlob(b64, type = 'audio/wav') {
    const bin = atob(b64);
    const len = bin.length;
    const u8 = new Uint8Array(len);
    for (let i = 0; i < len; i++) u8[i] = bin.charCodeAt(i);
    return new Blob([u8], { type });
  }

  async function loadChunkBlob(bridge, chunkObj) {
    const streamUrl = `https://sublater-chunks.local/chunk?path=${encodeURIComponent(chunkObj.path)}`;
    try {
      const resp = await fetch(streamUrl);
      if (resp.ok) {
        const blob = await resp.blob();
        if (blob && blob.size > 44) {
          if (bridge && typeof bridge.deleteChunkFile === 'function') {
            try { bridge.deleteChunkFile(chunkObj.path); } catch (e) { diag.logWarn('bridge.deleteChunk', e); }
          }
          return blob;
        }
      }
    } catch (e) {
      diag.logWarn('bridge.streamChunkFallback', e);
    }
    if (!bridge || typeof bridge.readChunkBase64 !== 'function') return null;
    const b64 = bridge.readChunkBase64(chunkObj.path);
    if (!b64) return null;
    if (typeof bridge.deleteChunkFile === 'function') {
      try { bridge.deleteChunkFile(chunkObj.path); } catch (e) { diag.logWarn('bridge.deleteChunk', e); }
    }
    return b64ToBlob(b64, 'audio/wav');
  }

  function deactivateNativeExoFallback(videoEl) {
    if (!videoEl) return;
    ['currentTime', 'duration', 'paused', 'videoWidth', 'videoHeight', 'play', 'pause'].forEach((prop) => {
      if (Object.prototype.hasOwnProperty.call(videoEl, prop)) {
        try {
          delete videoEl[prop];
        } catch (e) {
          diag.logWarn('bridge.restoreVideoProp.' + prop, e);
        }
      }
    });
  }

  root.bridge = {
    getBridge,
    callNativeAsync,
    loadChunkBlob,
    deactivateNativeExoFallback
  };
})();
