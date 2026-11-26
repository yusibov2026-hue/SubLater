(function (root, factory) {
  const api = factory(root.SubLyter);
  if (typeof module !== 'undefined' && module.exports) {
    module.exports = api;
  }
  root.SubLyter = root.SubLyter || {};
  root.SubLyter.localWhisper = api;
})(typeof globalThis !== 'undefined' ? globalThis : window, function (subLyterRoot) {
  'use strict';

  const STORAGE_KEY = 'sublater_local_whisper_v1';
  const TRANSFORMERS_CDN_URLS = [
    'https://cdn.jsdelivr.net/npm/@huggingface/transformers@3.5.1/+esm',
    'https://cdn.jsdelivr.net/npm/@huggingface/transformers@3/+esm'
  ];

  const LOCAL_MODELS = {
    'local-whisper-base': {
      id: 'local-whisper-base',
      repoId: 'onnx-community/whisper-base',
      sizeMb: 76,
      dtype: 'q8',
      fallbackDtype: 'q8',
      badgeKey: 'model.base.badge',
      titleKey: 'model.base.title'
    },
    'local-whisper-small': {
      id: 'local-whisper-small',
      repoId: 'onnx-community/whisper-small',
      sizeMb: 245,
      dtype: { encoder_model: 'q8', decoder_model_merged: 'q4' },
      fallbackDtype: 'q8',
      badgeKey: 'model.small.badge',
      titleKey: 'model.small.title'
    }
  };

  const pipelineCache = new Map();
  const activeDownloads = new Map();
  let transformersModPromise = null;

  function getDiag() {
    return (subLyterRoot && subLyterRoot.diagnostics) || { logWarn() {}, logError() {} };
  }

  function isLocalWhisperModel(modelId) {
    return Object.prototype.hasOwnProperty.call(LOCAL_MODELS, String(modelId || ''));
  }

  function getLocalModelConfig(modelId) {
    return LOCAL_MODELS[String(modelId || '')] || null;
  }

  function readMetaMap() {
    try {
      if (typeof localStorage === 'undefined') return {};
      const raw = localStorage.getItem(STORAGE_KEY) || '';
      const parsed = raw ? JSON.parse(raw) : {};
      return parsed && typeof parsed === 'object' ? parsed : {};
    } catch (_) {
      return {};
    }
  }

  function writeMetaMap(map) {
    try {
      if (typeof localStorage === 'undefined') return;
      localStorage.setItem(STORAGE_KEY, JSON.stringify(map || {}));
    } catch (_) {}
  }

  function isModelDownloaded(modelId) {
    if (!isLocalWhisperModel(modelId)) return false;
    const map = readMetaMap();
    return Boolean(map[modelId] && map[modelId].downloaded);
  }

  function setModelDownloaded(modelId, downloaded) {
    const cfg = getLocalModelConfig(modelId);
    if (!cfg) return;
    const map = readMetaMap();
    if (downloaded) {
      map[modelId] = {
        downloaded: true,
        updatedAt: Date.now(),
        sizeMb: cfg.sizeMb
      };
    } else {
      delete map[modelId];
    }
    writeMetaMap(map);
  }

  function decodeWavToFloat32(arrayBuffer) {
    if (!arrayBuffer || arrayBuffer.byteLength < 44) return null;
    const dv = new DataView(arrayBuffer);
    if (dv.getUint32(0, false) !== 0x52494646 || dv.getUint32(8, false) !== 0x57415645) {
      return null;
    }

    let sampleRate = 16000;
    let channels = 1;
    let bitsPerSample = 16;
    let audioFormat = 1;
    let dataOff = -1;
    let dataLen = 0;
    let offset = 12;

    while (offset + 8 <= dv.byteLength) {
      const chunkId = dv.getUint32(offset, false);
      const chunkSize = dv.getUint32(offset + 4, true);
      const body = offset + 8;
      if (chunkId === 0x666d7420 && body + 16 <= dv.byteLength) {
        audioFormat = dv.getUint16(body, true);
        channels = Math.max(1, dv.getUint16(body + 2, true));
        sampleRate = Math.max(8000, dv.getUint32(body + 4, true));
        bitsPerSample = dv.getUint16(body + 14, true);
      } else if (chunkId === 0x64617461) {
        dataOff = body;
        dataLen = Math.min(chunkSize, dv.byteLength - body);
        break;
      }
      offset = body + chunkSize + (chunkSize % 2);
    }

    if (dataOff < 0 || dataLen <= 0 || audioFormat !== 1 || bitsPerSample !== 16) {
      return null;
    }

    const totalSamples = Math.floor(dataLen / 2);
    const totalFrames = Math.floor(totalSamples / channels);
    if (totalFrames <= 0) return new Float32Array(0);

    const mono = new Float32Array(totalFrames);
    if (channels === 1) {
      for (let i = 0; i < totalFrames; i++) {
        mono[i] = dv.getInt16(dataOff + i * 2, true) / 32768;
      }
    } else {
      for (let i = 0; i < totalFrames; i++) {
        let sum = 0;
        const frameBase = dataOff + i * channels * 2;
        for (let ch = 0; ch < channels; ch++) {
          sum += dv.getInt16(frameBase + ch * 2, true) / 32768;
        }
        mono[i] = sum / channels;
      }
    }

    if (sampleRate === 16000) return mono;

    const outLen = Math.max(1, Math.round((totalFrames * 16000) / sampleRate));
    const resampled = new Float32Array(outLen);
    const step = sampleRate / 16000;
    for (let i = 0; i < outLen; i++) {
      const pos = i * step;
      const i0 = Math.min(totalFrames - 1, Math.floor(pos));
      const i1 = Math.min(totalFrames - 1, i0 + 1);
      const frac = pos - i0;
      resampled[i] = mono[i0] + (mono[i1] - mono[i0]) * frac;
    }
    return resampled;
  }

  function normalizeWhisperChunksToSegments(result) {
    if (!result) return { segments: [], words: [] };
    const rawChunks = Array.isArray(result.chunks) ? result.chunks : [];
    const segments = [];

    for (let i = 0; i < rawChunks.length; i++) {
      const ch = rawChunks[i];
      if (!ch) continue;
      const text = String(ch.text || '').trim();
      if (!text) continue;
      const ts = Array.isArray(ch.timestamp) ? ch.timestamp : [];
      const prevEnd = segments.length ? segments[segments.length - 1].end : 0;
      let start = Number(ts[0]);
      if (!Number.isFinite(start) || start < 0) start = prevEnd;
      let end = Number(ts[1]);
      if (!Number.isFinite(end) || end <= start) {
        const nextCh = rawChunks[i + 1];
        const nextStart = nextCh && Array.isArray(nextCh.timestamp) ? Number(nextCh.timestamp[0]) : NaN;
        if (Number.isFinite(nextStart) && nextStart > start) {
          end = nextStart;
        } else {
          end = start + Math.max(1.2, Math.min(6, text.split(/\s+/).length * 0.45));
        }
      }
      if (start < prevEnd) start = prevEnd;
      if (end <= start) end = start + 0.4;
      segments.push({ start, end, text });
    }

    if (!segments.length) {
      const fallbackText = String(result.text || '').trim();
      if (fallbackText) {
        const wordsCount = fallbackText.split(/\s+/).filter(Boolean).length;
        segments.push({
          start: 0,
          end: Math.max(2, Math.round(wordsCount * 0.45 * 10) / 10),
          text: fallbackText
        });
      }
    }

    return { segments, words: [] };
  }

  async function loadTransformersModule() {
    if (!transformersModPromise) {
      transformersModPromise = (async () => {
        let lastErr = null;
        for (const url of TRANSFORMERS_CDN_URLS) {
          try {
            const mod = await import(/* webpackIgnore: true */ url);
            if (mod && typeof mod.pipeline === 'function') {
              if (mod.env) {
                mod.env.useBrowserCache = true;
                mod.env.allowLocalModels = false;
                if (typeof navigator !== 'undefined' && navigator.onLine === false) {
                  mod.env.allowRemoteModels = false;
                } else {
                  mod.env.allowRemoteModels = true;
                }
              }
              return mod;
            }
          } catch (e) {
            lastErr = e;
          }
        }
        transformersModPromise = null;
        throw lastErr || new Error('Failed to load offline Whisper runtime (@huggingface/transformers).');
      })();
    }
    return transformersModPromise;
  }

  async function createWhisperPipeline(cfg, onProgress) {
    const mod = await loadTransformersModule();
    if (mod.env) {
      mod.env.allowRemoteModels = typeof navigator === 'undefined' || navigator.onLine !== false;
    }

    const fileTotals = new Map();
    const progress_callback = (ev) => {
      if (!ev || typeof onProgress !== 'function') return;
      const key = String(ev.file || 'model');
      if (ev.status === 'progress' && Number.isFinite(ev.loaded) && Number.isFinite(ev.total) && ev.total > 0) {
        fileTotals.set(key, { loaded: ev.loaded, total: ev.total });
      } else if (ev.status === 'done') {
        const prev = fileTotals.get(key);
        if (prev && prev.total > 0) {
          fileTotals.set(key, { loaded: prev.total, total: prev.total });
        }
      }
      let sumLoaded = 0;
      let sumTotal = 0;
      fileTotals.forEach((v) => {
        sumLoaded += v.loaded;
        sumTotal += v.total;
      });
      const pct = sumTotal > 0
        ? Math.min(99, Math.max(1, Math.round((sumLoaded / sumTotal) * 100)))
        : (Number.isFinite(ev.progress) ? Math.min(99, Math.max(1, Math.round(ev.progress))) : 0);
      onProgress({
        pct,
        loadedMb: Math.round((sumLoaded / (1024 * 1024)) * 10) / 10,
        totalMb: Math.round((sumTotal / (1024 * 1024)) * 10) / 10,
        file: key
      });
    };

    try {
      return await mod.pipeline('automatic-speech-recognition', cfg.repoId, {
        device: 'wasm',
        dtype: cfg.dtype,
        progress_callback
      });
    } catch (firstErr) {
      if (cfg.fallbackDtype && cfg.fallbackDtype !== cfg.dtype) {
        getDiag().logWarn('localWhisper.dtypeFallback', firstErr);
        return await mod.pipeline('automatic-speech-recognition', cfg.repoId, {
          device: 'wasm',
          dtype: cfg.fallbackDtype,
          progress_callback
        });
      }
      throw firstErr;
    }
  }

  async function downloadModel(modelId, onProgress) {
    const cfg = getLocalModelConfig(modelId);
    if (!cfg) throw new Error(`Unknown offline model: ${modelId}`);
    if (pipelineCache.has(modelId)) {
      setModelDownloaded(modelId, true);
      if (typeof onProgress === 'function') onProgress({ pct: 100, loadedMb: cfg.sizeMb, totalMb: cfg.sizeMb });
      return pipelineCache.get(modelId);
    }
    if (activeDownloads.has(modelId)) {
      return activeDownloads.get(modelId);
    }

    const promise = (async () => {
      try {
        const pipe = await createWhisperPipeline(cfg, onProgress);
        pipelineCache.set(modelId, pipe);
        setModelDownloaded(modelId, true);
        if (typeof onProgress === 'function') {
          onProgress({ pct: 100, loadedMb: cfg.sizeMb, totalMb: cfg.sizeMb });
        }
        return pipe;
      } finally {
        activeDownloads.delete(modelId);
      }
    })();

    activeDownloads.set(modelId, promise);
    return promise;
  }

  async function deleteModel(modelId) {
    const cfg = getLocalModelConfig(modelId);
    if (!cfg) return false;

    const pipe = pipelineCache.get(modelId);
    pipelineCache.delete(modelId);
    if (pipe && typeof pipe.dispose === 'function') {
      try { await pipe.dispose(); } catch (_) {}
    }

    setModelDownloaded(modelId, false);

    if (typeof caches !== 'undefined' && typeof caches.open === 'function') {
      try {
        const cache = await caches.open('transformers-cache');
        const keys = await cache.keys();
        await Promise.all(
          keys
            .filter((req) => req && req.url && req.url.includes(cfg.repoId))
            .map((req) => cache.delete(req))
        );
      } catch (e) {
        getDiag().logWarn('localWhisper.cacheDelete', e);
      }
    }
    return true;
  }

  async function transcribeFloat32(modelId, float32Pcm16k, opts = {}) {
    if (opts.signal && opts.signal.aborted) {
      throw new Error('Cancelled by user.');
    }
    const pipe = await downloadModel(modelId, opts.onProgress);
    if (opts.signal && opts.signal.aborted) {
      throw new Error('Cancelled by user.');
    }
    const audioInput = float32Pcm16k instanceof Float32Array
      ? float32Pcm16k
      : new Float32Array(float32Pcm16k || []);
    if (!audioInput.length) return { segments: [], words: [] };

    const result = await pipe(audioInput, {
      chunk_length_s: 30,
      stride_length_s: 5,
      return_timestamps: true,
      task: 'transcribe'
    });
    if (opts.signal && opts.signal.aborted) {
      throw new Error('Cancelled by user.');
    }
    return normalizeWhisperChunksToSegments(result);
  }

  async function transcribeBlob(modelId, blob, opts = {}) {
    if (!blob) throw new Error('Empty audio blob.');
    const ab = await blob.arrayBuffer();
    let float32 = decodeWavToFloat32(ab);
    if (!float32) {
      const AudioCtx = (typeof window !== 'undefined') && (window.AudioContext || window.webkitAudioContext);
      if (!AudioCtx) throw new Error('AudioContext is not available to decode audio.');
      const ctx = new AudioCtx({ sampleRate: 16000 });
      try {
        const audioBuf = await ctx.decodeAudioData(ab);
        float32 = audioBuf.getChannelData(0).slice();
      } finally {
        if (ctx.close) {
          try { await ctx.close(); } catch (_) {}
        }
      }
    }
    return transcribeFloat32(modelId, float32, opts);
  }

  return {
    LOCAL_MODELS,
    isLocalWhisperModel,
    getLocalModelConfig,
    isModelDownloaded,
    decodeWavToFloat32,
    normalizeWhisperChunksToSegments,
    downloadModel,
    deleteModel,
    transcribeFloat32,
    transcribeBlob
  };
});
