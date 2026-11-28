(function (root, factory) {
  const api = factory(
    root.SubLyter && root.SubLyter.subtitles,
    root.SubLyter && root.SubLyter.diagnostics
  );
  if (typeof module !== 'undefined' && module.exports) {
    module.exports = api;
  }
  root.SubLyter = root.SubLyter || {};
  root.SubLyter.groq = api;
})(typeof globalThis !== 'undefined' ? globalThis : window, function (subtitlesMod, diagMod) {
  'use strict';

  const diag = diagMod || { logWarn() {}, logError() {} };

  class KeyPool {
    constructor(getKeysFn) {
      this.getKeysFn = typeof getKeysFn === 'function' ? getKeysFn : () => [];
      this.counter = 0;
      this.cooldowns = new Map();
      this.invalidKeys = new Set();
    }

    getKeys() {
      const list = this.getKeysFn() || [];
      return Array.isArray(list) ? list : [];
    }

    markRateLimited(key, cooldownSec = 8, now = Date.now()) {
      if (!key) return;
      const ms = Math.max(1000, (Number(cooldownSec) || 8) * 1000);
      const prev = this.cooldowns.get(key) || 0;
      this.cooldowns.set(key, Math.max(prev, now + ms));
    }

    markInvalid(key) {
      if (!key) return;
      this.invalidKeys.add(key);
    }

    reset() {
      this.cooldowns.clear();
      this.invalidKeys.clear();
    }

    hasValidKeys() {
      return this.getKeys().some((k) => !this.invalidKeys.has(k));
    }

    pickKey(now = Date.now()) {
      const keys = this.getKeys();
      if (!keys.length) return { key: '', waitMs: 0, allLimited: false, allInvalid: false };
      const validKeys = keys.filter((k) => !this.invalidKeys.has(k));
      if (!validKeys.length) return { key: '', waitMs: 0, allLimited: false, allInvalid: true };

      for (let step = 0; step < validKeys.length; step++) {
        const idx = (this.counter + step) % validKeys.length;
        const candidate = validKeys[idx];
        const until = this.cooldowns.get(candidate) || 0;
        if (now >= until) {
          this.counter = (idx + 1) % 1000000;
          return { key: candidate, waitMs: 0, allLimited: false, allInvalid: false };
        }
      }

      // Все валидные ключи на кулдауне — выбираем ближайший к освобождению
      let bestKey = validKeys[0];
      let earliest = this.cooldowns.get(bestKey) || now;
      for (let i = 1; i < validKeys.length; i++) {
        const k = validKeys[i];
        const u = this.cooldowns.get(k) || now;
        if (u < earliest) {
          earliest = u;
          bestKey = k;
        }
      }
      return {
        key: bestKey,
        waitMs: Math.max(0, earliest - now),
        allLimited: true,
        allInvalid: false
      };
    }
  }

  function abortableDelay(ms, signal) {
    return new Promise((resolve, reject) => {
      if (signal && signal.aborted) {
        reject(new Error('Cancelled by user.'));
        return;
      }
      let timer = null;
      const onAbort = () => {
        if (timer) clearTimeout(timer);
        reject(new Error('Cancelled by user.'));
      };
      timer = setTimeout(() => {
        if (signal) signal.removeEventListener('abort', onAbort);
        resolve();
      }, ms);
      if (signal) signal.addEventListener('abort', onAbort, { once: true });
    });
  }

  async function fetchWithTimeout(url, opts, timeoutMs, externalSignal) {
    const controller = new AbortController();
    let timedOut = false;
    const onExternalAbort = () => controller.abort();
    if (externalSignal) {
      if (externalSignal.aborted) {
        controller.abort();
      } else {
        externalSignal.addEventListener('abort', onExternalAbort, { once: true });
      }
    }
    const timer = timeoutMs > 0
      ? setTimeout(() => {
          timedOut = true;
          controller.abort();
        }, timeoutMs)
      : null;

    try {
      const merged = Object.assign({}, opts, { signal: controller.signal });
      return await fetch(url, merged);
    } catch (e) {
      if (timedOut) {
        throw new Error(`Request timeout after ${Math.round(timeoutMs / 1000)}s`);
      }
      throw e;
    } finally {
      if (timer) clearTimeout(timer);
      if (externalSignal) externalSignal.removeEventListener('abort', onExternalAbort);
    }
  }

  async function runWithConcurrency(items, concurrency, workerFn) {
    if (!items || !items.length) return [];
    const results = new Array(items.length);
    let nextIdx = 0;
    const workerCount = Math.max(1, Math.min(concurrency, items.length));
    const workers = Array.from({ length: workerCount }, async (_, workerSlot) => {
      let isFirstForWorker = true;
      while (nextIdx < items.length) {
        const cur = nextIdx++;
        const firstFlag = isFirstForWorker;
        isFirstForWorker = false;
        results[cur] = await workerFn(items[cur], cur, workerSlot, firstFlag);
      }
    });
    await Promise.all(workers);
    return results;
  }

  async function normalizeWavBlob(blob) {
    try {
      if (!blob || blob.size < 44) return blob;
      if (blob.type && !/wav|wave|octet-stream/i.test(blob.type)) return blob;
      const buf = await blob.arrayBuffer();
      const dv = new DataView(buf);
      if (dv.byteLength < 44 || dv.getUint32(0, false) !== 0x52494646) return blob;
      let fmt = null;
      let dataOff = -1;
      let dataLen = 0;
      let offset = 12;
      while (offset + 8 <= dv.byteLength) {
        const id = dv.getUint32(offset, false);
        const size = dv.getUint32(offset + 4, true);
        const body = offset + 8;
        if (id === 0x666d7420) {
          fmt = {
            channels: dv.getUint16(body + 2, true),
            sampleRate: dv.getUint32(body + 4, true),
            bits: dv.getUint16(body + 14, true)
          };
        } else if (id === 0x64617461) {
          dataOff = body;
          dataLen = Math.min(size, dv.byteLength - body);
          break;
        }
        offset = body + size + (size % 2);
      }
      if (!fmt || fmt.bits !== 16 || dataOff < 0) return blob;
      const sampleCount = Math.floor(dataLen / 2);
      if (sampleCount < 160) return blob;
      let peak = 0;
      const aligned = (dataOff % 2) === 0;
      const pcm16 = aligned ? new Int16Array(buf, dataOff, sampleCount) : null;
      if (pcm16) {
        for (let i = 0; i < sampleCount; i += 8) {
          const v = pcm16[i] < 0 ? -pcm16[i] : pcm16[i];
          if (v > peak) {
            peak = v;
            if (peak >= 3500) break;
          }
        }
      } else {
        for (let i = 0; i < sampleCount; i += 8) {
          const v = Math.abs(dv.getInt16(dataOff + i * 2, true));
          if (v > peak) {
            peak = v;
            if (peak >= 3500) break;
          }
        }
      }
      if (peak < 8 || peak >= 3500) return blob;
      const target = 0.9 * 32767;
      let gain = target / peak;
      if (gain <= 1.05) return blob;
      gain = Math.min(gain, 8);
      if (pcm16) {
        for (let i = 0; i < sampleCount; i++) {
          const v = (pcm16[i] * gain) | 0;
          pcm16[i] = v > 32767 ? 32767 : (v < -32768 ? -32768 : v);
        }
      } else {
        for (let i = 0; i < sampleCount; i++) {
          let v = Math.round(dv.getInt16(dataOff + i * 2, true) * gain);
          if (v > 32767) v = 32767;
          else if (v < -32768) v = -32768;
          dv.setInt16(dataOff + i * 2, v, true);
        }
      }
      return new Blob([buf], { type: 'audio/wav' });
    } catch (e) {
      diag.logWarn('wav.normalize', e);
      return blob;
    }
  }

  function jitterMs(baseMs) {
    const factor = 0.8 + Math.random() * 0.4;
    return Math.round(baseMs * factor);
  }

  async function fetchGroqWithRotation(url, makeOptionsFn, signal, retries = 5, ctx = {}) {
    let lastErr = null;
    const keyPool = ctx.keyPool || new KeyPool(ctx.getKeys || (() => []));
    const onStatus = typeof ctx.onStatus === 'function' ? ctx.onStatus : () => {};
    const tr = (key, vars, fallback) => (typeof ctx.t === 'function' ? ctx.t(key, vars) : fallback);
    const timeoutMs = Number(ctx.timeoutMs) || 90000;
    const totalKeys = Math.max(1, keyPool.getKeys().length);
    let rotatedCount = 0;
    let invalidSwitches = 0;

    for (let i = 0; i <= retries; i++) {
      if (signal && signal.aborted) throw new Error('Cancelled by user.');
      let picked = keyPool.pickKey();
      if (picked.allInvalid || !picked.key) {
        throw new Error('All configured Groq API keys are invalid or rejected (401).');
      }
      try {
        if (picked.allLimited && picked.waitMs > 0) {
          const waitSec = Math.max(1, Math.ceil(picked.waitMs / 1000));
          onStatus(tr('status.rateLimitAll', { sec: waitSec }, `⏳ Rate limit on all keys. Waiting ${waitSec}s...`));
          await abortableDelay(picked.waitMs, signal);
          picked = keyPool.pickKey();
          if (picked.allInvalid || !picked.key) {
            throw new Error('All configured Groq API keys are invalid or rejected (401).');
          }
        }
        const currentKey = picked.key;
        const opts = makeOptionsFn(currentKey);
        const res = await fetchWithTimeout(url, opts, timeoutMs, signal);

        if (res.status === 401) {
          keyPool.markInvalid(currentKey);
          diag.logWarn('groq.auth', 'Key rejected with HTTP 401');
          if (keyPool.hasValidKeys() && invalidSwitches < totalKeys) {
            invalidSwitches++;
            i--;
            onStatus(tr('status.keyRejectedSwitch', null, '🔑 Key rejected (401). Switching to another key...'));
            continue;
          }
          return res;
        }

        if (res.status === 429) {
          const ra = parseInt(res.headers.get('retry-after') || '0', 10);
          const cooldownSec = ra > 0 ? ra : Math.ceil(1200 * Math.pow(2, i) / 1000);
          keyPool.markRateLimited(currentKey, cooldownSec);

          if (rotatedCount < totalKeys - 1) {
            rotatedCount++;
            i--;
            onStatus(tr('status.keyRateLimitedSwitch', null, '🔄 Key rate-limited. Trying another key...'));
            continue;
          }
          if (i < retries) {
            rotatedCount = 0;
            const waitSec = Math.max(1, cooldownSec);
            onStatus(tr('status.rateLimitWait', { sec: waitSec, attempt: i + 1, max: retries }, `⏳ Rate limit. Waiting ${waitSec}s... (Attempt ${i + 1}/${retries})`));
            await abortableDelay(jitterMs(waitSec * 1000), signal);
            continue;
          }
        }

        if ((res.status === 502 || res.status === 503) && i < retries) {
          rotatedCount = 0;
          const waitSec = Math.max(1, Math.ceil(1200 * Math.pow(2, i) / 1000));
          onStatus(tr('status.serverBusyRetry', { code: res.status, sec: waitSec }, `⏳ Server busy (${res.status}). Retrying in ${waitSec}s...`));
          await abortableDelay(jitterMs(waitSec * 1000), signal);
          continue;
        }
        return res;
      } catch (e) {
        if ((signal && signal.aborted) || (e && e.name === 'AbortError')) {
          throw new Error('Cancelled by user.');
        }
        diag.logWarn('groq.fetch', e);
        lastErr = e;
        if (i < retries) {
          onStatus(tr('status.networkRetry', null, '🌐 Network error. Retrying...'));
          await abortableDelay(jitterMs(2000), signal);
        }
      }
    }
    if (lastErr) throw lastErr;
    throw new Error('Network unavailable');
  }

  async function sendToGroq(blob, filename, model, promptHint, signal, ctx = {}) {
    const peak = typeof ctx.chunkPeak === 'number' ? ctx.chunkPeak : null;
    const peakAlreadyGood = peak !== null && (peak >= 3500 || (peak > 0 && peak < 8));
    if (!ctx.skipNormalize && !peakAlreadyGood) {
      blob = await normalizeWavBlob(blob);
    }
    const res = await fetchGroqWithRotation(
      'https://api.groq.com/openai/v1/audio/transcriptions',
      (key) => {
        const fd = new FormData();
        fd.append('file', blob, filename);
        fd.append('model', model);
        fd.append('temperature', '0');
        fd.append('response_format', 'verbose_json');
        fd.append('timestamp_granularities[]', 'word');
        fd.append('timestamp_granularities[]', 'segment');
        if (promptHint) fd.append('prompt', promptHint.slice(-220));
        return {
          method: 'POST',
          headers: { Authorization: 'Bearer ' + key },
          body: fd
        };
      },
      signal,
      5,
      Object.assign({ timeoutMs: 90000 }, ctx)
    );

    if (!res.ok) {
      if (res.status === 401) throw new Error('Invalid Groq API key.');
      if (res.status === 413) throw new Error('File too large to upload.');
      if (res.status === 429) throw new Error('Free tier rate limit reached. Wait a minute or check a second Groq key.');
      throw new Error(`Groq server error (Code ${res.status}). Try again later.`);
    }

    const data = await res.json();
    const filterEnabled = ctx.hallucinationFilter !== false;
    const filterFn = subtitlesMod ? subtitlesMod.isWhisperHallucination : () => false;
    const validSegments = (data.segments || []).filter((s) => !filterFn(s, filterEnabled));
    return { segments: validSegments, words: data.words || [] };
  }

  async function requestTranslationBatch(payload, prevContext, targetLang, modelName, signal, ctx = {}) {
    const contextHint = prevContext.length
      ? `\nPrevious lines for context only (DO NOT include in output): ${JSON.stringify(prevContext)}`
      : '';

    return fetchGroqWithRotation(
      'https://api.groq.com/openai/v1/chat/completions',
      (key) => ({
        method: 'POST',
        headers: {
          Authorization: 'Bearer ' + key,
          'Content-Type': 'application/json'
        },
        body: JSON.stringify({
          model: modelName,
          ...(String(modelName).startsWith('openai/gpt-oss') ? { reasoning_effort: 'low' } : {}),
          temperature: 0.1,
          response_format: { type: 'json_object' },
          messages: [
            {
              role: 'system',
              content: `You are a professional subtitle translator. Translate the "text" field of each item into ${targetLang}. Keep the exact same "id" numbers, preserve natural flow and gender agreement using context, and return ONLY valid JSON: {"items":[{"id":0,"text":"..."}]}. Do not merge, skip, or split items.${contextHint}`
            },
            { role: 'user', content: JSON.stringify({ items: payload }) }
          ]
        })
      }),
      signal,
      3,
      Object.assign({ timeoutMs: 60000 }, ctx)
    );
  }

  async function translateCuesDirect(batch, prevContext, targetLang, signal, ctx = {}) {
    if (!batch || !batch.length) return 0;
    const primaryModel = ctx.primaryModel || 'openai/gpt-oss-120b';
    const fallbackModel = 'openai/gpt-oss-20b';
    const payload = batch.map((c, idx) => ({ id: idx, text: c.text }));
    const origWordsByIdx = batch.map((c) => (
      Array.isArray(c.words) && c.words.length
        ? c.words.map((w) => ({ start: w.start, end: w.end }))
        : null
    ));
    let failed = 0;

    try {
      let res = await requestTranslationBatch(payload, prevContext, targetLang, primaryModel, signal, ctx);
      if (!res.ok && primaryModel !== fallbackModel) {
        res = await requestTranslationBatch(payload, prevContext, targetLang, fallbackModel, signal, ctx);
      }
      if (!res.ok) return batch.length;

      const data = await res.json();
      let content = String((data.choices && data.choices[0] && data.choices[0].message && data.choices[0].message.content) || '').trim();
      content = content.replace(/^```(?:json)?\s*/i, '').replace(/\s*```$/, '');
      const parsed = JSON.parse(content);

      const received = new Set();
      if (parsed.items && Array.isArray(parsed.items)) {
        parsed.items.forEach((item) => {
          const target = batch[item.id];
          if (target && typeof item.text === 'string') {
            received.add(item.id);
            const txt = item.text.trim();
            if (txt && txt !== target.text) {
              target.text = txt;
              target._translated = true;
            }
          }
        });
      }
      failed = batch.length - received.size;
    } catch (e) {
      if (e && e.message === 'Cancelled by user.') throw e;
      diag.logWarn('groq.translate', e);
      failed = batch.length;
    }

    const distributeFn = subtitlesMod ? subtitlesMod.distributeWords : null;
    for (let i = 0; i < batch.length; i++) {
      const c = batch[i];
      if (c._translated && distributeFn) distributeFn(c, origWordsByIdx[i]);
      delete c._translated;
    }
    return failed;
  }

  async function translateSegments(cueArr, targetLang, statusElRef, signal, concurrency = 10, ctx = {}) {
    const batchSize = 50;
    let failedLines = 0;

    const batches = [];
    for (let i = 0; i < cueArr.length; i += batchSize) {
      const batch = cueArr.slice(i, i + batchSize);
      const prevContext = i >= 2 ? cueArr.slice(i - 2, i).map((c) => c.text) : [];
      batches.push({ startLine: i, batch, prevContext });
    }

    let completedBatches = 0;
    const tr = (key, vars, fallback) => (typeof ctx.t === 'function' ? ctx.t(key, vars) : fallback);
    await runWithConcurrency(batches, Math.max(1, Number(concurrency) || 10), async (bObj) => {
      if (signal && signal.aborted) throw new Error('Cancelled by user.');
      const { startLine, batch, prevContext } = bObj;
      const failed = await translateCuesDirect(batch, prevContext, targetLang, signal, ctx);
      failedLines += failed;
      completedBatches++;
      const pct = 55 + Math.round((completedBatches / Math.max(1, batches.length)) * 43);
      const fromLine = startLine + 1;
      const toLine = Math.min(startLine + batchSize, cueArr.length);
      const msg = tr(
        'status.translatingBatch',
        { done: completedBatches, total: batches.length, from: fromLine, to: toLine },
        `⚡ Parallel translation: batch ${completedBatches} of ${batches.length} (lines ${fromLine}–${toLine})...`
      );
      if (statusElRef) statusElRef.innerText = msg;
      if (typeof ctx.onStatus === 'function') ctx.onStatus(msg);
      if (typeof ctx.setProgress === 'function') ctx.setProgress(pct);
    });

    return failedLines;
  }

  async function callGroqJson(systemPrompt, userContent, signal, ctx = {}) {
    const primaryModel = ctx.primaryModel || 'openai/gpt-oss-20b';
    const fallbackModel = ctx.fallbackModel || 'openai/gpt-oss-120b';
    const runModel = (modelName) => fetchGroqWithRotation(
      'https://api.groq.com/openai/v1/chat/completions',
      (key) => ({
        method: 'POST',
        headers: {
          Authorization: 'Bearer ' + key,
          'Content-Type': 'application/json'
        },
        body: JSON.stringify({
          model: modelName,
          ...(String(modelName).startsWith('openai/gpt-oss') ? { reasoning_effort: 'low' } : {}),
          temperature: 0.2,
          response_format: { type: 'json_object' },
          messages: [
            { role: 'system', content: systemPrompt },
            { role: 'user', content: userContent }
          ]
        })
      }),
      signal,
      3,
      Object.assign({ timeoutMs: 45000 }, ctx)
    );

    let res = await runModel(primaryModel);
    if (!res.ok && primaryModel !== fallbackModel) {
      res = await runModel(fallbackModel);
    }
    if (!res.ok) {
      if (res.status === 401) throw new Error('Invalid Groq API key.');
      if (res.status === 429) throw new Error('Rate limit reached. Try again in a moment.');
      throw new Error(`Groq AI error (HTTP ${res.status}).`);
    }
    const data = await res.json();
    let content = String((data.choices && data.choices[0] && data.choices[0].message && data.choices[0].message.content) || '').trim();
    content = content.replace(/^```(?:json)?\s*/i, '').replace(/\s*```$/, '');
    return JSON.parse(content || '{}');
  }

  async function explainWordInContext(word, sentence, originalSentence, targetLang, signal, ctx = {}) {
    const explainLang = targetLang || 'English';
    const systemPrompt = `You are a concise, brilliant language tutor inside a video player. Explain the requested word strictly in ${explainLang} using the exact scene sentence as context. Return ONLY valid JSON with keys: {"word":"...","translation":"...","partOfSpeech":"...","meaning":"...","nuance":"...","example":"..."}. Keep each field crisp (1 short sentence max).`;
    const userMsg = JSON.stringify({
      word,
      sentence: sentence || '',
      originalSentence: originalSentence || '',
      explainIn: explainLang
    });
    const parsed = await callGroqJson(systemPrompt, userMsg, signal, ctx);
    return {
      word: String(parsed.word || word),
      translation: String(parsed.translation || ''),
      partOfSpeech: String(parsed.partOfSpeech || ''),
      meaning: String(parsed.meaning || ''),
      nuance: String(parsed.nuance || ''),
      example: String(parsed.example || '')
    };
  }

  async function extractKnowledgeNote(sourceText, titleHint, targetLang, signal, ctx = {}) {
    const outLang = targetLang || 'English';
    const systemPrompt = `You build a structured "Second Brain" knowledge card from a video transcript. Write all fields in ${outLang}. Extract concrete takeaways, recipes, steps, tools, names, or quotes. Return ONLY valid JSON: {"title":"...","category":"...","summary":"...","points":["..."],"tags":["tag1","tag2"]}.`;
    const clipped = String(sourceText || '').slice(0, 14000);
    const parsed = await callGroqJson(systemPrompt, `Title: ${titleHint || 'Video'}\n\nTranscript:\n${clipped}`, signal, ctx);
    return {
      title: String(parsed.title || titleHint || 'Knowledge Note'),
      category: String(parsed.category || 'Video Note'),
      summary: String(parsed.summary || ''),
      points: Array.isArray(parsed.points) ? parsed.points.map(String).filter(Boolean) : [],
      tags: Array.isArray(parsed.tags) ? parsed.tags.map((t) => String(t).replace(/^#/, '').trim()).filter(Boolean) : []
    };
  }

  async function extractKnowledgeFromImage(base64Jpeg, mimeType, titleHint, targetLang, signal, ctx = {}) {
    const outLang = targetLang || 'English';
    const prompt = `Analyze this screenshot/image carefully. Extract any useful text, recipe, code/tool name, place, workout, movie title, or key idea into a structured "Second Brain" card written in ${outLang}. Return ONLY valid JSON: {"title":"...","category":"...","summary":"...","points":["..."],"tags":["tag1","tag2"]}.`;
    const cleanB64 = String(base64Jpeg || '').replace(/^data:image\/[^;]+;base64,/i, '');
    const safeMime = mimeType || 'image/jpeg';

    if (ctx.geminiKey) {
      try {
        const model = ctx.geminiModel || 'gemini-2.5-flash';
        const endpoint = `https://generativelanguage.googleapis.com/v1beta/models/${encodeURIComponent(model)}:generateContent`;
        const gRes = await fetchWithTimeout(endpoint, {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            'x-goog-api-key': ctx.geminiKey
          },
          body: JSON.stringify({
            contents: [{
              role: 'user',
              parts: [
                { inlineData: { mimeType: safeMime, data: cleanB64 } },
                { text: prompt }
              ]
            }],
            generationConfig: { responseMimeType: 'application/json', temperature: 0.2 }
          })
        }, 45000, signal);
        if (gRes.ok) {
          const gData = await gRes.json();
          const rawTxt = String((gData.candidates && gData.candidates[0] && gData.candidates[0].content && gData.candidates[0].content.parts || []).map((p) => p.text || '').join('')).trim();
          const parsed = JSON.parse(rawTxt.replace(/^```(?:json)?\s*/i, '').replace(/\s*```$/, ''));
          return {
            title: String(parsed.title || titleHint || 'Screenshot Note'),
            category: String(parsed.category || 'Screenshot'),
            summary: String(parsed.summary || ''),
            points: Array.isArray(parsed.points) ? parsed.points.map(String).filter(Boolean) : [],
            tags: Array.isArray(parsed.tags) ? parsed.tags.map((t) => String(t).replace(/^#/, '').trim()).filter(Boolean) : []
          };
        }
      } catch (e) {
        if (e && (e.name === 'AbortError' || e.message === 'Cancelled by user.')) throw e;
        diag.logWarn('vault.geminiImageFallback', e);
      }
    }

    const res = await fetchGroqWithRotation(
      'https://api.groq.com/openai/v1/chat/completions',
      (key) => ({
        method: 'POST',
        headers: {
          Authorization: 'Bearer ' + key,
          'Content-Type': 'application/json'
        },
        body: JSON.stringify({
          model: 'meta-llama/llama-4-scout-17b-16e-instruct',
          temperature: 0.2,
          response_format: { type: 'json_object' },
          messages: [{
            role: 'user',
            content: [
              { type: 'text', text: prompt },
              { type: 'image_url', image_url: { url: `data:${safeMime};base64,${cleanB64}` } }
            ]
          }]
        })
      }),
      signal,
      3,
      Object.assign({ timeoutMs: 45000 }, ctx)
    );
    if (!res.ok) {
      if (res.status === 401) throw new Error('Invalid Groq API key.');
      throw new Error(`Vision extraction error (HTTP ${res.status}).`);
    }
    const data = await res.json();
    const raw = String((data.choices && data.choices[0] && data.choices[0].message && data.choices[0].message.content) || '').trim();
    const parsed = JSON.parse(raw.replace(/^```(?:json)?\s*/i, '').replace(/\s*```$/, '') || '{}');
    return {
      title: String(parsed.title || titleHint || 'Screenshot Note'),
      category: String(parsed.category || 'Screenshot'),
      summary: String(parsed.summary || ''),
      points: Array.isArray(parsed.points) ? parsed.points.map(String).filter(Boolean) : [],
      tags: Array.isArray(parsed.tags) ? parsed.tags.map((t) => String(t).replace(/^#/, '').trim()).filter(Boolean) : []
    };
  }

  async function summarizeVoiceTranscript(transcriptText, targetLang, signal, ctx = {}) {
    const outLang = targetLang || 'English';
    const systemPrompt = ctx.forceTranslate
      ? `You translate and organize a spoken voice memo into an actionable note strictly in ${outLang}. Translate title, summary, actionItems, keyDates, and the full spoken transcript into ${outLang}. Return ONLY valid JSON: {"title":"...","summary":"...","actionItems":["..."],"keyDates":["..."],"tags":["tag1","tag2"],"translatedTranscript":"..."}.`
      : `You organize a spoken voice memo or lecture into an actionable note. Preserve the speaker's language unless translation is clearly needed; if uncertain write summary in ${outLang}. Return ONLY valid JSON: {"title":"...","summary":"...","actionItems":["..."],"keyDates":["..."],"tags":["tag1","tag2"]}.`;
    const clipped = String(transcriptText || '').slice(0, 14000);
    const parsed = await callGroqJson(systemPrompt, clipped, signal, ctx);
    return {
      title: String(parsed.title || 'Voice Note'),
      summary: String(parsed.summary || ''),
      actionItems: Array.isArray(parsed.actionItems) ? parsed.actionItems.map(String).filter(Boolean) : [],
      keyDates: Array.isArray(parsed.keyDates) ? parsed.keyDates.map(String).filter(Boolean) : [],
      tags: Array.isArray(parsed.tags) ? parsed.tags.map((t) => String(t).replace(/^#/, '').trim()).filter(Boolean) : [],
      translatedTranscript: typeof parsed.translatedTranscript === 'string' ? parsed.translatedTranscript.trim() : ''
    };
  }

  return {
    KeyPool,
    abortableDelay,
    fetchWithTimeout,
    runWithConcurrency,
    normalizeWavBlob,
    fetchGroqWithRotation,
    sendToGroq,
    requestTranslationBatch,
    translateCuesDirect,
    translateSegments,
    callGroqJson,
    explainWordInContext,
    extractKnowledgeNote,
    extractKnowledgeFromImage,
    summarizeVoiceTranscript
  };
});
