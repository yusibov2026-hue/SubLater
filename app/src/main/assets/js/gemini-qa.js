(function (root, factory) {
  const api = factory(root.SubLyter);
  if (typeof module !== 'undefined' && module.exports) {
    module.exports = api;
  }
  root.SubLyter = root.SubLyter || {};
  root.SubLyter.gemini = api;
})(typeof globalThis !== 'undefined' ? globalThis : window, function (subLyterRoot) {
  'use strict';

  const FALLBACK_MODELS = [
    'gemini-4-hydrogen',
    'gemini-4-argon',
    'gemini-3.8-flash',
    'gemini-2.5-flash',
    'gemini-2.0-flash'
  ];

  const VISUAL_QUESTION_RE = /\b(frame|screen|see|look|show|color|wear|wearing|written|read|scene|image|picture|photo|appear|happening|on\s+screen)\b|(?:кадр|экран|видиш|видно|показ|цвет|одет|написан|картинк|изображ|сцен|сейчас\s+в)/i;

  function formatInlineMarkdown(escapedLine) {
    return escapedLine
      .replace(/`([^`\n]+)`/g, '<code>$1</code>')
      .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
      .replace(/(^|[^\*])\*([^\*\n]+)\*(?!\*)/g, '$1<em>$2</em>')
      .replace(/\[?(\d{1,2}:\d{2}(?::\d{2})?)\]?/g, (match, tc) => {
        const parts = tc.split(':').map(Number);
        const sec = parts.length === 3
          ? parts[0] * 3600 + parts[1] * 60 + parts[2]
          : parts[0] * 60 + parts[1];
        if (!isFinite(sec)) return match;
        return `<button type="button" class="chapter-time" data-sec="${sec}">${tc}</button>`;
      });
  }

  function formatBotMessageHTML(rawText) {
    const escaped = String(rawText || '')
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#39;');

    return escaped
      .split(/\n{2,}/)
      .map((block) => {
        const lines = block.split('\n');
        const isBulletList = lines.length > 0 && lines.every((l) => /^\s*[-*•]\s+/.test(l));
        if (isBulletList) {
          const items = lines
            .map((l) => `<li>${formatInlineMarkdown(l.replace(/^\s*[-*•]\s+/, ''))}</li>`)
            .join('');
          return `<ul>${items}</ul>`;
        }
        return `<p>${lines.map((l) => formatInlineMarkdown(l)).join('<br>')}</p>`;
      })
      .join('');
  }

  function shouldAttachVideoFrame(questionText, forceVisual = false) {
    if (forceVisual) return true;
    return VISUAL_QUESTION_RE.test(String(questionText || ''));
  }

  function buildTranscriptContext(cues, formatTimeFn, maxChars = 120000) {
    const list = Array.isArray(cues) ? cues : [];
    if (!list.length) {
      return 'Subtitles have not been generated yet or the video has no subtitles.';
    }
    const fmt = typeof formatTimeFn === 'function' ? formatTimeFn : (s) => `${Math.round(Number(s) || 0)}s`;
    const lines = list.map((c) => `[${fmt(c.start)}] ${String(c.text || '').trim()}`);
    const full = lines.join('\n');
    if (full.length <= maxChars) return full;

    const avgLen = Math.max(24, Math.ceil(full.length / lines.length));
    const maxLines = Math.max(40, Math.floor(maxChars / avgLen));
    const step = Math.max(2, Math.ceil(lines.length / maxLines));
    const sampled = [];
    for (let i = 0; i < lines.length; i += step) {
      sampled.push(lines[i]);
    }
    return sampled.join('\n').slice(0, maxChars);
  }

  function buildGeminiRequestBody(modelId, systemPrompt, contents) {
    const genCfg = { temperature: 0.35 };
    if (String(modelId || '').startsWith('gemini-4')) {
      genCfg.thinkingConfig = {
        thinkingLevel: 'LOW',
        includeThoughts: false
      };
    }
    return {
      systemInstruction: { parts: [{ text: systemPrompt }] },
      contents,
      generationConfig: genCfg
    };
  }

  function extractCandidateText(data) {
    const parts = (data && data.candidates && data.candidates[0] && data.candidates[0].content && data.candidates[0].content.parts) || [];
    if (!parts.length) return '';
    const nonThought = parts
      .filter((p) => p && !p.thought && typeof p.text === 'string')
      .map((p) => p.text)
      .join('');
    if (nonThought) return nonThought;
    return parts
      .filter((p) => p && typeof p.text === 'string')
      .map((p) => p.text)
      .join('');
  }

  function createController(deps) {
    const {
      aiChatList,
      aiQuestionInput,
      aiSendBtn,
      clearAiChatBtn,
      aiQuickPrompts,
      aiSummaryBox,
      aiActiveModelBadge,
      geminiKeyInput,
      geminiModelSelect,
      videoEl,
      playerBox,
      getCues,
      getAiOutputLang,
      formatTime,
      syncTimeUI,
      updateSubtitleFrame,
      togglePlay,
      openSettings,
      getGeminiKeyFromKeystore
    } = deps;

    const diag = (subLyterRoot && subLyterRoot.diagnostics) || { logWarn() {}, logError() {} };
    const bridgeMod = (subLyterRoot && subLyterRoot.bridge) || null;
    const groqMod = (subLyterRoot && subLyterRoot.groq) || null;
    const translate = (k, vars) => {
      if (typeof deps.t === 'function') return deps.t(k, vars);
      const i18n = subLyterRoot && subLyterRoot.i18n;
      return i18n && typeof i18n.t === 'function' ? i18n.t(k, vars) : k;
    };

    let aiChatHistory = [];
    let aiBusy = false;
    let activeAiAbort = null;
    const SEND_ICON_SVG = '<svg viewBox="0 0 24 24"><path d="M2.01 21L23 12 2.01 3 2 10l15 2-15 2z"/></svg>';
    const STOP_ICON_SVG = '<svg viewBox="0 0 24 24"><rect x="6" y="6" width="12" height="12" rx="2.5"/></svg>';

    function setSendButtonStreaming(streaming) {
      if (!aiSendBtn) return;
      aiSendBtn.classList.toggle('is-streaming', streaming);
      aiSendBtn.innerHTML = streaming ? STOP_ICON_SVG : SEND_ICON_SVG;
      aiSendBtn.disabled = false;
    }

    function getGeminiKeys() {
      const currentVal = (geminiKeyInput && geminiKeyInput.value.trim()) || getGeminiKeyFromKeystore();
      return String(currentVal || '').split(/[\s,;]+/).map((k) => k.trim()).filter(Boolean);
    }

    async function captureCurrentVideoFrameBase64() {
      try {
        if (bridgeMod && typeof bridgeMod.callNativeAsync === 'function') {
          const nativeFrame = await bridgeMod.callNativeAsync('requestCurrentFrameAsync', 'getCurrentFrameBase64', 2500);
          if (nativeFrame && nativeFrame.length > 32) return nativeFrame;
        }
        if (!videoEl || videoEl.readyState < 2 || !videoEl.videoWidth) return null;
        const canvas = document.createElement('canvas');
        const scale = Math.min(1, 512 / videoEl.videoWidth);
        canvas.width = Math.max(1, Math.round(videoEl.videoWidth * scale));
        canvas.height = Math.max(1, Math.round(videoEl.videoHeight * scale));
        const ctx = canvas.getContext('2d');
        ctx.drawImage(videoEl, 0, 0, canvas.width, canvas.height);
        const dataUrl = canvas.toDataURL('image/jpeg', 0.72);
        return dataUrl.split(',')[1] || null;
      } catch (e) {
        diag.logWarn('gemini.captureFrame', e);
        return null;
      }
    }

    function appendAiMessage(role, text, isHtml = false) {
      const msg = document.createElement('div');
      msg.className = 'ai-msg ' + (role === 'user' ? 'user' : 'bot');
      if (isHtml) msg.innerHTML = text;
      else if (role === 'bot') msg.innerHTML = formatBotMessageHTML(text);
      else msg.textContent = text;
      aiChatList.appendChild(msg);
      aiChatList.scrollTop = aiChatList.scrollHeight;
      return msg;
    }

    function cancelActiveRequest() {
      if (activeAiAbort) {
        try { activeAiAbort.abort(); } catch (_) {}
        activeAiAbort = null;
      }
    }

    function resetChat(silent = false) {
      cancelActiveRequest();
      aiChatHistory = [];
      const text = silent ? translate('ai.greeting') : translate('ai.cleared');
      const greetingEl = document.createElement('div');
      greetingEl.className = 'ai-msg bot';
      greetingEl.id = 'aiSummaryText';
      if (silent) greetingEl.setAttribute('data-i18n', 'ai.greeting');
      greetingEl.textContent = text;
      aiChatList.innerHTML = '';
      aiChatList.appendChild(greetingEl);
    }

    function updateActiveBadgeLabel(modelId) {
      if (!aiActiveModelBadge) return;
      if (geminiModelSelect) {
        const opt = Array.from(geminiModelSelect.options).find((o) => o.value === modelId);
        if (opt) {
          aiActiveModelBadge.textContent = opt.textContent;
          return;
        }
      }
      aiActiveModelBadge.textContent = modelId;
    }

    async function callModelStreamingOrJson(apiKey, modelId, requestBody, signal, onChunkText, allowThinkingFallback = true) {
      const doFetch = groqMod && typeof groqMod.fetchWithTimeout === 'function'
        ? (url, opts, timeoutMs) => groqMod.fetchWithTimeout(url, opts, timeoutMs, signal)
        : (url, opts) => fetch(url, Object.assign({}, opts, { signal }));

      const headers = {
        'Content-Type': 'application/json',
        'x-goog-api-key': apiKey
      };

      const shouldRetryWithoutThinking = (status, errMsg) => (
        allowThinkingFallback &&
        status === 400 &&
        requestBody &&
        requestBody.generationConfig &&
        requestBody.generationConfig.thinkingConfig &&
        /thinking|thinkingConfig|thinkingLevel|INVALID_ARGUMENT/i.test(String(errMsg || ''))
      );

      const retryWithoutThinking = () => {
        const fallbackBody = Object.assign({}, requestBody, {
          generationConfig: Object.assign({}, requestBody.generationConfig)
        });
        delete fallbackBody.generationConfig.thinkingConfig;
        return callModelStreamingOrJson(apiKey, modelId, fallbackBody, signal, onChunkText, false);
      };

      const streamUrl = `https://generativelanguage.googleapis.com/v1beta/models/${encodeURIComponent(modelId)}:streamGenerateContent?alt=sse`;
      try {
        const res = await doFetch(streamUrl, {
          method: 'POST',
          headers,
          body: JSON.stringify(requestBody)
        }, 45000);

        if (res.ok && res.body && typeof res.body.getReader === 'function') {
          const reader = res.body.getReader();
          const decoder = new TextDecoder('utf-8');
          let buffer = '';
          let fullText = '';

          while (true) {
            const { value, done } = await reader.read();
            if (done) break;
            buffer += decoder.decode(value, { stream: true });
            const lines = buffer.split('\n');
            buffer = lines.pop() || '';
            for (const rawLine of lines) {
              const line = rawLine.trim();
              if (!line.startsWith('data:')) continue;
              const payload = line.slice(5).trim();
              if (!payload || payload === '[DONE]') continue;
              try {
                const parsed = JSON.parse(payload);
                const piece = extractCandidateText(parsed);
                if (piece) {
                  fullText += piece;
                  onChunkText(fullText);
                }
              } catch (_) {}
            }
          }
          if (fullText.trim()) return { ok: true, text: fullText.trim() };
        } else if (!res.ok) {
          const errJson = await res.json().catch(() => ({}));
          const errMsg = (errJson && errJson.error && errJson.error.message) || `HTTP ${res.status}`;
          if (shouldRetryWithoutThinking(res.status, errMsg)) {
            return retryWithoutThinking();
          }
          return { ok: false, status: res.status, message: errMsg };
        }
      } catch (e) {
        if ((signal && signal.aborted) || (e && e.name === 'AbortError')) throw e;
        diag.logWarn('gemini.sseFallback', e);
      }

      const jsonUrl = `https://generativelanguage.googleapis.com/v1beta/models/${encodeURIComponent(modelId)}:generateContent`;
      const res = await doFetch(jsonUrl, {
        method: 'POST',
        headers,
        body: JSON.stringify(requestBody)
      }, 45000);

      if (res.ok) {
        const data = await res.json();
        const txt = extractCandidateText(data).trim();
        if (txt) {
          onChunkText(txt);
          return { ok: true, text: txt };
        }
        return { ok: false, status: 500, message: 'Empty response from Gemini.' };
      }

      const errJson = await res.json().catch(() => ({}));
      const errMsg = (errJson && errJson.error && errJson.error.message) || `HTTP ${res.status}`;
      if (shouldRetryWithoutThinking(res.status, errMsg)) {
        return retryWithoutThinking();
      }
      return { ok: false, status: res.status, message: errMsg };
    }

    async function callGeminiVideoQA(questionText, forceVisual = false) {
      const geminiKeys = getGeminiKeys();
      if (!geminiKeys.length) {
        appendAiMessage(
          'bot',
          `${translate('ai.needKey')}<br><button type="button" class="ai-clear-btn open-gemini-settings-btn" style="margin-top:8px;">⚙️ ${translate('ai.openSettings')}</button>`,
          true
        );
        return;
      }

      cancelActiveRequest();
      const ctrl = new AbortController();
      activeAiAbort = ctrl;
      aiBusy = true;
      setSendButtonStreaming(true);

      appendAiMessage('user', questionText);
      const loadingBubble = appendAiMessage(
        'bot',
        `<span class="ai-typing-row"><span class="ai-typing-dots"><i></i><i></i><i></i></span><span>${translate('ai.thinking')}</span></span>`,
        true
      );

      let partialRenderedText = '';
      let renderScheduled = false;
      const scheduleChunkPaint = (fullText) => {
        partialRenderedText = fullText;
        if (renderScheduled) return;
        renderScheduled = true;
        const flush = () => {
          renderScheduled = false;
          loadingBubble.innerHTML = formatBotMessageHTML(partialRenderedText);
          aiChatList.scrollTop = aiChatList.scrollHeight;
        };
        if (typeof requestAnimationFrame === 'function') requestAnimationFrame(flush);
        else setTimeout(flush, 16);
      };

      try {
        const transcript = buildTranscriptContext(getCues ? getCues() : [], formatTime);
        const curTimeStr = formatTime(videoEl.currentTime || 0);
        const framePromise = shouldAttachVideoFrame(questionText, forceVisual)
          ? captureCurrentVideoFrameBase64()
          : Promise.resolve(null);

        const outLang = typeof getAiOutputLang === 'function' ? getAiOutputLang() : 'English';
        const systemPrompt =
          'You are a smart in-player video assistant (like YouTube "Ask about this video"). ' +
          `Reply clearly, concisely, and helpfully in ${outLang} (matching the user question language if clearer). ` +
          'When referencing moments from the video, ALWAYS include clickable timestamps in [MM:SS] format. ' +
          `Current player timestamp: [${curTimeStr}].\n\nVideo subtitles:\n${transcript}`;

        const contents = [];
        aiChatHistory.slice(-8).forEach((turn) => {
          contents.push({
            role: turn.role === 'user' ? 'user' : 'model',
            parts: [{ text: turn.text }]
          });
        });

        const frameB64 = await framePromise;
        const userParts = [{ text: questionText }];
        if (frameB64) {
          userParts.push({
            inlineData: {
              mimeType: 'image/jpeg',
              data: frameB64
            }
          });
        }
        contents.push({ role: 'user', parts: userParts });

        const selectedModel = (geminiModelSelect && geminiModelSelect.value) || 'gemini-4-hydrogen';
        const candidateModels = [selectedModel];
        FALLBACK_MODELS.forEach((m) => {
          if (!candidateModels.includes(m)) candidateModels.push(m);
        });

        let answerText = null;
        let lastErr = 'Failed to get response from Gemini API.';

        for (const apiKey of geminiKeys) {
          for (const modelId of candidateModels) {
            if (ctrl.signal.aborted) throw new Error('Cancelled by user.');
            const body = buildGeminiRequestBody(modelId, systemPrompt, contents);
            const result = await callModelStreamingOrJson(apiKey, modelId, body, ctrl.signal, scheduleChunkPaint);
            if (result.ok) {
              answerText = result.text;
              updateActiveBadgeLabel(modelId);
              break;
            }
            lastErr = result.message || lastErr;
            if ((result.status === 400 && /API_KEY_INVALID|API key not valid/i.test(lastErr)) || result.status === 403) {
              break;
            }
          }
          if (answerText) break;
        }

        if (!answerText) throw new Error(lastErr);

        aiChatHistory.push({ role: 'user', text: questionText });
        aiChatHistory.push({ role: 'model', text: answerText });
        loadingBubble.innerHTML = formatBotMessageHTML(answerText);
        aiChatList.scrollTop = aiChatList.scrollHeight;
      } catch (e) {
        if (ctrl.signal.aborted && (!e || e.name === 'AbortError' || e.message === 'Cancelled by user.')) {
          if (partialRenderedText) {
            loadingBubble.innerHTML = formatBotMessageHTML(partialRenderedText);
          } else {
            loadingBubble.innerHTML = formatBotMessageHTML('⏹');
          }
        } else {
          diag.logError('gemini.qa', e);
          loadingBubble.innerHTML = formatBotMessageHTML('Gemini API error: ' + (e.message || e));
        }
      } finally {
        if (activeAiAbort === ctrl) activeAiAbort = null;
        aiBusy = false;
        setSendButtonStreaming(false);
      }
    }

    function submitAiQuestion() {
      if (aiBusy) {
        cancelActiveRequest();
        return;
      }
      const q = aiQuestionInput.value.trim();
      if (!q) return;
      aiQuestionInput.value = '';
      callGeminiVideoQA(q, false);
    }

    aiChatList.addEventListener('click', (e) => {
      const timeBtn = e.target.closest('.chapter-time');
      if (timeBtn && timeBtn.dataset.sec !== undefined) {
        const sec = Math.max(0, Number(timeBtn.dataset.sec) || 0);
        videoEl.currentTime = sec;
        syncTimeUI();
        updateSubtitleFrame();
        playerBox.scrollIntoView({ behavior: 'smooth', block: 'center' });
        if (videoEl.paused) togglePlay();
        return;
      }
      const openCfgBtn = e.target.closest('.open-gemini-settings-btn');
      if (openCfgBtn) {
        openSettings();
        setTimeout(() => {
          try { geminiKeyInput.scrollIntoView({ behavior: 'smooth', block: 'center' }); } catch (_) {}
          try { geminiKeyInput.focus(); } catch (_) {}
        }, 200);
      }
    });

    const aiChatForm = aiQuestionInput && aiQuestionInput.closest ? aiQuestionInput.closest('form') : null;
    clearAiChatBtn.addEventListener('click', () => resetChat(false));
    if (aiChatForm) {
      aiChatForm.addEventListener('submit', (e) => {
        e.preventDefault();
        submitAiQuestion();
      });
    } else {
      aiSendBtn.addEventListener('click', submitAiQuestion);
    }
    aiQuestionInput.addEventListener('touchstart', (e) => e.stopPropagation(), { passive: true });
    aiQuestionInput.addEventListener('keydown', (e) => {
      if (e.key === 'Enter' || e.keyCode === 13 || e.which === 13) {
        e.preventDefault();
        submitAiQuestion();
      }
    });
    aiQuickPrompts.addEventListener('click', (e) => {
      const chip = e.target.closest('.ai-prompt-chip');
      if (!chip || aiBusy) return;
      const q = chip.dataset.q;
      const isVisual = chip.dataset.visual === '1';
      if (q) callGeminiVideoQA(q, isVisual);
    });

    return {
      generateVideoAiInsights() {
        aiSummaryBox.classList.add('show');
        aiSummaryBox.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
      },
      resetChat,
      cancelActiveRequest
    };
  }

  return {
    formatBotMessageHTML,
    shouldAttachVideoFrame,
    buildTranscriptContext,
    buildGeminiRequestBody,
    extractCandidateText,
    createController
  };
});
