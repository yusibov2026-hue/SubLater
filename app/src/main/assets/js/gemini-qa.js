(function (root, factory) {
  const api = factory(root.SubLyter);
  if (typeof module !== 'undefined' && module.exports) {
    module.exports = api;
  }
  root.SubLyter = root.SubLyter || {};
  root.SubLyter.gemini = api;
})(typeof globalThis !== 'undefined' ? globalThis : window, function (subLyterRoot) {
  'use strict';

  function formatBotMessageHTML(rawText) {
    const escaped = String(rawText || '')
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#39;');
    const withBold = escaped.replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>');
    const withTimestamps = withBold.replace(/\[?(\d{1,2}:\d{2}(?::\d{2})?)\]?/g, (match, tc) => {
      const parts = tc.split(':').map(Number);
      const sec = parts.length === 3
        ? parts[0] * 3600 + parts[1] * 60 + parts[2]
        : parts[0] * 60 + parts[1];
      if (!isFinite(sec)) return match;
      return `<button type="button" class="chapter-time" data-sec="${sec}">${tc}</button>`;
    });
    return withTimestamps
      .split(/\n{2,}/)
      .map((p) => `<p>${p.replace(/\n/g, '<br>')}</p>`)
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
      geminiKeyInput,
      geminiModelSelect,
      videoEl,
      playerBox,
      getCues,
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

    let aiChatHistory = [];
    let aiBusy = false;
    let activeAiAbort = null;

    function getGeminiKeys() {
      const currentVal = (geminiKeyInput && geminiKeyInput.value.trim()) || getGeminiKeyFromKeystore();
      return String(currentVal || '').split(/[\s,;]+/).map((k) => k.trim()).filter(Boolean);
    }

    async function captureCurrentVideoFrameBase64() {
      try {
        if (bridgeMod && typeof bridgeMod.callNativeAsync === 'function') {
          const nativeFrame = await bridgeMod.callNativeAsync('requestCurrentFrameAsync', 'getCurrentFrameBase64', 4000);
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

    function buildTranscriptContext() {
      const cues = getCues() || [];
      if (!cues.length) return 'Subtitles have not been generated yet or the video has no subtitles.';
      const step = Math.max(1, Math.ceil(cues.length / 180));
      return cues
        .filter((_, idx) => idx % step === 0)
        .map((c) => `[${formatTime(c.start)}] ${c.text}`)
        .join('\n');
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

    function resetChat() {
      cancelActiveRequest();
      aiChatHistory = [];
      aiChatList.innerHTML = '<div class="ai-msg bot">Chat cleared. Ask a new question about the video!</div>';
    }

    async function callGeminiVideoQA(questionText) {
      const geminiKeys = getGeminiKeys();
      if (!geminiKeys.length) {
        appendAiMessage(
          'bot',
          'To use <strong>AI Video</strong>, enter your Gemini API key in Settings.<br><button type="button" class="ai-clear-btn open-gemini-settings-btn" style="margin-top:8px;color:var(--accent);border-color:var(--accent);">⚙️ Open Settings and paste Gemini key</button>',
          true
        );
        return;
      }

      cancelActiveRequest();
      const ctrl = new AbortController();
      activeAiAbort = ctrl;
      aiBusy = true;
      aiSendBtn.disabled = true;
      appendAiMessage('user', questionText);
      const loadingBubble = appendAiMessage('bot', '⏳ AI is analyzing the video...');

      try {
        const transcript = buildTranscriptContext();
        const curTimeStr = formatTime(videoEl.currentTime || 0);
        const frameB64 = await captureCurrentVideoFrameBase64();
        const selectedModel = geminiModelSelect.value || 'gemini-4-hydrogen';

        const candidateModels = [selectedModel];
        ['gemini-4-hydrogen', 'gemini-3.8-flash', 'gemini-2.5-flash', 'gemini-2.0-flash'].forEach((m) => {
          if (!candidateModels.includes(m)) candidateModels.push(m);
        });

        const systemPrompt =
          'You are a smart in-player video assistant (like YouTube "Ask about this video"). ' +
          'Reply clearly, concisely, and helpfully in the user\'s language (default English). ' +
          'When referencing moments from the video, always include timestamps in [MM:SS] format so the user can tap them to seek. ' +
          `Current player timestamp: [${curTimeStr}].\n\nVideo subtitles with timestamps:\n${transcript}`;

        const contents = [];
        aiChatHistory.slice(-8).forEach((turn) => {
          contents.push({
            role: turn.role === 'user' ? 'user' : 'model',
            parts: [{ text: turn.text }]
          });
        });

        const userParts = [{ text: questionText }];
        if (frameB64) {
          userParts.push({
            inline_data: {
              mime_type: 'image/jpeg',
              data: frameB64
            }
          });
        }
        contents.push({ role: 'user', parts: userParts });

        let answerText = null;
        let lastErr = 'Failed to get response from Gemini API.';

        const doFetch = groqMod && typeof groqMod.fetchWithTimeout === 'function'
          ? (url, opts) => groqMod.fetchWithTimeout(url, opts, 45000, ctrl.signal)
          : (url, opts) => fetch(url, Object.assign({}, opts, { signal: ctrl.signal }));

        for (const apiKey of geminiKeys) {
          for (const modelId of candidateModels) {
            // Передаём ключ в заголовке x-goog-api-key, а не в URL
            const url = `https://generativelanguage.googleapis.com/v1beta/models/${encodeURIComponent(modelId)}:generateContent`;
            const res = await doFetch(url, {
              method: 'POST',
              headers: {
                'Content-Type': 'application/json',
                'x-goog-api-key': apiKey
              },
              body: JSON.stringify({
                systemInstruction: { parts: [{ text: systemPrompt }] },
                contents,
                generationConfig: { temperature: 0.35 }
              })
            });

            if (res.ok) {
              const data = await res.json();
              const parts = (data && data.candidates && data.candidates[0] && data.candidates[0].content && data.candidates[0].content.parts) || [];
              const txt = parts.map((p) => p.text || '').join('').trim();
              if (txt) {
                answerText = txt;
                break;
              }
            } else {
              const errJson = await res.json().catch(() => ({}));
              const errMsg = (errJson && errJson.error && errJson.error.message) || `HTTP ${res.status}`;
              lastErr = errMsg;
              if (res.status === 400 && /API_KEY_INVALID|API key not valid/i.test(errMsg)) {
                break;
              }
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
        if (ctrl.signal.aborted && (!e || e.name === 'AbortError')) {
          loadingBubble.innerHTML = formatBotMessageHTML('Request cancelled.');
        } else {
          diag.logError('gemini.qa', e);
          loadingBubble.innerHTML = formatBotMessageHTML('Gemini API error: ' + (e.message || e));
        }
      } finally {
        if (activeAiAbort === ctrl) activeAiAbort = null;
        aiBusy = false;
        aiSendBtn.disabled = false;
      }
    }

    function submitAiQuestion() {
      if (aiBusy) return;
      const q = aiQuestionInput.value.trim();
      if (!q) return;
      aiQuestionInput.value = '';
      callGeminiVideoQA(q);
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
        setTimeout(() => geminiKeyInput.focus(), 200);
      }
    });

    clearAiChatBtn.addEventListener('click', resetChat);
    aiSendBtn.addEventListener('click', submitAiQuestion);
    aiQuestionInput.addEventListener('keydown', (e) => {
      if (e.key === 'Enter') {
        e.preventDefault();
        submitAiQuestion();
      }
    });
    aiQuickPrompts.addEventListener('click', (e) => {
      const chip = e.target.closest('.ai-prompt-chip');
      if (!chip || aiBusy) return;
      const q = chip.dataset.q;
      if (q) callGeminiVideoQA(q);
    });

    return {
      generateVideoAiInsights() {
        aiSummaryBox.classList.add('show');
        aiSummaryBox.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
        setTimeout(() => aiQuestionInput.focus(), 180);
      },
      resetChat,
      cancelActiveRequest
    };
  }

  return {
    formatBotMessageHTML,
    createController
  };
});
