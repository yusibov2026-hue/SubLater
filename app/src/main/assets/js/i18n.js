(function (root, factory) {
  const api = factory(
    root.SubLyter && root.SubLyter.storage,
    root.SubLyter && root.SubLyter.bridge
  );
  if (typeof module !== 'undefined' && module.exports) {
    module.exports = api;
  }
  root.SubLyter = root.SubLyter || {};
  root.SubLyter.i18n = api;
})(typeof globalThis !== 'undefined' ? globalThis : window, function (storageMod, bridgeMod) {
  'use strict';

  const STORAGE_KEY = 'sublater_ui_lang';
  const listeners = new Set();

  const LANGUAGES = [
    { code: 'en', name: 'English', nativeName: 'English', aiName: 'English' },
    { code: 'ru', name: 'Russian', nativeName: 'Русский', aiName: 'Russian' },
    { code: 'tr', name: 'Turkish', nativeName: 'Türkçe', aiName: 'Turkish' },
    { code: 'az', name: 'Azerbaijani', nativeName: 'Azərbaycanca', aiName: 'Azerbaijani' },
    { code: 'ar', name: 'Arabic', nativeName: 'العربية', aiName: 'Arabic', rtl: true },
    { code: 'hy', name: 'Armenian', nativeName: 'Հայերեն', aiName: 'Armenian' },
    { code: 'be', name: 'Belarusian', nativeName: 'Беларуская', aiName: 'Belarusian' },
    { code: 'bn', name: 'Bengali', nativeName: 'বাংলা', aiName: 'Bengali' },
    { code: 'bg', name: 'Bulgarian', nativeName: 'Български', aiName: 'Bulgarian' },
    { code: 'ca', name: 'Catalan', nativeName: 'Català', aiName: 'Catalan' },
    { code: 'zh-CN', name: 'Chinese (Simplified)', nativeName: '简体中文', aiName: 'Chinese (Simplified)' },
    { code: 'zh-TW', name: 'Chinese (Traditional)', nativeName: '繁體中文', aiName: 'Chinese (Traditional)' },
    { code: 'hr', name: 'Croatian', nativeName: 'Hrvatski', aiName: 'Croatian' },
    { code: 'cs', name: 'Czech', nativeName: 'Čeština', aiName: 'Czech' },
    { code: 'da', name: 'Danish', nativeName: 'Dansk', aiName: 'Danish' },
    { code: 'nl', name: 'Dutch', nativeName: 'Nederlands', aiName: 'Dutch' },
    { code: 'et', name: 'Estonian', nativeName: 'Eesti', aiName: 'Estonian' },
    { code: 'fil', name: 'Filipino', nativeName: 'Filipino', aiName: 'Filipino' },
    { code: 'fi', name: 'Finnish', nativeName: 'Suomi', aiName: 'Finnish' },
    { code: 'fr', name: 'French', nativeName: 'Français', aiName: 'French' },
    { code: 'ka', name: 'Georgian', nativeName: 'ქართული', aiName: 'Georgian' },
    { code: 'de', name: 'German', nativeName: 'Deutsch', aiName: 'German' },
    { code: 'el', name: 'Greek', nativeName: 'Ελληνικά', aiName: 'Greek' },
    { code: 'he', name: 'Hebrew', nativeName: 'עברית', aiName: 'Hebrew', rtl: true },
    { code: 'hi', name: 'Hindi', nativeName: 'हिन्दी', aiName: 'Hindi' },
    { code: 'hu', name: 'Hungarian', nativeName: 'Magyar', aiName: 'Hungarian' },
    { code: 'id', name: 'Indonesian', nativeName: 'Bahasa Indonesia', aiName: 'Indonesian' },
    { code: 'it', name: 'Italian', nativeName: 'Italiano', aiName: 'Italian' },
    { code: 'ja', name: 'Japanese', nativeName: '日本語', aiName: 'Japanese' },
    { code: 'kk', name: 'Kazakh', nativeName: 'Қазақша', aiName: 'Kazakh' },
    { code: 'ko', name: 'Korean', nativeName: '한국어', aiName: 'Korean' },
    { code: 'ky', name: 'Kyrgyz', nativeName: 'Кыргызча', aiName: 'Kyrgyz' },
    { code: 'lv', name: 'Latvian', nativeName: 'Latviešu', aiName: 'Latvian' },
    { code: 'lt', name: 'Lithuanian', nativeName: 'Lietuvių', aiName: 'Lithuanian' },
    { code: 'ms', name: 'Malay', nativeName: 'Bahasa Melayu', aiName: 'Malay' },
    { code: 'nb', name: 'Norwegian', nativeName: 'Norsk bokmål', aiName: 'Norwegian' },
    { code: 'fa', name: 'Persian', nativeName: 'فارسی', aiName: 'Persian', rtl: true },
    { code: 'pl', name: 'Polish', nativeName: 'Polski', aiName: 'Polish' },
    { code: 'pt', name: 'Portuguese', nativeName: 'Português', aiName: 'Portuguese' },
    { code: 'ro', name: 'Romanian', nativeName: 'Română', aiName: 'Romanian' },
    { code: 'sr', name: 'Serbian', nativeName: 'Српски', aiName: 'Serbian' },
    { code: 'sk', name: 'Slovak', nativeName: 'Slovenčina', aiName: 'Slovak' },
    { code: 'sl', name: 'Slovenian', nativeName: 'Slovenščina', aiName: 'Slovenian' },
    { code: 'es', name: 'Spanish', nativeName: 'Español', aiName: 'Spanish' },
    { code: 'sw', name: 'Swahili', nativeName: 'Kiswahili', aiName: 'Swahili' },
    { code: 'sv', name: 'Swedish', nativeName: 'Svenska', aiName: 'Swedish' },
    { code: 'tg', name: 'Tajik', nativeName: 'Тоҷикӣ', aiName: 'Tajik' },
    { code: 'ta', name: 'Tamil', nativeName: 'தமிழ்', aiName: 'Tamil' },
    { code: 'th', name: 'Thai', nativeName: 'ไทย', aiName: 'Thai' },
    { code: 'tk', name: 'Turkmen', nativeName: 'Türkmençe', aiName: 'Turkmen' },
    { code: 'uk', name: 'Ukrainian', nativeName: 'Українська', aiName: 'Ukrainian' },
    { code: 'ur', name: 'Urdu', nativeName: 'اردو', aiName: 'Urdu', rtl: true },
    { code: 'uz', name: 'Uzbek', nativeName: 'Oʻzbekcha', aiName: 'Uzbek' },
    { code: 'vi', name: 'Vietnamese', nativeName: 'Tiếng Việt', aiName: 'Vietnamese' }
  ];

  const LANG_MAP = new Map(LANGUAGES.map((l) => [l.code, l]));

  const EN_FALLBACK = {
    'tab.subtitle': 'Subtitle',
    'tab.learn': 'Learn',
    'tab.history': 'History',
    'tab.voice': 'Voice',
    'model.sheet.title': 'Speech Model',
    'model.auto.title': 'Choose model (Auto)',
    'model.auto.short': 'Smart Groq LPU™ routing · Turbo speed',
    'model.auto.badge': 'Auto',
    'model.auto.desc': 'Automatically picks the fastest Whisper v3 Turbo pipeline on Groq LPU™ with seamless failover between keys.',
    'model.turbo.title': 'whisper-large-v3-turbo',
    'model.turbo.short': 'Ultra-fast Groq Whisper · Best daily balance',
    'model.turbo.badge': 'Fastest',
    'model.turbo.desc': 'Near-instant transcription with top accuracy-to-speed ratio. Recommended for 95% of videos, lectures, and voice notes.',
    'model.v3.title': 'whisper-large-v3',
    'model.v3.short': 'Maximum accuracy · Heavy accents & noise',
    'model.v3.badge': 'Pro',
    'model.v3.desc': 'Full-size multilingual model for tricky accents, background music, quiet speech, or rare terminology.',
    'model.base.title': 'Whisper Base (Offline)',
    'model.base.badge': '~76 MB · Offline',
    'model.base.desc': 'Compact on-device Whisper v3 ONNX model. Fast offline speech recognition without internet or API keys.',
    'model.small.title': 'Whisper Small (Offline)',
    'model.small.badge': '~245 MB · Offline',
    'model.small.desc': 'Balanced multilingual on-device Whisper model. Higher offline accuracy for accents and clear phrasing without API keys.',
    'model.download': 'Download',
    'model.downloading': 'Downloading {pct}%...',
    'model.downloaded': 'Downloaded ✓',
    'model.delete': 'Delete',
    'model.needDownload': 'Download the offline model first.',
    'lang.none': 'No translation',
    'file.pill': 'File',
    'file.notSelected': 'Not selected',
    'btn.generate': 'Generate subtitles',
    'btn.aiVideo': '✨ AI Video (ask about video)',
    'btn.restoreOrig': 'Restore original from cache',
    'btn.openCachedTrans': 'Open cached translation',
    'btn.quickTranslate': 'Quick translate from cache',
    'btn.retry': 'Retry',
    'btn.download': 'Download',
    'btn.text': 'Text',
    'btn.hide': 'Hide',
    'btn.cancel': 'Cancel process',
    'notif.cancel': 'Cancel',
    'dl.standard': 'Standard',
    'dl.karaoke': 'Karaoke',
    'dl.universal': 'Universal',
    'dl.plain': 'Plain text',
    'dl.share': 'Share',
    'ai.badge': '✨ AI Video',
    'ai.clear': 'Clear',
    'ai.chip.summary': '📝 What is this video about?',
    'ai.chip.summaryQ': 'What is this video about? Give a short, clear summary.',
    'ai.chip.timestamps': '⏱ Key timestamps',
    'ai.chip.timestampsQ': 'List key moments in the video with exact timestamps in [MM:SS] format.',
    'ai.chip.takeaways': '💡 Main takeaways',
    'ai.chip.takeawaysQ': 'What are the main takeaways and useful insights from this video?',
    'ai.chip.frame': '👁 What is in frame now?',
    'ai.chip.frameQ': 'What is happening in the video right now and what is being said at this moment?',
    'ai.greeting': 'Hi! I am your AI video assistant (like YouTube). Ask any question about the video below or pick a quick prompt above. Tap timestamps in replies to jump to that moment.',
    'ai.placeholder': 'Ask anything about the video...',
    'ai.vttTitle': 'Tap to copy text',
    'ai.cleared': 'Chat cleared. Ask a new question about the video!',
    'ai.thinking': 'Thinking...',
    'ai.needKey': 'Enter your Gemini API key in Settings to use AI Video.',
    'ai.openSettings': 'Open Settings',
    'learn.kicker': 'Language Studio',
    'learn.title': 'Learn from Real Videos',
    'learn.sub': 'Turn on Dual Subtitles when translating, tap any word in the player for instant contextual meaning, or practice shadowing.',
    'learn.dualTitle': 'Dual Subtitles',
    'learn.dualDesc': 'Show original line underneath translation',
    'learn.repeatBtn': '↺ Repeat line',
    'learn.slowBtn': '0.75× Shadow',
    'learn.slowActive': '0.75× Active',
    'learn.needSubtitles': 'Load or generate subtitles first to repeat phrases.',
    'learn.flashTitle': 'Flashcard Review',
    'learn.dueZero': '0 due',
    'learn.dueProgress': '{due} due · {total} total',
    'learn.empty': 'Tap any word on a playing subtitle to look it up and save flashcards.',
    'learn.caughtUp': 'All caught up! {total} cards in your deck.',
    'learn.cardHint': 'Tap card to reveal meaning & context',
    'learn.rateAgain': 'Again · 10m',
    'learn.rateGood': 'Good · 1d+',
    'learn.rateEasy': 'Easy · 4d+',
    'learn.savedWords': 'Saved Words',
    'learn.clear': 'Clear',
    'learn.delete': 'Delete',
    'learn.wordFallback': 'Word',
    'learn.dualLangLabel': 'Second subtitle language',
    'subMenu.title': 'Subtitles',
    'subMenu.singleTitle': 'Translated subtitles',
    'subMenu.singleDesc': 'Single track in selected language',
    'subMenu.dualTitle': 'Dual subtitles',
    'subMenu.dualDesc': 'Translated + second language',
    'subMenu.dualLang': 'Second language',
    'subMenu.origSpeech': 'Original (speech)',
    'subMenu.translating': '⚡ Translating dual track...',
    'history.title': 'Subtitle History',
    'history.clear': 'Clear',
    'history.searchPlaceholder': 'Search files, languages, subtitle text...',
    'history.empty': 'Your generated subtitles are saved here automatically for instant one-tap reopening.',
    'history.emptyFiltered': 'No saved projects match your search.',
    'history.filterAll': 'All',
    'history.filterTranslated': 'Translated',
    'history.filterIncomplete': 'In progress',
    'history.linesCount': '{n} lines',
    'history.inProgress': '⏸ In progress',
    'history.open': '▶ Open',
    'history.copy': 'Copy',
    'history.copied': 'Copied!',
    'history.export': '.SRT',
    'history.delete': 'Delete',
    'history.opened': '⚡ Restored "{name}" from History!',
    'history.justNow': 'Just now',
    'voice.kicker': 'Groq Whisper Voice',
    'voice.title': 'AI Voice Recorder & Notes',
    'voice.tapToRecord': 'Tap to record',
    'voice.recording': 'Recording · Tap to stop',
    'voice.importAudio': '📂 Import voice / audio',
    'voice.searchPlaceholder': 'Search transcripts & action items...',
    'voice.empty': 'Tap the mic to record a memo or import any Telegram/WhatsApp voice file.',
    'voice.emptyFiltered': 'No voice notes match your search.',
    'voice.transcribing': '⚡ Groq Whisper transcribing voice...',
    'voice.noSpeech': 'No speech detected in audio.',
    'voice.structuring': '✨ Structuring summary & action items...',
    'voice.saved': 'Voice note saved!',
    'voice.unsupported': 'Live mic unsupported here — use Import audio file instead.',
    'voice.speakClearly': 'Speak clearly... Groq will transcribe and summarize automatically.',
    'voice.tooShort': 'Recording too short.',
    'voice.micDenied': 'Microphone permission denied — allow mic access in Android Settings.',
    'voice.micError': 'Microphone error: {msg}',
    'voice.requestingPerm': 'Allow microphone permission in the system dialog...',
    'voice.fullTranscript': 'Full transcript',
    'voice.badgeDefault': '🎙 Voice',
    'voice.copy': 'Copy',
    'voice.copied': 'Copied!',
    'voice.delete': 'Delete',
    'lookup.analyzing': 'Analyzing in context...',
    'lookup.saveCard': 'Save Flashcard',
    'lookup.savedCard': 'Saved to Deck ✓',
    'lookup.resume': 'Resume Video',
    'lookup.failed': 'Lookup failed',
    'lookup.scene': 'Scene: “{ctx}”',
    'lookup.nuance': 'Nuance: {text}',
    'lookup.example': 'Example: {text}',
    'player.menuTitle': 'Player Settings',
    'player.size': 'Size',
    'player.width': 'Width',
    'player.height': 'Height',
    'player.offset': 'Offset (appear)',
    'player.hold': 'Hold (disappear)',
    'player.wordMode': 'Word mode',
    'player.wordMode.hidden': 'Word-by-word',
    'player.wordMode.karaoke': 'Karaoke',
    'player.wordMode.full': 'Full line',
    'player.boost': 'Volume boost',
    'player.audioTrack': 'Audio track',
    'player.dualSubs': 'Dual subtitles',
    'player.background': 'Background',
    'settings.title': 'Settings',
    'settings.subtitle': 'Material You • Theme & AI',
    'settings.cardTheme': 'Material You & Theme',
    'settings.language': 'Language',
    'settings.langAuto': 'Auto ({lang})',
    'settings.appearance': 'Appearance',
    'settings.dark': 'Dark',
    'settings.light': 'Light',
    'settings.auto': 'Auto',
    'settings.accentColor': 'Accent color',
    'settings.colorBtn': 'Color',
    'settings.themeBg': 'Theme background',
    'settings.accentWallpaper': 'Accent wallpaper',
    'settings.wallpaperColor': 'Wallpaper color',
    'settings.matchAccent': 'Match accent',
    'settings.wallpaperOpacity': 'Wallpaper opacity',
    'settings.cardPlayback': 'Title & Playback',
    'settings.titleFont': 'Title font',
    'settings.titleSize': 'Title size',
    'settings.preview': 'Preview',
    'settings.subColor': 'Subtitle text color',
    'settings.autoplay': 'Autoplay',
    'settings.cardApi': 'API Keys & AI Models',
    'settings.groqKeys': 'Groq API Keys',
    'settings.getApi': 'Get API',
    'settings.groqPlaceholder': 'Paste one or more Groq keys (one per line or comma-separated — gsk_...)',
    'settings.geminiKey': 'Gemini API Key',
    'settings.geminiPlaceholder': 'Enter Gemini key',
    'settings.geminiModel': 'Gemini model',
    'settings.translationModel': 'Translation model',
    'settings.cardGeneration': 'Generation & Subtitles',
    'settings.hallucination': 'Hallucination filter',
    'settings.smartSplit': 'Auto-split long phrases',
    'settings.fastAudio': 'Fast audio compression (16 kHz)',
    'settings.genMode': 'Generation mode',
    'settings.genAll': 'All at once',
    'settings.genProgressive': 'Progressive',
    'settings.glossaryPlaceholder': 'Glossary / custom names (optional)',
    'settings.loadSub': 'Load existing (.vtt / .srt)',
    'settings.choose': 'Choose',
    'settings.diagLog': 'Diagnostics log',
    'settings.copyLog': 'Copy log',
    'settings.copied': 'Copied!',
    'settings.copyFailed': 'Copy failed',
    'settings.welcomeTour': 'Welcome feature tour',
    'settings.show': 'Show',
    'keys.zero': '0 keys',
    'keys.one': '1 key',
    'keys.many': '{n} keys',
    'status.missingApiGen': 'Groq API key is missing — add it in Settings (API Keys section).',
    'status.missingApiIdle': 'No Groq API key yet — open Settings to add your API key.',
    'status.openSettings': 'Open Settings',
    'status.cancelled': '⏹ Cancelled by user.',
    'status.restoredHistory': '⚡ Subtitles instantly restored from local history!',
    'status.unfinishedHistory': '⏸ Unfinished progress found — tap "Generate subtitles" to resume.',
    'status.streamOpened': '📡 Stream opened. Tap "Generate subtitles" — audio will be processed via player.',
    'status.externalOpened': '📂 File opened from another app. Tap "Generate subtitles".',
    'status.fileStopped': '⏹ Generation stopped: another file selected.',
    'status.subLoadErr': 'Could not read subtitles from file.',
    'status.subLoadedPlay': 'Loaded subtitles ({n} lines) into player!',
    'status.subLoadedNoVid': 'Subtitles loaded ({n} lines). Select a video file to watch.',
    'status.subCopied': 'Subtitle text copied to clipboard!',
    'status.savedDownloads': '✅ File {name} saved to Downloads!',
    'status.genFirst': 'Generate subtitles first!',
    'status.generating': 'Generating subtitles...',
    'status.selectFile': 'Please select a video or audio file first.',
    'status.streamTranslating': '⚡ Streaming translation of final chunks...',
    'status.embeddedExtracted': '⚡ Extracted embedded subtitles ({n} lines)!',
    'status.downloadingModel': '📥 Downloading {model}...',
    'status.downloadingModelPct': '📥 Downloading {model}: {pct}%',
    'status.firstSubsReady': '▶️ First subtitles ready — you can watch while remaining parts process...',
    'status.offlineExtracting': '🧠 Offline Whisper ({model}): extracting audio...',
    'status.offlineDecoding': '🧠 Offline Whisper ({model}): decoding audio...',
    'status.offlineTranscribing': '🧠 Transcribing locally ({model})...',
    'status.offlineProgress': '🧠 Offline Whisper: {done} parts ({start}–{end}s)...',
    'status.extractingAll': '⚡ All at once: extracting audio (12 kHz, 5-min chunks)...',
    'status.extractingAllFrom': '⚡ All at once from {from}: extracting audio (12 kHz, 5-min chunks)...',
    'status.extractingProg': '⚡ Progressive: extracting audio (16 kHz, 60s chunks)...',
    'status.extractingProgFrom': '⚡ Progressive from {from}: extracting audio (16 kHz, 60s chunks)...',
    'status.extractingAudio': '⚡ Extracting and compressing audio track...',
    'status.directUpload': '🚀 Direct upload to Groq...',
    'status.parallelUpload': '🚀 Parallel upload ({streams} streams, {total} parts)...',
    'status.transcribedChunks': '🚀 Transcribed: {done} parts ({start}–{end}s)...',
    'status.transcribedOf': '🚀 Transcribed: {done} / {total} ({start}–{end}s)...',
    'status.usingCache': 'Using recognized text from cache...',
    'status.translatingBatch': '⚡ Translating: batch {done} / {total} (lines {from}–{to})...',
    'status.doneSaved': '✅ Done! Subtitles loaded and saved to history.',
    'status.doneLoaded': '✅ Done! Subtitles loaded.',
    'status.untranslatedWarn': ' ({n} lines not translated).',
    'status.rateLimitAll': '⏳ Rate limit on all keys. Waiting {sec}s...',
    'status.keyRejectedSwitch': '🔑 Key rejected (401). Switching key...',
    'status.keyRateLimitedSwitch': '🔄 Key rate-limited. Trying another key...',
    'status.rateLimitWait': '⏳ Rate limit. Waiting {sec}s ({attempt}/{max})...',
    'status.serverBusyRetry': '⏳ Server busy ({code}). Retrying in {sec}s...',
    'status.networkRetry': '🌐 Network error. Retrying...',
    'welcome.title': 'Welcome to SubLyter Studio',
    'welcome.sub': 'Groq-powered instant subtitles, Language Studio, Subtitle History & AI Voice Notes — crafted in Material You.',
    'welcome.step1.title': 'Groq Speed + 54 Languages',
    'welcome.step1.desc': 'Instant speech subtitles, adaptive borderless player & Gemini Video Q&A.',
    'welcome.step2.title': 'Language Studio (Learn)',
    'welcome.step2.desc': 'Dual subtitles, tap any word for contextual AI meaning, 0.75× shadowing & flashcards.',
    'welcome.step3.title': 'Subtitle History',
    'welcome.step3.desc': 'Auto-saved archive of all generated subtitles with search, language badges & one-tap reopen.',
    'welcome.step4.title': 'AI Voice Recorder & Notes',
    'welcome.step4.desc': 'Record live memos or import voice files for instant Groq transcripts & action items.',
    'welcome.addKeyBtn': 'Add API Key in Settings',
    'welcome.openSettingsBtn': 'Open Settings',
    'welcome.startBtn': 'Get Started'
  };

  const CACHE = { en: EN_FALLBACK };
  const DICT = { en: EN_FALLBACK };

  function resolveLanguageCode(rawTag) {
    const clean = String(rawTag || '').trim().replace(/_/g, '-');
    if (!clean) return 'en';
    const lower = clean.toLowerCase();

    for (const lang of LANGUAGES) {
      if (lang.code.toLowerCase() === lower) return lang.code;
    }

    if (lower.startsWith('zh')) {
      if (lower.includes('hant') || lower.includes('-tw') || lower.includes('-hk') || lower.includes('-mo')) {
        return 'zh-TW';
      }
      return 'zh-CN';
    }

    const base = lower.split('-')[0];
    const legacy = {
      iw: 'he',
      in: 'id',
      tl: 'fil',
      no: 'nb',
      nn: 'nb',
      mo: 'ro',
      sh: 'hr'
    };
    const mapped = legacy[base] || base;
    if (LANG_MAP.has(mapped)) return mapped;
    return 'en';
  }

  function detectSystemLang(overrideTag) {
    if (overrideTag) return resolveLanguageCode(overrideTag);
    try {
      const b = bridgeMod && typeof bridgeMod.getBridge === 'function'
        ? bridgeMod.getBridge()
        : (typeof window !== 'undefined' && (window.AndroidBridge || window.ExoPlayerManager));
      if (b && typeof b.getSystemLanguageTag === 'function') {
        const tag = b.getSystemLanguageTag();
        if (tag && String(tag).trim()) return resolveLanguageCode(tag);
      }
    } catch (_) {}
    try {
      if (typeof navigator !== 'undefined') {
        const navTag = (navigator.languages && navigator.languages[0]) || navigator.language;
        if (navTag) return resolveLanguageCode(navTag);
      }
    } catch (_) {}
    return 'en';
  }

  function readStoredPref() {
    try {
      const val = (storageMod && typeof storageMod.safeGetStorage === 'function')
        ? storageMod.safeGetStorage(STORAGE_KEY, 'auto')
        : (typeof localStorage !== 'undefined' ? (localStorage.getItem(STORAGE_KEY) || 'auto') : 'auto');
      const v = String(val || '').trim();
      if (!v || v === 'auto') return 'auto';
      if (LANG_MAP.has(v)) return v;
      return resolveLanguageCode(v);
    } catch (_) {
      return 'auto';
    }
  }

  function writeStoredPref(pref) {
    try {
      if (storageMod && typeof storageMod.safeSetStorage === 'function') {
        storageMod.safeSetStorage(STORAGE_KEY, pref);
        return;
      }
      if (typeof localStorage !== 'undefined') {
        localStorage.setItem(STORAGE_KEY, pref);
      }
    } catch (_) {}
  }

  async function loadLocale(code) {
    const valid = LANG_MAP.has(code) ? code : 'en';
    if (CACHE[valid]) return CACHE[valid];

    if (typeof require === 'function' && typeof process !== 'undefined' && process.versions && process.versions.node) {
      try {
        const fs = require('fs');
        const path = require('path');
        const filePath = path.join(__dirname, '..', 'i18n', `${valid}.json`);
        if (fs.existsSync(filePath)) {
          const parsed = JSON.parse(fs.readFileSync(filePath, 'utf8'));
          CACHE[valid] = parsed;
          DICT[valid] = parsed;
          return parsed;
        }
      } catch (_) {}
    }

    if (typeof fetch === 'function') {
      try {
        const resp = await fetch(`i18n/${encodeURIComponent(valid)}.json`);
        if (resp.ok) {
          const parsed = await resp.json();
          CACHE[valid] = parsed;
          DICT[valid] = parsed;
          return parsed;
        }
      } catch (_) {}
    }
    return CACHE.en;
  }

  let currentPref = readStoredPref();
  let currentLang = currentPref === 'auto' ? detectSystemLang() : currentPref;

  function getPref() {
    return currentPref;
  }

  function getLang() {
    return currentLang;
  }

  function getLangMeta(code) {
    return LANG_MAP.get(code || currentLang) || LANG_MAP.get('en');
  }

  function isRtl(code) {
    const meta = getLangMeta(code);
    return Boolean(meta && meta.rtl);
  }

  function t(key, vars) {
    const table = CACHE[currentLang] || EN_FALLBACK;
    let str = table[key];
    if (str === undefined) str = EN_FALLBACK[key];
    if (str === undefined) str = String(key);
    if (vars && typeof vars === 'object') {
      Object.keys(vars).forEach((k) => {
        str = str.replace(new RegExp(`\\{${k}\\}`, 'g'), String(vars[k]));
      });
    }
    return str;
  }

  function getAiOutputLanguageName(selectedTranslateLang) {
    const clean = String(selectedTranslateLang || '').trim();
    if (clean) return clean;
    const meta = getLangMeta(currentLang);
    return (meta && meta.aiName) || 'English';
  }

  function applyTranslations(container) {
    const scope = container || (typeof document !== 'undefined' ? document : null);
    if (!scope || typeof scope.querySelectorAll !== 'function') return;
    if (typeof document !== 'undefined' && document.documentElement) {
      document.documentElement.lang = currentLang;
      document.documentElement.dir = isRtl(currentLang) ? 'rtl' : 'ltr';
    }

    scope.querySelectorAll('[data-i18n]').forEach((el) => {
      const k = el.getAttribute('data-i18n');
      if (k) el.textContent = t(k);
    });
    scope.querySelectorAll('[data-i18n-placeholder]').forEach((el) => {
      const k = el.getAttribute('data-i18n-placeholder');
      if (k) el.setAttribute('placeholder', t(k));
    });
    scope.querySelectorAll('[data-i18n-aria]').forEach((el) => {
      const k = el.getAttribute('data-i18n-aria');
      if (k) el.setAttribute('aria-label', t(k));
    });
    scope.querySelectorAll('[data-i18n-title]').forEach((el) => {
      const k = el.getAttribute('data-i18n-title');
      if (k) el.setAttribute('title', t(k));
    });
    scope.querySelectorAll('[data-i18n-q]').forEach((el) => {
      const k = el.getAttribute('data-i18n-q');
      if (k) el.dataset.q = t(k);
    });
  }

  async function setLang(pref) {
    const raw = String(pref || '').trim();
    const normPref = (!raw || raw === 'auto') ? 'auto' : (LANG_MAP.has(raw) ? raw : resolveLanguageCode(raw));
    const effective = normPref === 'auto' ? detectSystemLang() : normPref;
    currentPref = normPref;
    currentLang = effective;
    writeStoredPref(normPref);
    await loadLocale(effective);
    applyTranslations();
    listeners.forEach((cb) => {
      try { cb(effective, normPref); } catch (_) {}
    });
    return effective;
  }

  async function init() {
    return setLang(readStoredPref());
  }

  function onChange(cb) {
    if (typeof cb === 'function') listeners.add(cb);
    return () => listeners.delete(cb);
  }

  return {
    LANGUAGES,
    DICT,
    STORAGE_KEY,
    resolveLanguageCode,
    detectSystemLang,
    loadLocale,
    init,
    getPref,
    getLang,
    getLangMeta,
    isRtl,
    setLang,
    t,
    getAiOutputLanguageName,
    applyTranslations,
    onChange
  };
});
