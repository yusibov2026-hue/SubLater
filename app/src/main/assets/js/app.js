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
  const i18nMod = root.i18n || {
    getLang: () => 'en',
    setLang: () => 'en',
    t: (k) => k,
    getAiOutputLanguageName: (l) => l || 'English',
    applyTranslations: () => {},
    onChange: () => () => {}
  };
  const t = i18nMod.t;

  const studioMod = root.studio;

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
    keysCountBadge.textContent = count === 0 ? t('keys.zero') : (count === 1 ? t('keys.one') : t('keys.many', { n: count }));
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
      ? t('status.missingApiGen')
      : t('status.missingApiIdle');
    statusEl.innerHTML = '';
    const wrap = document.createElement('span');
    wrap.className = 'status-api-hint';
    const txt = document.createElement('span');
    txt.textContent = msg;
    const goBtn = document.createElement('button');
    goBtn.type = 'button';
    goBtn.className = 'status-settings-btn';
    goBtn.textContent = t('status.openSettings');
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

  function syncForegroundStatus(text) {
    if (!activeAbortCtrl) return;
    const b = bridgeMod.getBridge();
    if (b && typeof b.updateForegroundProgress === 'function') {
      try {
        const msg = text || (statusEl && statusEl.innerText) || t('status.generating');
        b.updateForegroundProgress(msg, Math.max(0, Math.round(progressMaxReached)));
      } catch (_) {}
    }
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
          const msg = (statusEl && statusEl.innerText) ? statusEl.innerText : t('status.generating');
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
        statusEl.innerText = t('status.savedDownloads', { name: `${currentFileName}.${fmt}` });
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
      statusEl.innerText = t('status.genFirst');
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

  /* ---- Контроллер плеера и Studio (1, 2, 3) ---- */
  let playerCtrl = null;
  let studioCtrl = null;
  let origCuesCache = { ref: null, len: -1, smart: null, list: [] };
  function getOriginalCues() {
    const raw = cachedWhisper.rawSegments || [];
    const smart = Boolean(smartSplitToggleEl && smartSplitToggleEl.checked);
    if (!raw.length) {
      origCuesCache = { ref: raw, len: 0, smart, list: [] };
      return origCuesCache.list;
    }
    if (origCuesCache.ref === raw && origCuesCache.len === raw.length && origCuesCache.smart === smart) {
      return origCuesCache.list;
    }
    const built = subtitlesMod.buildCues(raw, cachedWhisper.rawWords || [], smart);
    origCuesCache = { ref: raw, len: raw.length, smart, list: built };
    return built;
  }

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
    getOriginalCues: () => {
      const dualLang = (studioCtrl && typeof studioCtrl.getDualSubLang === 'function')
        ? studioCtrl.getDualSubLang()
        : 'original';
      if (!dualLang || dualLang === 'original') return getOriginalCues();
      return (cachedWhisper.translations && cachedWhisper.translations[dualLang]) || [];
    },
    getDualSubEnabled: () => (studioCtrl ? studioCtrl.isDualSubEnabled() : false),
    onWordTap: (info) => {
      if (studioCtrl) studioCtrl.openWordLookup(info);
    },
    subMenuBtn: $('subMenuBtn'),
    subMenu: $('subMenu'),
    getAudioBoost: () => audioBoost,
    setAudioBoost: (v) => { audioBoost = v; },
    applySettings,
    syncM3RangeSlider: themeCtrl.syncM3RangeSlider,
    getFileSignature,
    requestWakeLock,
    releaseWakeLock
  });

  let dualAbortCtrl = null;
  async function ensureDualSubTrackReady(dualLang, statusElRef) {
    const sourceCues = getOriginalCues().length ? getOriginalCues() : cues;
    if (!dualLang || dualLang === 'original' || !sourceCues.length) {
      if (statusElRef) statusElRef.textContent = '';
      if (playerCtrl && playerCtrl.isPlayerActive()) playerCtrl.updateSubtitleFrame();
      return;
    }
    if (cachedWhisper.translations && cachedWhisper.translations[dualLang]) {
      if (statusElRef) statusElRef.textContent = '';
      if (playerCtrl && playerCtrl.isPlayerActive()) playerCtrl.updateSubtitleFrame();
      return;
    }
    if (!getApiKeys().length) {
      if (statusElRef) statusElRef.textContent = t('status.missingApiGen');
      return;
    }
    if (dualAbortCtrl) {
      try { dualAbortCtrl.abort(); } catch (_) {}
    }
    dualAbortCtrl = new AbortController();
    if (statusElRef) statusElRef.textContent = t('subMenu.translating');
    try {
      const cloned = subtitlesMod.cloneCues(sourceCues);
      const failed = await groqMod.translateSegments(
        cloned,
        dualLang,
        null,
        dualAbortCtrl.signal,
        10,
        { keyPool, primaryModel: (llmModelEl && llmModelEl.value) || 'openai/gpt-oss-120b' }
      );
      cachedWhisper.translations[dualLang] = cloned;
      if (statusElRef) statusElRef.textContent = failed > 0 ? `Partial (${failed})` : '';
      if (playerCtrl && playerCtrl.isPlayerActive()) playerCtrl.updateSubtitleFrame();
    } catch (e) {
      if (e && e.message === 'Cancelled by user.') return;
      diag.logWarn('dualSub.translate', e);
      if (statusElRef) statusElRef.textContent = e && e.message ? e.message : String(e);
    }
  }

  function getProjectExportCues(project) {
    if (!project || typeof project !== 'object') return [];
    const smartSplit = Boolean(smartSplitToggleEl && smartSplitToggleEl.checked);
    if (Array.isArray(project.cues) && project.cues.length) return project.cues;
    if (project.activeLang && project.translations && Array.isArray(project.translations[project.activeLang]) && project.translations[project.activeLang].length) {
      return project.translations[project.activeLang];
    }
    if (Array.isArray(project.rawSegments) && project.rawSegments.length) {
      return subtitlesMod.buildCues(project.rawSegments, project.rawWords || [], smartSplit);
    }
    return [];
  }

  async function openHistoryProject(project) {
    if (!project || typeof project !== 'object') return;
    genCtrl.cancelActiveGeneration();
    resetGenerationResults();

    const displayName = project.fileName || currentFileName || 'subtitles';
    currentFileName = String(displayName).replace(/\.[^/.]+$/, '') || 'subtitles';
    if ($('fileName')) $('fileName').innerText = displayName;

    const smartSplit = Boolean(smartSplitToggleEl && smartSplitToggleEl.checked);
    const activeMedia = getActiveMedia();
    const mediaMatches = Boolean(activeMedia && project.fileSig && getFileSignature(activeMedia) === project.fileSig);

    if (!mediaMatches && project.fileUri) {
      externalMedia = {
        name: displayName,
        size: Number(project.fileSize) || 0,
        mime: project.fileMime || 'video/*',
        lastModified: 0,
        uri: project.fileUri,
        isStream: Boolean(project.isStream)
      };
      externalMedia.file = __virtualExternalFile(externalMedia);
      const pickedInput = $('mediaFile');
      if (pickedInput) pickedInput.value = '';
      window.externalMediaUri = project.fileUri;
    }

    if (project.isComplete !== false && ((Array.isArray(project.rawSegments) && project.rawSegments.length) || (Array.isArray(project.cues) && project.cues.length))) {
      cachedWhisper = {
        fileSig: project.fileSig || '',
        model: project.model || getActiveWhisperModel(),
        rawSegments: project.rawSegments || [],
        rawWords: project.rawWords || [],
        translations: project.translations || {},
        activeLang: project.activeLang || null,
        aiSummary: project.aiSummary || null
      };
      const translateEl = $('translateLang');
      if (translateEl && project.activeLang && Array.from(translateEl.options).some((o) => o.value === project.activeLang)) {
        translateEl.value = project.activeLang;
      }
      const selectedLang = translateEl ? translateEl.value : '';
      if (selectedLang && cachedWhisper.translations[selectedLang]) {
        cues = subtitlesMod.cloneCues(cachedWhisper.translations[selectedLang]);
      } else if (project.activeLang && cachedWhisper.translations[project.activeLang]) {
        cues = subtitlesMod.cloneCues(cachedWhisper.translations[project.activeLang]);
      } else if (cachedWhisper.rawSegments.length) {
        cues = subtitlesMod.buildCues(cachedWhisper.rawSegments, cachedWhisper.rawWords, smartSplit);
      } else {
        cues = subtitlesMod.cloneCues(project.cues || []);
      }

      refreshAllExports();
      actionRow.style.display = 'flex';
      const targetMedia = mediaMatches ? activeMedia : (project.fileUri ? getActiveMedia() : null);
      if (targetMedia) {
        playerCtrl.setupPlayer(targetMedia);
      } else if (vttPanel) {
        vttPanel.classList.remove('hidden');
        if (openBtn) openBtn.innerText = t('btn.hide');
      }
      if (statusEl) statusEl.innerText = t('history.opened', { name: currentFileName });
    } else if (project.isComplete === false && project.partialChunks && Object.keys(project.partialChunks).length) {
      cachedPartialChunks = {
        fileSig: project.fileSig || '',
        model: project.model || getActiveWhisperModel(),
        chunks: project.partialChunks
      };
      const targetMedia = mediaMatches ? activeMedia : (project.fileUri ? getActiveMedia() : null);
      if (targetMedia) playerCtrl.setupPlayer(targetMedia);
      if (statusEl) statusEl.innerText = t('status.unfinishedHistory');
    }
    updateButtonLabel();
  }

  function exportHistoryProject(project, fmt = 'srt') {
    const projectCues = getProjectExportCues(project);
    if (!projectCues.length) return;
    const ext = String(fmt || 'srt').toLowerCase();
    let content = '';
    if (ext === 'vtt') content = subtitlesMod.generateVTT(projectCues);
    else if (ext === 'ass') content = subtitlesMod.generateASS(projectCues, buildAssStyleOpts());
    else if (ext === 'txt') content = subtitlesMod.generateTXT(projectCues);
    else content = subtitlesMod.generateSRT(projectCues);
    if (!content) return;

    const baseName = String((project && (project.fileName || project.fileSig)) || 'subtitles').replace(/\.[^/.]+$/, '') || 'subtitles';
    const fileName = `${baseName}.${ext}`;
    const b = bridgeMod.getBridge();
    const hStatus = $('historyStatus') || statusEl;
    if (b && typeof b.saveSubtitleFile === 'function') {
      const ok = b.saveSubtitleFile(fileName, content, ext);
      if (ok) {
        if (hStatus) hStatus.textContent = t('status.savedDownloads', { name: fileName });
        return;
      }
    }
    const blob = new Blob([content], { type: 'text/plain;charset=utf-8' });
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = fileName;
    document.body.appendChild(a);
    a.click();
    setTimeout(() => { URL.revokeObjectURL(a.href); a.remove(); }, 1000);
    if (hStatus) hStatus.textContent = t('status.savedDownloads', { name: fileName });
  }

  studioCtrl = studioMod ? studioMod.createStudioController({
    keyPool,
    getApiKeys,
    getGeminiKeys: () => {
      const k = (geminiKeyInput && geminiKeyInput.value.trim()) || storageMod.getGeminiKeyFromKeystore();
      return k ? [k] : [];
    },
    getActiveWhisperModel,
    getActiveLlmModel: () => (llmModelEl ? llmModelEl.value : 'openai/gpt-oss-120b'),
    getActiveGeminiModel: () => (geminiModelSelect ? geminiModelSelect.value : 'gemini-2.5-flash'),
    getTranslateLang: () => ($('translateLang') && $('translateLang').value) || '',
    getAiOutputLang: () => i18nMod.getAiOutputLanguageName($('translateLang') && $('translateLang').value),
    getCues: () => cues,
    getCurrentFileName: () => currentFileName,
    getPlayerCtrl: () => playerCtrl,
    onDualSubLangChanged: ensureDualSubTrackReady,
    onOpenHistoryProject: openHistoryProject,
    onExportHistoryProject: exportHistoryProject,
    showMissingApiStatus,
    openApiSettingsFocus,
    formatTime: playerCtrl.formatTime
  }) : null;

  /* ---- Контроллер AI видео ---- */
  const geminiCtrl = geminiMod.createController({
    aiChatList: $('aiChatList'),
    aiQuestionInput: $('aiQuestionInput'),
    aiSendBtn: $('aiSendBtn'),
    clearAiChatBtn: $('clearAiChatBtn'),
    aiQuickPrompts: $('aiQuickPrompts'),
    aiSummaryBox,
    aiActiveModelBadge,
    geminiKeyInput,
    geminiModelSelect,
    videoEl,
    playerBox,
    getCues: () => cues,
    getAiOutputLang: () => i18nMod.getAiOutputLanguageName($('translateLang') && $('translateLang').value),
    t,
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
        btn.innerText = cachedWhisper.activeLang === '' ? t('btn.aiVideo') : t('btn.restoreOrig');
      } else if (cachedWhisper.activeLang === tLang) {
        btn.innerText = t('btn.aiVideo');
      } else if (cachedWhisper.translations[tLang]) {
        btn.innerText = t('btn.openCachedTrans');
      } else {
        btn.innerText = t('btn.quickTranslate');
      }
    } else {
      btn.innerText = t('btn.generate');
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
    formatTime: playerCtrl.formatTime,
    t,
    syncForegroundStatus
  });

  btn.addEventListener('click', genCtrl.generateSubtitles);

  window.__cancelFromNotification = () => {
    if (activeAbortCtrl) {
      genCtrl.cancelActiveGeneration();
      setProgress(null);
      if (statusEl) statusEl.innerText = t('status.cancelled');
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
    if (geminiCtrl && typeof geminiCtrl.resetChat === 'function') {
      geminiCtrl.resetChat(true);
    }
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
        statusEl.innerText = t('status.restoredHistory');
        updateButtonLabel();
      } else if (saved && saved.isComplete === false && saved.partialChunks && Object.keys(saved.partialChunks).length) {
        cachedPartialChunks = {
          fileSig: saved.fileSig,
          model: saved.model || getActiveWhisperModel(),
          chunks: saved.partialChunks
        };
        statusEl.innerText = t('status.unfinishedHistory');
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
        ? t('status.streamOpened')
        : t('status.externalOpened');
    }
    updateButtonLabel();
    if (media) await restoreHistoryForMedia(media);
  };

  async function updateFileName() {
    const wasGenerating = genCtrl.cancelActiveGeneration();
    if (wasGenerating) {
      statusEl.innerText = t('status.fileStopped');
    }
    resetGenerationResults();

    const f = getActiveMedia();
    $('fileName').innerText = f ? f.name : t('file.notSelected');
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
      statusEl.innerText = t('status.subLoadErr');
      return;
    }
    cues = parsedCues;
    refreshAllExports();
    actionRow.style.display = 'flex';
    closeSettings();

    const mediaF = getActiveMedia();
    const displayName = (mediaF && mediaF.name) || subF.name || currentFileName || 'subtitles';
    if (!mediaF) {
      currentFileName = String(displayName).replace(/\.[^/.]+$/, '') || 'subtitles';
      if ($('fileName')) $('fileName').innerText = displayName;
    }

    storageMod.saveProjectToHistory({
      fileSig: mediaF ? getFileSignature(mediaF) : `sub_${subF.name}_${subF.size || 0}_${subF.lastModified || 0}`,
      fileName: displayName,
      fileSize: (mediaF && mediaF.size) || subF.size || 0,
      fileUri: (mediaF && mediaF.uri) || '',
      fileMime: (mediaF && mediaF.type) || '',
      isStream: Boolean(mediaF && mediaF.isStream),
      updatedAt: Date.now(),
      model: 'subtitles',
      isComplete: true,
      partialChunks: null,
      rawSegments: parsedCues.map((c) => ({ start: c.start, end: c.end, text: c.text })),
      rawWords: [],
      translations: {},
      activeLang: '',
      cues: parsedCues
    });

    if (mediaF) {
      playerCtrl.setupPlayer(mediaF);
      statusEl.innerText = t('status.subLoadedPlay', { n: cues.length });
    } else {
      statusEl.innerText = t('status.subLoadedNoVid', { n: cues.length });
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
    openBtn.innerText = isHidden ? t('btn.text') : t('btn.hide');
  });

  /* ---- Кастомный Material You выбор Speech-модели ---- */
  const modelSelectEl = $('model');
  const modelPickerBtn = $('modelPickerBtn');
  const modelPickerBackdrop = $('modelPickerBackdrop');
  const modelPickerSheet = $('modelPickerSheet');
  const closeModelPickerBtn = $('closeModelPickerBtn');
  const modelPickerLabel = $('modelPickerLabel');
  const modelPickerBadge = $('modelPickerBadge');

  const MODEL_META = {
    'auto': { titleKey: 'model.auto.title', badgeKey: 'model.auto.badge' },
    'whisper-large-v3-turbo': { titleKey: 'model.turbo.title', badgeKey: 'model.turbo.badge' },
    'whisper-large-v3': { titleKey: 'model.v3.title', badgeKey: 'model.v3.badge' },
    'local-whisper-base': { titleKey: 'model.base.title', badgeKey: 'model.base.badge' },
    'local-whisper-small': { titleKey: 'model.small.title', badgeKey: 'model.small.badge' }
  };

  function syncLocalModelCardsUI() {
    const lw = root.localWhisper;
    document.querySelectorAll('#modelPickerList .m3-model-local').forEach((card) => {
      const mId = card.dataset.model;
      const meta = MODEL_META[mId];
      const downloaded = Boolean(lw && lw.isModelDownloaded(mId));
      const badgeEl = card.querySelector('.m3-model-opt-badge');
      const dlBtn = card.querySelector('.m3-model-dl-btn');
      const delBtn = card.querySelector('.m3-model-del-btn');
      if (badgeEl && meta) {
        badgeEl.textContent = downloaded ? t('model.downloaded') : t(meta.badgeKey);
      }
      if (dlBtn) {
        if (!dlBtn.dataset.downloading) {
          dlBtn.textContent = downloaded ? t('model.downloaded') : t('model.download');
          dlBtn.disabled = downloaded;
        }
      }
      if (delBtn) {
        delBtn.style.display = downloaded ? 'inline-flex' : 'none';
      }
    });
  }

  function syncModelPickerUI() {
    if (!modelSelectEl) return;
    const val = MODEL_META[modelSelectEl.value] ? modelSelectEl.value : 'auto';
    const meta = MODEL_META[val];
    const lw = root.localWhisper;
    const titleTxt = t(meta.titleKey);
    const isLocalDone = Boolean(lw && lw.isLocalWhisperModel(val) && lw.isModelDownloaded(val));
    const badgeTxt = isLocalDone ? t('model.downloaded') : t(meta.badgeKey);
    if (modelPickerLabel) modelPickerLabel.textContent = titleTxt;
    if (modelPickerBadge) modelPickerBadge.textContent = badgeTxt;
    document.querySelectorAll('#modelPickerList .m3-model-option').forEach((opt) => {
      opt.classList.toggle('active', opt.dataset.model === val);
    });
    syncLocalModelCardsUI();
  }

  function openModelPicker() {
    syncModelPickerUI();
    if (modelPickerBackdrop) modelPickerBackdrop.classList.add('show');
    if (modelPickerSheet) modelPickerSheet.classList.add('show');
    if (modelPickerBtn) modelPickerBtn.setAttribute('aria-expanded', 'true');
  }

  function closeModelPicker() {
    if (modelPickerBackdrop) modelPickerBackdrop.classList.remove('show');
    if (modelPickerSheet) modelPickerSheet.classList.remove('show');
    if (modelPickerBtn) modelPickerBtn.setAttribute('aria-expanded', 'false');
  }

  if (modelSelectEl) {
    const savedSpeechModel = storageMod.safeGetStorage('sublater_whisper_model', 'auto');
    if (MODEL_META[savedSpeechModel]) modelSelectEl.value = savedSpeechModel;
  }
  if (modelPickerBtn) modelPickerBtn.addEventListener('click', openModelPicker);
  if (modelPickerBackdrop) modelPickerBackdrop.addEventListener('click', closeModelPicker);
  if (closeModelPickerBtn) closeModelPickerBtn.addEventListener('click', closeModelPicker);

  document.querySelectorAll('#modelPickerList [data-local-dl]').forEach((btnEl) => {
    btnEl.addEventListener('click', async (e) => {
      e.stopPropagation();
      const mId = btnEl.dataset.localDl;
      const lw = root.localWhisper;
      if (!lw || btnEl.dataset.downloading) return;
      const card = btnEl.closest('.m3-model-option');
      const bar = card ? card.querySelector('.m3-model-dl-bar') : null;
      const fill = card ? card.querySelector('.m3-model-dl-fill') : null;
      btnEl.dataset.downloading = '1';
      btnEl.disabled = true;
      btnEl.textContent = t('model.downloading', { pct: 0 });
      if (bar) bar.style.display = 'block';
      if (fill) fill.style.width = '4%';
      try {
        await lw.downloadModel(mId, ({ pct }) => {
          const p = Math.max(0, Math.min(100, Number(pct) || 0));
          btnEl.textContent = t('model.downloading', { pct: p });
          if (fill) fill.style.width = `${Math.max(4, p)}%`;
        });
        if (modelSelectEl) {
          modelSelectEl.value = mId;
          storageMod.safeSetStorage('sublater_whisper_model', mId);
          modelSelectEl.dispatchEvent(new Event('change'));
        }
      } catch (err) {
        diag.logWarn('localWhisper.download', err);
        if (statusEl) statusEl.textContent = err && err.message ? err.message : String(err);
      } finally {
        delete btnEl.dataset.downloading;
        if (bar) bar.style.display = 'none';
        if (fill) fill.style.width = '0%';
        syncModelPickerUI();
      }
    });
  });

  document.querySelectorAll('#modelPickerList [data-local-del]').forEach((delEl) => {
    delEl.addEventListener('click', async (e) => {
      e.stopPropagation();
      const mId = delEl.dataset.localDel;
      const lw = root.localWhisper;
      if (!lw) return;
      await lw.deleteModel(mId);
      syncModelPickerUI();
    });
  });

  document.querySelectorAll('#modelPickerList .m3-model-option').forEach((opt) => {
    const selectOption = (e) => {
      if (e.target && e.target.closest && e.target.closest('.m3-model-dl-btn, .m3-model-del-btn')) return;
      const m = opt.dataset.model || 'auto';
      if (modelSelectEl) {
        modelSelectEl.value = m;
        storageMod.safeSetStorage('sublater_whisper_model', m);
        modelSelectEl.dispatchEvent(new Event('change'));
      }
      syncModelPickerUI();
      closeModelPicker();
    };
    opt.addEventListener('click', selectOption);
    opt.addEventListener('keydown', (e) => {
      if (e.key === 'Enter' || e.key === ' ') {
        e.preventDefault();
        selectOption(e);
      }
    });
  });

  /* ---- Выбор языка интерфейса (Auto по системе + 54 языка) ---- */
  const appLangSelect = $('appLangSelect');

  function populateAppLanguageSelect() {
    if (!appLangSelect) return;
    const langs = Array.isArray(i18nMod.LANGUAGES) ? i18nMod.LANGUAGES : [];
    const sysCode = typeof i18nMod.detectSystemLang === 'function' ? i18nMod.detectSystemLang() : 'en';
    const sysMeta = typeof i18nMod.getLangMeta === 'function' ? i18nMod.getLangMeta(sysCode) : { nativeName: 'English' };
    const currentPref = typeof i18nMod.getPref === 'function' ? i18nMod.getPref() : 'auto';

    appLangSelect.innerHTML = '';
    const autoOpt = document.createElement('option');
    autoOpt.value = 'auto';
    autoOpt.textContent = t('settings.langAuto', { lang: (sysMeta && sysMeta.nativeName) || 'English' });
    appLangSelect.appendChild(autoOpt);

    langs.forEach((l) => {
      const opt = document.createElement('option');
      opt.value = l.code;
      opt.textContent = l.nativeName === l.name ? l.nativeName : `${l.nativeName} (${l.name})`;
      appLangSelect.appendChild(opt);
    });
    appLangSelect.value = currentPref;
  }

  function syncAppLanguageUI() {
    populateAppLanguageSelect();
    i18nMod.applyTranslations();
    syncModelPickerUI();
    syncKeysBadge(getApiKeys());
    refreshMissingApiStatusHint();
    updateButtonLabel();
    const f = getActiveMedia();
    if ($('fileName') && !f) $('fileName').innerText = t('file.notSelected');
    if (openBtn) openBtn.innerText = vttPanel.classList.contains('hidden') ? t('btn.text') : t('btn.hide');
    applySettings();
    if (studioCtrl && typeof studioCtrl.onLanguageChanged === 'function') studioCtrl.onLanguageChanged();
  }

  if (appLangSelect) {
    appLangSelect.addEventListener('change', async () => {
      await i18nMod.setLang(appLangSelect.value || 'auto');
      syncAppLanguageUI();
    });
  }
  syncAppLanguageUI();
  if (typeof i18nMod.init === 'function') {
    i18nMod.init().then(syncAppLanguageUI).catch(() => {});
  }

  vttPanel.addEventListener('click', () => {
    if (!vttPanel.innerText) return;
    navigator.clipboard?.writeText(vttPanel.innerText).then(() => {
      const prev = statusEl.innerText;
      statusEl.innerText = t('status.subCopied');
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
      copyDiagLogBtn.textContent = copied ? t('settings.copied') : t('settings.copyFailed');
      copyResetTimer = setTimeout(() => {
        copyDiagLogBtn.textContent = t('settings.copyLog');
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
      welcomeOpenSettingsBtn.textContent = getApiKeys().length > 0 ? t('welcome.openSettingsBtn') : t('welcome.addKeyBtn');
    }
    welcomeBackdropEl.classList.add('show');
    welcomeModalEl.classList.add('show');
  }

  function closeWelcomeModal(markSeen = true) {
    if (markSeen) storageMod.safeSetStorage('sublater_welcome_seen_v2', '1');
    document.body.classList.remove('welcome-modal-open');
    if (welcomeBackdropEl) welcomeBackdropEl.classList.remove('show');
    if (welcomeModalEl) welcomeModalEl.classList.remove('show');
    if (window.__syncNativePlayerBounds) window.__syncNativePlayerBounds();
  }

  const openWelcomeGuideBtn = $('openWelcomeGuideBtn');
  if (openWelcomeGuideBtn) {
    openWelcomeGuideBtn.addEventListener('click', () => {
      closeSettings();
      openWelcomeModal();
    });
  }

  if (welcomeStartBtn) welcomeStartBtn.addEventListener('click', () => closeWelcomeModal(true));
  if (welcomeBackdropEl) welcomeBackdropEl.addEventListener('click', () => closeWelcomeModal(true));
  if (welcomeOpenSettingsBtn) {
    welcomeOpenSettingsBtn.addEventListener('click', () => {
      closeWelcomeModal(true);
      openApiSettingsFocus();
    });
  }

  if (storageMod.safeGetStorage('sublater_welcome_seen_v2', '') !== '1') {
    setTimeout(openWelcomeModal, 120);
  }
})();
