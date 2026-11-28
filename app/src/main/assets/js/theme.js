(function () {
  'use strict';

  const root = window.SubLyter = window.SubLyter || {};
  const diag = root.diagnostics || { logWarn() {} };
  const getBridge = () => (root.bridge ? root.bridge.getBridge() : (window.AndroidBridge || window.ExoPlayerManager || null));

  const COLOR_PRESETS = [
    '#ffffff', '#000000', '#9ca3af', '#ef4444', '#f97316', '#f59e0b',
    '#eab308', '#84cc16', '#22c55e', '#10b981', '#14b8a6', '#06b6d4',
    '#0ea5e9', '#1b8ef2', '#3b82f6', '#6366f1', '#8b5cf6', '#a855f7', '#ec4899', '#f43f5e'
  ];
  const DEFAULT_ACCENT = '#1b8ef2';
  const M3_SEED_PRESETS = [
    '#ffffff', '#1b8ef2', '#06b6d4', '#006a6a', '#22c55e', '#386a20',
    '#eab308', '#f97316', '#b3261e', '#ec4899', '#984061', '#6750a4'
  ];
  const GRAY_THEMES = {
    'dark-gray':   { main: '#161922', surface: '#202532', elevated: '#1b202c', border: '#31384a', baseL: 10 },
    'gray':        { main: '#242936', surface: '#2f3545', elevated: '#282e3d', border: '#41495e', baseL: 15 },
    'light-slate': { main: '#2f3646', surface: '#3b4357', elevated: '#343b4d', border: '#4e5870', baseL: 20 },
    'warm-gray':   { main: '#211f26', surface: '#2c2933', elevated: '#26242d', border: '#3e3a47', baseL: 13 }
  };

  function cpClamp(v, min, max) { return Math.min(max, Math.max(min, v)); }

  function cpHexToHsl(hex) {
    const m = /^#?([0-9a-f]{6})$/i.exec((hex || '').trim());
    if (!m) return null;
    const n = parseInt(m[1], 16);
    const r = ((n >> 16) & 255) / 255;
    const g = ((n >> 8) & 255) / 255;
    const b = (n & 255) / 255;
    const max = Math.max(r, g, b);
    const min = Math.min(r, g, b);
    const d = max - min;
    let h = 0;
    let s = 0;
    const l = (max + min) / 2;
    if (d) {
      s = l > 0.5 ? d / (2 - max - min) : d / (max + min);
      if (max === r) h = (g - b) / d + (g < b ? 6 : 0);
      else if (max === g) h = (b - r) / d + 2;
      else h = (r - g) / d + 4;
      h *= 60;
    }
    return { h, s: s * 100, l: l * 100 };
  }

  function cpHslToHex(h, s, l) {
    h = ((h % 360) + 360) % 360;
    s = cpClamp(s, 0, 100) / 100;
    l = cpClamp(l, 0, 100) / 100;
    const c = (1 - Math.abs(2 * l - 1)) * s;
    const x = c * (1 - Math.abs((h / 60) % 2 - 1));
    const m = l - c / 2;
    let r = 0, g = 0, b = 0;
    if (h < 60) { r = c; g = x; }
    else if (h < 120) { r = x; g = c; }
    else if (h < 180) { g = c; b = x; }
    else if (h < 240) { g = x; b = c; }
    else if (h < 300) { r = x; b = c; }
    else { r = c; b = x; }
    const toHex = (v) => Math.round((v + m) * 255).toString(16).padStart(2, '0');
    return '#' + toHex(r) + toHex(g) + toHex(b);
  }

  function resolveIsLightM3Mode(mode) {
    if (mode === 'light') return true;
    if (mode === 'dark') return false;
    const bridge = getBridge();
    if (bridge && typeof bridge.isSystemDarkMode === 'function') {
      try {
        return !bridge.isSystemDarkMode();
      } catch (e) {
        diag.logWarn('theme.systemDark', e);
      }
    }
    return !(window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches);
  }

  function getReadableOnAccent(hex) {
    const m = /^#?([0-9a-f]{6})$/i.exec((hex || '').trim());
    if (!m) return '#041016';
    const n = parseInt(m[1], 16);
    const r = (n >> 16) & 255;
    const g = (n >> 8) & 255;
    const b = n & 255;
    const luma = (0.299 * r + 0.587 * g + 0.114 * b) / 255;
    return luma > 0.56 ? '#0a0f18' : '#ffffff';
  }

  function isWhiteColorHex(hex) {
    const hsl = cpHexToHsl(hex);
    return Boolean(hsl && (hsl.l >= 93 || (hsl.s < 12 && hsl.l >= 84)));
  }

  function syncM3RangeSlider(el) {
    if (!el) return;
    const min = parseFloat(el.min || '0');
    const max = parseFloat(el.max || '100');
    const val = parseFloat(el.value || String(min));
    const pct = max > min ? Math.min(100, Math.max(0, ((val - min) / (max - min)) * 100)) : 0;
    el.style.setProperty('--range-pct', pct.toFixed(1) + '%');
  }

  function syncAllM3Sliders() {
    document
      .querySelectorAll('#globalSettings input[type="range"]:not(.cp-h):not(.cp-s):not(.cp-l), #settingsMenu input[type="range"]')
      .forEach(syncM3RangeSlider);
  }

  function initThemeModule(storageMod) {
    const $ = (id) => document.getElementById(id);
    const colorPickerByNative = new WeakMap();
    const colorPickers = {};

    function initColorPicker(cpRoot) {
      if (!cpRoot) return null;
      const native = cpRoot.querySelector('.cp-native');
      const swatch = cpRoot.querySelector('.cp-swatch');
      const hexInput = cpRoot.querySelector('.cp-hex');
      const hEl = cpRoot.querySelector('.cp-h');
      const sEl = cpRoot.querySelector('.cp-s');
      const lEl = cpRoot.querySelector('.cp-l');
      const hv = cpRoot.querySelector('.cp-h-val');
      const sv = cpRoot.querySelector('.cp-s-val');
      const lv = cpRoot.querySelector('.cp-l-val');
      const presetsWrap = cpRoot.querySelector('.cp-presets');
      const toggle = cpRoot.id ? document.querySelector('.cp-toggle[data-cp-target="' + cpRoot.id + '"]') : null;
      let h = 0, s = 100, l = 50;

      if (presetsWrap) {
        COLOR_PRESETS.forEach((color) => {
          const btn = document.createElement('button');
          btn.type = 'button';
          btn.className = 'cp-preset';
          btn.dataset.color = color;
          btn.title = color;
          btn.style.background = color;
          btn.addEventListener('click', () => setHex(color, true));
          presetsWrap.appendChild(btn);
        });
      }

      function paint(emit) {
        const hex = cpHslToHex(h, s, l);
        cpRoot.style.setProperty('--cp-color', hex);
        if (swatch) swatch.style.background = hex;
        if (toggle) {
          toggle.style.setProperty('--cp-toggle-color', hex);
          const dot = toggle.querySelector('.cp-toggle-dot');
          if (dot) dot.style.background = hex;
        }
        if (hexInput) hexInput.value = hex;
        if (hEl) hEl.value = Math.round(h);
        if (sEl) sEl.value = Math.round(s);
        if (lEl) lEl.value = Math.round(l);
        if (hv) hv.textContent = Math.round(h);
        if (sv) sv.textContent = Math.round(s);
        if (lv) lv.textContent = Math.round(l);
        cpRoot.querySelectorAll('.cp-preset').forEach((b) => {
          b.classList.toggle('active', (b.dataset.color || '').toLowerCase() === hex.toLowerCase());
        });
        if (emit && native) {
          native.value = hex;
          native.dispatchEvent(new Event('input', { bubbles: true }));
          native.dispatchEvent(new Event('change', { bubbles: true }));
        }
      }

      function setHex(hex, emit) {
        const hsl = cpHexToHsl(hex);
        if (!hsl) return;
        h = hsl.h;
        s = hsl.s;
        l = hsl.l;
        paint(emit);
      }

      function syncFromNative(emit) {
        if (native && native.value) setHex(native.value, emit);
        else paint(emit);
      }

      [hEl, sEl, lEl].forEach((el, idx) => {
        if (!el) return;
        el.addEventListener('input', () => {
          if (idx === 0) h = parseFloat(hEl.value) || 0;
          if (idx === 1) s = parseFloat(sEl.value) || 0;
          if (idx === 2) l = parseFloat(lEl.value) || 0;
          paint(true);
        });
      });
      if (hexInput) {
        hexInput.addEventListener('change', () => setHex(hexInput.value, true));
        hexInput.addEventListener('keydown', (e) => {
          if (e.key === 'Enter') {
            e.preventDefault();
            setHex(hexInput.value, true);
            hexInput.blur();
          }
        });
      }
      if (toggle) {
        toggle.addEventListener('click', () => {
          const open = cpRoot.classList.toggle('open');
          toggle.setAttribute('aria-expanded', open ? 'true' : 'false');
          if (open) syncFromNative(false);
        });
      }

      const controller = { root: cpRoot, native, syncFromNative, setHex };
      if (native) colorPickerByNative.set(native, controller);
      if (cpRoot.dataset.picker) colorPickers[cpRoot.dataset.picker] = controller;
      syncFromNative(false);
      return controller;
    }

    document.querySelectorAll('.color-picker').forEach(initColorPicker);

    function syncColorPickers(exceptNative = null) {
      document.querySelectorAll('.cp-native').forEach((native) => {
        if (exceptNative && native === exceptNative) return;
        const ctrl = colorPickerByNative.get(native);
        if (ctrl) ctrl.syncFromNative(false);
      });
    }

    const accentColorEl = $('accentColor');
    const m3SeedSwatchesEl = $('m3SeedSwatches');
    const m3ThemeModeEl = $('m3ThemeMode');
    const m3ThemeModeBtns = document.querySelectorAll('#m3ThemeModeGroup .m3-seg-btn');
    const savedAccent = storageMod.safeGetStorage('sublater_accent', DEFAULT_ACCENT);
    accentColorEl.value = savedAccent;
    if (colorPickers.accent) colorPickers.accent.syncFromNative(false);
    document.documentElement.style.setProperty('--accent', savedAccent);

    const appThemeBgEl = $('appThemeBg');
    const appWallpaperEl = $('appWallpaper');
    const accentWallpaperSelect = $('accentWallpaper');
    const wallpaperOpacityInput = $('wallpaperOpacity');
    const wallpaperOpacityRow = $('wallpaperOpacityRow');
    const wallpaperColorInput = $('wallpaperColor');
    const wallpaperFollowAccentInput = $('wallpaperFollowAccent');
    const wallpaperColorRow = $('wallpaperColorRow');
    const wallpaperPickerRoot = $('wallpaperPicker');
    const wallpaperPickerToggle = document.querySelector('.cp-toggle[data-cp-target="wallpaperPicker"]');

    function setWallpaperOpacityMax() {
      if (wallpaperOpacityInput) {
        wallpaperOpacityInput.value = '100';
        syncM3RangeSlider(wallpaperOpacityInput);
      }
      if (accentWallpaperSelect) {
        applyAccentWallpaper(accentWallpaperSelect.value, 100);
      }
    }

    function syncNativeSurfaceHex(mainBg) {
      if (!mainBg) return;
      const meta = $('themeColorMeta');
      if (meta) meta.setAttribute('content', mainBg);
      const bridge = getBridge();
      if (!bridge) return;
      if (typeof bridge.setNativeSurfaceColor === 'function') {
        try { bridge.setNativeSurfaceColor(mainBg); } catch (e) { diag.logWarn('theme.nativeSurface', e); }
      } else if (typeof bridge.setStatusBarColor === 'function') {
        try { bridge.setStatusBarColor(mainBg); } catch (e) { diag.logWarn('theme.statusBar', e); }
      }
    }

    function applyMaterialYouSettingsTheme() {
      const seedHex = (accentColorEl && accentColorEl.value) ? accentColorEl.value : DEFAULT_ACCENT;
      const grayKey = (appThemeBgEl && appThemeBgEl.value) ? appThemeBgEl.value : 'dark-gray';
      const mode = m3ThemeModeEl ? (m3ThemeModeEl.value || 'system') : 'system';
      const isLight = resolveIsLightM3Mode(mode);
      const hsl = cpHexToHsl(seedHex) || { h: 210, s: 88, l: 53 };
      const h = hsl.h;
      const isAchromatic = hsl.s < 10;
      const isWhiteSeed = isAchromatic && hsl.l >= 82;
      const nHue = isAchromatic ? 222 : h;
      const pSat = isAchromatic ? 0 : Math.min(94, Math.max(32, hsl.s));
      const secHue = (nHue + 14) % 360;
      const themeCfg = GRAY_THEMES[grayKey] || GRAY_THEMES['dark-gray'];
      const baseL = themeCfg.baseL;
      const nSat = isAchromatic ? 0 : Math.min(18, Math.max(8, pSat * 0.16));
      const lSat = isAchromatic ? 0 : Math.min(26, Math.max(11, pSat * 0.26));

      let tokens;
      if (!isLight) {
        const effectiveAccent = seedHex;
        const onAccent = getReadableOnAccent(effectiveAccent);
        const chevronStroke = onAccent === '#ffffff' ? '%23ffffff' : '%230a0f18';
        tokens = {
          '--accent': effectiveAccent,
          '--on-accent': onAccent,
          '--on-accent-chevron': "url(\"data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='none' stroke='" + chevronStroke + "' stroke-width='2.3' stroke-linecap='round' stroke-linejoin='round'%3E%3Cpolyline points='6 9 12 15 18 9'/%3E%3C/svg%3E\")",
          '--bg-main': themeCfg.main,
          '--bg-surface': themeCfg.surface,
          '--bg-elevated': themeCfg.elevated,
          '--border-soft': themeCfg.border,
          '--text-muted': '#9aa4b8',
          '--md-primary': isWhiteSeed ? '#ffffff' : cpHslToHex(h, Math.min(95, Math.max(40, pSat)), 71),
          '--md-on-primary': isWhiteSeed ? '#11131a' : cpHslToHex(h, Math.min(95, pSat), 14),
          '--md-primary-container': isWhiteSeed ? '#343946' : cpHslToHex(h, Math.min(82, Math.max(32, pSat * 0.78)), 28),
          '--md-on-primary-container': isWhiteSeed ? '#ffffff' : cpHslToHex(h, Math.min(95, pSat), 92),
          '--md-secondary-container': isWhiteSeed ? '#2b303c' : cpHslToHex(secHue, Math.min(54, Math.max(22, pSat * 0.48)), Math.min(34, baseL + 20)),
          '--md-on-secondary-container': isWhiteSeed ? '#f1f4fa' : cpHslToHex(secHue, Math.min(84, pSat * 0.84), 92),
          '--md-surface-container-low': cpHslToHex(nHue, nSat, baseL + 3),
          '--md-surface-container': cpHslToHex(nHue, isAchromatic ? 0 : nSat + 2, baseL + 8),
          '--md-surface-container-high': cpHslToHex(nHue, isAchromatic ? 0 : nSat + 3, baseL + 13),
          '--md-surface-container-highest': cpHslToHex(nHue, isAchromatic ? 0 : nSat + 4, baseL + 18),
          '--md-on-surface': cpHslToHex(nHue, isAchromatic ? 0 : Math.min(16, nSat + 6), 93),
          '--md-on-surface-variant': cpHslToHex(nHue, isAchromatic ? 0 : Math.min(16, nSat + 6), 75),
          '--md-outline': cpHslToHex(nHue, isAchromatic ? 0 : 12, 56),
          '--md-outline-variant': cpHslToHex(nHue, isAchromatic ? 0 : 14, baseL + 21),
          '--md-slider-track': cpHslToHex(nHue, isAchromatic ? 0 : Math.min(18, nSat + 4), Math.min(42, baseL + 24)),
          '--md-error-container': '#93000a',
          '--md-on-error-container': '#ffdad6'
        };
      } else {
        const effectiveAccent = isWhiteSeed
          ? '#1e222b'
          : (hsl.l > 54 ? cpHslToHex(h, Math.min(92, Math.max(38, hsl.s)), 42) : seedHex);
        const onAccent = getReadableOnAccent(effectiveAccent);
        const chevronStroke = onAccent === '#ffffff' ? '%23ffffff' : '%230a0f18';
        tokens = {
          '--accent': effectiveAccent,
          '--on-accent': onAccent,
          '--on-accent-chevron': "url(\"data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='none' stroke='" + chevronStroke + "' stroke-width='2.3' stroke-linecap='round' stroke-linejoin='round'%3E%3Cpolyline points='6 9 12 15 18 9'/%3E%3C/svg%3E\")",
          '--bg-main': isAchromatic ? '#e8ecf2' : cpHslToHex(nHue, Math.min(18, lSat + 4), 91),
          '--bg-surface': '#ffffff',
          '--bg-elevated': isAchromatic ? '#dfe4ec' : cpHslToHex(nHue, Math.min(20, lSat + 5), 87),
          '--border-soft': isAchromatic ? '#b9c3d2' : cpHslToHex(nHue, Math.min(22, lSat + 6), 74),
          '--text-muted': '#3b4758',
          '--md-primary': isWhiteSeed ? '#1b202b' : cpHslToHex(h, Math.min(92, Math.max(44, pSat)), 38),
          '--md-on-primary': '#ffffff',
          '--md-primary-container': isWhiteSeed ? '#d2d8e4' : cpHslToHex(h, Math.min(88, pSat * 0.85), 82),
          '--md-on-primary-container': isWhiteSeed ? '#111620' : cpHslToHex(h, Math.min(95, pSat), 14),
          '--md-secondary-container': isWhiteSeed ? '#cdd5e3' : cpHslToHex(secHue, Math.min(60, Math.max(24, pSat * 0.50)), 80),
          '--md-on-secondary-container': isWhiteSeed ? '#111620' : cpHslToHex(secHue, Math.max(30, pSat * 0.72), 14),
          '--md-surface-container-low': cpHslToHex(nHue, lSat, 93),
          '--md-surface-container': cpHslToHex(nHue, isAchromatic ? 0 : lSat + 3, 87),
          '--md-surface-container-high': cpHslToHex(nHue, isAchromatic ? 0 : lSat + 5, 81),
          '--md-surface-container-highest': cpHslToHex(nHue, isAchromatic ? 0 : lSat + 6, 73),
          '--md-on-surface': cpHslToHex(nHue, isAchromatic ? 0 : Math.min(20, lSat + 4), 10),
          '--md-on-surface-variant': cpHslToHex(nHue, isAchromatic ? 0 : Math.min(18, lSat + 4), 28),
          '--md-outline': cpHslToHex(nHue, isAchromatic ? 0 : 14, 42),
          '--md-outline-variant': cpHslToHex(nHue, isAchromatic ? 0 : 16, 68),
          '--md-slider-track': cpHslToHex(nHue, isAchromatic ? 0 : Math.min(24, lSat + 6), 68),
          '--md-error-container': '#ffdad6',
          '--md-on-error-container': '#410002'
        };
      }

      const styleRoot = document.documentElement.style;
      Object.keys(tokens).forEach((k) => styleRoot.setProperty(k, tokens[k]));
      document.body.dataset.m3Mode = isLight ? 'light' : 'dark';
      syncNativeSurfaceHex(tokens['--bg-main']);
      if (m3SeedSwatchesEl) {
        const activeHex = seedHex.toLowerCase();
        m3SeedSwatchesEl.querySelectorAll('.m3-seed-chip').forEach((chip) => {
          chip.classList.toggle('active', (chip.dataset.seed || '').toLowerCase() === activeHex);
        });
      }
      syncAllM3Sliders();
    }

    if (m3SeedSwatchesEl) {
      M3_SEED_PRESETS.forEach((seed) => {
        const chip = document.createElement('button');
        chip.type = 'button';
        chip.className = 'm3-seed-chip';
        chip.dataset.seed = seed;
        chip.title = seed;
        chip.style.background = seed;
        chip.addEventListener('click', () => {
          accentColorEl.value = seed;
          accentColorEl.dispatchEvent(new Event('input', { bubbles: true }));
          if (colorPickers.accent) colorPickers.accent.syncFromNative(false);
        });
        m3SeedSwatchesEl.appendChild(chip);
      });
    }

    const savedM3Mode = storageMod.safeGetStorage('sublater_m3_theme_mode', 'system');
    if (m3ThemeModeEl) {
      m3ThemeModeEl.value = ['dark', 'light', 'system'].includes(savedM3Mode) ? savedM3Mode : 'system';
      m3ThemeModeBtns.forEach((b) => b.classList.toggle('active', b.dataset.themeMode === m3ThemeModeEl.value));
      m3ThemeModeBtns.forEach((b) => {
        b.addEventListener('click', () => {
          const val = b.dataset.themeMode || 'system';
          m3ThemeModeEl.value = val;
          m3ThemeModeBtns.forEach((btn) => btn.classList.toggle('active', btn === b));
          storageMod.safeSetStorage('sublater_m3_theme_mode', val);
          applyMaterialYouSettingsTheme();
          if (val === 'light') setWallpaperOpacityMax();
        });
      });
    }
    if (window.matchMedia) {
      try {
        window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', () => {
          if (m3ThemeModeEl && m3ThemeModeEl.value === 'system') applyMaterialYouSettingsTheme();
        });
      } catch (e) {
        diag.logWarn('theme.matchMedia', e);
      }
    }

    const savedGrayTheme = storageMod.safeGetStorage('sublater_gray_theme', 'dark-gray');
    if (appThemeBgEl) {
      appThemeBgEl.value = GRAY_THEMES[savedGrayTheme] ? savedGrayTheme : 'dark-gray';
      storageMod.safeSetStorage('sublater_gray_theme', appThemeBgEl.value);
      applyMaterialYouSettingsTheme();
      appThemeBgEl.addEventListener('change', () => {
        storageMod.safeSetStorage('sublater_gray_theme', appThemeBgEl.value);
        applyMaterialYouSettingsTheme();
      });
    } else {
      applyMaterialYouSettingsTheme();
    }

    /* ---- Шрифт и размер заголовка SubLyter ---- */
    const DEFAULT_TITLE_FONT = "'Outfit', 'Inter', sans-serif";
    const DEFAULT_TITLE_SIZE = 34;
    const titleFontFamilyEl = $('titleFontFamily');
    const titleFontSizeEl = $('titleFontSize');
    const titleFontSizeValEl = $('titleFontSizeVal');

    function applyTitleTypography(fontCss, sizePx, persist = true) {
      const font = fontCss || DEFAULT_TITLE_FONT;
      const size = Math.min(38, Math.max(18, parseInt(sizePx, 10) || DEFAULT_TITLE_SIZE));
      document.documentElement.style.setProperty('--title-font', font);
      document.documentElement.style.setProperty('--title-size', size + 'px');
      if (titleFontSizeValEl) titleFontSizeValEl.textContent = size + 'px';
      if (persist) {
        storageMod.safeSetStorage('sublater_title_font', font);
        storageMod.safeSetStorage('sublater_title_size', String(size));
      }
    }

    if (storageMod.safeGetStorage('sublater_title_size_v34', '') !== '1') {
      storageMod.safeSetStorage('sublater_title_size', String(DEFAULT_TITLE_SIZE));
      storageMod.safeSetStorage('sublater_title_size_v34', '1');
    }

    const savedTitleFont = storageMod.safeGetStorage('sublater_title_font', DEFAULT_TITLE_FONT);
    const savedTitleSize = parseInt(storageMod.safeGetStorage('sublater_title_size', String(DEFAULT_TITLE_SIZE)), 10) || DEFAULT_TITLE_SIZE;
    if (titleFontFamilyEl) {
      const hasFontOpt = Array.from(titleFontFamilyEl.options).some((o) => o.value === savedTitleFont);
      titleFontFamilyEl.value = hasFontOpt ? savedTitleFont : DEFAULT_TITLE_FONT;
      titleFontFamilyEl.addEventListener('change', () => {
        applyTitleTypography(titleFontFamilyEl.value, titleFontSizeEl ? titleFontSizeEl.value : savedTitleSize, true);
      });
    }
    if (titleFontSizeEl) {
      titleFontSizeEl.value = Math.min(38, Math.max(18, savedTitleSize));
      syncM3RangeSlider(titleFontSizeEl);
      titleFontSizeEl.addEventListener('input', () => {
        syncM3RangeSlider(titleFontSizeEl);
        applyTitleTypography(titleFontFamilyEl ? titleFontFamilyEl.value : savedTitleFont, titleFontSizeEl.value, true);
      });
    }
    applyTitleTypography(titleFontFamilyEl ? titleFontFamilyEl.value : savedTitleFont, titleFontSizeEl ? titleFontSizeEl.value : savedTitleSize, false);

    /* ---- Обои и снежинки ---- */
    function applyWallpaperColor() {
      const follow = wallpaperFollowAccentInput ? wallpaperFollowAccentInput.checked : true;
      const accentNow = (accentColorEl && accentColorEl.value) ? accentColorEl.value : DEFAULT_ACCENT;
      const customColor = (wallpaperColorInput && wallpaperColorInput.value) ? wallpaperColorInput.value : accentNow;
      const styleRoot = document.documentElement.style;
      if (follow) {
        styleRoot.removeProperty('--wallpaper-color');
      } else {
        styleRoot.setProperty('--wallpaper-color', customColor);
      }
      if (wallpaperPickerRoot) {
        wallpaperPickerRoot.style.display = follow ? 'none' : '';
        if (follow) {
          wallpaperPickerRoot.classList.remove('open');
          if (wallpaperPickerToggle) wallpaperPickerToggle.setAttribute('aria-expanded', 'false');
        }
      }
      if (wallpaperPickerToggle) wallpaperPickerToggle.style.display = follow ? 'none' : 'inline-flex';
      storageMod.safeSetStorage('sublater_wallpaper_follow_accent', follow ? '1' : '0');
      storageMod.safeSetStorage('sublater_wallpaper_color', customColor);
    }

    function syncSnowflakes(isSnow) {
      if (!appWallpaperEl) return;
      if (!isSnow) {
        if (appWallpaperEl.firstChild) appWallpaperEl.innerHTML = '';
        return;
      }
      if (appWallpaperEl.querySelector('.sf')) return;
      const frag = document.createDocumentFragment();
      const cols = 6;
      const rows = 13;
      const total = cols * rows;
      for (let i = 0; i < total; i++) {
        const outer = document.createElement('span');
        outer.className = 'sf';
        const col = i % cols;
        const row = Math.floor(i / cols);
        const h1 = Math.sin((i + 1) * 12.9898) * 43758.5453;
        const h2 = Math.sin((i + 1) * 78.233) * 24634.6345;
        const h3 = Math.sin((i + 1) * 45.164) * 19283.7191;
        const r1 = h1 - Math.floor(h1);
        const r2 = h2 - Math.floor(h2);
        const r3 = h3 - Math.floor(h3);

        const cellW = 100 / cols;
        const x = (col * cellW + (0.14 + r1 * 0.72) * cellW).toFixed(1);
        const phase = (row + 0.12 + r2 * 0.76) / rows;
        const sz = (2.6 + r3 * 2.2).toFixed(1);
        const dur = (16.5 + r1 * 5.5).toFixed(2);
        const delay = (-phase * parseFloat(dur)).toFixed(2);
        const drift = Math.round(-22 + r2 * 44);
        const sway = (7 + r3 * 12).toFixed(1);
        const swayDur = (4.2 + r1 * 3.8).toFixed(2);
        const swayDelay = (-(r2 * parseFloat(swayDur))).toFixed(2);
        const op = (0.42 + r3 * 0.48).toFixed(2);
        outer.style.setProperty('--x', x + '%');
        outer.style.setProperty('--sz', sz + 'px');
        outer.style.setProperty('--dur', dur + 's');
        outer.style.setProperty('--delay', delay + 's');
        outer.style.setProperty('--drift', drift + 'px');
        outer.style.setProperty('--sway', sway + 'px');
        outer.style.setProperty('--sway-dur', swayDur + 's');
        outer.style.setProperty('--sway-delay', swayDelay + 's');
        outer.style.setProperty('--op', op);
        const inner = document.createElement('i');
        inner.className = 'sfi';
        outer.appendChild(inner);
        frag.appendChild(outer);
      }
      appWallpaperEl.appendChild(frag);
    }

    function applyAccentWallpaper(wallpaperKey, opacityVal) {
      const key = wallpaperKey || 'snow';
      const op = opacityVal !== undefined ? opacityVal : 45;
      if (appWallpaperEl) {
        appWallpaperEl.setAttribute('data-wallpaper', key);
        appWallpaperEl.style.setProperty('--wallpaper-opacity', (op / 100).toString());
        syncSnowflakes(key === 'snow');
      }
      if (wallpaperOpacityRow) wallpaperOpacityRow.style.display = key === 'none' ? 'none' : 'flex';
      if (wallpaperColorRow) wallpaperColorRow.style.display = key === 'none' ? 'none' : 'flex';
      storageMod.safeSetStorage('sublater_accent_wallpaper', key);
      storageMod.safeSetStorage('sublater_wallpaper_opacity', op.toString());
    }

    const savedWallpaper = storageMod.safeGetStorage('sublater_accent_wallpaper', 'snow');
    const rawSavedOpacity = storageMod.safeGetStorage('sublater_wallpaper_opacity', '');
    const savedFollowAccent = storageMod.safeGetStorage('sublater_wallpaper_follow_accent', '1') !== '0';
    const savedWallpaperColor = storageMod.safeGetStorage('sublater_wallpaper_color', '');
    const activeStartWallColor = savedFollowAccent
      ? ((accentColorEl && accentColorEl.value) || DEFAULT_ACCENT)
      : (savedWallpaperColor || ((accentColorEl && accentColorEl.value) || DEFAULT_ACCENT));
    const savedWallpaperOpacity = rawSavedOpacity !== ''
      ? (parseInt(rawSavedOpacity, 10) || 45)
      : ((document.body.dataset.m3Mode === 'light' || isWhiteColorHex(activeStartWallColor)) ? 100 : 45);

    if (accentWallpaperSelect) {
      accentWallpaperSelect.value = savedWallpaper;
      if (accentWallpaperSelect.value !== savedWallpaper) accentWallpaperSelect.value = 'snow';
      if (wallpaperOpacityInput) {
        wallpaperOpacityInput.value = savedWallpaperOpacity;
        syncM3RangeSlider(wallpaperOpacityInput);
      }
      if (wallpaperFollowAccentInput) wallpaperFollowAccentInput.checked = savedFollowAccent;
      if (wallpaperColorInput) {
        wallpaperColorInput.value = savedWallpaperColor || ((accentColorEl && accentColorEl.value) || DEFAULT_ACCENT);
      }
      if (colorPickers.wallpaper) colorPickers.wallpaper.syncFromNative(false);
      applyWallpaperColor();
      applyAccentWallpaper(accentWallpaperSelect.value, savedWallpaperOpacity);

      accentWallpaperSelect.addEventListener('change', () => {
        const op = wallpaperOpacityInput ? parseInt(wallpaperOpacityInput.value, 10) : 45;
        applyAccentWallpaper(accentWallpaperSelect.value, op);
      });
      if (wallpaperOpacityInput) {
        wallpaperOpacityInput.addEventListener('input', () => {
          syncM3RangeSlider(wallpaperOpacityInput);
          applyAccentWallpaper(accentWallpaperSelect.value, parseInt(wallpaperOpacityInput.value, 10));
        });
      }
      if (wallpaperColorInput) {
        wallpaperColorInput.addEventListener('input', () => {
          if (wallpaperFollowAccentInput) wallpaperFollowAccentInput.checked = false;
          applyWallpaperColor();
          if (isWhiteColorHex(wallpaperColorInput.value)) setWallpaperOpacityMax();
        });
      }
      if (wallpaperFollowAccentInput) {
        wallpaperFollowAccentInput.addEventListener('change', () => {
          applyWallpaperColor();
          if (wallpaperFollowAccentInput.checked && accentColorEl && isWhiteColorHex(accentColorEl.value)) {
            setWallpaperOpacityMax();
          }
        });
      }
    }

    return {
      DEFAULT_ACCENT,
      isWhiteColorHex,
      setWallpaperOpacityMax,
      syncColorPickers,
      syncM3RangeSlider,
      syncAllM3Sliders,
      applyMaterialYouSettingsTheme,
      wallpaperFollowAccentInput
    };
  }

  root.theme = {
    cpHexToHsl,
    cpHslToHex,
    initThemeModule
  };
})();
