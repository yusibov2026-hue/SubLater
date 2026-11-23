(function () {
  'use strict';

  const root = window.SubLyter = window.SubLyter || {};
  const diag = root.diagnostics || { logWarn() {}, logError() {} };
  const bridgeMod = root.bridge;
  const storageMod = root.storage;
  const subtitlesMod = root.subtitles;
  const groqMod = root.groq;
  const geminiMod = root.gemini;
  const themeMod = root.theme;
  const playerMod = root.player;
  const pipelineMod = root.audioPipeline;

  const $ = (id) => document.getElementById(id);

  /* ---- Wake Lock ---- */
  let wakeLock = null;
  async function requestWakeLock() {
    if ('wakeLock' in navigator && !wakeLock) {
      try {
        wakeLock = await navigator.wakeLock.request('screen');
        wakeLock.addEventListener('release', () => { wakeLock = null; });
      } catch (e) {
        diag.logWarn('wakeLock.request', e);
      }
    }
  }
  function releaseWakeLock() {
    if (wakeLock) {
      wakeLock.release().catch(() => {});
      wakeLock = null;
    }
  }

  /* ---- Инициализация темы, обоев и пикеров цвета ---- */
  const themeCtrl = themeMod.initThemeModule(storageMod);

  /* ---- DOM-элементы ---- */
  const settingsToggle = $('settingsToggle');
  const globalSettings = $('globalSettings');
  const settingsBackdrop = $('settingsBackdrop');
  const closeSettingsBtn = $('closeSettingsBtn');
  const accentColorEl = $('accentColor');
  const apiKeysListEl = $('apiKeysList');
  const keysCountBadge = $('keysCountBadge');
  const keyInput = $('apiKey');
  const keyInput2 = $('apiKey2');
  const geminiKeyInput = $('geminiApiKey');
  const geminiModelSelect = $('geminiModel');
  const aiActiveModelBadge = $('aiActiveModelBadge');

  const videoEl = $('videoPlayer');
  const playerBox = $('playerContainer');
  const overlayEl = $('subtitleOverlay');
  const subtitleEl = $('subtitleText');
  const statusEl = $('status');
  const btn = $('btn');
  const progressWrap = $('progressWrap');
  const progressBar = $('progressBar');
  const actionRow = $('actionRow');
  const exportFormat = $('exportFormat');
  const dlBtn = $('dlBtn');
  const openBtn = $('openBtn');
  const vttPanel = $('vttPanel');
  const aiSummaryBox = $('aiSummaryBox');
  const aiSummaryText = $('aiSummaryText');
  const aiChaptersList = $('aiChaptersList');
  const retryBtn = $('retryBtn');
  const dlWrap = $('dlWrap');
  const dlFormatMenu = $('dlFormatMenu');
  const shareFmtItem = $('shareFmtItem');
  const shareFmtExt = $('shareFmtExt');

  const sizeEl = $('subSize');
  const widthEl = $('subWidth');
  const bottomEl = $('subBottom');
  const bgToggle = $('bgToggle');
  const wordModeSelect = $('wordModeSelect');
  const fitModeSelect = $('fitModeSelect');
  const speedSlider = $('speedSlider');
  const speedVal = $('speedVal');
  const subOffsetEl = $('subOffset');
  const subOffsetVal = $('subOffsetVal');
  const subHoldEl = $('subHold');
  const subHoldVal = $('subHoldVal');
  const sizeVal = $('subSizeVal');
  const widthVal = $('subWidthVal');
  const bottomVal = $('subBottomVal');
  const volSlider = $('volSlider');
  const volVal = $('volVal');
  const subTextColorEl = $('subTextColor');
  const bgOpacityEl = $('bgOpacity');
  const autoPlayToggle = $('autoPlayToggle');
  const smoothHzEl = $('smoothHz');
  const boostSlider = $('boostSlider');
  const boostVal = $('boostVal');
  const llmModelEl = $('llmModel');
  const hallucinationFilterEl = $('hallucinationFilter');
  const smartSplitToggleEl = $('smartSplitToggle');
  const fastAudioCompressEl = $('fastAudioCompress');
  const genModeEl = $('genMode');
  const genModeAllBtn = $('genModeAllBtn');
  const genModeProgressiveBtn = $('genModeProgressiveBtn');
  const whisperPromptEl = $('whisperPrompt');
  const audioTrackAccItem = $('audioTrackAccItem');
  const audioTrackSelect = $('audioTrackSelect');
  const audioTrackVal = $('audioTrackVal');

  /* ---- Состояние приложения ---- */
  let exportData = { vtt: '', srt: '', ass: '', txt: '' };
  let currentFileName = 'subtitles';
  let cues = [];
  let cachedWhisper = {
    fileSig: '',
    model: '',
    rawSegments: [],
    rawWords: [],
    translations: {},
    activeLang: null,
    aiSummary: null
  };
  let cachedPartialChunks = { fileSig: '', model: '', chunks: {} };
  let activeAbortCtrl = null;
  let audioBoost = 100;
  let progressMaxReached = -1;
  let externalMedia = null;

  function getActiveWhisperModel() {
    const modelEl = $('model');
    const raw = modelEl ? modelEl.value : 'auto';
    return raw === 'auto' ? 'whisper-large-v3-turbo' : raw;
  }

  function getFileSignature(file) {
    if (!file) return '';
    if (file.uri) return `${file.name}_${file.size || 0}_${file.uri}`;
    return `${file.name}_${file.size || 0}_${file.lastModified || 0}`;
  }

  function __virtualExternalFile(info) {
    return {
      name: info.name,
      size: info.size,
      type: info.mime || 'video/*',
      lastModified: Number(info.lastModified) || 0,
      uri: info.uri || '',
      isExternalMedia: true,
      isStream: Boolean(info.isStream)
    };
  }

  function getActiveMedia() {
    const picked = $('mediaFile').files[0];
    if (picked) return picked;
    const info = externalMedia;
    if (!info) return null;
    if (!info.file) info.file = __virtualExternalFile(info);
    return info.file;
  }

  /* ---- Модальное окно настроек ---- */
  function openSettings() {
    document.body.classList.add('global-settings-open');
    if (window.__syncNativePlayerBounds) window.__syncNativePlayerBounds();
    globalSettings.classList.add('show');
    settingsBackdrop.classList.add('show');
    settingsToggle.classList.add('open');
  }
  function closeSettings() {
    document.body.classList.remove('global-settings-open');
    if (window.__syncNativePlayerBounds) window.__syncNativePlayerBounds();
    globalSettings.classList.remove('show');
    settingsBackdrop.classList.remove('show');
    settingsToggle.classList.remove('open');
  }
  settingsToggle.addEventListener('click', openSettings);
  settingsBackdrop.addEventListener('click', closeSettings);
  closeSettingsBtn.addEventListener('click', closeSettings);

  function openApiSettingsFocus() {
    openSettings();
    setTimeout(() => {
      if (apiKeysListEl) {
        try { apiKeysListEl.scrollIntoView({ behavior: 'smooth', block: 'center' }); } catch (_) {}
        try { apiKeysListEl.focus(); } catch (_) {}
      }
    }, 180);
  }

  /* ---- Управление API-ключами ---- */
  function syncKeysBadge(keys) {
    if (!keysCountBadge) return;
    const count = keys.length;
    keysCountBadge.style.color = '';
    keysCountBadge.classList.toggle('keys-empty', count === 0);
    keysCountBadge.classList.toggle('keys-ready', count > 0);
    keysCountBadge.textContent = count === 0 ? '0 keys' : (count === 1 ? '1 key' : `${count} keys`);
  }

  function getApiKeys() {
    const raw = (apiKeysListEl && apiKeysListEl.value.trim()) || storageMod.getGroqApiKeysFromKeystore();
    const keys = storageMod.parseKeyList(raw);
    syncKeysBadge(keys);
    return keys;
  }

  const keyPool = new groqMod.KeyPool(getApiKeys);

  function showMissingApiStatus(fromGenerate = false) {
    if (!statusEl) return;
    const msg = fromGenerate
      ? 'Groq API key is missing — add it in Settings (API Keys section).'
      : 'No Groq API key yet — open Settings to add your API key.';
    statusEl.innerHTML = '';
    const wrap = document.createElement('span');
    wrap.className = 'status-api-hint';
    const txt = document.createElement('span');
    txt.textContent = msg;
    const goBtn = document.createElement('button');
    goBtn.type = 'button';
    goBtn.className = 'status-settings-btn';
    goBtn.textContent = 'Open Settings';
    goBtn.addEventListener('click', openApiSettingsFocus);
    wrap.appendChild(txt);
    wrap.appendChild(goBtn);
    statusEl.appendChild(wrap);
  }

  function refreshMissingApiStatusHint() {
    if (!statusEl) return;
    const keys = getApiKeys();
    const hasMissingBtn = Boolean(statusEl.querySelector('.status-settings-btn'));
    if (keys.length === 0) {
      if (!statusEl.textContent.trim() || hasMissingBtn) showMissingApiStatus(false);
    } else if (hasMissingBtn) {
      statusEl.innerHTML = '';
    }
  }

  const initialGroqKeys = storageMod.getGroqApiKeysFromKeystore();
  if (apiKeysListEl) {
    apiKeysListEl.value = initialGroqKeys;
    const onKeysEdit = () => {
      const keys = storageMod.saveGroqApiKeys(apiKeysListEl.value);
      keyPool.reset();
      syncKeysBadge(keys);
      if (keyInput) keyInput.value = keys[0] || '';
      if (keyInput2) keyInput2.value = keys[1] || '';
      refreshMissingApiStatusHint();
    };
    apiKeysListEl.addEventListener('input', onKeysEdit);
    apiKeysListEl.addEventListener('change', onKeysEdit);
  }
  syncKeysBadge(getApiKeys());
  refreshMissingApiStatusHint();

  geminiKeyInput.value = storageMod.getGeminiKeyFromKeystore();
  geminiKeyInput.addEventListener('input', () => storageMod.saveGeminiKeyToKeystore(geminiKeyInput.value));
  geminiKeyInput.addEventListener('change', () => storageMod.saveGeminiKeyToKeystore(geminiKeyInput.value));

  const savedGeminiModel = storageMod.safeGetStorage('gemini_model', 'gemini-4-hydrogen');
  const hasGeminiOpt = savedGeminiModel && Array.from(geminiModelSelect.options).some((o) => o.value === savedGeminiModel);
  geminiModelSelect.value = hasGeminiOpt ? savedGeminiModel : 'gemini-4-hydrogen';
  function syncModelBadge() {
    const opt = geminiModelSelect.options[geminiModelSelect.selectedIndex];
    aiActiveModelBadge.textContent = opt ? opt.textContent : 'Gemini 4 Hydrogen';
    storageMod.safeSetStorage('gemini_model', geminiModelSelect.value);
  }
  geminiModelSelect.addEventListener('change', syncModelBadge);
  syncModelBadge();

  /* ---- Прогресс ---- */
  function beginProgress() {
    progressMaxReached = 0;
    progressWrap.style.display = 'block';
    progressBar.style.width = '0%';
  }

  function setProgress(pct) {
    if (pct === null) {
      progressWrap.style.display = 'none';
      progressBar.style.width = '0%';
      progressMaxReached = -1;
      return;
    }
    if (progressMaxReached < 0) {
      progressMaxReached = 0;
      progressWrap.style.display = 'block';
    }
    const value = Math.min(100, Math.max(0, Number(pct) || 0));
    progressMaxReached = Math.max(progressMaxReached, value);
    progressBar.style.width = progressMaxReached + '%';

    if (activeAbortCtrl) {
      const b = bridgeMod.getBridge();
      if (b && typeof b.updateForegroundProgress === 'function') {
        try {
          const msg = (statusEl && statusEl.innerText) ? statusEl.innerText : 'Generating subtitles...';
          b.updateForegroundProgress(msg, Math.round(progressMaxReached));
        } catch (_) {}
      }
    }
  }

  /* ---- Экспорт субтитров ---- */
  function buildAssStyleOpts() {
    return {
      size: sizeEl.value,
      bottom: bottomEl.value,
      textColor: subTextColorEl.value,
      accentColor: accentColorEl.value,
      bgOpacity: bgOpacityEl ? bgOpacityEl.value : '62',
      bgEnabled: bgToggle.checked
    };
  }

  function updateExportPreview() {
    const fmt = exportFormat.value || 'vtt';
    vttPanel.innerText = exportData[fmt] || '';
    if (shareFmtExt) shareFmtExt.textContent = '.' + fmt.toUpperCase();
  }
  exportFormat.addEventListener('change', updateExportPreview);

  function refreshAllExports() {
    exportData.vtt = subtitlesMod.generateVTT(cues);
    exportData.srt = subtitlesMod.generateSRT(cues);
    exportData.ass = subtitlesMod.generateASS(cues, buildAssStyleOpts());
    exportData.txt = subtitlesMod.generateTXT(cues);
    updateExportPreview();
  }

  function downloadSubtitle(fmtOverride) {
    const fmt = fmtOverride || exportFormat.value || 'vtt';
    exportFormat.value = fmt;
    updateExportPreview();
    const content = exportData[fmt];
    if (!content) return;
    const b = bridgeMod.getBridge();
    if (b && typeof b.saveSubtitleFile === 'function') {
      const ok = b.saveSubtitleFile(`${currentFileName}.${fmt}`, content, fmt);
      if (ok) {
        statusEl.innerText = `✅ File ${currentFileName}.${fmt} saved to Downloads!`;
        return;
      }
    }
    const blob = new Blob([content], { type: 'text/plain;charset=utf-8' });
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = `${currentFileName}.${fmt}`;
    document.body.appendChild(a);
    a.click();
    setTimeout(() => { URL.revokeObjectURL(a.href); a.remove(); }, 1000);
  }

  async function shareSubtitles(fmtOverride) {
    const fmt = fmtOverride || exportFormat.value || 'vtt';
    exportFormat.value = fmt;
    updateExportPreview();
    const content = exportData[fmt];
    if (!content) {
      statusEl.innerText = 'Generate subtitles first!';
      return;
    }
    const safeFileName = `${currentFileName}.${fmt}`;
    const b = bridgeMod.getBridge();
    if (b && typeof b.shareSubtitleFile === 'function') {
      const ok = b.shareSubtitleFile(safeFileName, content, fmt);
      if (ok) return;
    }
    if (navigator.share) {
      try {
        const blob = new Blob([content], { type: 'text/plain;charset=utf-8' });
        const file = new File([blob], safeFileName, { type: 'text/plain' });
        if (navigator.canShare && navigator.canShare({ files: [file] })) {
          await navigator.share({ files: [file], title: safeFileName, text: content });
          return;
        }
        await navigator.share({ title: safeFileName, text: content });
        return;
      } catch (err) {
        if (err && err.name === 'AbortError') return;
      }
    }
    downloadSubtitle(fmt);
  }

  /* ---- Контроллер плеера ---- */
  let playerCtrl = null;
  function applySettings() {
    const size = Math.min(48, Math.max(12, parseInt(sizeEl.value, 10) || 18));
    const width = Math.min(100, Math.max(30, parseInt(widthEl.value, 10) || 85));
    const bottom = Math.min(35, Math.max(5, parseInt(bottomEl.value, 10) || 12));
    const wordMode = wordModeSelect.value || 'hidden';
    const fitMode = fitModeSelect.value || 'contain';
    const textColor = subTextColorEl.value;
    const fontFamily = "'Inter', -apple-system, sans-serif";
    const bgOpacity = bgOpacityEl ? bgOpacityEl.value : '62';
    const smoothHz = smoothHzEl ? Math.min(144, Math.max(15, parseInt(smoothHzEl.value, 10) || 90)) : 90;
    const boost = Math.min(200, Math.max(100, parseInt(boostSlider.value, 10) || 100));
    audioBoost = boost;
    window.__nativeSyncHz = smoothHz;
    if (boostVal) boostVal.textContent = boost + '%';

    const subOffset = parseInt(subOffsetEl.value, 10) || 0;
    const subHold = parseInt(subHoldEl.value, 10) || 0;

    const styleRoot = document.documentElement.style;
    styleRoot.setProperty('--sub-size', size + 'px');
    styleRoot.setProperty('--sub-width', width + '%');
    styleRoot.setProperty('--sub-bottom', bottom + '%');
    styleRoot.setProperty('--sub-color', textColor);
    styleRoot.setProperty('--sub-font', fontFamily);
    styleRoot.setProperty('--bg-opacity', bgOpacity);
    if (subtitleEl) subtitleEl.style.fontFamily = fontFamily;

    sizeVal.textContent = size + 'px';
    widthVal.textContent = width + '%';
    bottomVal.textContent = bottom + '%';
    subOffsetVal.textContent = subOffset + ' ms';
    subHoldVal.textContent = subHold + ' ms';
    const fitModeValEl = $('fitModeVal');
    if (fitModeValEl && fitModeSelect.selectedIndex >= 0) {
      fitModeValEl.textContent = fitModeSelect.options[fitModeSelect.selectedIndex].textContent;
    }
    const wordModeValEl = $('wordModeVal');
    if (wordModeValEl && wordModeSelect.selectedIndex >= 0) {
      wordModeValEl.textContent = wordModeSelect.options[wordModeSelect.selectedIndex].textContent;
    }

    videoEl.classList.toggle('fill-crop', fitMode === 'cover');
    videoEl.classList.toggle('fill-stretch', fitMode === 'fill');
    subtitleEl.classList.toggle('bg-on', bgToggle.checked);
    subtitleEl.classList.toggle('bg-off', !bgToggle.checked);
    subtitleEl.classList.toggle('mode-karaoke', wordMode === 'karaoke');
    subtitleEl.classList.toggle('mode-full', wordMode === 'full');

    if (cues.length) {
      exportData.ass = subtitlesMod.generateASS(cues, buildAssStyleOpts());
      if (exportFormat.value === 'ass') updateExportPreview();
    }
    if (playerCtrl && playerCtrl.isPlayerActive()) {
      playerCtrl.updateOverlayGeometry();
      playerCtrl.updateSubtitleFrame();
    }

    storageMod.safeSetStorage('groq_sub_settings', JSON.stringify({
      size, width, bottom,
      bg: bgToggle.checked,
      wordMode, fitMode,
      textColor, fontFamily, bgOpacity, smoothHz, boost,
      subOffset, subHold,
      autoPlay: autoPlayToggle.checked,
      llmModel: llmModelEl.value,
      hallucinationFilter: hallucinationFilterEl.checked,
      smartSplit: smartSplitToggleEl.checked,
      fastAudioCompress: fastAudioCompressEl.checked,
      genMode: genModeEl ? genModeEl.value : 'progressive',
      whisperPrompt: whisperPromptEl.value
    }));
  }

  playerCtrl = playerMod.createPlayerController({
    videoEl,
    playerBox,
    overlayEl,
    subtitleEl,
    statusEl,
    seekBar: $('seekBar'),
    timeText: $('timeText'),
    fsBtn: $('fsBtn'),
    settingsBtn: $('settingsBtn'),
    settingsMenu: $('settingsMenu'),
    controlsEl: $('playerControls'),
    fitModeSelect,
    wordModeSelect,
    bottomEl,
    subOffsetEl,
    subHoldEl,
    volSlider,
    volVal,
    speedSlider,
    speedVal,
    boostSlider,
    boostVal,
    autoPlayToggle,
    audioTrackAccItem,
    audioTrackSelect,
    audioTrackVal,
    getCues: () => cues,
    getAudioBoost: () => audioBoost,
    setAudioBoost: (v) => { audioBoost = v; },
    applySettings,
    syncM3RangeSlider: themeCtrl.syncM3RangeSlider,
    getFileSignature,
    requestWakeLock,
    releaseWakeLock
  });

  /* ---- Контроллер AI видео ---- */
  const geminiCtrl = geminiMod.createController({
    aiChatList: $('aiChatList'),
    aiQuestionInput: $('aiQuestionInput'),
    aiSendBtn: $('aiSendBtn'),
    clearAiChatBtn: $('clearAiChatBtn'),
    aiQuickPrompts: $('aiQuickPrompts'),
    aiSummaryBox,
    geminiKeyInput,
    geminiModelSelect,
    videoEl,
    playerBox,
    getCues: () => cues,
    formatTime: playerCtrl.formatTime,
    syncTimeUI: playerCtrl.syncTimeUI,
    updateSubtitleFrame: playerCtrl.updateSubtitleFrame,
    togglePlay: playerCtrl.togglePlay,
    openSettings,
    getGeminiKeyFromKeystore: storageMod.getGeminiKeyFromKeystore
  });
  $('closeAiBox').addEventListener('click', () => aiSummaryBox.classList.remove('show'));

  /* ---- Настройки режима генерации и сохранение ---- */
  function setGenMode(mode, save = true) {
    const m = (mode === 'all') ? 'all' : 'progressive';
    if (genModeEl) genModeEl.value = m;
    if (genModeAllBtn) genModeAllBtn.classList.toggle('active', m === 'all');
    if (genModeProgressiveBtn) genModeProgressiveBtn.classList.toggle('active', m === 'progressive');
    if (save) applySettings();
  }
  if (genModeAllBtn) genModeAllBtn.addEventListener('click', () => setGenMode('all'));
  if (genModeProgressiveBtn) genModeProgressiveBtn.addEventListener('click', () => setGenMode('progressive'));

  (function loadSettings() {
    try {
      const s = JSON.parse(storageMod.safeGetStorage('groq_sub_settings', '{}'));
      if (s.size) sizeEl.value = s.size;
      if (s.width) widthEl.value = s.width;
      if (s.bottom) bottomEl.value = s.bottom;
      if (typeof s.bg === 'boolean') bgToggle.checked = s.bg;
      else if (typeof s.bg === 'string') bgToggle.checked = (s.bg !== 'none');
      if (s.wordMode) wordModeSelect.value = s.wordMode;
      if (s.fitMode) fitModeSelect.value = s.fitMode;
      if (s.textColor) subTextColorEl.value = s.textColor;
      if (bgOpacityEl && s.bgOpacity !== undefined) bgOpacityEl.value = s.bgOpacity;
      if (smoothHzEl && s.smoothHz) smoothHzEl.value = s.smoothHz;
      if (s.boost !== undefined) boostSlider.value = s.boost;
      if (s.subOffset !== undefined) subOffsetEl.value = s.subOffset;
      if (s.subHold !== undefined) subHoldEl.value = s.subHold;
      if (s.autoPlay !== undefined) autoPlayToggle.checked = s.autoPlay;
      if (s.llmModel) llmModelEl.value = s.llmModel;
      if (s.hallucinationFilter !== undefined) hallucinationFilterEl.checked = s.hallucinationFilter;
      if (s.smartSplit !== undefined) smartSplitToggleEl.checked = s.smartSplit;
      if (s.fastAudioCompress !== undefined) fastAudioCompressEl.checked = s.fastAudioCompress;
      if (s.genMode) setGenMode(s.genMode, false);
      if (s.whisperPrompt !== undefined) whisperPromptEl.value = s.whisperPrompt;
    } catch (e) {
      diag.logWarn('settings.load', e);
    }
    applySettings();
    themeCtrl.syncColorPickers();
    themeCtrl.syncAllM3Sliders();
  })();

  [
    sizeEl, widthEl, bottomEl, bgToggle, wordModeSelect,
    fitModeSelect, subTextColorEl, subOffsetEl, subHoldEl,
    boostSlider, autoPlayToggle, llmModelEl, hallucinationFilterEl,
    fastAudioCompressEl, whisperPromptEl
  ].forEach((el) => {
    if (!el) return;
    el.addEventListener('input', () => {
      if (el.type === 'range') themeCtrl.syncM3RangeSlider(el);
      applySettings();
    });
    el.addEventListener('change', () => {
      if (el.type === 'range') themeCtrl.syncM3RangeSlider(el);
      applySettings();
    });
  });

  accentColorEl.addEventListener('input', (e) => {
    const color = e.target.value;
    storageMod.safeSetStorage('sublater_accent', color);
    themeCtrl.applyMaterialYouSettingsTheme();
    if ((!themeCtrl.wallpaperFollowAccentInput || themeCtrl.wallpaperFollowAccentInput.checked) && themeCtrl.isWhiteColorHex(color)) {
      themeCtrl.setWallpaperOpacityMax();
    }
    if (cues.length) {
      exportData.ass = subtitlesMod.generateASS(cues, buildAssStyleOpts());
      if (exportFormat.value === 'ass') updateExportPreview();
    }
  });

  smartSplitToggleEl.addEventListener('change', () => {
    applySettings();
    if (cachedWhisper.rawSegments.length && cachedWhisper.activeLang === '') {
      cues = subtitlesMod.buildCues(cachedWhisper.rawSegments, cachedWhisper.rawWords, smartSplitToggleEl.checked);
      refreshAllExports();
      playerCtrl.resetCueCursor();
      playerCtrl.updateSubtitleFrame();
    }
  });

  /* ---- Кнопка генерации и пайплайн ---- */
  function updateButtonLabel() {
    if (activeAbortCtrl) return;
    const f = getActiveMedia();
    const sig = getFileSignature(f);
    const model = getActiveWhisperModel();
    const tLang = $('translateLang').value;

    if (f && cachedWhisper.fileSig === sig && cachedWhisper.model === model && cachedWhisper.rawSegments.length) {
      if (!tLang) {
        btn.innerText = cachedWhisper.activeLang === '' ? '✨ AI Video (ask about video)' : 'Restore original from cache';
      } else if (cachedWhisper.activeLang === tLang) {
        btn.innerText = '✨ AI Video (ask about video)';
      } else if (cachedWhisper.translations[tLang]) {
        btn.innerText = 'Open cached translation';
      } else {
        btn.innerText = 'Quick translate from cache';
      }
    } else {
      btn.innerText = 'Generate subtitles';
    }
  }

  const genCtrl = pipelineMod.createGenerationController({
    btn,
    statusEl,
    actionRow,
    vttPanel,
    openBtn,
    videoEl,
    translateLangEl: $('translateLang'),
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
    getActiveAbortCtrl: () => activeAbortCtrl,
    setActiveAbortCtrl: (v) => { activeAbortCtrl = v; },
    getCachedWhisper: () => cachedWhisper,
    setCachedWhisper: (v) => { cachedWhisper = v; },
    getCachedPartialChunks: () => cachedPartialChunks,
    setCachedPartialChunks: (v) => { cachedPartialChunks = v; },
    getCues: () => cues,
    setCues: (v) => { cues = v; },
    setCurrentFileName: (v) => { currentFileName = v; },
    refreshAllExports,
    setupPlayer: playerCtrl.setupPlayer,
    updateSubtitleFrame: playerCtrl.updateSubtitleFrame,
    updateButtonLabel,
    generateVideoAiInsights: geminiCtrl.generateVideoAiInsights,
    requestWakeLock,
    releaseWakeLock,
    formatTime: playerCtrl.formatTime
  });

  btn.addEventListener('click', genCtrl.generateSubtitles);

  window.__cancelFromNotification = () => {
    if (activeAbortCtrl) {
      genCtrl.cancelActiveGeneration();
      setProgress(null);
      if (statusEl) statusEl.innerText = '⏹ Cancelled by user.';
    }
  };

  function resetGenerationResults() {
    cues = [];
    playerCtrl.resetOverlayState();
    geminiCtrl.cancelActiveRequest();
    exportData = { vtt: '', srt: '', ass: '', txt: '' };
    cachedWhisper = {
      fileSig: '',
      model: '',
      rawSegments: [],
      rawWords: [],
      translations: {},
      activeLang: null,
      aiSummary: null
    };
    cachedPartialChunks = { fileSig: '', model: '', chunks: {} };
    actionRow.style.display = 'none';
    vttPanel.classList.add('hidden');
    aiSummaryBox.classList.remove('show');
    aiSummaryText.textContent = '';
    aiChaptersList.innerHTML = '';
    setProgress(null);
    refreshAllExports();
  }

  async function restoreHistoryForMedia(f) {
    if (!f) return;
    const sig = getFileSignature(f);
    try {
      const saved = await storageMod.getProjectFromHistory(sig);
      if (getFileSignature(getActiveMedia()) !== sig || activeAbortCtrl) return;
      const smartSplit = Boolean(smartSplitToggleEl && smartSplitToggleEl.checked);
      if (saved && saved.isComplete !== false && saved.rawSegments && saved.rawSegments.length) {
        cachedWhisper = {
          fileSig: saved.fileSig,
          model: saved.model || getActiveWhisperModel(),
          rawSegments: saved.rawSegments,
          rawWords: saved.rawWords || [],
          translations: saved.translations || {},
          activeLang: saved.activeLang || null,
          aiSummary: saved.aiSummary || null
        };
        const tLang = $('translateLang').value;
        if (!tLang) {
          cues = subtitlesMod.buildCues(cachedWhisper.rawSegments, cachedWhisper.rawWords, smartSplit);
        } else if (cachedWhisper.translations[tLang]) {
          cues = subtitlesMod.cloneCues(cachedWhisper.translations[tLang]);
        } else {
          cues = subtitlesMod.buildCues(cachedWhisper.rawSegments, cachedWhisper.rawWords, smartSplit);
        }
        refreshAllExports();
        actionRow.style.display = 'flex';
        statusEl.innerText = '⚡ Subtitles instantly restored from local history!';
        updateButtonLabel();
      } else if (saved && saved.isComplete === false && saved.partialChunks && Object.keys(saved.partialChunks).length) {
        cachedPartialChunks = {
          fileSig: saved.fileSig,
          model: saved.model || getActiveWhisperModel(),
          chunks: saved.partialChunks
        };
        statusEl.innerText = '⏸ Unfinished progress found — tap "Generate subtitles" to resume.';
      }
    } catch (e) {
      diag.logWarn('history.restore', e);
    }
  }

  window.__onExternalMedia = async function (json) {
    try {
      const info = typeof json === 'string' ? JSON.parse(json) : json;
      if (!info || !info.name) return;
      externalMedia = {
        name: info.name,
        size: Number(info.size) || 0,
        mime: info.mime || 'video/*',
        lastModified: Number(info.lastModified) || 0,
        uri: info.uri || '',
        isStream: Boolean(info.isStream)
      };
      externalMedia.file = __virtualExternalFile(externalMedia);
      const pickedInput = $('mediaFile');
      if (pickedInput) pickedInput.value = '';
      window.externalMediaUri = info.uri || '';
    } catch (e) {
      diag.logWarn('externalMedia.parse', e);
      return;
    }

    genCtrl.cancelActiveGeneration();
    resetGenerationResults();
    currentFileName = externalMedia.name.replace(/\.[^/.]+$/, '');
    $('fileName').innerText = externalMedia.name;
    applySettings();

    const media = getActiveMedia();
    if (media) playerCtrl.setupPlayer(media);
    if (statusEl) {
      statusEl.innerText = externalMedia.isStream
        ? '📡 Stream opened. Tap "Generate subtitles" — audio will be processed via player.'
        : '📂 File opened from another app. Tap "Generate subtitles".';
    }
    updateButtonLabel();
    if (media) await restoreHistoryForMedia(media);
  };

  async function updateFileName() {
    const wasGenerating = genCtrl.cancelActiveGeneration();
    if (wasGenerating) {
      statusEl.innerText = '⏹ Generation stopped: another file selected.';
    }
    resetGenerationResults();

    const f = getActiveMedia();
    $('fileName').innerText = f ? f.name : 'Not selected';
    if (f) {
      currentFileName = f.name.replace(/\.[^/.]+$/, '');
      applySettings();
      playerCtrl.setupPlayer(f);
      await restoreHistoryForMedia(f);
    }
    aiSummaryBox.classList.remove('show');
    updateButtonLabel();
  }

  $('mediaFile').addEventListener('change', () => {
    if ($('mediaFile').files[0]) externalMedia = null;
    updateFileName();
  });
  $('translateLang').addEventListener('change', updateButtonLabel);
  $('model').addEventListener('change', updateButtonLabel);

  /* ---- Загрузка готового .vtt / .srt без API ---- */
  $('subFile').addEventListener('change', async (e) => {
    const subF = e.target.files[0];
    if (!subF) return;
    const text = await subF.text();
    const parsedCues = subtitlesMod.parseSubtitleText(text);
    if (!parsedCues.length) {
      statusEl.innerText = 'Could not read subtitles from file.';
      return;
    }
    cues = parsedCues;
    refreshAllExports();
    actionRow.style.display = 'flex';
    closeSettings();

    const mediaF = getActiveMedia();
    if (mediaF) {
      playerCtrl.setupPlayer(mediaF);
      statusEl.innerText = `Loaded subtitles (${cues.length} lines) into player!`;
    } else {
      statusEl.innerText = `Subtitles loaded (${cues.length} lines). Select a video file to watch.`;
    }
  });

  /* ---- Меню Скачать / Поделиться / Повтор / Текст ---- */
  dlBtn.addEventListener('click', (e) => {
    e.stopPropagation();
    const openNow = dlFormatMenu.classList.toggle('show');
    if (dlWrap) dlWrap.classList.toggle('open', openNow);
  });

  dlFormatMenu.querySelectorAll('.dl-fmt-item[data-fmt]').forEach((item) => {
    item.addEventListener('click', (e) => {
      e.stopPropagation();
      const fmt = item.dataset.fmt || 'vtt';
      dlFormatMenu.classList.remove('show');
      if (dlWrap) dlWrap.classList.remove('open');
      downloadSubtitle(fmt);
    });
  });

  if (shareFmtItem) {
    shareFmtItem.addEventListener('click', (e) => {
      e.stopPropagation();
      dlFormatMenu.classList.remove('show');
      if (dlWrap) dlWrap.classList.remove('open');
      shareSubtitles(exportFormat.value || 'vtt');
    });
  }

  document.addEventListener('click', (e) => {
    if (dlFormatMenu.classList.contains('show') && !dlFormatMenu.contains(e.target) && !dlBtn.contains(e.target)) {
      dlFormatMenu.classList.remove('show');
      if (dlWrap) dlWrap.classList.remove('open');
    }
  });

  retryBtn.addEventListener('click', () => {
    cachedWhisper = {
      fileSig: '',
      model: '',
      rawSegments: [],
      rawWords: [],
      translations: {},
      activeLang: null,
      aiSummary: null
    };
    genCtrl.generateSubtitles();
  });

  openBtn.addEventListener('click', () => {
    const isHidden = vttPanel.classList.toggle('hidden');
    openBtn.innerText = isHidden ? 'Text' : 'Hide';
  });

  vttPanel.addEventListener('click', () => {
    if (!vttPanel.innerText) return;
    navigator.clipboard?.writeText(vttPanel.innerText).then(() => {
      const prev = statusEl.innerText;
      statusEl.innerText = 'Subtitle text copied to clipboard!';
      setTimeout(() => { statusEl.innerText = prev; }, 2000);
    }).catch(() => {});
  });

  const copyDiagLogBtn = $('copyDiagLogBtn');
  if (copyDiagLogBtn) {
    let copyResetTimer = null;
    copyDiagLogBtn.addEventListener('click', async () => {
      const text = (typeof diag.formatLogsText === 'function' ? diag.formatLogsText() : '') || 'No warnings or errors recorded.';
      let copied = false;
      try {
        if (navigator.clipboard && typeof navigator.clipboard.writeText === 'function') {
          await navigator.clipboard.writeText(text);
          copied = true;
        }
      } catch (e) {
        diag.logWarn('diag.copyClipboard', e);
      }
      if (!copied) {
        try {
          const ta = document.createElement('textarea');
          ta.value = text;
          ta.style.position = 'fixed';
          ta.style.opacity = '0';
          document.body.appendChild(ta);
          ta.select();
          copied = document.execCommand('copy');
          ta.remove();
        } catch (e) {
          diag.logWarn('diag.copyFallback', e);
        }
      }
      if (copyResetTimer) clearTimeout(copyResetTimer);
      copyDiagLogBtn.textContent = copied ? 'Copied!' : 'Copy failed';
      copyResetTimer = setTimeout(() => {
        copyDiagLogBtn.textContent = 'Copy log';
      }, 1800);
    });
  }

  /* ---- Приветствие Material You ---- */
  const welcomeModalEl = $('m3WelcomeModal');
  const welcomeBackdropEl = $('welcomeBackdrop');
  const welcomeOpenSettingsBtn = $('welcomeOpenSettingsBtn');
  const welcomeStartBtn = $('welcomeStartBtn');

  function openWelcomeModal() {
    if (!welcomeModalEl || !welcomeBackdropEl) return;
    document.body.classList.add('welcome-modal-open');
    if (window.__syncNativePlayerBounds) window.__syncNativePlayerBounds();
    if (welcomeOpenSettingsBtn) {
      welcomeOpenSettingsBtn.textContent = getApiKeys().length > 0 ? 'Open Settings' : 'Add API Key in Settings';
    }
    welcomeBackdropEl.classList.add('show');
    welcomeModalEl.classList.add('show');
  }

  function closeWelcomeModal(markSeen = true) {
    if (markSeen) storageMod.safeSetStorage('sublater_welcome_seen', '1');
    document.body.classList.remove('welcome-modal-open');
    if (welcomeBackdropEl) welcomeBackdropEl.classList.remove('show');
    if (welcomeModalEl) welcomeModalEl.classList.remove('show');
    if (window.__syncNativePlayerBounds) window.__syncNativePlayerBounds();
  }

  if (welcomeStartBtn) welcomeStartBtn.addEventListener('click', () => closeWelcomeModal(true));
  if (welcomeBackdropEl) welcomeBackdropEl.addEventListener('click', () => closeWelcomeModal(true));
  if (welcomeOpenSettingsBtn) {
    welcomeOpenSettingsBtn.addEventListener('click', () => {
      closeWelcomeModal(true);
      openApiSettingsFocus();
    });
  }

  if (storageMod.safeGetStorage('sublater_welcome_seen', '') !== '1') {
    setTimeout(openWelcomeModal, 120);
  }
})();
