(function () {
  'use strict';

  const root = window.SubLyter = window.SubLyter || {};
  const diag = root.diagnostics || { logWarn() {} };
  const bridgeMod = root.bridge;
  const subtitlesMod = root.subtitles;

  function createPlayerController(deps) {
    const {
      videoEl,
      playerBox,
      overlayEl,
      subtitleEl,
      statusEl,
      seekBar,
      timeText,
      fsBtn,
      settingsBtn,
      settingsMenu,
      controlsEl,
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
      audioTrackAccItem,
      audioTrackSelect,
      audioTrackVal,
      getCues,
      getOriginalCues,
      getDualSubEnabled,
      onWordTap,
      subMenuBtn,
      subMenu,
      repeatLineBtn,
      shadowSpeedBtn,
      getAudioBoost,
      setAudioBoost,
      applySettings,
      syncM3RangeSlider,
      getFileSignature,
      requestWakeLock,
      releaseWakeLock
    } = deps;

    const TRANSPARENT_POSTER = 'data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7';
    const secondarySubtitleEl = document.getElementById('subtitleSecondaryText');

    let mediaObjectURL = null;
    let wordSpans = [];
    let currentCueIdx = -2;
    let lastValidCue = null;
    let currentOrigLine = '';
    let rafId = null;
    let boxVisible = null;
    let playerActive = false;
    let lastVideoWidth = 0;
    let lastVideoHeight = 0;
    let usingNativeExoFallback = false;
    let hideControlsTimer = null;
    let audioTracksRefreshTimer = null;
    let lastTimeLabel = '';
    let lastSeekPct = -1;
    let lastRenderTs = 0;
    let cachedOffsetSec = (parseInt(subOffsetEl.value, 10) || 0) / 1000;
    let cachedHoldSec = (parseInt(subHoldEl.value, 10) || 0) / 1000;
    let cachedWordMode = wordModeSelect.value || 'hidden';

    const syncCachedSubPrefs = () => {
      cachedOffsetSec = (parseInt(subOffsetEl.value, 10) || 0) / 1000;
      cachedHoldSec = (parseInt(subHoldEl.value, 10) || 0) / 1000;
      cachedWordMode = wordModeSelect.value || 'hidden';
    };
    subOffsetEl.addEventListener('input', syncCachedSubPrefs);
    subOffsetEl.addEventListener('change', syncCachedSubPrefs);
    subHoldEl.addEventListener('input', syncCachedSubPrefs);
    subHoldEl.addEventListener('change', syncCachedSubPrefs);
    wordModeSelect.addEventListener('change', syncCachedSubPrefs);

    /* ---- Web Audio (локальный boost для HTML5 <video>) ---- */
    let audioCtx, gainNode, compressorNode, sourceNode;

    function initAudio() {
      if (usingNativeExoFallback) return;
      const vol = parseInt(volSlider.value, 10) || 100;
      const boost = getAudioBoost();
      if (!audioCtx && vol <= 100 && boost <= 100) return;
      if (!audioCtx) {
        try {
          const AudioContext = window.AudioContext || window.webkitAudioContext;
          audioCtx = new AudioContext();
          sourceNode = audioCtx.createMediaElementSource(videoEl);
          gainNode = audioCtx.createGain();
          compressorNode = audioCtx.createDynamicsCompressor();

          compressorNode.threshold.value = -3.0;
          compressorNode.knee.value = 6.0;
          compressorNode.ratio.value = 20.0;
          compressorNode.attack.value = 0.002;
          compressorNode.release.value = 0.15;

          sourceNode.connect(gainNode);
          gainNode.connect(compressorNode);
          compressorNode.connect(audioCtx.destination);
          gainNode.gain.value = (vol / 100) * (boost / 100);
        } catch (e) {
          diag.logWarn('player.webAudio', e);
        }
      }
      if (audioCtx && audioCtx.state === 'suspended') {
        audioCtx.resume().catch(() => {});
      }
    }

    volSlider.addEventListener('input', (e) => {
      const val = parseInt(e.target.value, 10);
      volVal.textContent = val + '%';
      if (usingNativeExoFallback) {
        const b = bridgeMod.getBridge();
        if (b && typeof b.setVolume === 'function') {
          try { b.setVolume(val || 100); } catch (err) { diag.logWarn('native.setVolume', err); }
        }
        return;
      }
      const boost = getAudioBoost();
      if (val > 100 || boost > 100) initAudio();
      if (gainNode) {
        gainNode.gain.value = (val / 100) * (boost / 100);
      } else {
        videoEl.volume = Math.min(val / 100, 1.0);
      }
    });

    boostSlider.addEventListener('input', (e) => {
      syncM3RangeSlider(boostSlider);
      const val = Math.min(200, Math.max(100, parseInt(e.target.value, 10) || 100));
      boostVal.textContent = val + '%';
      setAudioBoost(val);
      applySettings();
      if (!usingNativeExoFallback) {
        if (val > 100) initAudio();
        if (gainNode) {
          gainNode.gain.value = ((parseInt(volSlider.value, 10) || 100) / 100) * (val / 100);
        }
      }
      const b = bridgeMod.getBridge();
      if (b && typeof b.setBoost === 'function') {
        try { b.setBoost(val); } catch (err) { diag.logWarn('native.setBoost', err); }
      }
    });

    function setPlaybackSpeed(spd) {
      const clamped = Math.min(2.0, Math.max(0.5, Number(spd) || 1));
      videoEl.playbackRate = clamped;
      nativePlaybackRate = clamped;
      if (speedSlider) speedSlider.value = String(clamped);
      if (speedVal) speedVal.textContent = clamped.toFixed(clamped % 1 === 0 ? 1 : 2) + 'x';
      if (shadowSpeedBtn) {
        const isSlow = Math.abs(clamped - 0.75) < 0.05;
        shadowSpeedBtn.classList.toggle('active', isSlow);
        shadowSpeedBtn.textContent = isSlow ? '0.75×' : '1.0×';
      }
      if (usingNativeExoFallback) {
        const b = bridgeMod.getBridge();
        if (b && typeof b.setSpeed === 'function') {
          try { b.setSpeed(clamped); } catch (err) { diag.logWarn('native.setSpeed', err); }
        }
      }
      return clamped;
    }

    speedSlider.addEventListener('input', (e) => {
      setPlaybackSpeed(parseFloat(e.target.value) || 1);
    });

    fitModeSelect.addEventListener('change', (e) => {
      if (!usingNativeExoFallback) return;
      const b = bridgeMod.getBridge();
      if (b && typeof b.setResizeMode === 'function') {
        try { b.setResizeMode(e.target.value || 'contain'); } catch (err) { diag.logWarn('native.setResizeMode', err); }
      }
    });

    function formatTime(sec) {
      if (isNaN(sec) || !isFinite(sec)) return '0:00';
      const m = Math.floor(sec / 60);
      const s = Math.floor(sec % 60);
      return `${m}:${String(s).padStart(2, '0')}`;
    }

    function syncTimeUI() {
      if (controlsEl && controlsEl.classList.contains('hidden-controls') && !seekBar.dataset.dragging) {
        return;
      }
      const cur = videoEl.currentTime;
      const dur = videoEl.duration;
      if (!seekBar.dataset.dragging) {
        const pct = dur > 0 ? (cur / dur) * 100 : 0;
        if (Math.abs(pct - lastSeekPct) >= 0.1 || (pct === 0 && lastSeekPct !== 0)) {
          lastSeekPct = pct;
          seekBar.value = pct;
          seekBar.style.setProperty('--progress', pct.toFixed(1) + '%');
        }
      }
      const nextLabel = `${formatTime(cur)} / ${formatTime(dur)}`;
      if (nextLabel !== lastTimeLabel) {
        lastTimeLabel = nextLabel;
        timeText.textContent = nextLabel;
      }
    }

    function showControls() {
      controlsEl.classList.remove('hidden-controls');
      syncTimeUI();
      clearTimeout(hideControlsTimer);
      if (!videoEl.paused) {
        hideControlsTimer = setTimeout(() => {
          if (!settingsMenu.classList.contains('show') && !(subMenu && subMenu.classList.contains('show'))) {
            controlsEl.classList.add('hidden-controls');
          }
        }, 2500);
      }
    }

    function rebuildCueDOM(cue, origText) {
      subtitleEl.innerHTML = '';
      if (secondarySubtitleEl) {
        secondarySubtitleEl.innerHTML = '';
        secondarySubtitleEl.style.opacity = '0';
        secondarySubtitleEl.classList.remove('has-dual-text');
      }
      if (overlayEl) overlayEl.classList.remove('has-secondary');
      wordSpans = [];
      boxVisible = false;
      subtitleEl.style.opacity = '0';
      if (!cue) return;

      const mainWrap = document.createElement('div');
      mainWrap.className = 'sub-main-line';
      const tappable = typeof onWordTap === 'function';
      cue.words.forEach((w, i) => {
        if (i > 0) mainWrap.appendChild(document.createTextNode(' '));
        const span = document.createElement('span');
        span.className = tappable ? 'word tappable' : 'word';
        span.textContent = w.word;
        span.dataset.state = 'h';
        mainWrap.appendChild(span);
        wordSpans.push(span);
      });
      subtitleEl.appendChild(mainWrap);

      if (origText && origText.trim() && origText.trim() !== String(cue.text || '').trim()) {
        const origDiv = document.createElement('div');
        origDiv.className = 'sub-dual-orig';
        origDiv.textContent = origText.trim();
        if (overlayEl && overlayEl.classList.contains('dual-split') && secondarySubtitleEl) {
          secondarySubtitleEl.appendChild(origDiv);
          secondarySubtitleEl.classList.add('has-dual-text');
          overlayEl.classList.add('has-secondary');
        } else {
          subtitleEl.appendChild(origDiv);
        }
      }
    }

    function updateSubtitleFrame() {
      const cues = getCues();
      if (!playerActive || !cues.length) return;

      const t = videoEl.currentTime;
      const offsetSec = cachedOffsetSec;
      const holdSec = cachedHoldSec;

      const idx = subtitlesMod.findActiveCueIndex(cues, t, offsetSec, holdSec, currentCueIdx);
      if (idx >= 0 && cues[idx]) lastValidCue = cues[idx];

      let origText = '';
      if (idx >= 0 && typeof getDualSubEnabled === 'function' && getDualSubEnabled()) {
        const origCues = typeof getOriginalCues === 'function' ? getOriginalCues() : [];
        if (origCues && origCues.length) {
          const origIdx = subtitlesMod.findActiveCueIndex(origCues, t, offsetSec, holdSec, -1);
          if (origIdx >= 0 && origCues[origIdx]) origText = origCues[origIdx].text || '';
        }
      }

      const mode = cachedWordMode;
      const cueChanged = idx !== currentCueIdx || origText !== currentOrigLine;
      if (cueChanged) {
        currentCueIdx = idx;
        currentOrigLine = origText;
        rebuildCueDOM(idx >= 0 ? cues[idx] : null, origText);
      }
      if (idx < 0) return;

      if (mode === 'full' && !cueChanged && boxVisible) {
        return;
      }

      const cue = cues[idx];
      let anySpoken = mode !== 'hidden';

      if (mode !== 'full') {
        for (let i = 0; i < cue.words.length; i++) {
          const w = cue.words[i];
          const span = wordSpans[i];
          if (!span) continue;

          const spoken = t >= (w.start + offsetSec);
          if (spoken) anySpoken = true;

          const state = spoken ? 'v' : 'h';
          if (span.dataset.state !== state) {
            span.dataset.state = state;
            if (spoken) span.classList.add('spoken');
            else span.classList.remove('spoken');
          }
        }
      }

      if (anySpoken !== boxVisible) {
        boxVisible = anySpoken;
        subtitleEl.style.opacity = anySpoken ? '1' : '0';
        if (secondarySubtitleEl) secondarySubtitleEl.style.opacity = anySpoken ? '1' : '0';
      }
    }

    subtitleEl.addEventListener('click', (e) => {
      const wordEl = e.target && e.target.closest ? e.target.closest('.word.tappable') : null;
      if (!wordEl || typeof onWordTap !== 'function') return;
      e.stopPropagation();
      e.preventDefault();
      const cues = getCues();
      const cue = (currentCueIdx >= 0 && cues[currentCueIdx]) ? cues[currentCueIdx] : lastValidCue;
      const wasPlaying = !videoEl.paused;
      if (wasPlaying) videoEl.pause();
      const t = videoEl.currentTime || 0;
      const offsetSec = (parseInt(subOffsetEl.value, 10) || 0) / 1000;
      const holdSec = (parseInt(subHoldEl.value, 10) || 0) / 1000;
      const origCues = typeof getOriginalCues === 'function' ? getOriginalCues() : [];
      const origIdx = (origCues && origCues.length)
        ? subtitlesMod.findActiveCueIndex(origCues, t, offsetSec, holdSec, -1)
        : -1;
      onWordTap({
        word: wordEl.textContent || '',
        sentence: cue ? cue.text : '',
        originalSentence: (origIdx >= 0 && origCues[origIdx]) ? origCues[origIdx].text : '',
        timeSec: t,
        wasPlaying
      });
    });

    function startRenderLoop() {
      if (rafId) return;
      lastRenderTs = 0;
      const loop = (now) => {
        const minInterval = cachedWordMode === 'full' ? 66 : 32;
        if (!lastRenderTs || (now - lastRenderTs) >= minInterval) {
          lastRenderTs = now;
          syncTimeUI();
          updateSubtitleFrame();
        }
        if (!videoEl.paused) {
          rafId = requestAnimationFrame(loop);
        } else {
          rafId = null;
        }
      };
      rafId = requestAnimationFrame(loop);
    }

    function stopRenderLoop() {
      if (rafId) {
        cancelAnimationFrame(rafId);
        rafId = null;
      }
    }

    function updatePlayState() {
      const paused = videoEl.paused;
      document.body.classList.toggle('player-playing', !paused);
      if (paused) {
        controlsEl.classList.remove('hidden-controls');
        syncTimeUI();
        clearTimeout(hideControlsTimer);
        stopRenderLoop();
        if (audioCtx && audioCtx.state === 'running') {
          audioCtx.suspend().catch(() => {});
        }
        releaseWakeLock();
      } else {
        initAudio();
        requestWakeLock();
        showControls();
        startRenderLoop();
      }
    }

    function showPlayFlash(isPlaying) {
      const old = playerBox.querySelector('.play-flash');
      if (old) old.remove();
      const el = document.createElement('div');
      el.className = 'play-flash';
      el.innerHTML = isPlaying
        ? '<svg viewBox="0 0 24 24"><path d="M8 5v14l11-7z"/></svg>'
        : '<svg viewBox="0 0 24 24"><path d="M6 19h4V5H6v14zm8-14v14h4V5h-4z"/></svg>';
      playerBox.appendChild(el);
      setTimeout(() => el.remove(), 500);
    }

    function togglePlay() {
      initAudio();
      if (videoEl.paused) {
        videoEl.play().catch(() => {});
        showPlayFlash(true);
      } else {
        videoEl.pause();
        showPlayFlash(false);
      }
    }

    function updateOverlayGeometry() {
      const cw = playerBox.clientWidth;
      const ch = playerBox.clientHeight;
      if (!cw || !ch) return;

      const vw = videoEl.videoWidth || lastVideoWidth || cw;
      const vh = videoEl.videoHeight || lastVideoHeight || ch;
      const fitMode = fitModeSelect.value || 'contain';

      let dw = cw;
      let dh = ch;
      if (fitMode === 'contain') {
        const scale = Math.min(cw / vw, ch / vh);
        dw = Math.round(vw * scale);
        dh = Math.round(vh * scale);
      }

      const left = Math.round((cw - dw) / 2);
      const top = Math.round((ch - dh) / 2);

      overlayEl.style.left = left + 'px';
      overlayEl.style.width = dw + 'px';
      overlayEl.style.right = 'auto';
      overlayEl.style.top = top + 'px';
      overlayEl.style.height = dh + 'px';
      overlayEl.style.bottom = 'auto';
      const bottomPct = parseFloat(
        getComputedStyle(document.documentElement).getPropertyValue('--sub-bottom')
      ) || 12;
      const marginPx = (dh * bottomPct / 100) + 'px';
      const subPos = overlayEl.dataset.subPos || 'bottom';
      const dualOrder = overlayEl.dataset.dualOrder || 'trans-top';
      if (dualOrder === 'split-orig-top' || dualOrder === 'split-trans-top') {
        overlayEl.style.alignItems = '';
        overlayEl.style.paddingTop = marginPx;
        overlayEl.style.paddingBottom = marginPx;
      } else if (subPos === 'top') {
        overlayEl.style.alignItems = 'flex-start';
        overlayEl.style.paddingTop = marginPx;
        overlayEl.style.paddingBottom = '0px';
      } else if (subPos === 'middle') {
        overlayEl.style.alignItems = 'center';
        overlayEl.style.paddingTop = '0px';
        overlayEl.style.paddingBottom = '0px';
      } else {
        overlayEl.style.alignItems = 'flex-end';
        overlayEl.style.paddingTop = '0px';
        overlayEl.style.paddingBottom = marginPx;
      }
    }

    function syncPlayerAspectRatio(rawW, rawH) {
      if (!playerBox) return;
      const isFs = document.body.classList.contains('player-fullscreen-open') ||
        Boolean(document.fullscreenElement || document.webkitFullscreenElement);
      if (isFs) {
        playerBox.style.width = '';
        playerBox.style.height = '';
        playerBox.style.aspectRatio = '';
        updateOverlayGeometry();
        if (window.__syncNativePlayerBounds) window.__syncNativePlayerBounds();
        return;
      }

      const vw = Number(rawW) > 0 ? Number(rawW) : (Number(videoEl.videoWidth) || lastVideoWidth);
      const vh = Number(rawH) > 0 ? Number(rawH) : (Number(videoEl.videoHeight) || lastVideoHeight);

      if (vw > 0 && vh > 0) {
        lastVideoWidth = vw;
        lastVideoHeight = vh;
        const parentEl = playerBox.parentElement || document.body;
        const pStyle = getComputedStyle(parentEl);
        const padX = (parseFloat(pStyle.paddingLeft) || 0) + (parseFloat(pStyle.paddingRight) || 0);
        const maxAvailW = Math.max(160, (parentEl.clientWidth || window.innerWidth || 360) - padX);
        const availW = Math.min(maxAvailW, window.innerWidth || maxAvailW);
        const maxH = Math.max(220, Math.round((window.innerHeight || 720) * 0.76));

        const ar = vw / vh;
        let targetW = availW;
        let targetH = Math.round(targetW / ar);
        if (targetH > maxH) {
          targetH = maxH;
          targetW = Math.max(1, Math.round(targetH * ar));
        }

        playerBox.style.setProperty('--video-ar', `${vw} / ${vh}`);
        playerBox.style.aspectRatio = `${vw} / ${vh}`;
        playerBox.style.width = targetW + 'px';
        playerBox.style.height = Math.max(1, targetH) + 'px';
      } else {
        playerBox.style.width = '';
        playerBox.style.height = '';
        playerBox.style.aspectRatio = '';
      }

      updateOverlayGeometry();
      if (window.__syncNativePlayerBounds) window.__syncNativePlayerBounds();
    }

    async function applyRealVideoPreviewPoster() {
      const b = bridgeMod.getBridge();
      if (!b) return false;
      try {
        const b64 = await bridgeMod.callNativeAsync('requestCurrentFrameAsync', 'getCurrentFrameBase64', 4000);
        if (b64 && b64.length > 32) {
          videoEl.poster = 'data:image/jpeg;base64,' + b64;
          return true;
        }
      } catch (e) {
        diag.logWarn('player.poster', e);
      }
      return false;
    }

    async function refreshAudioTracks() {
      if (!audioTrackAccItem || !audioTrackSelect) return;
      const b = bridgeMod.getBridge();
      if (!b || (typeof b.requestAudioTracksAsync !== 'function' && typeof b.getAudioTracksJson !== 'function')) {
        audioTrackAccItem.style.display = 'none';
        return;
      }

      let tracks = [];
      try {
        const raw = await bridgeMod.callNativeAsync('requestAudioTracksAsync', 'getAudioTracksJson', 4000);
        tracks = JSON.parse(raw || '[]');
      } catch (e) {
        diag.logWarn('player.audioTracks', e);
        tracks = [];
      }
      if (!Array.isArray(tracks) || tracks.length <= 1) {
        audioTrackAccItem.style.display = 'none';
        return;
      }

      audioTrackSelect.innerHTML = '';
      tracks.forEach((track, i) => {
        const opt = document.createElement('option');
        const rawIndex = Number(track.index);
        opt.value = String(Number.isFinite(rawIndex) ? rawIndex : i);
        opt.textContent = track.title || ('Track ' + (i + 1));
        if (track.selected) opt.selected = true;
        audioTrackSelect.appendChild(opt);
      });

      const selected = audioTrackSelect.options[audioTrackSelect.selectedIndex];
      if (audioTrackVal) audioTrackVal.textContent = selected ? selected.textContent : 'Track 1';
      audioTrackAccItem.style.display = '';
    }

    function scheduleAudioTracksRefresh(delay = 250) {
      if (audioTracksRefreshTimer) clearTimeout(audioTracksRefreshTimer);
      audioTracksRefreshTimer = setTimeout(() => {
        audioTracksRefreshTimer = null;
        refreshAudioTracks();
      }, delay);
    }

    audioTrackSelect.addEventListener('change', () => {
      const idx = parseInt(audioTrackSelect.value, 10) || 0;
      const b = bridgeMod.getBridge();
      if (b && typeof b.selectAudioTrack === 'function') {
        try { b.selectAudioTrack(idx); } catch (e) { diag.logWarn('player.selectTrack', e); }
      }
      const selected = audioTrackSelect.options[audioTrackSelect.selectedIndex];
      if (audioTrackVal) audioTrackVal.textContent = selected ? selected.textContent : ('Track ' + (idx + 1));
    });

    /* ---- Мост нативного ExoPlayer (событийная синхронизация, ноль опроса в простое) ---- */
    let nativeSyncTimer = null;
    let scheduleNativeSyncTick = () => {};
    let nativeSyncAnchorPerfMs = 0;
    let nativePlaybackRate = 1;

    const nativeBridge = bridgeMod.getBridge();
    if (nativeBridge) {
      let nativePaused = true;
      let nativeCurrentTime = 0;
      let nativeDuration = 0;
      let lastUserPlayActionAt = 0;
      let boundsRafPending = false;
      let lastSentBoundsKey = '';

      try {
        if (typeof nativeBridge.setPlayerVisibility === 'function') {
          nativeBridge.setPlayerVisibility(false);
        }
      } catch (_) {}

      const flushNativePlayerBounds = () => {
        boundsRafPending = false;
        const visible = Boolean(
          playerActive &&
          usingNativeExoFallback &&
          playerBox.style.display !== 'none' &&
          !document.body.classList.contains('global-settings-open') &&
          !document.body.classList.contains('welcome-modal-open')
        );
        if (!visible) {
          if (lastSentBoundsKey !== 'hidden') {
            lastSentBoundsKey = 'hidden';
            try {
              if (typeof nativeBridge.setPlayerVisibility === 'function') {
                nativeBridge.setPlayerVisibility(false);
              }
            } catch (_) {}
          }
          return;
        }
        try {
          const rect = playerBox.getBoundingClientRect();
          const vw = window.innerWidth || 1;
          const vh = window.innerHeight || 1;
          const isFs = Boolean(
            document.body.classList.contains('player-fullscreen-open') ||
            document.fullscreenElement ||
            document.webkitFullscreenElement
          );
          const nextKey = `${Math.round(rect.left)}_${Math.round(rect.top)}_${Math.round(rect.width)}_${Math.round(rect.height)}_${vw}_${vh}_${isFs ? 1 : 0}`;
          if (nextKey === lastSentBoundsKey) return;
          lastSentBoundsKey = nextKey;
          if (typeof nativeBridge.setPlayerVisibility === 'function') {
            nativeBridge.setPlayerVisibility(true);
          }
          nativeBridge.setPlayerBounds(rect.left, rect.top, rect.width, rect.height, vw, vh, isFs);
        } catch (e) {
          diag.logWarn('native.bounds', e);
        }
      };

      const syncNativePlayerBounds = () => {
        if (boundsRafPending) return;
        boundsRafPending = true;
        if (typeof requestAnimationFrame === 'function') {
          requestAnimationFrame(flushNativePlayerBounds);
        } else {
          flushNativePlayerBounds();
        }
      };

      window.__syncNativePlayerBounds = syncNativePlayerBounds;
      window.addEventListener('scroll', syncNativePlayerBounds, { passive: true });
      window.addEventListener('resize', syncNativePlayerBounds);

      const syncNativeNowOnce = () => {
        if (!playerActive || !usingNativeExoFallback) return;
        try {
          if (Date.now() - lastUserPlayActionAt >= 320) {
            const playingNow = Boolean(nativeBridge.isPlaying());
            if (playingNow === nativePaused) {
              nativePaused = !playingNow;
              videoEl.dispatchEvent(new Event(playingNow ? 'play' : 'pause'));
            }
          }
          nativeCurrentTime = Number(nativeBridge.getCurrentTime()) || 0;
          nativeSyncAnchorPerfMs = typeof performance !== 'undefined' ? performance.now() : Date.now();
          const dur = Number(nativeBridge.getDuration()) || 0;
          if (dur > 0 && Math.abs(dur - nativeDuration) > 0.1) {
            nativeDuration = dur;
            updateOverlayGeometry();
            videoEl.dispatchEvent(new Event('loadedmetadata'));
          }
          const nw = Number(nativeBridge.getVideoWidth()) || 0;
          const nh = Number(nativeBridge.getVideoHeight()) || 0;
          if (nw > 0 && nh > 0 && (nw !== lastVideoWidth || nh !== lastVideoHeight)) {
            syncPlayerAspectRatio(nw, nh);
          }
          syncTimeUI();
          updateSubtitleFrame();
        } catch (e) {
          diag.logWarn('native.syncTick', e);
        }
      };

      scheduleNativeSyncTick = (immediate = false) => {
        if (nativeSyncTimer) {
          clearTimeout(nativeSyncTimer);
          nativeSyncTimer = null;
        }
        if (!playerActive || !usingNativeExoFallback) return;
        if (immediate) syncNativeNowOnce();
        // Опрос только пока не пришли метаданные/размеры; при воспроизведении позиция пушится из ExoPlayer (120 мс) + интерполяция в JS
        const awaitingMetadata = nativeDuration <= 0 || lastVideoWidth <= 0;
        if (!awaitingMetadata) return;
        nativeSyncTimer = setTimeout(() => {
          nativeSyncTimer = null;
          syncNativeNowOnce();
          scheduleNativeSyncTick(false);
        }, 250);
      };

      window.__onNativePlaybackStateChanged = (isPlaying, posSec, durSec, speed) => {
        if (!playerActive || !usingNativeExoFallback) return;
        try {
          if (Number.isFinite(posSec) && posSec >= 0) {
            nativeCurrentTime = posSec;
            nativeSyncAnchorPerfMs = typeof performance !== 'undefined' ? performance.now() : Date.now();
          }
          if (Number.isFinite(speed) && speed > 0) {
            nativePlaybackRate = speed;
          }
          if (Number.isFinite(durSec) && durSec > 0 && Math.abs(durSec - nativeDuration) > 0.1) {
            nativeDuration = durSec;
            updateOverlayGeometry();
            videoEl.dispatchEvent(new Event('loadedmetadata'));
          }
          if (Date.now() - lastUserPlayActionAt >= 320 && Boolean(isPlaying) === nativePaused) {
            nativePaused = !isPlaying;
            videoEl.dispatchEvent(new Event(isPlaying ? 'play' : 'pause'));
          }
          syncTimeUI();
          updateSubtitleFrame();
          scheduleNativeSyncTick(false);
        } catch (e) {
          diag.logWarn('native.playbackEvent', e);
        }
      };

      window.__activateNativeExoFallback = function () {
        usingNativeExoFallback = true;
        lastSentBoundsKey = '';
        nativeSyncAnchorPerfMs = typeof performance !== 'undefined' ? performance.now() : Date.now();
        document.documentElement.classList.add('native-exo-fallback');
        document.body.classList.add('native-exo-fallback');
        try {
          if (typeof nativeBridge.prepareStoredVideo === 'function') {
            nativeBridge.prepareStoredVideo();
          }
          Object.defineProperty(videoEl, 'currentTime', {
            get() {
              if (nativePaused) return nativeCurrentTime;
              const nowMs = typeof performance !== 'undefined' ? performance.now() : Date.now();
              const elapsed = Math.max(0, (nowMs - nativeSyncAnchorPerfMs) / 1000) * nativePlaybackRate;
              const est = nativeCurrentTime + elapsed;
              return nativeDuration > 0 ? Math.min(nativeDuration, est) : est;
            },
            set(val) {
              const sec = Math.max(0, Number(val) || 0);
              nativeCurrentTime = sec;
              nativeSyncAnchorPerfMs = typeof performance !== 'undefined' ? performance.now() : Date.now();
              lastUserPlayActionAt = Date.now();
              try { nativeBridge.seekTo(sec); } catch (e) { diag.logWarn('native.seekTo', e); }
            },
            configurable: true
          });

          Object.defineProperty(videoEl, 'duration', {
            get() {
              return nativeDuration || 0;
            },
            configurable: true
          });

          Object.defineProperty(videoEl, 'paused', {
            get() {
              return nativePaused;
            },
            configurable: true
          });

          Object.defineProperty(videoEl, 'videoWidth', {
            get() {
              return lastVideoWidth || 0;
            },
            configurable: true
          });

          Object.defineProperty(videoEl, 'videoHeight', {
            get() {
              return lastVideoHeight || 0;
            },
            configurable: true
          });

          videoEl.play = function () {
            try {
              nativePaused = false;
              nativeSyncAnchorPerfMs = typeof performance !== 'undefined' ? performance.now() : Date.now();
              lastUserPlayActionAt = Date.now();
              nativeBridge.play();
              videoEl.dispatchEvent(new Event('play'));
              scheduleNativeSyncTick(false);
            } catch (e) {
              diag.logWarn('native.play', e);
            }
            return Promise.resolve();
          };

          videoEl.pause = function () {
            try {
              try {
                const pos = Number(nativeBridge.getCurrentTime());
                if (Number.isFinite(pos) && pos >= 0) nativeCurrentTime = pos;
              } catch (_) {}
              nativePaused = true;
              nativeSyncAnchorPerfMs = typeof performance !== 'undefined' ? performance.now() : Date.now();
              lastUserPlayActionAt = Date.now();
              nativeBridge.pause();
              videoEl.dispatchEvent(new Event('pause'));
              scheduleNativeSyncTick(true);
            } catch (e) {
              diag.logWarn('native.pause', e);
            }
          };

          syncNativePlayerBounds();
          try { nativeBridge.setBoost(getAudioBoost()); } catch (e) { diag.logWarn('native.setBoost', e); }
          scheduleNativeSyncTick(true);
          if (deps.autoPlayToggle.checked && getCues().length > 0) {
            videoEl.play();
          }
        } catch (e) {
          diag.logWarn('native.activateFallback', e);
        }
      };
    }

    function setupPlayer(file) {
      const newSig = getFileSignature(file);
      if (file && videoEl.dataset.fileSig !== newSig) {
        lastVideoWidth = 0;
        lastVideoHeight = 0;
        playerBox.style.width = '';
        playerBox.style.height = '';
        playerBox.style.aspectRatio = '';
        playerBox.style.removeProperty('--video-ar');

        if (usingNativeExoFallback) {
          usingNativeExoFallback = false;
          if (nativeSyncTimer) {
            clearTimeout(nativeSyncTimer);
            nativeSyncTimer = null;
          }
          // Удаляем monkey-patch свойства с <video>, чтобы обычные файлы играли нативно в WebView
          bridgeMod.deactivateNativeExoFallback(videoEl);
        }
        document.documentElement.classList.remove('native-exo-fallback');
        document.body.classList.remove('native-exo-fallback');
        const b = bridgeMod.getBridge();
        if (b && typeof b.setPlayerVisibility === 'function') {
          try { b.setPlayerVisibility(false); } catch (e) { diag.logWarn('native.setVisibility', e); }
        }
        videoEl.poster = TRANSPARENT_POSTER;
        if (file.isExternalMedia) {
          try {
            if (window.__activateNativeExoFallback) window.__activateNativeExoFallback();
          } catch (e) {
            diag.logWarn('player.externalFallback', e);
          }
        } else {
          if (mediaObjectURL) URL.revokeObjectURL(mediaObjectURL);
          mediaObjectURL = URL.createObjectURL(file);
          videoEl.src = mediaObjectURL + '#t=0.1';
          setTimeout(applyRealVideoPreviewPoster, 200);
        }
        videoEl.dataset.fileSig = newSig;
      }

      playerBox.style.display = 'flex';
      videoEl.playbackRate = parseFloat(speedSlider.value) || 1;
      playerActive = true;
      syncPlayerAspectRatio(videoEl.videoWidth, videoEl.videoHeight);
      if (usingNativeExoFallback) {
        if (window.__syncNativePlayerBounds) {
          try { window.__syncNativePlayerBounds(); } catch (_) {}
        }
        scheduleNativeSyncTick(true);
      }

      currentCueIdx = -2;
      wordSpans = [];
      boxVisible = false;
      subtitleEl.innerHTML = '';
      subtitleEl.style.opacity = '0';
      if (secondarySubtitleEl) {
        secondarySubtitleEl.innerHTML = '';
        secondarySubtitleEl.style.opacity = '0';
        secondarySubtitleEl.classList.remove('has-dual-text');
      }
      if (overlayEl) overlayEl.classList.remove('has-secondary');

      updatePlayState();
      updateOverlayGeometry();
      updateSubtitleFrame();
      scheduleAudioTracksRefresh(200);
    }

    function enterFullscreenPlayer() {
      document.documentElement.classList.add('player-fullscreen-open');
      document.body.classList.add('player-fullscreen-open');
      syncPlayerAspectRatio();
      const b = bridgeMod.getBridge();
      if (b && typeof b.setImmersiveFullscreen === 'function') {
        try { b.setImmersiveFullscreen(true); } catch (e) { diag.logWarn('native.immersiveEnter', e); }
      }
      setTimeout(() => syncPlayerAspectRatio(), 60);
    }

    function exitFullscreenPlayer() {
      settingsMenu.classList.remove('show');
      if (subMenu) subMenu.classList.remove('show');
      if (document.fullscreenElement || document.webkitFullscreenElement) {
        document.exitFullscreen?.().catch(() => {});
      }
      document.documentElement.classList.remove('player-fullscreen-open');
      document.body.classList.remove('player-fullscreen-open');
      syncPlayerAspectRatio();
      const b = bridgeMod.getBridge();
      if (b && typeof b.setImmersiveFullscreen === 'function') {
        try { b.setImmersiveFullscreen(false); } catch (e) { diag.logWarn('native.immersiveExit', e); }
      }
      if (b && typeof b.toggleLandscape === 'function') {
        try { b.toggleLandscape(false); } catch (e) { diag.logWarn('native.landscapeExit', e); }
      }
      setTimeout(() => syncPlayerAspectRatio(), 60);
    }

    function positionPlayerSettingsMenu() {
      if (!settingsMenu.classList.contains('show')) return;
      const isFs = document.body.classList.contains('player-fullscreen-open') || Boolean(document.fullscreenElement || document.webkitFullscreenElement);
      if (isFs) {
        settingsMenu.style.right = '14px';
        settingsMenu.style.bottom = '54px';
        settingsMenu.style.maxHeight = 'calc(100dvh - 68px)';
        return;
      }
      const rect = settingsBtn.getBoundingClientRect();
      const pRect = playerBox ? playerBox.getBoundingClientRect() : rect;
      const vw = window.innerWidth || document.documentElement.clientWidth || 360;
      const vh = window.innerHeight || document.documentElement.clientHeight || 780;
      let rightPx = Math.max(12, Math.round(vw - pRect.right + 8));
      if (vw - rightPx - 288 < 12) rightPx = 12;
      const bottomPx = Math.max(12, Math.round(vh - rect.top + 8));
      const availH = Math.max(180, Math.round(rect.top - 16));
      settingsMenu.style.right = rightPx + 'px';
      settingsMenu.style.bottom = bottomPx + 'px';
      settingsMenu.style.maxHeight = Math.min(380, availH) + 'px';
    }

    function positionPlayerSubMenu() {
      if (!subMenu || !subMenu.classList.contains('show') || !subMenuBtn) return;
      const isFs = document.body.classList.contains('player-fullscreen-open') || Boolean(document.fullscreenElement || document.webkitFullscreenElement);
      if (isFs) {
        subMenu.style.right = '14px';
        subMenu.style.bottom = '54px';
        subMenu.style.maxHeight = 'calc(100dvh - 68px)';
        return;
      }
      const rect = subMenuBtn.getBoundingClientRect();
      const pRect = playerBox ? playerBox.getBoundingClientRect() : rect;
      const vw = window.innerWidth || document.documentElement.clientWidth || 360;
      const vh = window.innerHeight || document.documentElement.clientHeight || 780;
      let rightPx = Math.max(12, Math.round(vw - pRect.right + 8));
      if (vw - rightPx - 288 < 12) rightPx = 12;
      const bottomPx = Math.max(12, Math.round(vh - rect.top + 8));
      const availH = Math.max(180, Math.round(rect.top - 16));
      subMenu.style.right = rightPx + 'px';
      subMenu.style.bottom = bottomPx + 'px';
      subMenu.style.maxHeight = Math.min(380, availH) + 'px';
    }

    function showSeekFlash(direction, totalDelta) {
      const old = playerBox.querySelector('.seek-flash.' + direction);
      if (old) old.remove();
      const el = document.createElement('div');
      el.className = 'seek-flash ' + direction;
      const svg = direction === 'left'
        ? '<svg viewBox="0 0 24 24"><path d="M11 18V6l-8.5 6 8.5 6zm.5-6l8.5 6V6l-8.5 6z"/></svg>'
        : '<svg viewBox="0 0 24 24"><path d="M4 18l8.5-6L4 6v12zm9-12v12l8.5-6L13 6z"/></svg>';
      const label = totalDelta < 0 ? `−${Math.abs(totalDelta)}s` : `+${totalDelta}s`;
      el.innerHTML = svg + `<span>${label}</span>`;
      playerBox.appendChild(el);
      setTimeout(() => el.remove(), 650);
    }

    function seekBy(delta) {
      const maxT = isFinite(videoEl.duration) ? videoEl.duration : Infinity;
      videoEl.currentTime = Math.max(0, Math.min(maxT, videoEl.currentTime + delta));
      syncTimeUI();
      updateSubtitleFrame();
    }

    /* ---- Слушатели плеера ---- */
    window.__onNativeVideoSizeChanged = (w, h) => syncPlayerAspectRatio(w, h);
    window.__onNativeAudioTracksUpdated = () => scheduleAudioTracksRefresh(120);

    videoEl.addEventListener('play', updatePlayState);
    videoEl.addEventListener('pause', updatePlayState);
    videoEl.addEventListener('loadedmetadata', () => {
      syncTimeUI();
      syncPlayerAspectRatio(videoEl.videoWidth, videoEl.videoHeight);
    });
    videoEl.addEventListener('loadeddata', () => {
      updateOverlayGeometry();
      if (videoEl.paused && videoEl.currentTime === 0) {
        try { videoEl.currentTime = 0.1; } catch (_) {}
      }
    });
    videoEl.addEventListener('resize', () => syncPlayerAspectRatio(videoEl.videoWidth, videoEl.videoHeight));
    videoEl.addEventListener('seeked', () => {
      syncTimeUI();
      updateSubtitleFrame();
      if (videoEl.paused && videoEl.currentTime > 0 && videoEl.currentTime <= 0.5 && videoEl.poster === TRANSPARENT_POSTER) {
        try {
          if (videoEl.videoWidth > 0 && videoEl.videoHeight > 0) {
            const c = document.createElement('canvas');
            const scale = Math.min(1, 640 / videoEl.videoWidth);
            c.width = Math.max(1, Math.round(videoEl.videoWidth * scale));
            c.height = Math.max(1, Math.round(videoEl.videoHeight * scale));
            c.getContext('2d').drawImage(videoEl, 0, 0, c.width, c.height);
            const dataUrl = c.toDataURL('image/jpeg', 0.82);
            if (dataUrl && dataUrl.length > 64) videoEl.poster = dataUrl;
          }
        } catch (e) {
          diag.logWarn('player.posterCapture', e);
        }
      }
    });
    videoEl.addEventListener('error', () => {
      const b = bridgeMod.getBridge();
      if (b && !usingNativeExoFallback) {
        usingNativeExoFallback = true;
        if (window.__activateNativeExoFallback) window.__activateNativeExoFallback();
        return;
      }
      if (videoEl.error) {
        let msg = 'Playback error.';
        if (videoEl.error.code === 4) {
          msg = 'Browser does not support this video codec (e.g., MKV). Convert the file to MP4 (H.264).';
        }
        statusEl.innerText = msg;
      }
    });

    seekBar.addEventListener('input', () => {
      seekBar.dataset.dragging = 'true';
      seekBar.style.setProperty('--progress', seekBar.value + '%');
    });
    seekBar.addEventListener('change', () => {
      videoEl.currentTime = (seekBar.value / 100) * (videoEl.duration || 0);
      delete seekBar.dataset.dragging;
      syncTimeUI();
      updateSubtitleFrame();
    });

    fsBtn.addEventListener('click', (e) => {
      e.stopPropagation();
      const isFull = document.body.classList.contains('player-fullscreen-open') || Boolean(document.fullscreenElement || document.webkitFullscreenElement);
      const b = bridgeMod.getBridge();
      if (!isFull) {
        enterFullscreenPlayer();
        if (b && typeof b.toggleLandscape === 'function') {
          try { b.toggleLandscape(true); } catch (e) { diag.logWarn('native.landscapeEnter', e); }
        } else if (playerBox.requestFullscreen && !b) {
          playerBox.requestFullscreen().catch(() => {});
        }
      } else {
        exitFullscreenPlayer();
      }
    });

    settingsMenu.querySelectorAll('.yt-acc-header').forEach((hdr) => {
      hdr.addEventListener('click', (e) => {
        e.stopPropagation();
        const item = hdr.closest('.yt-acc-item');
        if (!item) return;
        const wasOpen = item.classList.contains('open');
        settingsMenu.querySelectorAll('.yt-acc-item.open').forEach((other) => other.classList.remove('open'));
        if (!wasOpen) {
          item.classList.add('open');
          setTimeout(() => {
            try { item.scrollIntoView({ block: 'nearest', behavior: 'smooth' }); } catch (_) {}
          }, 120);
        }
      });
    });

    window.addEventListener('resize', positionPlayerSettingsMenu);
    window.addEventListener('scroll', positionPlayerSettingsMenu, { passive: true });
    window.addEventListener('resize', positionPlayerSubMenu);
    window.addEventListener('scroll', positionPlayerSubMenu, { passive: true });
    window.addEventListener('resize', () => syncPlayerAspectRatio());
    window.addEventListener('orientationchange', () => setTimeout(() => syncPlayerAspectRatio(), 160));
    document.addEventListener('fullscreenchange', () => setTimeout(() => syncPlayerAspectRatio(), 80));
    if (window.ResizeObserver) {
      new ResizeObserver(() => {
        updateOverlayGeometry();
        if (window.__syncNativePlayerBounds) window.__syncNativePlayerBounds();
      }).observe(playerBox);
    }
    bottomEl.addEventListener('input', updateOverlayGeometry);

    settingsBtn.addEventListener('click', (e) => {
      e.stopPropagation();
      if (subMenu) subMenu.classList.remove('show');
      const willShow = !settingsMenu.classList.contains('show');
      settingsMenu.classList.toggle('show', willShow);
      settingsBtn.classList.toggle('open', willShow);
      if (willShow) positionPlayerSettingsMenu();
      showControls();
    });

    if (subMenuBtn && subMenu) {
      subMenuBtn.addEventListener('click', (e) => {
        e.stopPropagation();
        settingsMenu.classList.remove('show');
        settingsBtn.classList.remove('open');
        const willShow = !subMenu.classList.contains('show');
        subMenu.classList.toggle('show', willShow);
        if (willShow) positionPlayerSubMenu();
        showControls();
      });
    }

    document.addEventListener('click', (e) => {
      if (settingsMenu.classList.contains('show')
        && !settingsMenu.contains(e.target)
        && !settingsBtn.contains(e.target)) {
        settingsMenu.classList.remove('show');
        settingsBtn.classList.remove('open');
        showControls();
      }
      if (subMenu && subMenu.classList.contains('show')
        && !subMenu.contains(e.target)
        && (!subMenuBtn || !subMenuBtn.contains(e.target))) {
        subMenu.classList.remove('show');
        showControls();
      }
    });

    playerBox.addEventListener('mousemove', showControls);
    playerBox.addEventListener('touchstart', showControls, { passive: true });

    let tapTimer = null;
    let lastTapSide = null;
    let seekAccum = 0;
    let firstTapPlayState = null;

    function replayCurrentCue() {
      const cues = getCues();
      if (!cues.length) return false;
      const cue = (currentCueIdx >= 0 && cues[currentCueIdx]) ? cues[currentCueIdx] : (lastValidCue || cues[0]);
      if (!cue) return false;
      const offsetSec = (parseInt(subOffsetEl.value, 10) || 0) / 1000;
      videoEl.currentTime = Math.max(0, cue.start + offsetSec);
      syncTimeUI();
      updateSubtitleFrame();
      if (videoEl.paused) {
        videoEl.play().catch(() => {});
        showPlayFlash(true);
      }
      return true;
    }

    if (repeatLineBtn) {
      repeatLineBtn.addEventListener('click', (e) => {
        e.stopPropagation();
        replayCurrentCue();
        showControls();
      });
    }

    if (shadowSpeedBtn) {
      shadowSpeedBtn.addEventListener('click', (e) => {
        e.stopPropagation();
        const isSlow = Math.abs((videoEl.playbackRate || 1) - 0.75) < 0.05;
        setPlaybackSpeed(isSlow ? 1.0 : 0.75);
        showControls();
      });
    }

    playerBox.addEventListener('click', (e) => {
      if (controlsEl.contains(e.target) || settingsMenu.contains(e.target) || (subMenu && subMenu.contains(e.target)) || (e.target.closest && e.target.closest('#subtitleText .word.tappable'))) return;
      const rect = playerBox.getBoundingClientRect();
      const x = e.clientX - rect.left;
      const side = x < rect.width / 2 ? 'left' : 'right';

      if (tapTimer && lastTapSide === side) {
        clearTimeout(tapTimer);
        tapTimer = null;

        if (firstTapPlayState === 'paused' && !videoEl.paused) {
          videoEl.pause();
          showPlayFlash(false);
        } else if (firstTapPlayState === 'playing' && videoEl.paused) {
          videoEl.play().catch(() => {});
          showPlayFlash(true);
        }

        const step = side === 'left' ? -10 : 10;
        seekAccum += step;
        seekBy(step);
        showSeekFlash(side, seekAccum);

        firstTapPlayState = null;
        tapTimer = setTimeout(() => {
          tapTimer = null;
          lastTapSide = null;
          seekAccum = 0;
        }, 380);
        return;
      }

      if (tapTimer) {
        clearTimeout(tapTimer);
        tapTimer = null;
        seekAccum = 0;
      }

      lastTapSide = side;
      firstTapPlayState = videoEl.paused ? 'paused' : 'playing';
      togglePlay();
      showControls();

      tapTimer = setTimeout(() => {
        tapTimer = null;
        lastTapSide = null;
        firstTapPlayState = null;
        seekAccum = 0;
      }, 280);
    });

    document.addEventListener('keydown', (e) => {
      if (!playerActive) return;
      const tag = document.activeElement && document.activeElement.tagName;
      if (['INPUT', 'SELECT', 'TEXTAREA'].includes(tag)) return;

      if (e.code === 'Space') {
        e.preventDefault();
        togglePlay();
        showControls();
      } else if (e.code === 'ArrowRight') {
        e.preventDefault();
        seekBy(5);
        showControls();
      } else if (e.code === 'ArrowLeft') {
        e.preventDefault();
        seekBy(-5);
        showControls();
      } else if (e.code === 'Escape') {
        e.preventDefault();
        exitFullscreenPlayer();
      } else if (e.code === 'KeyF') {
        e.preventDefault();
        if (!document.fullscreenElement) playerBox.requestFullscreen?.();
        else document.exitFullscreen?.();
      }
    });

    return {
      setupPlayer,
      updateOverlayGeometry,
      updateSubtitleFrame,
      syncTimeUI,
      togglePlay,
      replayCurrentCue,
      setPlaybackSpeed,
      getPlaybackSpeed: () => Number(videoEl.playbackRate) || 1,
      getCurrentCue: () => {
        const cues = getCues();
        return (currentCueIdx >= 0 && cues[currentCueIdx]) ? cues[currentCueIdx] : lastValidCue;
      },
      formatTime,
      isPlayerActive: () => playerActive,
      resetOverlayState() {
        wordSpans = [];
        currentCueIdx = -2;
        lastValidCue = null;
        currentOrigLine = '';
        boxVisible = false;
        lastTimeLabel = '';
        lastSeekPct = -1;
        document.body?.classList.remove('player-playing');
        subtitleEl.innerHTML = '';
        subtitleEl.style.opacity = '0';
        if (secondarySubtitleEl) {
          secondarySubtitleEl.innerHTML = '';
          secondarySubtitleEl.style.opacity = '0';
          secondarySubtitleEl.classList.remove('has-dual-text');
        }
        if (overlayEl) overlayEl.classList.remove('has-secondary');
      },
      resetCueCursor() {
        currentCueIdx = -2;
      }
    };
  }

  root.player = { createPlayerController };
})();
