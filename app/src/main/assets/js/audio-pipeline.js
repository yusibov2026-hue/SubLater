(function () {
  'use strict';

  const root = window.SubLyter = window.SubLyter || {};
  const diag = root.diagnostics || { logWarn() {}, logError() {} };
  const bridgeMod = root.bridge;
  const subtitlesMod = root.subtitles;
  const groqMod = root.groq;
  const storageMod = root.storage;

  const PARALLEL_CHUNK_CONCURRENCY = 10;

  function extractMonoChannelData(audioBuffer) {
    const numChannels = audioBuffer.numberOfChannels || 1;
    if (numChannels <= 1) return audioBuffer.getChannelData(0);
    const length = audioBuffer.length;
    const mono = new Float32Array(length);
    const scale = 1 / numChannels;
    for (let ch = 0; ch < numChannels; ch++) {
      const chData = audioBuffer.getChannelData(ch);
      for (let i = 0; i < length; i++) {
        mono[i] += chData[i] * scale;
      }
    }
    return mono;
  }

  function encodeWAV(samples, sampleRate) {
    const numSamples = samples.length;
    const buffer = new ArrayBuffer(44 + numSamples * 2);
    const view = new DataView(buffer);
    const writeString = (offset, str) => {
      for (let i = 0; i < str.length; i++) view.setUint8(offset + i, str.charCodeAt(i));
    };
    writeString(0, 'RIFF');
    view.setUint32(4, 36 + numSamples * 2, true);
    writeString(8, 'WAVE');
    writeString(12, 'fmt ');
    view.setUint32(16, 16, true);
    view.setUint16(20, 1, true);
    view.setUint16(22, 1, true);
    view.setUint32(24, sampleRate, true);
    view.setUint32(28, sampleRate * 2, true);
    view.setUint16(32, 2, true);
    view.setUint16(34, 16, true);
    writeString(36, 'data');
    view.setUint32(40, numSamples * 2, true);

    const pcm16 = new Int16Array(buffer, 44, numSamples);
    for (let i = 0; i < numSamples; i++) {
      const s = samples[i];
      pcm16[i] = s <= -1 ? -32768 : (s >= 1 ? 32767 : (s * 32767) | 0);
    }
    return new Blob([buffer], { type: 'audio/wav' });
  }

  /**
   * Общий конвейер потокового перевода и живых субтитров (без дублирования
   * между нативным MediaExtractor и браузерным WebAudio).
   */
  function createLivePipeline(opts) {
    const {
      file,
      isBatchMode,
      signal,
      streamingTranslate,
      targetLang,
      smartSplitEnabled,
      pipelineTranslatedCuesMap,
      setCues,
      onLivePublish,
      onStreamTranslated,
      groqCtx
    } = opts;

    const completedChunkMap = new Map();
    const liveChunkCuesMap = new Map();
    const liveCues = [];
    const streamTranslationPromises = [];
    let nextChunkToStream = 0;
    let nextLiveChunk = 0;
    let streamContextLines = [];
    let livePlayerStarted = false;
    let lastLivePublishedCount = 0;

    function sliceChunkToCues(chunkItem) {
      const cSegs = [];
      const cWords = [];
      (chunkItem.segments || []).forEach((seg) => {
        const absMid = (seg.start + seg.end) / 2 + chunkItem.pos;
        if (absMid < chunkItem.cutoff || absMid >= chunkItem.nextCutoff) return;
        cSegs.push({ start: seg.start + chunkItem.pos, end: seg.end + chunkItem.pos, text: seg.text });
      });
      (chunkItem.words || []).forEach((w) => {
        const absMid = (w.start + w.end) / 2 + chunkItem.pos;
        if (absMid < chunkItem.cutoff || absMid >= chunkItem.nextCutoff) return;
        cWords.push({ word: w.word, start: w.start + chunkItem.pos, end: w.end + chunkItem.pos });
      });
      return cSegs.length > 0 ? subtitlesMod.buildCues(cSegs, cWords, smartSplitEnabled) : [];
    }

    function flushLiveCues() {
      if (isBatchMode || signal.aborted) return;
      if (targetLang && !streamingTranslate) return;
      while (liveChunkCuesMap.has(nextLiveChunk)) {
        const arr = liveChunkCuesMap.get(nextLiveChunk);
        nextLiveChunk++;
        if (arr && arr.length) liveCues.push(...arr);
      }
      if (liveCues.length === lastLivePublishedCount) return;
      lastLivePublishedCount = liveCues.length;
      setCues(liveCues.slice());
      const isFirstStart = !livePlayerStarted;
      livePlayerStarted = true;
      onLivePublish(file, isFirstStart);
    }

    function checkAndTriggerStreamTranslations() {
      if (!streamingTranslate) return;
      while (completedChunkMap.has(nextChunkToStream)) {
        const cIdx = nextChunkToStream;
        const chunkItem = completedChunkMap.get(cIdx);
        nextChunkToStream++;

        const chunkCues = sliceChunkToCues(chunkItem);
        liveChunkCuesMap.set(cIdx, chunkCues);
        if (chunkCues.length > 0) {
          const prevCtx = streamContextLines.slice(-2);
          streamContextLines = chunkCues.map((c) => c.text);
          const p = groqMod.translateCuesDirect(chunkCues, prevCtx, targetLang, signal, groqCtx).then((res) => {
            if (signal.aborted) return res;
            pipelineTranslatedCuesMap.set(cIdx, chunkCues);
            onStreamTranslated();
            flushLiveCues();
            return res;
          });
          streamTranslationPromises.push(p);
        } else {
          flushLiveCues();
        }
      }
    }

    function handleCompletedChunk(chunkIdx, chunkItem) {
      completedChunkMap.set(chunkIdx, chunkItem);
      checkAndTriggerStreamTranslations();
      if (!isBatchMode && !streamingTranslate) {
        liveChunkCuesMap.set(chunkIdx, sliceChunkToCues(chunkItem));
        flushLiveCues();
      }
    }

    async function awaitPendingTranslations(statusEl) {
      checkAndTriggerStreamTranslations();
      if (streamingTranslate && streamTranslationPromises.length > 0) {
        if (statusEl) statusEl.innerText = '⚡ Streaming translation of final chunks...';
        await Promise.all(streamTranslationPromises);
      }
    }

    return {
      handleCompletedChunk,
      awaitPendingTranslations
    };
  }

  function createGenerationController(deps) {
    const {
      btn,
      statusEl,
      actionRow,
      vttPanel,
      openBtn,
      videoEl,
      translateLangEl,
      whisperPromptEl,
      fastAudioCompressEl,
      genModeEl,
      autoPlayToggle,
      smartSplitToggleEl,
      hallucinationFilterEl,
      llmModelEl,
      keyPool,
      getActiveMedia,
      getActiveWhisperModel,
      getApiKeys,
      getFileSignature,
      showMissingApiStatus,
      beginProgress,
      setProgress,
      getActiveAbortCtrl,
      setActiveAbortCtrl,
      getCachedWhisper,
      setCachedWhisper,
      getCachedPartialChunks,
      setCachedPartialChunks,
      getCues,
      setCues,
      setCurrentFileName,
      refreshAllExports,
      setupPlayer,
      updateSubtitleFrame,
      updateButtonLabel,
      generateVideoAiInsights,
      requestWakeLock,
      releaseWakeLock,
      formatTime
    } = deps;

    const getBatchConcurrency = () => Math.min(8, Math.max(4, getApiKeys().length * 4));

    let streamTranslatedChunks = 0;
    let updateStreamProgress = () => {};

    function markStreamChunkTranslated() {
      streamTranslatedChunks++;
      updateStreamProgress();
    }

    function cancelActiveGeneration() {
      const ctrl = getActiveAbortCtrl();
      if (!ctrl) return false;
      try { ctrl.abort(); } catch (_) {}
      const b = bridgeMod.getBridge();
      if (b) {
        try { if (typeof b.cancelNativeAudioExtraction === 'function') b.cancelNativeAudioExtraction(); } catch (e) { diag.logWarn('cancel.extract', e); }
        try { if (typeof b.clearAudioCache === 'function') b.clearAudioCache(); } catch (e) { diag.logWarn('cancel.cache', e); }
        try { if (typeof b.stopForegroundService === 'function') b.stopForegroundService(); } catch (e) { diag.logWarn('cancel.fg', e); }
      }
      return true;
    }

    async function generateSubtitles() {
      if (getActiveAbortCtrl()) {
        cancelActiveGeneration();
        setProgress(null);
        statusEl.innerText = '⏹ Cancelled by user.';
        return;
      }

      const file = getActiveMedia();
      const model = getActiveWhisperModel();
      const targetLang = translateLangEl.value;
      const fileSig = getFileSignature(file);
      const langCode = subtitlesMod.LANG_CODES[targetLang] || '';
      const cachedWhisper = getCachedWhisper();

      if (
        file &&
        cachedWhisper.fileSig === fileSig &&
        cachedWhisper.model === model &&
        cachedWhisper.rawSegments.length &&
        cachedWhisper.activeLang === targetLang
      ) {
        await generateVideoAiInsights();
        return;
      }

      const keys = getApiKeys();
      const customPrompt = whisperPromptEl.value.trim();

      if (!keys.length) {
        showMissingApiStatus(true);
        return;
      }
      if (!file) {
        statusEl.innerText = 'Please select a video or audio file first.';
        return;
      }

      const myAbortCtrl = new AbortController();
      setActiveAbortCtrl(myAbortCtrl);
      const signal = myAbortCtrl.signal;
      beginProgress();
      streamTranslatedChunks = 0;
      updateStreamProgress = () => {};

      btn.classList.add('cancel-mode');
      btn.innerText = 'Cancel process';
      actionRow.style.display = 'none';
      vttPanel.classList.add('hidden');
      openBtn.innerText = 'Text';
      setProgress(5);
      await requestWakeLock();

      setCurrentFileName(file.name.replace(/\.[^/.]+$/, '') + (langCode ? `_${langCode}` : ''));
      const bridge = bridgeMod.getBridge();
      const isBatchMode = Boolean(genModeEl && genModeEl.value === 'all');
      const startFromSec = (Number(videoEl.currentTime) || 0) >= 30 ? Number(videoEl.currentTime) : 0;
      let generationDoneForHistory = false;

      let estimatedTotalChunks = 0;
      try {
        let durationSec = Number(videoEl.duration) || 0;
        if (!durationSec && bridge && typeof bridge.getDuration === 'function') {
          durationSec = Number(bridge.getDuration()) || 0;
        }
        if (durationSec > startFromSec) {
          const remSec = durationSec - startFromSec;
          if (isBatchMode) {
            const dynDur = remSec <= 720 ? Math.min(180, Math.max(45, Math.ceil(remSec / 4))) : 180;
            estimatedTotalChunks = Math.max(1, Math.ceil(remSec / Math.max(1, dynDur)));
          } else {
            estimatedTotalChunks = Math.max(1, Math.ceil(remSec / 60));
          }
        }
      } catch (e) {
        diag.logWarn('gen.estimate', e);
      }

      if (bridge && typeof bridge.startForegroundService === 'function') {
        try { bridge.startForegroundService('Generating subtitles...'); } catch (e) { diag.logWarn('gen.fgStart', e); }
      }

      let totalChunks = 0;
      const pipelineTranslatedCuesMap = new Map();
      const smartSplitEnabled = Boolean(smartSplitToggleEl && smartSplitToggleEl.checked);
      const groqCtx = {
        keyPool,
        onStatus: (txt) => { if (statusEl) statusEl.innerText = txt; },
        hallucinationFilter: Boolean(hallucinationFilterEl && hallucinationFilterEl.checked),
        primaryModel: (llmModelEl && llmModelEl.value) || 'openai/gpt-oss-120b',
        setProgress
      };

      try {
        if (cachedWhisper.fileSig !== fileSig || cachedWhisper.model !== model || !cachedWhisper.rawSegments.length || startFromSec > 0) {
          let allSegments = [];
          let allWords = [];
          let usedEmbeddedSubtitles = false;

          if (
            !file.isStream &&
            startFromSec === 0 &&
            bridge &&
            (typeof bridge.requestEmbeddedSubtitlesAsync === 'function' || typeof bridge.extractEmbeddedSubtitleSegmentsJson === 'function') &&
            bridge.hasNativeVideo()
          ) {
            try {
              const embRaw = await bridgeMod.callNativeAsync('requestEmbeddedSubtitlesAsync', 'extractEmbeddedSubtitleSegmentsJson', 10000);
              const embSegs = JSON.parse(embRaw || '[]');
              if (Array.isArray(embSegs) && embSegs.length >= 5) {
                embSegs.forEach((s) => {
                  if (s && s.text) {
                    allSegments.push({ start: Number(s.start) || 0, end: Number(s.end) || 0, text: String(s.text).trim() });
                  }
                });
                if (allSegments.length >= 5) {
                  usedEmbeddedSubtitles = true;
                  statusEl.innerText = `⚡ Extracted embedded subtitles from container (${allSegments.length} lines)!`;
                  setProgress(50);
                }
              }
            } catch (e) {
              diag.logWarn('gen.embeddedSubs', e);
            }
          }

          if (!usedEmbeddedSubtitles) {
            const cachedPartial = getCachedPartialChunks();
            const resumeChunks = (!startFromSec && cachedPartial.fileSig === fileSig && cachedPartial.model === model && cachedPartial.chunks)
              ? cachedPartial.chunks
              : {};
            const completedChunksObj = {};
            const shouldCompress = isBatchMode
              ? (Boolean(file.isExternalMedia) || file.size > 24 * 1024 * 1024 || (fastAudioCompressEl.checked && file.size > 14 * 1024 * 1024))
              : (Boolean(file.isExternalMedia) || file.size > 24 * 1024 * 1024 || fastAudioCompressEl.checked);
            const useNativeExtractor = Boolean(
              shouldCompress &&
              bridge &&
              typeof bridge.startNativeAudioExtraction === 'function' &&
              (typeof bridge.readChunkBase64 === 'function' || typeof fetch === 'function') &&
              bridge.hasNativeVideo()
            );

            const streamingTranslate = Boolean(targetLang && !(cachedWhisper.translations && cachedWhisper.translations[targetLang]));
            const livePipeline = createLivePipeline({
              file,
              isBatchMode,
              signal,
              streamingTranslate,
              targetLang,
              smartSplitEnabled,
              pipelineTranslatedCuesMap,
              setCues,
              onLivePublish: (f, isFirstStart) => {
                if (isFirstStart) {
                  setupPlayer(f);
                  statusEl.innerText = '▶️ First subtitles ready — you can start watching while remaining parts process...';
                  if (autoPlayToggle.checked) videoEl.play().catch(() => {});
                }
                refreshAllExports();
                updateSubtitleFrame();
              },
              onStreamTranslated: markStreamChunkTranslated,
              groqCtx
            });

            if (useNativeExtractor) {
              if (isBatchMode) {
                if (startFromSec > 0) {
                  const fromLabel = formatTime(startFromSec);
                  statusEl.innerText = file.isStream
                    ? `📡 Fast mode from ${fromLabel}: 16 kHz multi-stream...`
                    : `⚡ Fast mode from ${fromLabel}: 16 kHz parallel streams...`;
                } else {
                  statusEl.innerText = file.isStream
                    ? '📡 Fast mode: streaming 16 kHz audio chunks...'
                    : '⚡ Fast mode: parallel 16 kHz audio extraction & upload...';
                }
              } else if (startFromSec > 0) {
                const fromLabel = formatTime(startFromSec);
                statusEl.innerText = file.isStream
                  ? `📡 Stream (from ${fromLabel}): decoding audio via player (16 kHz, 60s)...`
                  : `⚡ Parallel audio extraction from ${fromLabel} (16 kHz, 60s chunks)...`;
              } else {
                statusEl.innerText = file.isStream
                  ? '📡 Stream: decoding audio via player (16 kHz, 60s chunks)...'
                  : '⚡ Parallel native audio extraction (16 kHz, 60s chunks)...';
              }

              const incomingQueue = [];
              const nativeOrderedResults = [];
              let nativeExtractionFinished = false;
              let nativeError = null;
              let totalChunksSeen = 0;
              let completedChunks = 0;
              const chunkWaiters = [];

              const updateNativeProgress = (recognitionDone) => {
                const total = Math.max(1, estimatedTotalChunks, totalChunksSeen, recognitionDone);
                if (streamingTranslate) {
                  const units = Math.min(recognitionDone + streamTranslatedChunks, total * 2);
                  setProgress(5 + Math.round((units / (total * 2)) * 93));
                } else {
                  setProgress(5 + Math.round((Math.min(recognitionDone, total) / total) * 50));
                }
              };
              updateStreamProgress = () => updateNativeProgress(completedChunks);

              const notifyWaiters = () => {
                if (nativeError || nativeExtractionFinished) {
                  while (chunkWaiters.length > 0) {
                    const w = chunkWaiters.shift();
                    if (w) w();
                  }
                  return;
                }
                while (chunkWaiters.length > 0 && incomingQueue.length > 0) {
                  const w = chunkWaiters.shift();
                  if (w) w();
                }
              };

              window.__onNativeChunkReady = (chunkObj, isLast) => {
                incomingQueue.push({ chunkObj, isLast });
                if (incomingQueue.length > 1) {
                  incomingQueue.sort((a, b) => a.chunkObj.index - b.chunkObj.index);
                }
                totalChunksSeen = Math.max(totalChunksSeen, chunkObj.index + 1);
                totalChunks = totalChunksSeen;
                if (isLast) nativeExtractionFinished = true;
                notifyWaiters();
              };

              window.__onNativeChunkDone = (count) => {
                nativeExtractionFinished = true;
                if (typeof count === 'number' && count > 0) {
                  totalChunksSeen = Math.max(totalChunksSeen, count);
                  totalChunks = totalChunksSeen;
                }
                notifyWaiters();
              };

              window.__onNativeChunkError = (errMsg) => {
                nativeError = new Error(errMsg || 'Native audio extraction error');
                nativeExtractionFinished = true;
                notifyWaiters();
              };

              const onSignalAbort = () => {
                if (bridge && typeof bridge.cancelNativeAudioExtraction === 'function') {
                  try { bridge.cancelNativeAudioExtraction(); } catch (e) { diag.logWarn('pipeline.abortCancel', e); }
                }
                notifyWaiters();
              };
              signal.addEventListener('abort', onSignalAbort, { once: true });

              if (bridge && typeof bridge.clearAudioCache === 'function') {
                try { bridge.clearAudioCache(); } catch (e) { diag.logWarn('pipeline.clearCache', e); }
              }

              const startedOk = (typeof bridge.startNativeAudioExtractionMode === 'function')
                ? bridge.startNativeAudioExtractionMode(startFromSec, isBatchMode)
                : ((typeof bridge.startNativeAudioExtractionFrom === 'function')
                  ? bridge.startNativeAudioExtractionFrom(startFromSec)
                  : bridge.startNativeAudioExtraction());
              if (!startedOk) {
                throw new Error('Failed to start native audio extractor');
              }

              const getNextIncomingChunk = async () => {
                while (true) {
                  if (signal.aborted) throw new Error('Cancelled by user.');
                  if (nativeError) throw nativeError;
                  if (incomingQueue.length > 0) return incomingQueue.shift();
                  if (nativeExtractionFinished) return null;
                  await new Promise((resolve) => chunkWaiters.push(resolve));
                }
              };

              const processSingleChunk = async (chunkItem) => {
                if (signal.aborted) throw new Error('Cancelled by user.');
                const { chunkObj } = chunkItem;
                const cachedChunk = resumeChunks[String(chunkObj.index)] || null;
                let r;
                if (cachedChunk && Array.isArray(cachedChunk.segments)) {
                  if (bridge && typeof bridge.deleteChunkFile === 'function' && chunkObj.path) {
                    try { bridge.deleteChunkFile(chunkObj.path); } catch (e) { diag.logWarn('pipeline.deleteChunk', e); }
                  }
                  r = { segments: cachedChunk.segments, words: cachedChunk.words || [] };
                } else {
                  const wavBlob = await bridgeMod.loadChunkBlob(bridge, chunkObj);
                  if (!wavBlob) throw new Error(`Failed to read chunk ${chunkObj.index}`);
                  r = await groqMod.sendToGroq(
                    wavBlob,
                    chunkObj.fileName || `chunk_${chunkObj.index}.wav`,
                    model,
                    customPrompt,
                    signal,
                    groqCtx
                  );
                }

                completedChunks++;
                const startSec = chunkObj.pos !== undefined ? chunkObj.pos : chunkObj.startSec;
                const endSec = chunkObj.chunkEnd !== undefined ? chunkObj.chunkEnd : chunkObj.endSec;
                const cutoffSec = chunkObj.cutoff !== undefined ? chunkObj.cutoff : chunkObj.cutoffSec;
                const nextCutoffSec = chunkObj.nextCutoff !== undefined ? chunkObj.nextCutoff : chunkObj.nextCutoffSec;

                statusEl.innerText = `🚀 Transcribed: ${completedChunks} chunks (${Math.round(startSec)}–${Math.round(endSec)}s)...`;
                updateNativeProgress(completedChunks);

                const resItem = {
                  index: chunkObj.index,
                  pos: startSec,
                  cutoff: cutoffSec,
                  nextCutoff: nextCutoffSec,
                  segments: r.segments || [],
                  words: r.words || []
                };

                completedChunksObj[String(chunkObj.index)] = {
                  pos: startSec,
                  cutoff: cutoffSec,
                  nextCutoff: nextCutoffSec,
                  segments: resItem.segments,
                  words: resItem.words
                };
                if (startFromSec === 0 && !generationDoneForHistory && !signal.aborted) {
                  storageMod.saveProjectToHistory({
                    fileSig,
                    fileName: file.name,
                    fileSize: file.size,
                    updatedAt: Date.now(),
                    model,
                    isComplete: false,
                    partialChunks: { ...completedChunksObj }
                  });
                }

                nativeOrderedResults[chunkObj.index] = resItem;
                livePipeline.handleCompletedChunk(chunkObj.index, resItem);
                return resItem;
              };

              try {
                const workerCount = isBatchMode ? getBatchConcurrency() : Math.min(PARALLEL_CHUNK_CONCURRENCY, 10);
                const staggerMs = isBatchMode ? 35 : 25;
                const workers = Array.from({ length: workerCount }, async (_, workerSlot) => {
                  if (workerSlot > 0) await groqMod.abortableDelay(workerSlot * staggerMs, signal);
                  while (true) {
                    const item = await getNextIncomingChunk();
                    if (!item) break;
                    await processSingleChunk(item);
                  }
                });
                await Promise.all(workers);
                if (nativeError) throw nativeError;
                if (signal.aborted) throw new Error('Cancelled by user.');
                await livePipeline.awaitPendingTranslations(statusEl);

                const merged = subtitlesMod.mergeChunkResults(nativeOrderedResults);
                allSegments = merged.allSegments;
                allWords = merged.allWords;
              } finally {
                window.__onNativeChunkReady = null;
                window.__onNativeChunkDone = null;
                window.__onNativeChunkError = null;
                signal.removeEventListener('abort', onSignalAbort);
              }
            } else {
              // WebAudio / прямой upload для браузера или небольших файлов
              let audioBuffer = null;
              if (shouldCompress) {
                statusEl.innerText = isBatchMode
                  ? '⚡ Fast mode: extracting audio at 16 kHz...'
                  : '⚡ Fast audio track extraction & compression (16 kHz)...';
                setProgress(10);
                try {
                  const ab = await file.arrayBuffer();
                  const decodeCtx = new (window.AudioContext || window.webkitAudioContext)({ sampleRate: 16000 });
                  audioBuffer = await decodeCtx.decodeAudioData(ab);
                  if (decodeCtx.close) decodeCtx.close();
                } catch (e) {
                  diag.logWarn('gen.webAudioDecode', e);
                  if (file.size > 24 * 1024 * 1024) {
                    throw new Error('Browser does not support this file codec (>24 MB). Convert to MP4/MP3.');
                  }
                }
              }

              if (!audioBuffer) {
                statusEl.innerText = '🚀 Uploading directly to Groq...';
                setProgress(25);
                const r = await groqMod.sendToGroq(file, file.name, model, customPrompt, signal, groqCtx);
                r.segments.forEach((s) => allSegments.push({ start: s.start, end: s.end, text: s.text }));
                r.words.forEach((w) => allWords.push({ word: w.word, start: w.start, end: w.end }));
              } else {
                if (signal.aborted) throw new Error('Cancelled by user.');
                const channelData = extractMonoChannelData(audioBuffer);
                const sampleRate = audioBuffer.sampleRate;
                const totalDuration = channelData.length / sampleRate;
                const effectiveStart = (startFromSec > 0 && startFromSec < totalDuration - 1) ? startFromSec : 0;
                const effectiveRem = Math.max(0, totalDuration - effectiveStart);
                const chunkDuration = isBatchMode
                  ? (effectiveRem <= 720 ? Math.min(180, Math.max(45, Math.ceil(effectiveRem / 4))) : 180)
                  : 60;
                const overlap = isBatchMode ? 2 : 1;
                const advance = chunkDuration - overlap;

                const chunkTasks = [];
                let pos = effectiveStart;
                let cutoff = effectiveStart;
                let c = 0;

                while (pos < totalDuration - 0.1) {
                  const chunkEnd = Math.min(pos + chunkDuration, totalDuration);
                  const isLast = chunkEnd >= totalDuration - 0.1;
                  const nextCutoff = isLast ? totalDuration + 10 : (pos + chunkDuration - overlap / 2);
                  const startSample = Math.floor(pos * sampleRate);
                  const endSample = Math.min(Math.floor(chunkEnd * sampleRate), channelData.length);
                  if (endSample - startSample >= sampleRate) {
                    chunkTasks.push({ index: c, pos, chunkEnd, cutoff, nextCutoff, startSample, endSample });
                  }
                  cutoff = nextCutoff;
                  pos += advance;
                  c++;
                }

                totalChunks = chunkTasks.length;
                let completedChunks = 0;
                statusEl.innerText = isBatchMode
                  ? `🚀 Fast mode: parallel Groq upload (${Math.min(getBatchConcurrency(), Math.max(1, totalChunks))} streams, ${totalChunks} parts)...`
                  : `🚀 Parallel Groq upload (${Math.min(PARALLEL_CHUNK_CONCURRENCY, Math.max(1, totalChunks))} streams, ${totalChunks} parts)...`;
                setProgress(10);

                const updateWebProgress = (recognitionDone) => {
                  const total = Math.max(1, totalChunks, recognitionDone);
                  if (streamingTranslate) {
                    const units = Math.min(recognitionDone + streamTranslatedChunks, total * 2);
                    setProgress(5 + Math.round((units / (total * 2)) * 93));
                  } else {
                    setProgress(5 + Math.round((Math.min(recognitionDone, total) / total) * 50));
                  }
                };
                updateStreamProgress = () => updateWebProgress(completedChunks);

                const webConcurrency = isBatchMode ? getBatchConcurrency() : PARALLEL_CHUNK_CONCURRENCY;
                const orderedResults = await groqMod.runWithConcurrency(
                  chunkTasks,
                  webConcurrency,
                  async (task, _taskIdx, workerSlot, isFirstForWorker) => {
                    if (signal.aborted) throw new Error('Cancelled by user.');
                    if (isFirstForWorker && workerSlot > 0) {
                      await groqMod.abortableDelay(workerSlot * (isBatchMode ? 35 : 25), signal);
                    }
                    if (signal.aborted) throw new Error('Cancelled by user.');

                    const cachedChunk = resumeChunks[String(task.index)] || null;
                    let r;
                    if (cachedChunk && Array.isArray(cachedChunk.segments)) {
                      r = { segments: cachedChunk.segments, words: cachedChunk.words || [] };
                    } else {
                      const chunkSamples = channelData.subarray(task.startSample, task.endSample);
                      const wavBlob = encodeWAV(chunkSamples, sampleRate);
                      r = await groqMod.sendToGroq(wavBlob, `chunk_${task.index}.wav`, model, customPrompt, signal, groqCtx);
                    }

                    completedChunks++;
                    statusEl.innerText = `🚀 Transcribed: ${completedChunks} of ${totalChunks} (${Math.round(task.pos)}–${Math.round(task.chunkEnd)}s)...`;
                    updateWebProgress(completedChunks);

                    const chunkItem = {
                      index: task.index,
                      pos: task.pos,
                      cutoff: task.cutoff,
                      nextCutoff: task.nextCutoff,
                      segments: r.segments || [],
                      words: r.words || []
                    };

                    completedChunksObj[String(task.index)] = {
                      pos: task.pos,
                      cutoff: task.cutoff,
                      nextCutoff: task.nextCutoff,
                      segments: chunkItem.segments,
                      words: chunkItem.words
                    };
                    if (startFromSec === 0 && !generationDoneForHistory && !signal.aborted) {
                      storageMod.saveProjectToHistory({
                        fileSig,
                        fileName: file.name,
                        fileSize: file.size,
                        updatedAt: Date.now(),
                        model,
                        isComplete: false,
                        partialChunks: { ...completedChunksObj }
                      });
                    }

                    livePipeline.handleCompletedChunk(task.index, chunkItem);
                    return chunkItem;
                  }
                );

                if (signal.aborted) throw new Error('Cancelled by user.');
                await livePipeline.awaitPendingTranslations(statusEl);

                const merged = subtitlesMod.mergeChunkResults(orderedResults);
                allSegments = merged.allSegments;
                allWords = merged.allWords;
              }
            }
          }

          if (!allSegments.length) throw new Error('Could not recognize speech.');

          setCachedWhisper({
            fileSig,
            model,
            rawSegments: allSegments,
            rawWords: allWords,
            translations: {},
            activeLang: null,
            aiSummary: null
          });
        } else {
          statusEl.innerText = 'Using recognized text from cache...';
          setProgress(55);
        }

        const latestCached = getCachedWhisper();
        let translationWarn = '';
        if (!targetLang) {
          setCues(subtitlesMod.buildCues(latestCached.rawSegments, latestCached.rawWords, smartSplitEnabled));
        } else if (latestCached.translations[targetLang]) {
          setCues(subtitlesMod.cloneCues(latestCached.translations[targetLang]));
        } else if (pipelineTranslatedCuesMap.size > 0) {
          const streamedCues = [];
          for (let i = 0; i < (totalChunks || 0); i++) {
            const chCues = pipelineTranslatedCuesMap.get(i);
            if (chCues && chCues.length) streamedCues.push(...chCues);
          }
          const translateConcurrency = isBatchMode ? getBatchConcurrency() : 10;
          if (streamedCues.length > 0) {
            setCues(streamedCues);
            latestCached.translations[targetLang] = subtitlesMod.cloneCues(streamedCues);
          } else {
            const freshCues = subtitlesMod.buildCues(latestCached.rawSegments, latestCached.rawWords, smartSplitEnabled);
            const failed = await groqMod.translateSegments(freshCues, targetLang, statusEl, signal, translateConcurrency, groqCtx);
            if (failed > 0) translationWarn = ` (${failed} lines not translated).`;
            latestCached.translations[targetLang] = subtitlesMod.cloneCues(freshCues);
            setCues(freshCues);
          }
        } else {
          const translateConcurrency = isBatchMode ? getBatchConcurrency() : 10;
          const freshCues = subtitlesMod.buildCues(latestCached.rawSegments, latestCached.rawWords, smartSplitEnabled);
          const failed = await groqMod.translateSegments(freshCues, targetLang, statusEl, signal, translateConcurrency, groqCtx);
          if (failed > 0) translationWarn = ` (${failed} lines not translated).`;
          if (failed < freshCues.length) {
            latestCached.translations[targetLang] = subtitlesMod.cloneCues(freshCues);
          }
          setCues(freshCues);
        }

        if (signal.aborted) throw new Error('Cancelled by user.');
        if (!getCues().length) throw new Error('Failed to build subtitles.');

        generationDoneForHistory = true;
        setCachedPartialChunks({ fileSig: '', model: '', chunks: {} });
        latestCached.activeLang = targetLang;
        setProgress(100);
        refreshAllExports();

        if (startFromSec === 0) {
          storageMod.saveProjectToHistory({
            fileSig,
            fileName: file.name,
            fileSize: file.size,
            updatedAt: Date.now(),
            model,
            isComplete: true,
            partialChunks: null,
            rawSegments: latestCached.rawSegments,
            rawWords: latestCached.rawWords,
            translations: latestCached.translations,
            activeLang: latestCached.activeLang,
            cues: getCues()
          });
        }

        actionRow.style.display = 'flex';
        statusEl.innerText = (startFromSec === 0 ? 'Done! Subtitles loaded and saved to history.' : 'Done! Subtitles loaded.') + translationWarn;
        setupPlayer(file);

        if (autoPlayToggle.checked && videoEl.paused) {
          videoEl.play().catch(() => {});
        }
      } catch (e) {
        diag.logError('gen.pipeline', e);
        statusEl.innerText = 'Status: ' + (e && e.message ? e.message : e);
      } finally {
        if (getActiveAbortCtrl() === myAbortCtrl) {
          if (bridge && typeof bridge.cancelNativeAudioExtraction === 'function') {
            try { bridge.cancelNativeAudioExtraction(); } catch (e) { diag.logWarn('pipeline.finalCancel', e); }
          }
          if (bridge && typeof bridge.clearAudioCache === 'function') {
            try { bridge.clearAudioCache(); } catch (e) { diag.logWarn('pipeline.finalClearCache', e); }
          }
          if (bridge && typeof bridge.stopForegroundService === 'function') {
            try { bridge.stopForegroundService(); } catch (e) { diag.logWarn('pipeline.finalStopFg', e); }
          }
          setActiveAbortCtrl(null);
          btn.classList.remove('cancel-mode');
          updateButtonLabel();
          if (signal.aborted) {
            if (getCues().length) {
              refreshAllExports();
              actionRow.style.display = 'flex';
            }
            statusEl.innerText = '⏹ Cancelled by user.';
          }
          setTimeout(() => { if (!getActiveAbortCtrl()) setProgress(null); }, 1200);
          if (btn.blur) btn.blur();
          if (videoEl.paused) releaseWakeLock();
        }
      }
    }

    return {
      generateSubtitles,
      cancelActiveGeneration
    };
  }

  root.audioPipeline = {
    createGenerationController,
    encodeWAV,
    extractMonoChannelData
  };
})();
