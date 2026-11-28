(function (root, factory) {
  const api = factory(
    root.SubLyter && root.SubLyter.storage,
    root.SubLyter && root.SubLyter.subtitles,
    root.SubLyter && root.SubLyter.groq,
    root.SubLyter && root.SubLyter.diagnostics,
    root.SubLyter && root.SubLyter.i18n,
    root.SubLyter && root.SubLyter.bridge
  );
  if (typeof module !== 'undefined' && module.exports) {
    module.exports = api;
  }
  root.SubLyter = root.SubLyter || {};
  root.SubLyter.studio = api;
})(typeof globalThis !== 'undefined' ? globalThis : window, function (storageMod, subtitlesMod, groqMod, diagMod, i18nArg, bridgeArg) {
  'use strict';

  const diag = diagMod || { logWarn() {}, logError() {} };
  const getI18n = () => i18nArg || (typeof globalThis !== 'undefined' && globalThis.SubLyter && globalThis.SubLyter.i18n) || null;
  const t = (key, vars) => {
    const mod = getI18n();
    return mod && typeof mod.t === 'function' ? mod.t(key, vars) : key;
  };
  const getBridgeMod = () => bridgeArg || (typeof globalThis !== 'undefined' && globalThis.SubLyter && globalThis.SubLyter.bridge) || null;
  const DAY_MS = 24 * 60 * 60 * 1000;
  const REVIEW_STEPS_DAYS = [1, 3, 7, 14, 30];

  const KEYS = {
    flashcards: 'sublater_flashcards_v1',
    voice: 'sublater_voice_notes_v1',
    dualSub: 'sublater_dual_sub',
    dualSubLang: 'sublater_dual_sub_lang',
    voiceTranslateLang: 'sublater_voice_translate_lang'
  };

  function cleanTappedWord(raw) {
    return String(raw || '')
      .trim()
      .replace(/^[\p{P}\p{S}]+|[\p{P}\p{S}]+$/gu, '')
      .trim();
  }

  function makeId(prefix = 'id') {
    return `${prefix}_${Date.now().toString(36)}_${Math.random().toString(36).slice(2, 7)}`;
  }

  function normalizeFlashcard(input, now = Date.now()) {
    const item = input || {};
    const word = cleanTappedWord(item.word || item.front || '') || String(item.word || '').trim();
    return {
      id: item.id || makeId('fc'),
      word,
      translation: String(item.translation || '').trim(),
      partOfSpeech: String(item.partOfSpeech || '').trim(),
      meaning: String(item.meaning || '').trim(),
      nuance: String(item.nuance || '').trim(),
      example: String(item.example || '').trim(),
      context: String(item.context || '').trim(),
      sourceTitle: String(item.sourceTitle || '').trim(),
      reps: Math.max(0, Number(item.reps) || 0),
      dueAt: Number(item.dueAt) > 0 ? Number(item.dueAt) : now,
      createdAt: Number(item.createdAt) > 0 ? Number(item.createdAt) : now
    };
  }

  function reviewFlashcard(card, rating, now = Date.now()) {
    const base = normalizeFlashcard(card, now);
    if (rating === 'again') {
      return Object.assign({}, base, {
        reps: 0,
        dueAt: now + 10 * 60 * 1000
      });
    }
    const stepIdx = Math.min(REVIEW_STEPS_DAYS.length - 1, base.reps);
    let intervalDays = REVIEW_STEPS_DAYS[stepIdx];
    let nextReps = base.reps + 1;
    if (rating === 'easy') {
      intervalDays = Math.max(4, Math.round(intervalDays * 1.8));
      nextReps = base.reps + 2;
    }
    return Object.assign({}, base, {
      reps: nextReps,
      dueAt: now + intervalDays * DAY_MS
    });
  }

  function getDueFlashcards(cards, now = Date.now()) {
    return (Array.isArray(cards) ? cards : [])
      .filter((c) => (Number(c.dueAt) || 0) <= now)
      .sort((a, b) => (a.dueAt || 0) - (b.dueAt || 0));
  }

  function getHistoryProjectCues(project) {
    if (!project || typeof project !== 'object') return [];
    if (Array.isArray(project.cues) && project.cues.length) return project.cues;
    if (project.activeLang && project.translations && Array.isArray(project.translations[project.activeLang]) && project.translations[project.activeLang].length) {
      return project.translations[project.activeLang];
    }
    if (Array.isArray(project.rawSegments) && project.rawSegments.length) return project.rawSegments;
    if (project.partialChunks && typeof project.partialChunks === 'object') {
      const merged = [];
      Object.keys(project.partialChunks)
        .sort((a, b) => Number(a) - Number(b))
        .forEach((k) => {
          const ch = project.partialChunks[k];
          if (ch && Array.isArray(ch.segments)) {
            ch.segments.forEach((s) => { if (s) merged.push(s); });
          }
        });
      return merged;
    }
    return [];
  }

  function buildHistoryPreview(project, maxChars = 130) {
    if (project && typeof project.preview === 'string' && project.preview) {
      const limit = Math.max(10, Number(maxChars) || 130);
      if (project.preview.length <= limit) return project.preview;
      return project.preview.slice(0, limit).trimEnd() + '…';
    }
    const items = getHistoryProjectCues(project);
    if (!items.length) return '';
    const joined = items
      .map((c) => String((c && c.text) || '').trim())
      .filter(Boolean)
      .join(' ')
      .replace(/\s+/g, ' ')
      .trim();
    if (!joined) return '';
    const limit = Math.max(10, Number(maxChars) || 130);
    if (joined.length <= limit) return joined;
    return joined.slice(0, limit).trimEnd() + '…';
  }

  function getHistoryDurationSec(project) {
    if (project && typeof project.durationSec === 'number' && Number.isFinite(project.durationSec)) {
      return Math.max(0, Math.round(project.durationSec));
    }
    const cuesList = getHistoryProjectCues(project);
    const rawList = (project && Array.isArray(project.rawSegments)) ? project.rawSegments : [];
    let maxEnd = 0;
    [cuesList, rawList].forEach((arr) => {
      arr.forEach((c) => {
        if (!c) return;
        const val = Number(c.end || c.start || 0);
        if (Number.isFinite(val) && val > maxEnd) maxEnd = val;
      });
    });
    return Math.max(0, Math.round(maxEnd));
  }

  function getHistoryLanguages(project) {
    if (!project || typeof project !== 'object') return [];
    if (Array.isArray(project.languages)) {
      return project.languages.filter((x) => typeof x === 'string' && x.trim());
    }
    const out = [];
    const seen = new Set();
    const pushLang = (val) => {
      const clean = String(val || '').trim();
      if (!clean || clean === 'null' || clean === 'undefined') return;
      const key = clean.toLowerCase();
      if (seen.has(key)) return;
      seen.add(key);
      out.push(clean);
    };
    if (project.translations && typeof project.translations === 'object') {
      Object.keys(project.translations).forEach(pushLang);
    }
    pushLang(project.activeLang);
    return out;
  }

  function filterHistoryProjects(projects, query = '', filterMode = 'all') {
    const list = Array.isArray(projects) ? projects : [];
    const mode = String(filterMode || 'all').toLowerCase();
    const q = String(query || '').trim().toLowerCase();
    return list.filter((p) => {
      if (!p) return false;
      if (mode === 'translated' && getHistoryLanguages(p).length === 0) return false;
      if (mode === 'incomplete' && p.isComplete !== false) return false;
      if (!q) return true;
      const textSnippet = (typeof p.searchSnippet === 'string' && p.searchSnippet)
        ? p.searchSnippet
        : buildHistoryPreview(p, 800);
      const hay = [
        p.fileName,
        p.model,
        getHistoryLanguages(p).join(' '),
        textSnippet
      ].join(' ').toLowerCase();
      return hay.includes(q);
    });
  }

  function createVoiceNote(input, now = Date.now()) {
    const item = input || {};
    return {
      id: item.id || makeId('vo'),
      title: String(item.title || 'Voice Note').trim(),
      durationSec: Math.max(0, Math.round(Number(item.durationSec) || 0)),
      summary: String(item.summary || '').trim(),
      actionItems: Array.isArray(item.actionItems) ? item.actionItems.map((x) => String(x).trim()).filter(Boolean) : [],
      keyDates: Array.isArray(item.keyDates) ? item.keyDates.map((x) => String(x).trim()).filter(Boolean) : [],
      tags: Array.isArray(item.tags) ? Array.from(new Set(item.tags.map((t) => String(t).replace(/^#/, '').trim().toLowerCase()).filter(Boolean))) : [],
      transcript: String(item.transcript || '').trim(),
      createdAt: Number(item.createdAt) > 0 ? Number(item.createdAt) : now
    };
  }

  function filterVoiceNotes(notes, query = '') {
    const q = String(query || '').trim().toLowerCase();
    if (!q) return Array.isArray(notes) ? notes.slice() : [];
    return (Array.isArray(notes) ? notes : []).filter((n) => {
      const hay = [
        n.title,
        n.summary,
        n.transcript,
        (n.actionItems || []).join(' '),
        (n.tags || []).join(' ')
      ].join(' ').toLowerCase();
      return hay.includes(q);
    });
  }

  function findOriginalCueForTime(origCues, timeSec, offsetSec = 0, holdSec = 0) {
    if (!Array.isArray(origCues) || !origCues.length || !subtitlesMod || typeof subtitlesMod.findActiveCueIndex !== 'function') {
      return null;
    }
    const idx = subtitlesMod.findActiveCueIndex(origCues, timeSec, offsetSec, holdSec, -1);
    return idx >= 0 ? origCues[idx] : null;
  }

  function createStudioController(deps) {
    const $ = (id) => document.getElementById(id);
    const readJson = (key, fallback) => {
      try {
        const raw = storageMod.safeGetStorage(key, '');
        return raw ? JSON.parse(raw) : fallback;
      } catch (e) {
        diag.logWarn('studio.readJson', e);
        return fallback;
      }
    };
    const writeJson = (key, val) => {
      try {
        storageMod.safeSetStorage(key, JSON.stringify(val));
      } catch (e) {
        diag.logWarn('studio.writeJson', e);
      }
    };

    let flashcards = (readJson(KEYS.flashcards, []) || []).map((c) => normalizeFlashcard(c));
    let historyProjects = [];
    let voiceNotes = (readJson(KEYS.voice, []) || []).map((v) => createVoiceNote(v));
    let dualSubEnabled = storageMod.safeGetStorage(KEYS.dualSub, '1') !== '0';
    let dualSubLang = storageMod.safeGetStorage(KEYS.dualSubLang, 'original') || 'original';

    let activeTab = 'video';
    let activeHistoryFilter = 'all';
    let activeLookupCard = null;
    let lookupResumeAfterClose = false;
    let lookupAbortCtrl = null;
    const TABS = ['video', 'learn', 'history', 'voice'];
    const dirty = { learn: true, history: true, voice: true };
    let animTimer = null;
    let animRaf = 0;
    let boundsTimer = null;

    let recStream = null;
    let mediaRecorder = null;
    let recChunks = [];
    let recStartAt = 0;
    let recTimerId = null;
    let isRecording = false;
    let isNativeRec = false;
    let awaitingNativeMicPerm = false;

    const getGroqCtx = () => ({
      keyPool: deps.keyPool,
      primaryModel: deps.getActiveLlmModel ? deps.getActiveLlmModel() : 'openai/gpt-oss-120b',
      geminiModel: deps.getActiveGeminiModel ? deps.getActiveGeminiModel() : 'gemini-2.5-flash'
    });

    function ensureGroqKey(statusTargetEl) {
      const keys = deps.getApiKeys ? deps.getApiKeys() : [];
      if (!keys.length) {
        if (statusTargetEl) statusTargetEl.textContent = t('status.missingApiGen');
        if (deps.showMissingApiStatus) deps.showMissingApiStatus(true);
        if (deps.openApiSettingsFocus) deps.openApiSettingsFocus();
        return false;
      }
      return true;
    }

    function updateTabBadges() {
      const dueCount = getDueFlashcards(flashcards).length;
      const learnBadge = $('learnDueCount');
      const historyBadge = $('historyCount');
      const voiceBadge = $('voiceCount');
      if (learnBadge) learnBadge.textContent = dueCount > 0 ? String(dueCount) : '';
      if (historyBadge) historyBadge.textContent = historyProjects.length > 0 ? String(historyProjects.length) : '';
      if (voiceBadge) voiceBadge.textContent = voiceNotes.length > 0 ? String(voiceNotes.length) : '';
    }

    const hubIndicator = $('hubIndicator');
    const hubViewport = $('hubViewport');
    const panelMap = {
      video: $('hubPanelVideo'),
      learn: $('hubPanelLearn'),
      history: $('hubPanelHistory'),
      voice: $('hubPanelVoice')
    };

    function renderTabIfDirty(tabName) {
      if (tabName === 'learn' && dirty.learn) {
        dirty.learn = false;
        renderLearnPanel();
      } else if (tabName === 'history' && dirty.history) {
        dirty.history = false;
        refreshHistoryFromDb(true);
      } else if (tabName === 'voice' && dirty.voice) {
        dirty.voice = false;
        renderVoicePanel();
      }
    }

    function syncBoundsSoon() {
      if (window.__syncNativePlayerBounds) {
        if (boundsTimer) clearTimeout(boundsTimer);
        boundsTimer = setTimeout(() => {
          try { window.__syncNativePlayerBounds(); } catch (_) {}
        }, 40);
      }
    }

    function positionHubIndicator(floatIdx) {
      if (!hubIndicator) return;
      const buttons = Array.from(document.querySelectorAll('#hubTabs .m3-hub-tab'));
      if (!buttons.length) return;
      const maxIdx = buttons.length - 1;
      const clamped = Math.max(0, Math.min(maxIdx, Number(floatIdx) || 0));
      const i0 = Math.floor(clamped);
      const i1 = Math.min(maxIdx, i0 + 1);
      const frac = clamped - i0;
      const b0 = buttons[i0];
      const b1 = buttons[i1];
      const x = b0.offsetLeft + (b1.offsetLeft - b0.offsetLeft) * frac;
      const w = b0.offsetWidth + (b1.offsetWidth - b0.offsetWidth) * frac;
      if (w > 0) {
        hubIndicator.style.width = `${w}px`;
        hubIndicator.style.transform = `translate3d(${x}px, 0, 0)`;
      }
    }

    function switchTab(tabName) {
      const next = TABS.includes(tabName) ? tabName : 'video';
      const prev = activeTab;
      const nextIdx = TABS.indexOf(next);
      const prevIdx = TABS.indexOf(prev);

      if (animTimer) { clearTimeout(animTimer); animTimer = null; }
      if (animRaf) { cancelAnimationFrame(animRaf); animRaf = 0; }

      document.querySelectorAll('#hubTabs .m3-hub-tab').forEach((btn) => {
        const on = btn.dataset.hubTab === next;
        btn.classList.toggle('active', on);
        btn.setAttribute('aria-selected', on ? 'true' : 'false');
      });
      if (hubIndicator) {
        hubIndicator.classList.remove('dragging');
        positionHubIndicator(nextIdx);
      }

      const prevEl = panelMap[prev];
      const nextEl = panelMap[next];

      if (next === 'learn' && activeLearnSubTab === 'style' && typeof deps.onStyleTabOpened === 'function') {
        setTimeout(() => deps.onStyleTabOpened(), 40);
      }

      if (prev === next || !prevEl || !nextEl) {
        activeTab = next;
        TABS.forEach((k) => {
          const el = panelMap[k];
          if (!el) return;
          el.classList.remove('is-leaving', 'dragging');
          el.classList.toggle('active', k === next);
          el.style.transform = '';
          el.style.opacity = '';
        });
        if (hubViewport) hubViewport.style.height = '';
        renderTabIfDirty(next);
        syncBoundsSoon();
        return;
      }

      const fromH = hubViewport ? Math.round(hubViewport.getBoundingClientRect().height) : 0;
      activeTab = next;

      TABS.forEach((k) => {
        const el = panelMap[k];
        if (!el || k === prev || k === next) return;
        el.classList.remove('active', 'is-leaving', 'dragging');
        el.style.transform = '';
        el.style.opacity = '';
      });

      const dirSign = nextIdx > prevIdx ? 1 : -1;
      if (hubViewport && fromH > 0) hubViewport.style.height = `${fromH}px`;

      prevEl.classList.remove('active', 'dragging');
      prevEl.classList.add('is-leaving');
      prevEl.style.transform = 'translate3d(0, 0, 0)';
      prevEl.style.opacity = '1';

      nextEl.classList.remove('is-leaving');
      nextEl.classList.add('active', 'dragging');
      nextEl.style.transform = `translate3d(${dirSign * 28}px, 0, 0)`;
      nextEl.style.opacity = '0';

      renderTabIfDirty(next);

      const toH = Math.round(nextEl.scrollHeight || nextEl.getBoundingClientRect().height || 0);
      if (hubViewport) void hubViewport.offsetHeight;
      nextEl.classList.remove('dragging');

      animRaf = requestAnimationFrame(() => {
        animRaf = 0;
        if (hubViewport && toH > 0) hubViewport.style.height = `${toH}px`;
        nextEl.style.transform = 'translate3d(0, 0, 0)';
        nextEl.style.opacity = '1';
        prevEl.style.transform = `translate3d(${-dirSign * 28}px, 0, 0)`;
        prevEl.style.opacity = '0';
      });

      animTimer = setTimeout(() => {
        animTimer = null;
        prevEl.classList.remove('is-leaving', 'dragging');
        prevEl.style.transform = '';
        prevEl.style.opacity = '';
        nextEl.style.transform = '';
        nextEl.style.opacity = '';
        if (hubViewport) hubViewport.style.height = '';
        syncBoundsSoon();
      }, 260);
    }

    document.querySelectorAll('#hubTabs .m3-hub-tab').forEach((btn) => {
      btn.addEventListener('click', () => switchTab(btn.dataset.hubTab));
    });

    let activeLearnSubTab = 'learn';
    function setLearnSubTab(subTab) {
      activeLearnSubTab = subTab === 'style' ? 'style' : 'learn';
      document.querySelectorAll('#learnSubTabs [data-learn-subtab]').forEach((btn) => {
        const on = btn.dataset.learnSubtab === activeLearnSubTab;
        btn.classList.toggle('active', on);
        btn.setAttribute('aria-selected', on ? 'true' : 'false');
      });
      const studyView = $('learnStudyView');
      const styleView = $('learnStyleView');
      if (studyView) studyView.classList.toggle('active', activeLearnSubTab === 'learn');
      if (styleView) styleView.classList.toggle('active', activeLearnSubTab === 'style');
      if (hubViewport) hubViewport.style.height = '';
      syncBoundsSoon();
      if (activeLearnSubTab === 'style' && typeof deps.onStyleTabOpened === 'function') {
        requestAnimationFrame(() => deps.onStyleTabOpened());
      }
    }
    document.querySelectorAll('#learnSubTabs [data-learn-subtab]').forEach((btn) => {
      btn.addEventListener('click', () => setLearnSubTab(btn.dataset.learnSubtab));
    });

    /* ---- Плавный свайп вкладок по всему экрану ---- */
    (function setupFullScreenSwipe() {
      let startX = 0;
      let startY = 0;
      let startT = 0;
      let tracking = false;
      let horizLock = false;
      let viewportW = 360;

      function isSwipeBlocked(target) {
        const cl = document.body.classList;
        if (cl.contains('global-settings-open') || cl.contains('welcome-modal-open') || cl.contains('player-fullscreen-open')) return true;
        const wls = $('wordLookupSheet');
        const mps = $('modelPickerSheet');
        const pMenu = $('settingsMenu');
        const sMenu = $('subMenu');
        if ((wls && wls.classList.contains('show')) || (mps && mps.classList.contains('show')) || (pMenu && pMenu.classList.contains('show')) || (sMenu && sMenu.classList.contains('show'))) return true;
        if (target && target.closest && target.closest('#playerContainer, #aiSummaryBox, .ai-Prompts-row, #aiQuickPrompts, #aiChatList, input, textarea, select, .m3-chips-scroll, #vttPanel')) return true;
        return false;
      }

      document.addEventListener('touchstart', (e) => {
        if (!e.touches || e.touches.length !== 1) return;
        if (isSwipeBlocked(e.target)) return;
        const touch = e.touches[0];
        startX = touch.clientX;
        startY = touch.clientY;
        startT = Date.now();
        tracking = true;
        horizLock = false;
        viewportW = Math.max(240, (hubViewport && hubViewport.clientWidth) || window.innerWidth || 360);
      }, { passive: true });

      document.addEventListener('touchmove', (e) => {
        if (!tracking || !e.touches || e.touches.length !== 1) return;
        const touch = e.touches[0];
        const dx = touch.clientX - startX;
        const dy = touch.clientY - startY;

        if (!horizLock) {
          if (Math.abs(dy) > 14 && Math.abs(dy) >= Math.abs(dx)) {
            tracking = false;
            return;
          }
          if (Math.abs(dx) > 14 && Math.abs(dx) > Math.abs(dy) * 1.2) {
            horizLock = true;
            if (panelMap[activeTab]) panelMap[activeTab].classList.add('dragging');
            if (hubIndicator) hubIndicator.classList.add('dragging');
          } else {
            return;
          }
        }

        const curIdx = TABS.indexOf(activeTab);
        const atEdge = (curIdx === 0 && dx > 0) || (curIdx === TABS.length - 1 && dx < 0);
        const ratio = (dx / viewportW) * (atEdge ? 0.25 : 1);
        const activeEl = panelMap[activeTab];
        if (activeEl) {
          activeEl.style.transform = `translate3d(${Math.max(-34, Math.min(34, dx * 0.16))}px, 0, 0)`;
        }
        if (hubIndicator) {
          positionHubIndicator(curIdx - ratio);
        }
      }, { passive: true });

      function endSwipe(e) {
        if (!tracking) return;
        tracking = false;
        if (!horizLock) return;
        horizLock = false;
        const touch = e.changedTouches && e.changedTouches[0];
        const dx = touch ? touch.clientX - startX : 0;
        const dt = Math.max(1, Date.now() - startT);
        const vel = Math.abs(dx) / dt;
        const curIdx = TABS.indexOf(activeTab);
        let nextIdx = curIdx;
        if (Math.abs(dx) > Math.max(48, viewportW * 0.16) || vel > 0.36) {
          nextIdx = dx < 0 ? Math.min(TABS.length - 1, curIdx + 1) : Math.max(0, curIdx - 1);
        }
        switchTab(TABS[nextIdx]);
      }

      document.addEventListener('touchend', endSwipe, { passive: true });
      document.addEventListener('touchcancel', endSwipe, { passive: true });
    })();

    /* ---- FEATURE 1: LANGUAGE STUDIO & DUAL SUBTITLES ---- */
    const dualSubToggle = $('dualSubToggle');
    const learnDualLangSelect = $('learnDualLangSelect');
    const dualSubLangSelect = $('dualSubLangSelect');
    const subModeSingleBtn = $('subModeSingleBtn');
    const subModeDualBtn = $('subModeDualBtn');
    const subMenuDualLangWrap = $('subMenuDualLangWrap');
    const dualSubStatusEl = $('dualSubStatus');
    const learnStatusEl = $('learnStatus');

    function populateDualLangSelects() {
      const translateSelect = $('translateLang');
      const opts = [{ value: 'original', label: t('subMenu.origSpeech') }];
      if (translateSelect) {
        Array.from(translateSelect.options).forEach((o) => {
          if (!o.value) return;
          opts.push({
            value: o.value,
            label: o.textContent.replace(/^Translate to\s+/i, '').trim() || o.value
          });
        });
      }
      [learnDualLangSelect, dualSubLangSelect].forEach((sel) => {
        if (!sel) return;
        sel.innerHTML = '';
        opts.forEach((item) => {
          const opt = document.createElement('option');
          opt.value = item.value;
          opt.textContent = item.label;
          sel.appendChild(opt);
        });
        const hasSaved = opts.some((item) => item.value === dualSubLang);
        sel.value = hasSaved ? dualSubLang : 'original';
      });
    }

    const syncDualToggles = (val) => {
      dualSubEnabled = Boolean(val);
      storageMod.safeSetStorage(KEYS.dualSub, dualSubEnabled ? '1' : '0');
      if (dualSubToggle) dualSubToggle.checked = dualSubEnabled;
      if (subModeSingleBtn) subModeSingleBtn.classList.toggle('active', !dualSubEnabled);
      if (subModeDualBtn) subModeDualBtn.classList.toggle('active', dualSubEnabled);
      if (subMenuDualLangWrap) subMenuDualLangWrap.style.display = dualSubEnabled ? 'flex' : 'none';
      const pCtrl = deps.getPlayerCtrl ? deps.getPlayerCtrl() : null;
      if (pCtrl && pCtrl.isPlayerActive()) pCtrl.updateSubtitleFrame();
    };

    function handleDualLangChange(newLang, statusTarget) {
      dualSubLang = String(newLang || 'original').trim() || 'original';
      storageMod.safeSetStorage(KEYS.dualSubLang, dualSubLang);
      if (learnDualLangSelect) learnDualLangSelect.value = dualSubLang;
      if (dualSubLangSelect) dualSubLangSelect.value = dualSubLang;
      if (!dualSubEnabled) syncDualToggles(true);
      if (typeof deps.onDualSubLangChanged === 'function') {
        deps.onDualSubLangChanged(dualSubLang, statusTarget || dualSubStatusEl);
      } else {
        const pCtrl = deps.getPlayerCtrl ? deps.getPlayerCtrl() : null;
        if (pCtrl && pCtrl.isPlayerActive()) pCtrl.updateSubtitleFrame();
      }
    }

    populateDualLangSelects();
    syncDualToggles(dualSubEnabled);
    if (dualSubToggle) dualSubToggle.addEventListener('change', () => syncDualToggles(dualSubToggle.checked));
    if (subModeSingleBtn) subModeSingleBtn.addEventListener('click', () => syncDualToggles(false));
    if (subModeDualBtn) subModeDualBtn.addEventListener('click', () => syncDualToggles(true));
    if (learnDualLangSelect) {
      learnDualLangSelect.addEventListener('change', () => handleDualLangChange(learnDualLangSelect.value, learnStatusEl));
    }
    if (dualSubLangSelect) {
      dualSubLangSelect.addEventListener('change', () => handleDualLangChange(dualSubLangSelect.value, dualSubStatusEl));
    }

    function renderLearnPanel() {
      updateTabBadges();
      dirty.learn = false;
      const due = getDueFlashcards(flashcards);
      const deckBox = $('flashcardDeckBox');
      const emptyBox = $('flashcardEmpty');
      const progEl = $('flashcardProgress');
      const cardEl = $('flashcardCard');
      const wordEl = $('flashcardFrontWord');
      const posEl = $('flashcardPosPill');
      const transEl = $('flashcardBackTrans');
      const meaningEl = $('flashcardBackMeaning');
      const ctxEl = $('flashcardBackCtx');

      if (due.length > 0 && deckBox && emptyBox && cardEl) {
        const top = due[0];
        deckBox.style.display = 'flex';
        emptyBox.style.display = 'none';
        cardEl.classList.remove('flipped');
        if (progEl) progEl.textContent = t('learn.dueProgress', { due: due.length, total: flashcards.length });
        if (wordEl) wordEl.textContent = top.word;
        if (posEl) {
          posEl.textContent = top.partOfSpeech || t('learn.wordFallback');
          posEl.style.display = 'inline-block';
        }
        if (transEl) transEl.textContent = top.translation || '—';
        if (meaningEl) meaningEl.textContent = [top.meaning, top.nuance].filter(Boolean).join(' · ');
        if (ctxEl) ctxEl.textContent = top.context ? `“${top.context}”` : '';
      } else if (deckBox && emptyBox) {
        deckBox.style.display = 'none';
        emptyBox.style.display = 'block';
        if (progEl) progEl.textContent = t('learn.dueZero');
        emptyBox.textContent = flashcards.length
          ? t('learn.caughtUp', { total: flashcards.length })
          : t('learn.empty');
      }

      const savedWordsCard = $('savedWordsCard');
      if (savedWordsCard) savedWordsCard.style.display = flashcards.length ? 'flex' : 'none';

      const vocabList = $('vocabList');
      if (vocabList) {
        vocabList.innerHTML = '';
        flashcards.slice(0, 30).forEach((card) => {
          const row = document.createElement('div');
          row.className = 'm3-vocab-item';
          const topRow = document.createElement('div');
          topRow.className = 'm3-vocab-item-top';

          const left = document.createElement('div');
          const w = document.createElement('div');
          w.className = 'm3-vocab-word';
          w.textContent = card.word + (card.partOfSpeech ? ` (${card.partOfSpeech})` : '');
          const tEl = document.createElement('div');
          tEl.className = 'm3-vocab-trans';
          tEl.textContent = card.translation || card.meaning || '';
          left.appendChild(w);
          left.appendChild(tEl);

          const delBtn = document.createElement('button');
          delBtn.type = 'button';
          delBtn.className = 'm3-icon-mini';
          delBtn.textContent = t('learn.delete');
          delBtn.addEventListener('click', () => {
            flashcards = flashcards.filter((x) => x.id !== card.id);
            writeJson(KEYS.flashcards, flashcards);
            dirty.learn = true;
            renderLearnPanel();
          });

          topRow.appendChild(left);
          topRow.appendChild(delBtn);
          row.appendChild(topRow);

          if (card.context) {
            const ctx = document.createElement('div');
            ctx.className = 'm3-studio-row-desc';
            ctx.textContent = `“${card.context}”`;
            row.appendChild(ctx);
          }
          vocabList.appendChild(row);
        });
      }
    }

    const flashcardCard = $('flashcardCard');
    if (flashcardCard) {
      flashcardCard.addEventListener('click', () => {
        flashcardCard.classList.toggle('flipped');
      });
    }
    const ratingRow = $('flashcardRatingRow');
    if (ratingRow) {
      ratingRow.querySelectorAll('[data-rate]').forEach((btn) => {
        btn.addEventListener('click', () => {
          const due = getDueFlashcards(flashcards);
          if (!due.length) return;
          const updated = reviewFlashcard(due[0], btn.dataset.rate, Date.now());
          flashcards = flashcards.map((c) => (c.id === updated.id ? updated : c));
          writeJson(KEYS.flashcards, flashcards);
          renderLearnPanel();
        });
      });
    }
    const clearVocabBtn = $('clearVocabBtn');
    if (clearVocabBtn) {
      clearVocabBtn.addEventListener('click', () => {
        flashcards = [];
        writeJson(KEYS.flashcards, flashcards);
        renderLearnPanel();
      });
    }

    /* Word Lookup Sheet */
    const lookupBackdrop = $('wordLookupBackdrop');
    const lookupSheet = $('wordLookupSheet');
    const closeLookupBtn = $('closeWordLookupBtn');
    const resumeLookupBtn = $('resumeFromLookupBtn');
    const saveWordCardBtn = $('saveWordCardBtn');

    function closeWordLookup(resumePlayback = false) {
      if (lookupAbortCtrl) {
        try { lookupAbortCtrl.abort(); } catch (_) {}
        lookupAbortCtrl = null;
      }
      if (lookupBackdrop) lookupBackdrop.classList.remove('show');
      if (lookupSheet) lookupSheet.classList.remove('show');
      if (resumePlayback && lookupResumeAfterClose) {
        const pCtrl = deps.getPlayerCtrl ? deps.getPlayerCtrl() : null;
        if (pCtrl && pCtrl.isPlayerActive()) pCtrl.togglePlay();
      }
      lookupResumeAfterClose = false;
    }

    if (closeLookupBtn) closeLookupBtn.addEventListener('click', () => closeWordLookup(false));
    if (lookupBackdrop) lookupBackdrop.addEventListener('click', () => closeWordLookup(true));
    if (resumeLookupBtn) resumeLookupBtn.addEventListener('click', () => closeWordLookup(true));

    if (saveWordCardBtn) {
      saveWordCardBtn.addEventListener('click', () => {
        if (!activeLookupCard) return;
        const idx = flashcards.findIndex((c) => c.word.toLowerCase() === activeLookupCard.word.toLowerCase());
        if (idx >= 0) {
          flashcards[idx] = normalizeFlashcard(Object.assign({}, flashcards[idx], activeLookupCard, { dueAt: Date.now() }));
        } else {
          flashcards.unshift(normalizeFlashcard(activeLookupCard, Date.now()));
        }
        writeJson(KEYS.flashcards, flashcards);
        dirty.learn = true;
        updateTabBadges();
        if (activeTab === 'learn') renderLearnPanel();
        saveWordCardBtn.textContent = t('lookup.savedCard');
        saveWordCardBtn.disabled = true;
      });
    }

    async function openWordLookup(info) {
      const word = cleanTappedWord(info && info.word);
      if (!word) return;
      lookupResumeAfterClose = Boolean(info && info.wasPlaying);
      activeLookupCard = {
        word,
        translation: '',
        partOfSpeech: '',
        meaning: '',
        nuance: '',
        example: '',
        context: (info && (info.originalSentence || info.sentence)) || '',
        sourceTitle: deps.getCurrentFileName ? deps.getCurrentFileName() : ''
      };

      const titleEl = $('wordLookupTitle');
      const posEl = $('wordLookupPos');
      const transEl = $('wordLookupTranslation');
      const meaningEl = $('wordLookupMeaning');
      const nuanceEl = $('wordLookupNuance');
      const exEl = $('wordLookupExample');
      const ctxEl = $('wordLookupContextLine');
      const stEl = $('wordLookupStatus');

      if (titleEl) titleEl.textContent = word;
      if (posEl) posEl.textContent = '...';
      if (transEl) transEl.textContent = t('lookup.analyzing');
      if (meaningEl) meaningEl.textContent = '';
      if (nuanceEl) nuanceEl.textContent = '';
      if (exEl) exEl.textContent = '';
      if (ctxEl) ctxEl.textContent = activeLookupCard.context ? t('lookup.scene', { ctx: activeLookupCard.context }) : '';
      if (stEl) stEl.textContent = '';
      if (saveWordCardBtn) {
        saveWordCardBtn.textContent = t('lookup.saveCard');
        saveWordCardBtn.disabled = true;
      }

      if (lookupBackdrop) lookupBackdrop.classList.add('show');
      if (lookupSheet) lookupSheet.classList.add('show');

      if (!ensureGroqKey(stEl)) return;

      if (lookupAbortCtrl) {
        try { lookupAbortCtrl.abort(); } catch (_) {}
      }
      lookupAbortCtrl = new AbortController();
      const targetLang = (deps.getAiOutputLang && deps.getAiOutputLang()) || 'English';

      try {
        const res = await groqMod.explainWordInContext(
          word,
          info.sentence,
          info.originalSentence,
          targetLang,
          lookupAbortCtrl.signal,
          getGroqCtx()
        );
        activeLookupCard = Object.assign({}, activeLookupCard, res);
        if (titleEl) titleEl.textContent = res.word || word;
        if (posEl) posEl.textContent = res.partOfSpeech || t('learn.wordFallback');
        if (transEl) transEl.textContent = res.translation || '—';
        if (meaningEl) meaningEl.textContent = res.meaning || '';
        if (nuanceEl) nuanceEl.textContent = res.nuance ? t('lookup.nuance', { text: res.nuance }) : '';
        if (exEl) exEl.textContent = res.example ? t('lookup.example', { text: res.example }) : '';
        if (saveWordCardBtn) saveWordCardBtn.disabled = false;
      } catch (e) {
        if (e && e.message === 'Cancelled by user.') return;
        diag.logWarn('studio.wordLookup', e);
        if (transEl) transEl.textContent = t('lookup.failed');
        if (stEl) stEl.textContent = e && e.message ? e.message : String(e);
      }
    }

    /* ---- FEATURE 2: SUBTITLE HISTORY ---- */
    const historySearchInput = $('historySearchInput');
    const historyFilterBar = $('historyFilterBar');
    const clearHistoryBtn = $('clearHistoryBtn');
    const historyStatusEl = $('historyStatus');

    function formatHistoryDate(ts) {
      const num = Number(ts) || 0;
      if (!num) return '';
      if (Date.now() - num < 60000) return t('history.justNow');
      try {
        const mod = getI18n();
        const lang = mod && typeof mod.getLang === 'function' ? mod.getLang() : undefined;
        return new Intl.DateTimeFormat(lang || undefined, {
          month: 'short',
          day: 'numeric',
          hour: '2-digit',
          minute: '2-digit'
        }).format(new Date(num));
      } catch (_) {
        return new Date(num).toLocaleString();
      }
    }

    async function copyTextWithFallback(text) {
      try {
        if (navigator.clipboard && typeof navigator.clipboard.writeText === 'function') {
          await navigator.clipboard.writeText(text);
          return true;
        }
      } catch (e) {
        diag.logWarn('history.clipboard', e);
      }
      try {
        const ta = document.createElement('textarea');
        ta.value = text;
        ta.style.position = 'fixed';
        ta.style.opacity = '0';
        document.body.appendChild(ta);
        ta.select();
        const ok = document.execCommand('copy');
        ta.remove();
        return ok;
      } catch (e) {
        diag.logWarn('history.copyFallback', e);
        return false;
      }
    }

    function renderHistoryPanel() {
      updateTabBadges();
      dirty.history = false;
      const listEl = $('historyList');
      const emptyEl = $('historyEmpty');
      if (!listEl || !emptyEl) return;

      const hasItems = historyProjects.length > 0;
      if (clearHistoryBtn) clearHistoryBtn.style.display = hasItems ? 'inline-flex' : 'none';
      if (historySearchInput) historySearchInput.style.display = hasItems ? 'block' : 'none';

      const hasIncomplete = historyProjects.some((p) => p && p.isComplete === false);
      if (activeHistoryFilter === 'incomplete' && !hasIncomplete) {
        activeHistoryFilter = 'all';
      }

      if (historyFilterBar) {
        historyFilterBar.innerHTML = '';
        historyFilterBar.style.display = hasItems ? 'flex' : 'none';
        if (hasItems) {
          const filters = [
            { id: 'all', label: t('history.filterAll') },
            { id: 'translated', label: t('history.filterTranslated') }
          ];
          if (hasIncomplete) {
            filters.push({ id: 'incomplete', label: t('history.filterIncomplete') });
          }
          filters.forEach((f) => {
            const btn = document.createElement('button');
            btn.type = 'button';
            btn.className = 'm3-filter-chip' + (activeHistoryFilter === f.id ? ' active' : '');
            btn.textContent = f.label;
            btn.addEventListener('click', () => {
              activeHistoryFilter = f.id;
              renderHistoryPanel();
            });
            historyFilterBar.appendChild(btn);
          });
        }
      }

      const filtered = filterHistoryProjects(
        historyProjects,
        historySearchInput ? historySearchInput.value : '',
        activeHistoryFilter
      );
      listEl.innerHTML = '';

      if (!filtered.length) {
        emptyEl.style.display = 'block';
        emptyEl.textContent = hasItems ? t('history.emptyFiltered') : t('history.empty');
        return;
      }
      emptyEl.style.display = 'none';

      filtered.forEach((project) => {
        const card = document.createElement('div');
        card.className = 'm3-hist-card';

        const incomplete = project.isComplete === false;
        const top = document.createElement('div');
        top.className = 'm3-hist-top';

        const iconBox = document.createElement('div');
        iconBox.className = 'm3-hist-icon' + (incomplete ? ' is-incomplete' : '');
        iconBox.innerHTML = incomplete
          ? '<svg viewBox="0 0 24 24"><rect x="6" y="4" width="4" height="16"/><rect x="14" y="4" width="4" height="16"/></svg>'
          : '<svg viewBox="0 0 24 24"><rect x="3" y="5" width="18" height="14" rx="4"/><path d="M7 13h4"/><path d="M14 13h3"/><path d="M7 16h10"/></svg>';

        const metaCol = document.createElement('div');
        metaCol.className = 'm3-hist-meta-col';

        const titleEl = document.createElement('h4');
        titleEl.className = 'm3-hist-title';
        titleEl.textContent = String(project.fileName || project.fileSig || 'subtitles').replace(/\.[^/.]+$/, '') || 'subtitles';

        const timeEl = document.createElement('div');
        timeEl.className = 'm3-hist-time';
        const formattedTime = formatHistoryDate(project.updatedAt);
        timeEl.textContent = [formattedTime, project.model || ''].filter(Boolean).join(' · ');

        metaCol.appendChild(titleEl);
        metaCol.appendChild(timeEl);
        top.appendChild(iconBox);
        top.appendChild(metaCol);
        card.appendChild(top);

        const hasPrecomputedLines = typeof project.linesCount === 'number';
        const cuesList = hasPrecomputedLines ? [] : getHistoryProjectCues(project);
        const linesCount = hasPrecomputedLines ? project.linesCount : cuesList.length;
        const durSec = getHistoryDurationSec(project);
        const langs = getHistoryLanguages(project);
        const pillsWrap = document.createElement('div');
        pillsWrap.className = 'm3-hist-pills';

        if (incomplete) {
          const pWarn = document.createElement('span');
          pWarn.className = 'm3-hist-pill warn';
          pWarn.textContent = t('history.inProgress');
          pillsWrap.appendChild(pWarn);
        }
        if (linesCount > 0) {
          const pLines = document.createElement('span');
          pLines.className = 'm3-hist-pill';
          pLines.textContent = t('history.linesCount', { n: linesCount });
          pillsWrap.appendChild(pLines);
        }
        if (durSec > 0 && typeof deps.formatTime === 'function') {
          const pDur = document.createElement('span');
          pDur.className = 'm3-hist-pill';
          pDur.textContent = deps.formatTime(durSec);
          pillsWrap.appendChild(pDur);
        }
        langs.forEach((langName) => {
          const pLang = document.createElement('span');
          pLang.className = 'm3-hist-pill accent';
          pLang.textContent = langName;
          pillsWrap.appendChild(pLang);
        });
        if (pillsWrap.childNodes.length) card.appendChild(pillsWrap);

        const preview = buildHistoryPreview(project, 130);
        if (preview) {
          const prevEl = document.createElement('p');
          prevEl.className = 'm3-hist-preview';
          prevEl.textContent = preview;
          card.appendChild(prevEl);
        }

        const resolveFullProject = async () => {
          if (project.rawSegments || project.cues || project.partialChunks) return project;
          if (storageMod && typeof storageMod.getProjectFromHistory === 'function') {
            return (await storageMod.getProjectFromHistory(project.fileSig)) || project;
          }
          return project;
        };

        const openProjectFromCard = async () => {
          const full = await resolveFullProject();
          switchTab('video');
          if (typeof deps.onOpenHistoryProject === 'function') {
            await deps.onOpenHistoryProject(full);
          }
        };

        card.addEventListener('click', async (e) => {
          if (e.target && e.target.closest && e.target.closest('.m3-hist-actions')) return;
          await openProjectFromCard();
        });

        const acts = document.createElement('div');
        acts.className = 'm3-hist-actions';

        const openBtn = document.createElement('button');
        openBtn.type = 'button';
        openBtn.className = 'm3-hist-open-btn';
        openBtn.textContent = t('history.open');
        openBtn.addEventListener('click', async (e) => {
          e.stopPropagation();
          await openProjectFromCard();
        });
        acts.appendChild(openBtn);

        const copyBtn = document.createElement('button');
        copyBtn.type = 'button';
        copyBtn.className = 'm3-icon-mini';
        copyBtn.textContent = t('history.copy');
        copyBtn.addEventListener('click', async (e) => {
          e.stopPropagation();
          const full = await resolveFullProject();
          const fullCues = getHistoryProjectCues(full);
          const fullText = fullCues
            .map((c) => String((c && c.text) || '').trim())
            .filter(Boolean)
            .join('\n');
          if (!fullText) return;
          const ok = await copyTextWithFallback(fullText);
          if (ok) {
            copyBtn.textContent = t('history.copied');
            setTimeout(() => { copyBtn.textContent = t('history.copy'); }, 1400);
          }
        });
        acts.appendChild(copyBtn);

        if (linesCount > 0 && !incomplete) {
          const srtBtn = document.createElement('button');
          srtBtn.type = 'button';
          srtBtn.className = 'm3-icon-mini';
          srtBtn.textContent = t('history.export');
          srtBtn.addEventListener('click', async (e) => {
            e.stopPropagation();
            const full = await resolveFullProject();
            if (typeof deps.onExportHistoryProject === 'function') {
              deps.onExportHistoryProject(full, 'srt');
            }
          });
          acts.appendChild(srtBtn);
        }

        const delBtn = document.createElement('button');
        delBtn.type = 'button';
        delBtn.className = 'm3-icon-mini';
        delBtn.textContent = t('history.delete');
        delBtn.addEventListener('click', async (e) => {
          e.stopPropagation();
          if (storageMod && typeof storageMod.deleteProjectFromHistory === 'function') {
            await storageMod.deleteProjectFromHistory(project.fileSig);
          }
          await refreshHistoryFromDb(true);
        });
        acts.appendChild(delBtn);

        card.appendChild(acts);
        listEl.appendChild(card);
      });
    }

    async function refreshHistoryFromDb(forceRender = false) {
      if (!storageMod || typeof storageMod.listHistoryProjects !== 'function') return;
      try {
        const items = await storageMod.listHistoryProjects();
        historyProjects = Array.isArray(items) ? items : [];
        dirty.history = true;
        updateTabBadges();
        if (forceRender || activeTab === 'history') {
          renderHistoryPanel();
        }
      } catch (e) {
        diag.logWarn('history.refresh', e);
      }
    }

    if (historySearchInput) historySearchInput.addEventListener('input', renderHistoryPanel);
    if (clearHistoryBtn) {
      clearHistoryBtn.addEventListener('click', async () => {
        if (storageMod && typeof storageMod.clearHistoryProjects === 'function') {
          await storageMod.clearHistoryProjects();
        }
        if (historyStatusEl) historyStatusEl.textContent = '';
        await refreshHistoryFromDb(true);
      });
    }

    if (typeof window !== 'undefined') {
      window.__onHistoryUpdated = () => {
        refreshHistoryFromDb(false);
      };
    }
    refreshHistoryFromDb(false);

    /* ---- FEATURE 3: AI VOICE RECORDER & VOICE NOTES ---- */
    const voiceRecordBtn = $('voiceRecordBtn');
    const voiceRecordLabel = $('voiceRecordLabel');
    const voiceTimerEl = $('voiceTimer');
    const voiceStatusEl = $('voiceStatus');
    const voiceImportInput = $('voiceImportInput');
    const voiceFileNameEl = $('voiceFileName');
    const voiceTranslateLangEl = $('voiceTranslateLang');
    const voiceSearchInput = $('voiceSearchInput');
    let voiceTranslateLang = storageMod.safeGetStorage(KEYS.voiceTranslateLang, '') || '';
    let lastVoiceFileName = '';

    function populateVoiceTranslateSelect() {
      if (!voiceTranslateLangEl) return;
      const translateSelect = $('translateLang');
      voiceTranslateLangEl.innerHTML = '';
      if (translateSelect) {
        Array.from(translateSelect.options).forEach((o) => {
          const opt = document.createElement('option');
          opt.value = o.value;
          opt.textContent = o.value === '' ? t('lang.none') : o.textContent;
          voiceTranslateLangEl.appendChild(opt);
        });
      } else {
        const opt = document.createElement('option');
        opt.value = '';
        opt.textContent = t('lang.none');
        voiceTranslateLangEl.appendChild(opt);
      }
      const hasSaved = Array.from(voiceTranslateLangEl.options).some((o) => o.value === voiceTranslateLang);
      voiceTranslateLangEl.value = hasSaved ? voiceTranslateLang : '';
    }

    populateVoiceTranslateSelect();
    if (voiceTranslateLangEl) {
      voiceTranslateLangEl.addEventListener('change', () => {
        voiceTranslateLang = String(voiceTranslateLangEl.value || '').trim();
        storageMod.safeSetStorage(KEYS.voiceTranslateLang, voiceTranslateLang);
      });
    }

    function renderVoicePanel() {
      updateTabBadges();
      dirty.voice = false;
      const listEl = $('voiceList');
      const emptyEl = $('voiceEmpty');
      if (!listEl || !emptyEl) return;

      const filtered = filterVoiceNotes(voiceNotes, voiceSearchInput ? voiceSearchInput.value : '');
      if (voiceSearchInput) voiceSearchInput.style.display = voiceNotes.length ? 'block' : 'none';
      listEl.innerHTML = '';
      if (!filtered.length) {
        if (voiceNotes.length) {
          emptyEl.style.display = 'block';
          emptyEl.textContent = t('voice.emptyFiltered');
        } else {
          emptyEl.style.display = 'none';
          emptyEl.textContent = '';
        }
        return;
      }
      emptyEl.style.display = 'none';

      filtered.forEach((vn) => {
        const card = document.createElement('div');
        card.className = 'm3-note-card';

        const head = document.createElement('div');
        head.className = 'm3-note-head';
        const badge = document.createElement('span');
        badge.className = 'm3-note-cat';
        badge.textContent = vn.durationSec > 0 ? `🎙 ${deps.formatTime(vn.durationSec)}` : t('voice.badgeDefault');

        const acts = document.createElement('div');
        acts.style.display = 'flex';
        acts.style.gap = '6px';
        const copyBtn = document.createElement('button');
        copyBtn.type = 'button';
        copyBtn.className = 'm3-icon-mini';
        copyBtn.textContent = t('voice.copy');
        copyBtn.addEventListener('click', async () => {
          try {
            const payload = [vn.title, vn.summary, vn.actionItems.map((a) => `- [ ] ${a}`).join('\n'), vn.transcript].filter(Boolean).join('\n\n');
            await navigator.clipboard.writeText(payload);
            copyBtn.textContent = t('voice.copied');
            setTimeout(() => { copyBtn.textContent = t('voice.copy'); }, 1400);
          } catch (e) {
            diag.logWarn('voice.copy', e);
          }
        });
        const delBtn = document.createElement('button');
        delBtn.type = 'button';
        delBtn.className = 'm3-icon-mini';
        delBtn.textContent = t('voice.delete');
        delBtn.addEventListener('click', () => {
          voiceNotes = voiceNotes.filter((x) => x.id !== vn.id);
          writeJson(KEYS.voice, voiceNotes);
          dirty.voice = true;
          renderVoicePanel();
        });
        acts.appendChild(copyBtn);
        acts.appendChild(delBtn);
        head.appendChild(badge);
        head.appendChild(acts);
        card.appendChild(head);

        const title = document.createElement('h4');
        title.className = 'm3-note-title';
        title.textContent = vn.title;
        card.appendChild(title);

        if (vn.summary) {
          const sum = document.createElement('p');
          sum.className = 'm3-note-summary';
          sum.textContent = vn.summary;
          card.appendChild(sum);
        }

        if (vn.actionItems && vn.actionItems.length) {
          const ul = document.createElement('ul');
          ul.className = 'm3-note-points';
          vn.actionItems.forEach((ai) => {
            const li = document.createElement('li');
            li.textContent = `✓ ${ai}`;
            ul.appendChild(li);
          });
          card.appendChild(ul);
        }

        if (vn.transcript) {
          const det = document.createElement('details');
          det.className = 'm3-transcript-details';
          const sm = document.createElement('summary');
          sm.textContent = t('voice.fullTranscript');
          const body = document.createElement('div');
          body.className = 'm3-transcript-body';
          body.textContent = vn.transcript;
          det.appendChild(sm);
          det.appendChild(body);
          card.appendChild(det);
        }

        listEl.appendChild(card);
      });
    }

    if (voiceSearchInput) voiceSearchInput.addEventListener('input', renderVoicePanel);

    async function processAudioBlobToVoiceNote(blob, filename, durationSec) {
      const model = 'whisper-large-v3-turbo';
      const voiceTargetLang = (voiceTranslateLangEl && voiceTranslateLangEl.value) || '';
      const keys = deps.getApiKeys ? deps.getApiKeys() : [];

      if (!ensureGroqKey(voiceStatusEl)) return;
      if (voiceStatusEl) voiceStatusEl.textContent = t('voice.transcribing');
      try {
        const sttRes = await groqMod.sendToGroq(blob, filename, model, '', null, Object.assign(getGroqCtx(), {
          hallucinationFilter: true
        }));
        const rawTranscript = (sttRes.segments || []).map((s) => String(s.text || '').trim()).filter(Boolean).join(' ').trim();
        if (!rawTranscript) {
          if (voiceStatusEl) voiceStatusEl.textContent = t('voice.noSpeech');
          return;
        }
        const computedDur = durationSec || Math.round((sttRes.segments[sttRes.segments.length - 1] && sttRes.segments[sttRes.segments.length - 1].end) || 0);
        let noteData;
        if (keys.length > 0) {
          if (voiceStatusEl) voiceStatusEl.textContent = t('voice.structuring');
          const outLang = voiceTargetLang || (deps.getAiOutputLang && deps.getAiOutputLang()) || 'English';
          const structured = await groqMod.summarizeVoiceTranscript(
            rawTranscript,
            outLang,
            null,
            Object.assign(getGroqCtx(), { forceTranslate: Boolean(voiceTargetLang) })
          );
          noteData = Object.assign({}, structured, {
            durationSec: computedDur,
            transcript: (voiceTargetLang && structured.translatedTranscript) ? structured.translatedTranscript : rawTranscript
          });
        } else {
          noteData = {
            title: lastVoiceFileName ? lastVoiceFileName.replace(/\.[^/.]+$/, '') : t('voice.badgeDefault'),
            summary: '',
            actionItems: [],
            keyDates: [],
            tags: [],
            durationSec: computedDur,
            transcript: rawTranscript
          };
        }
        const note = createVoiceNote(noteData);
        voiceNotes.unshift(note);
        writeJson(KEYS.voice, voiceNotes);
        dirty.voice = true;
        if (voiceStatusEl) voiceStatusEl.textContent = t('voice.saved');
        renderVoicePanel();
      } catch (e) {
        diag.logError('voice.transcribe', e);
        if (voiceStatusEl) voiceStatusEl.textContent = e && e.message ? e.message : String(e);
      }
    }

    function stopMicTracks() {
      if (recTimerId) {
        clearInterval(recTimerId);
        recTimerId = null;
      }
      if (recStream) {
        try { recStream.getTracks().forEach((tr) => tr.stop()); } catch (_) {}
        recStream = null;
      }
    }

    function beginRecordingTimerUi() {
      recStartAt = Date.now();
      isRecording = true;
      if (voiceRecordBtn) voiceRecordBtn.classList.add('recording');
      if (voiceRecordLabel) voiceRecordLabel.textContent = t('voice.recording');
      if (voiceStatusEl) voiceStatusEl.textContent = t('voice.speakClearly');
      if (voiceTimerEl) voiceTimerEl.textContent = '0:00';
      if (recTimerId) clearInterval(recTimerId);
      recTimerId = setInterval(() => {
        const sec = Math.floor((Date.now() - recStartAt) / 1000);
        if (voiceTimerEl) voiceTimerEl.textContent = deps.formatTime(sec);
      }, 250);
    }

    function resetRecordingUi() {
      isRecording = false;
      isNativeRec = false;
      stopMicTracks();
      if (voiceRecordBtn) voiceRecordBtn.classList.remove('recording');
      if (voiceRecordLabel) voiceRecordLabel.textContent = t('voice.tapToRecord');
    }

    async function stopNativeRecorderAndProcess(b) {
      resetRecordingUi();
      if (voiceStatusEl) voiceStatusEl.textContent = t('voice.transcribing');
      try {
        const raw = b.stopNativeVoiceRecording();
        const info = raw ? JSON.parse(raw) : null;
        if (!info || !info.path) {
          if (voiceStatusEl) voiceStatusEl.textContent = t('voice.tooShort');
          return;
        }
        const bMod = getBridgeMod();
        const wavBlob = bMod && typeof bMod.loadChunkBlob === 'function'
          ? await bMod.loadChunkBlob(b, { path: info.path })
          : null;
        if (!wavBlob || wavBlob.size < 64) {
          if (voiceStatusEl) voiceStatusEl.textContent = t('voice.tooShort');
          return;
        }
        await processAudioBlobToVoiceNote(wavBlob, `voice_${Date.now()}.wav`, Number(info.durationSec) || 1);
      } catch (e) {
        diag.logWarn('voice.nativeStop', e);
        if (voiceStatusEl) voiceStatusEl.textContent = t('voice.micError', { msg: e && e.message ? e.message : String(e) });
      }
    }

    function tryStartNativeRecorder(b) {
      const startRes = String(b.startNativeVoiceRecording() || '');
      if (startRes === 'ok') {
        isNativeRec = true;
        beginRecordingTimerUi();
        return true;
      }
      if (startRes === 'permission_required') {
        awaitingNativeMicPerm = true;
        if (voiceStatusEl) voiceStatusEl.textContent = t('voice.requestingPerm');
        if (typeof b.requestMicPermission === 'function') b.requestMicPermission();
        return true;
      }
      if (voiceStatusEl) voiceStatusEl.textContent = t('voice.micError', { msg: startRes.replace(/^error:/, '') });
      return false;
    }

    window.__onNativeMicPermission = (granted) => {
      if (!awaitingNativeMicPerm) return;
      awaitingNativeMicPerm = false;
      const bMod = getBridgeMod();
      const b = (bMod && bMod.getBridge()) || window.AndroidBridge || window.ExoPlayerManager || null;
      if (granted && b && typeof b.startNativeVoiceRecording === 'function') {
        tryStartNativeRecorder(b);
      } else {
        if (voiceStatusEl) voiceStatusEl.textContent = t('voice.micDenied');
      }
    };

    async function toggleVoiceRecording() {
      const bMod = getBridgeMod();
      const b = (bMod && bMod.getBridge()) || window.AndroidBridge || window.ExoPlayerManager || null;
      const hasNativeRec = Boolean(b && typeof b.startNativeVoiceRecording === 'function' && typeof b.stopNativeVoiceRecording === 'function');

      if (isRecording) {
        if (isNativeRec && hasNativeRec) {
          await stopNativeRecorderAndProcess(b);
          return;
        }
        if (mediaRecorder) {
          isRecording = false;
          if (voiceRecordBtn) voiceRecordBtn.classList.remove('recording');
          if (voiceRecordLabel) voiceRecordLabel.textContent = t('voice.tapToRecord');
          try { mediaRecorder.stop(); } catch (_) { stopMicTracks(); }
          return;
        }
      }

      if (!ensureGroqKey(voiceStatusEl)) return;

      if (hasNativeRec) {
        tryStartNativeRecorder(b);
        return;
      }

      if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia || typeof MediaRecorder === 'undefined') {
        if (voiceStatusEl) voiceStatusEl.textContent = t('voice.unsupported');
        return;
      }

      try {
        recStream = await navigator.mediaDevices.getUserMedia({ audio: true });
        const candidates = ['audio/webm;codecs=opus', 'audio/webm', 'audio/mp4', ''];
        const chosenMime = candidates.find((m) => !m || (MediaRecorder.isTypeSupported && MediaRecorder.isTypeSupported(m))) || '';
        mediaRecorder = chosenMime ? new MediaRecorder(recStream, { mimeType: chosenMime }) : new MediaRecorder(recStream);
        recChunks = [];
        isNativeRec = false;
        beginRecordingTimerUi();

        mediaRecorder.ondataavailable = (ev) => {
          if (ev.data && ev.data.size > 0) recChunks.push(ev.data);
        };
        mediaRecorder.onstop = async () => {
          const elapsedSec = Math.max(1, Math.round((Date.now() - recStartAt) / 1000));
          const type = (mediaRecorder && mediaRecorder.mimeType) || chosenMime || 'audio/webm';
          const ext = type.includes('mp4') ? 'm4a' : (type.includes('ogg') ? 'ogg' : 'webm');
          const blob = new Blob(recChunks, { type });
          resetRecordingUi();
          if (blob.size < 256) {
            if (voiceStatusEl) voiceStatusEl.textContent = t('voice.tooShort');
            return;
          }
          await processAudioBlobToVoiceNote(blob, `voice_${Date.now()}.${ext}`, elapsedSec);
        };

        mediaRecorder.start(250);
      } catch (e) {
        resetRecordingUi();
        diag.logWarn('voice.mic', e);
        const msg = e && (e.message || e.name) ? `${e.name || ''} ${e.message || ''}`.trim() : 'Permission denied';
        if (voiceStatusEl) voiceStatusEl.textContent = t('voice.micError', { msg });
      }
    }

    if (voiceRecordBtn) voiceRecordBtn.addEventListener('click', toggleVoiceRecording);

    if (voiceImportInput) {
      voiceImportInput.addEventListener('change', async (e) => {
        const audioFile = e.target.files && e.target.files[0];
        if (!audioFile) return;
        lastVoiceFileName = audioFile.name || 'voice.ogg';
        if (voiceFileNameEl) voiceFileNameEl.textContent = lastVoiceFileName;
        voiceImportInput.value = '';
        await processAudioBlobToVoiceNote(audioFile, lastVoiceFileName, 0);
      });
    }

    updateTabBadges();
    if (typeof requestAnimationFrame === 'function') {
      requestAnimationFrame(() => positionHubIndicator(TABS.indexOf(activeTab)));
    }
    if (typeof window !== 'undefined') {
      window.addEventListener('resize', () => positionHubIndicator(TABS.indexOf(activeTab)));
    }

    function onLanguageChanged() {
      dirty.learn = true;
      dirty.history = true;
      dirty.voice = true;
      populateDualLangSelects();
      populateVoiceTranslateSelect();
      if (voiceFileNameEl && !lastVoiceFileName) voiceFileNameEl.textContent = t('file.notSelected');
      if (activeTab === 'learn') renderLearnPanel();
      if (activeTab === 'history') renderHistoryPanel();
      if (activeTab === 'voice') renderVoicePanel();
      if (voiceRecordLabel) voiceRecordLabel.textContent = isRecording ? t('voice.recording') : t('voice.tapToRecord');
      positionHubIndicator(TABS.indexOf(activeTab));
    }

    return {
      switchTab,
      isDualSubEnabled: () => dualSubEnabled,
      getDualSubLang: () => dualSubLang,
      openWordLookup,
      closeWordLookup,
      refreshHistory: () => refreshHistoryFromDb(false),
      renderLearnPanel,
      renderHistoryPanel,
      renderVoicePanel,
      onLanguageChanged
    };
  }

  return {
    cleanTappedWord,
    normalizeFlashcard,
    reviewFlashcard,
    getDueFlashcards,
    buildHistoryPreview,
    getHistoryDurationSec,
    getHistoryLanguages,
    filterHistoryProjects,
    createVoiceNote,
    filterVoiceNotes,
    findOriginalCueForTime,
    createStudioController
  };
});
