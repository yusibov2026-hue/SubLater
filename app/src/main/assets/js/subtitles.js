(function (root, factory) {
  const api = factory();
  if (typeof module !== 'undefined' && module.exports) {
    module.exports = api;
  }
  root.SubLyter = root.SubLyter || {};
  root.SubLyter.subtitles = api;
})(typeof globalThis !== 'undefined' ? globalThis : window, function () {
  'use strict';

  const LANG_CODES = {
    Russian: 'ru',
    English: 'en',
    Turkish: 'tr',
    Azerbaijani: 'az',
    Arabic: 'ar',
    Armenian: 'hy',
    Belarusian: 'be',
    Bengali: 'bn',
    Bulgarian: 'bg',
    Catalan: 'ca',
    'Chinese (Simplified)': 'zh',
    'Chinese (Traditional)': 'zh-TW',
    Croatian: 'hr',
    Czech: 'cs',
    Danish: 'da',
    Dutch: 'nl',
    Estonian: 'et',
    Filipino: 'tl',
    Finnish: 'fi',
    French: 'fr',
    Georgian: 'ka',
    German: 'de',
    Greek: 'el',
    Hebrew: 'he',
    Hindi: 'hi',
    Hungarian: 'hu',
    Indonesian: 'id',
    Italian: 'it',
    Japanese: 'ja',
    Kazakh: 'kk',
    Korean: 'ko',
    Kyrgyz: 'ky',
    Latvian: 'lv',
    Lithuanian: 'lt',
    Malay: 'ms',
    Norwegian: 'no',
    Persian: 'fa',
    Polish: 'pl',
    Portuguese: 'pt',
    Romanian: 'ro',
    Serbian: 'sr',
    Slovak: 'sk',
    Slovenian: 'sl',
    Spanish: 'es',
    Swahili: 'sw',
    Swedish: 'sv',
    Tajik: 'tg',
    Tamil: 'ta',
    Thai: 'th',
    Ukrainian: 'uk',
    Urdu: 'ur',
    Uzbek: 'uz',
    Vietnamese: 'vi'
  };

  const HALLUCINATION_REGEX = /(субтитры\s+(сделал|создал|добавил|подготовил|писал)|редактор\s+субтитров|корректор\s+|dimatorzok|продолжение\s+следует|amara\.org|подписывайтесь\s+на\s+канал|ставь(те)?\s+лайк)/i;

  function isWhisperHallucination(seg, enabled = true) {
    const text = String((seg && seg.text) || '').trim();
    if (!text) return true;
    if (!enabled) return false;
    if (HALLUCINATION_REGEX.test(text)) return true;
    if (typeof seg.no_speech_prob === 'number' && seg.no_speech_prob > 0.85 && (seg.avg_logprob < -0.8 || text.length < 16)) {
      return true;
    }
    if (typeof seg.compression_ratio === 'number' && seg.compression_ratio > 2.6) {
      return true;
    }
    return false;
  }

  function parseTimeCode(str) {
    const p = String(str || '').trim().replace(',', '.').split(':');
    if (p.length === 3) return (+p[0]) * 3600 + (+p[1]) * 60 + (+p[2]);
    if (p.length === 2) return (+p[0]) * 60 + (+p[1]);
    return 0;
  }

  function distributeWords(cue) {
    const words = String(cue.text || '').split(/\s+/).filter(Boolean);
    cue.words = [];
    if (!words.length) return;

    const start = Math.max(0, Number(cue.start) || 0);
    const end = Math.max(start + 0.1, Number(cue.end) || 0);
    const dur = end - start;

    const weights = words.map((w) => {
      const core = w.replace(/[^\p{L}\p{N}]/gu, '');
      return Math.max(2, core.length);
    });
    const total = weights.reduce((a, b) => a + b, 0) || 1;

    let acc = 0;
    cue.words = words.map((w, i) => {
      const ws = start + (acc / total) * dur;
      acc += weights[i];
      const we = start + (acc / total) * dur;
      return { word: w, start: ws, end: we };
    });
  }

  function parseSubtitleText(text) {
    const blocks = String(text || '').replace(/\r\n?/g, '\n').split(/\n\n+/);
    const parsedCues = [];
    for (const b of blocks) {
      const lines = b.trim().split('\n');
      const arrowIdx = lines.findIndex((l) => l.includes('-->'));
      if (arrowIdx === -1) continue;
      const [sStr, eStr] = lines[arrowIdx].split('-->');
      const start = parseTimeCode(sStr);
      const end = parseTimeCode((eStr || '').trim().split(/\s+/)[0]);
      const cueText = lines.slice(arrowIdx + 1).join(' ').replace(/<[^>]+>/g, '').trim();
      if (cueText && end > start) {
        const cue = { start, end, text: cueText, words: [] };
        distributeWords(cue);
        parsedCues.push(cue);
      }
    }
    return parsedCues;
  }

  function smartSplitLongCues(cueList, maxWords = 14, enabled = true) {
    if (!enabled) return cueList;
    const out = [];

    for (const cue of cueList) {
      if (!cue.words || cue.words.length <= maxWords) {
        out.push(cue);
        continue;
      }

      let currentWords = [];
      for (let i = 0; i < cue.words.length; i++) {
        const w = cue.words[i];
        currentWords.push(w);

        const isLast = i === cue.words.length - 1;
        const nextW = !isLast ? cue.words[i + 1] : null;
        const gap = nextW ? (nextW.start - w.end) : 0;
        const hasStrongPunct = /[.!?…]$/.test(w.word);
        const hasSoftPunct = /[,;:—–-]$/.test(w.word);

        const shouldSplit = !isLast && (
          (currentWords.length >= 6 && hasStrongPunct) ||
          (currentWords.length >= 9 && (hasSoftPunct || gap >= 0.35)) ||
          currentWords.length >= maxWords
        );

        if (shouldSplit || isLast) {
          out.push({
            start: currentWords[0].start,
            end: Math.max(currentWords[currentWords.length - 1].end, currentWords[0].start + 0.05),
            text: currentWords.map((x) => x.word).join(' '),
            words: currentWords
          });
          currentWords = [];
        }
      }
    }
    return out;
  }

  /** Выравнивание слов по сегментам за O(W + S) двумя указателями вместо O(W * S). */
  function buildCues(rawSegments, rawWords, smartSplitEnabled = true) {
    const segs = (rawSegments || [])
      .map((s) => ({
        start: Number(s.start) || 0,
        end: Number(s.end) || 0,
        text: String(s.text || '').trim(),
        words: []
      }))
      .filter((c) => c.text && c.end > c.start)
      .sort((a, b) => a.start - b.start);

    if (!segs.length) return [];

    const words = (rawWords || [])
      .map((w) => {
        const start = Number(w.start);
        let end = Number(w.end);
        const text = String(w.word || '').trim();
        if (!text || !isFinite(start) || !isFinite(end)) return null;
        if (!(end > start)) end = start + 0.08;
        return { word: text, start, end };
      })
      .filter(Boolean)
      .sort((a, b) => a.start - b.start);

    if (words.length) {
      const distToSeg = (mid, seg) => (mid < seg.start ? seg.start - mid : (mid > seg.end ? mid - seg.end : 0));
      let segIdx = 0;
      for (const w of words) {
        const mid = (w.start + w.end) / 2;
        while (segIdx + 1 < segs.length && segs[segIdx].end < mid && distToSeg(mid, segs[segIdx + 1]) <= distToSeg(mid, segs[segIdx])) {
          segIdx++;
        }
        let bestIdx = segIdx;
        let bestDist = distToSeg(mid, segs[segIdx]);
        // Проверяем соседний сегмент слева на случай граничных слов
        if (segIdx > 0) {
          const prevDist = distToSeg(mid, segs[segIdx - 1]);
          if (prevDist < bestDist) {
            bestDist = prevDist;
            bestIdx = segIdx - 1;
          }
        }
        if (bestDist <= 2.5) {
          segs[bestIdx].words.push(w);
        }
      }
    }

    let result = segs.map((c) => {
      if (c.words.length) {
        c.words.sort((a, b) => a.start - b.start);
        return {
          start: c.words[0].start,
          end: Math.max(c.words[c.words.length - 1].end, c.words[0].start + 0.05),
          text: c.words.map((w) => w.word).join(' '),
          words: c.words
        };
      }
      const cue = { start: c.start, end: c.end, text: c.text, words: [] };
      distributeWords(cue);
      return cue;
    });

    result = smartSplitLongCues(result, 14, smartSplitEnabled);
    result.sort((a, b) => a.start - b.start);

    for (let i = 0; i < result.length - 1; i++) {
      if (result[i].end > result[i + 1].start) {
        result[i].end = Math.max(result[i].start + 0.03, result[i + 1].start - 0.001);
      }
    }

    return result.filter((c) => c.text && c.words.length);
  }

  function getCueWindow(cues, i, offsetSec, holdSec) {
    const startTime = Math.max(0, cues[i].start + offsetSec);
    let endTime = cues[i].end + offsetSec;
    if (i === cues.length - 1) {
      endTime += Math.max(0.6, holdSec);
    } else {
      const nextStart = Math.max(0, cues[i + 1].start + offsetSec);
      endTime = Math.min(endTime + holdSec, nextStart);
    }
    return { startTime, endTime };
  }

  /** Быстрый поиск активной реплики: O(1) по курсору hintIdx + O(log N) бинарный поиск. */
  function findActiveCueIndex(cues, t, offsetSec = 0, holdSec = 0, hintIdx = -1) {
    const len = cues ? cues.length : 0;
    if (!len) return -1;

    if (hintIdx >= 0 && hintIdx < len) {
      const cur = getCueWindow(cues, hintIdx, offsetSec, holdSec);
      if (t >= cur.startTime && t < cur.endTime) return hintIdx;
      if (hintIdx + 1 < len) {
        const nxt = getCueWindow(cues, hintIdx + 1, offsetSec, holdSec);
        if (t >= nxt.startTime && t < nxt.endTime) return hintIdx + 1;
      }
    }

    let lo = 0;
    let hi = len - 1;
    let candidate = -1;
    while (lo <= hi) {
      const mid = (lo + hi) >>> 1;
      const startTime = Math.max(0, cues[mid].start + offsetSec);
      if (startTime <= t) {
        candidate = mid;
        lo = mid + 1;
      } else {
        hi = mid - 1;
      }
    }
    if (candidate < 0) return -1;
    const win = getCueWindow(cues, candidate, offsetSec, holdSec);
    return (t >= win.startTime && t < win.endTime) ? candidate : -1;
  }

  function mergeChunkResults(orderedItems) {
    const allSegments = [];
    const allWords = [];
    for (let idx = 0; idx < orderedItems.length; idx++) {
      const item = orderedItems[idx];
      if (!item) continue;
      const cPos = Number(item.pos) || 0;
      const cCutoff = Number(item.cutoff);
      const cNextCutoff = Number(item.nextCutoff);

      (item.segments || []).forEach((seg) => {
        const absMid = (seg.start + seg.end) / 2 + cPos;
        if (absMid < cCutoff || absMid >= cNextCutoff) return;
        allSegments.push({
          start: seg.start + cPos,
          end: seg.end + cPos,
          text: seg.text
        });
      });

      (item.words || []).forEach((w) => {
        const absMid = (w.start + w.end) / 2 + cPos;
        if (absMid < cCutoff || absMid >= cNextCutoff) return;
        allWords.push({
          word: w.word,
          start: w.start + cPos,
          end: w.end + cPos
        });
      });
    }
    return { allSegments, allWords };
  }

  function cloneCues(cueList) {
    return (cueList || []).map((c) => ({
      start: c.start,
      end: c.end,
      text: c.text,
      words: (c.words || []).map((w) => ({ word: w.word, start: w.start, end: w.end }))
    }));
  }

  const timeVTT = (sec) => {
    const ms = Math.max(0, Math.round(sec * 1000));
    return `${String(Math.floor(ms / 3600000)).padStart(2, '0')}:${String(Math.floor((ms % 3600000) / 60000)).padStart(2, '0')}:${String(Math.floor((ms % 60000) / 1000)).padStart(2, '0')}.${String(ms % 1000).padStart(3, '0')}`;
  };
  const timeSRT = (sec) => timeVTT(sec).replace('.', ',');
  const timeASS = (sec) => {
    const cs = Math.max(0, Math.round(sec * 100));
    return `${Math.floor(cs / 360000)}:${String(Math.floor((cs % 360000) / 6000)).padStart(2, '0')}:${String(Math.floor((cs % 6000) / 100)).padStart(2, '0')}.${String(cs % 100).padStart(2, '0')}`;
  };

  function hexToASS(hex, alphaHex = '00') {
    const clean = String(hex || '#ffffff').replace('#', '');
    if (clean.length !== 6) return `&H${alphaHex}FFFFFF`;
    const r = clean.substring(0, 2).toUpperCase();
    const g = clean.substring(2, 4).toUpperCase();
    const b = clean.substring(4, 6).toUpperCase();
    return `&H${alphaHex}${b}${g}${r}`;
  }

  function generateVTT(cueArr) {
    return 'WEBVTT\n\n' + cueArr.map((c) => `${timeVTT(c.start)} --> ${timeVTT(c.end)}\n${c.text.replace(/-->/g, '—>')}\n`).join('\n');
  }

  function generateSRT(cueArr) {
    return cueArr.map((c, i) => `${i + 1}\n${timeSRT(c.start)} --> ${timeSRT(c.end)}\n${c.text.replace(/-->/g, '—>')}\n`).join('\n');
  }

  function generateASS(cueArr, styleOpts = {}) {
    const cleanFont = 'Inter';
    const fontSize = Math.round((parseInt(styleOpts.size, 10) || 18) * 3.2);
    const marginV = Math.round((parseInt(styleOpts.bottom, 10) || 12) * 10.8);
    const primaryCol = hexToASS(styleOpts.textColor || '#ffffff', '00');
    const secondaryCol = hexToASS(styleOpts.accentColor || '#1b8ef2', '55');
    const opacityVal = styleOpts.bgOpacity !== undefined ? (parseInt(styleOpts.bgOpacity, 10) || 62) : 62;
    const bgEnabled = styleOpts.bgEnabled !== false;
    const backAlpha = bgEnabled
      ? Math.round(((100 - opacityVal) / 100) * 255).toString(16).padStart(2, '0').toUpperCase()
      : 'FF';
    const backCol = `&H${backAlpha}000000`;

    let out = `[Script Info]\nScriptType: v4.00+\nPlayResX: 1920\nPlayResY: 1080\nWrapStyle: 0\nScaledBorderAndShadow: yes\n\n[V4+ Styles]\nFormat: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding\nStyle: Default,${cleanFont},${fontSize},${primaryCol},${secondaryCol},&H00000000,${backCol},-1,0,0,0,100,100,0,0,1,3,0,2,40,40,${marginV},1\n\n[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n`;
    cueArr.forEach((c) => {
      let prevEnd = c.start;
      const txt = (c.words || []).map((w) => {
        const gapCs = Math.max(0, Math.round((w.start - prevEnd) * 100));
        const durCs = Math.max(1, Math.round((w.end - w.start) * 100));
        prevEnd = Math.max(prevEnd, w.end);
        const gapTag = gapCs > 0 ? `{\\k${gapCs}}` : '';
        const cleanWord = String(w.word).replace(/\r?\n/g, '\\N');
        return `${gapTag}{\\kf${durCs}}${cleanWord} `;
      }).join('');
      out += `Dialogue: 0,${timeASS(c.start)},${timeASS(c.end)},Default,,0,0,0,,${txt.trim()}\n`;
    });
    return out;
  }

  function generateTXT(cueArr) {
    return cueArr.map((c) => c.text).join('\n');
  }

  return {
    LANG_CODES,
    isWhisperHallucination,
    parseTimeCode,
    parseSubtitleText,
    distributeWords,
    smartSplitLongCues,
    buildCues,
    findActiveCueIndex,
    mergeChunkResults,
    cloneCues,
    timeVTT,
    timeSRT,
    timeASS,
    hexToASS,
    generateVTT,
    generateSRT,
    generateASS,
    generateTXT
  };
});
