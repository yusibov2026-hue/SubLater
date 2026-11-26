(function () {
  'use strict';

  const root = window.SubLyter = window.SubLyter || {};
  const diag = root.diagnostics || { logWarn() {}, logError() {} };
  const getBridge = () => (root.bridge ? root.bridge.getBridge() : (window.AndroidBridge || window.ExoPlayerManager || null));

  function safeGetStorage(k, fallback = '') {
    try { return localStorage.getItem(k) || fallback; } catch (e) {
      diag.logWarn('storage.get', e);
      return fallback;
    }
  }

  function safeSetStorage(k, v) {
    try { localStorage.setItem(k, v); } catch (e) {
      diag.logWarn('storage.set', e);
    }
  }

  /* ---- Защита ключей через EncryptedSharedPreferences (Android Keystore) ---- */
  let sessionGroqKeys = '';
  let sessionGeminiKey = '';

  function parseKeyList(rawText) {
    const keys = [];
    String(rawText || '').split(/[\s,;]+/).forEach((k) => {
      const item = k.trim();
      if (item && !keys.includes(item)) keys.push(item);
    });
    return keys;
  }

  function saveGroqApiKeys(rawText) {
    const keys = parseKeyList(rawText);
    const joined = keys.join('\n');
    sessionGroqKeys = joined;

    const bridge = getBridge();
    if (bridge && typeof bridge.saveSecureKey === 'function') {
      try {
        bridge.saveSecureKey('groq_api_keys', joined);
        // Всегда перезаписываем legacy-слоты (включая пустую строку), чтобы удалённый ключ не воскресал
        bridge.saveSecureKey('groq_api_key', keys[0] || '');
        bridge.saveSecureKey('groq_api_key_2', keys[1] || '');
      } catch (e) {
        diag.logError('keystore.saveGroq', e);
      }
    }
    return keys;
  }

  function getGroqApiKeysFromKeystore() {
    let stored = '';
    const bridge = getBridge();
    if (bridge && typeof bridge.getSecureKey === 'function') {
      try {
        stored = String(bridge.getSecureKey('groq_api_keys') || '').trim();
        if (!stored) {
          const k1 = String(bridge.getSecureKey('groq_api_key') || '').trim();
          const k2 = String(bridge.getSecureKey('groq_api_key_2') || '').trim();
          stored = [k1, k2].filter(Boolean).join('\n');
        }
      } catch (e) {
        diag.logError('keystore.getGroq', e);
      }
    }
    return stored || sessionGroqKeys || '';
  }

  function saveGeminiKeyToKeystore(key) {
    const clean = String(key || '').trim();
    sessionGeminiKey = clean;
    const bridge = getBridge();
    if (bridge && typeof bridge.saveGeminiApiKey === 'function') {
      try { bridge.saveGeminiApiKey(clean); } catch (e) { diag.logError('keystore.saveGemini', e); }
    } else if (bridge && typeof bridge.saveSecureKey === 'function') {
      try { bridge.saveSecureKey('gemini_api_key', clean); } catch (e) { diag.logError('keystore.saveGemini', e); }
    }
  }

  function getGeminiKeyFromKeystore() {
    const bridge = getBridge();
    if (bridge && typeof bridge.getGeminiApiKey === 'function') {
      try {
        const k = String(bridge.getGeminiApiKey() || '').trim();
        if (k) return k;
      } catch (e) {
        diag.logWarn('keystore.getGemini', e);
      }
    }
    if (bridge && typeof bridge.getSecureKey === 'function') {
      try {
        const k = String(bridge.getSecureKey('gemini_api_key') || '').trim();
        if (k) return k;
      } catch (e) {
        diag.logWarn('keystore.getGeminiFallback', e);
      }
    }
    return sessionGeminiKey;
  }

  /* ---- Локальная история проектов (IndexedDB: переиспользуемое соединение + сериализация) ---- */
  const DB_NAME = 'SubLaterHistory';
  const DB_VERSION = 2;
  const STORE_NAME = 'projects';
  const MAX_HISTORY_PROJECTS = 25;

  let cachedDbPromise = null;
  let pendingHistoryWrite = Promise.resolve();
  let lastPartialWriteAt = 0;

  function getHistoryDb() {
    if (cachedDbPromise) return cachedDbPromise;
    cachedDbPromise = new Promise((resolve) => {
      if (!window.indexedDB) {
        cachedDbPromise = null;
        resolve(null);
        return;
      }
      try {
        const req = indexedDB.open(DB_NAME, DB_VERSION);
        req.onupgradeneeded = (e) => {
          const db = e.target.result;
          let store;
          if (!db.objectStoreNames.contains(STORE_NAME)) {
            store = db.createObjectStore(STORE_NAME, { keyPath: 'fileSig' });
          } else {
            store = e.target.transaction.objectStore(STORE_NAME);
          }
          if (!store.indexNames.contains('updatedAt')) {
            store.createIndex('updatedAt', 'updatedAt', { unique: false });
          }
        };
        req.onsuccess = (e) => {
          const db = e.target.result;
          db.onversionchange = () => {
            db.close();
            cachedDbPromise = null;
          };
          resolve(db);
        };
        req.onerror = (e) => {
          diag.logWarn('idb.open', e && e.target ? e.target.error : 'open error');
          cachedDbPromise = null;
          resolve(null);
        };
      } catch (err) {
        diag.logWarn('idb.openCatch', err);
        cachedDbPromise = null;
        resolve(null);
      }
    });
    return cachedDbPromise;
  }

  function writeProjectInternal(projectData) {
    return getHistoryDb().then((db) => {
      if (!db) return;
      return new Promise((resolve) => {
        try {
          const tx = db.transaction(STORE_NAME, 'readwrite');
          const store = tx.objectStore(STORE_NAME);
          store.put(projectData);

          if (projectData.isComplete !== false) {
            const countReq = store.count();
            countReq.onsuccess = () => {
              const total = countReq.result || 0;
              if (total <= MAX_HISTORY_PROJECTS) return;
              let toDelete = total - MAX_HISTORY_PROJECTS;
              const idx = store.indexNames.contains('updatedAt') ? store.index('updatedAt') : null;
              if (!idx) return;
              const cursorReq = idx.openCursor(null, 'next');
              cursorReq.onsuccess = (ev) => {
                const cursor = ev.target.result;
                if (!cursor || toDelete <= 0) return;
                if (cursor.value && cursor.value.fileSig !== projectData.fileSig) {
                  cursor.delete();
                  toDelete--;
                }
                cursor.continue();
              };
            };
          }

          tx.oncomplete = () => {
            notifyHistoryUpdated();
            resolve();
          };
          tx.onerror = () => resolve();
          tx.onabort = () => resolve();
        } catch (e) {
          diag.logWarn('idb.write', e);
          resolve();
        }
      });
    });
  }

  function notifyHistoryUpdated() {
    try {
      if (typeof window !== 'undefined' && typeof window.__onHistoryUpdated === 'function') {
        window.__onHistoryUpdated();
      }
    } catch (_) {}
  }

  function saveProjectToHistory(projectData) {
    if (!projectData || !projectData.fileSig) return Promise.resolve();
    const now = Date.now();
    // Промежуточные сохранения во время потоковой генерации троттлим до 1 раза в 1.5 сек
    if (projectData.isComplete === false && now - lastPartialWriteAt < 1500) {
      return Promise.resolve();
    }
    if (projectData.isComplete === false) lastPartialWriteAt = now;

    pendingHistoryWrite = pendingHistoryWrite
      .then(() => writeProjectInternal(projectData))
      .catch((e) => diag.logWarn('idb.queue', e));
    return pendingHistoryWrite;
  }

  async function getProjectFromHistory(fileSig) {
    if (!fileSig) return null;
    try {
      const db = await getHistoryDb();
      if (!db) return null;
      return await new Promise((resolve) => {
        const tx = db.transaction(STORE_NAME, 'readonly');
        const store = tx.objectStore(STORE_NAME);
        const req = store.get(fileSig);
        req.onsuccess = () => resolve(req.result || null);
        req.onerror = () => resolve(null);
      });
    } catch (e) {
      diag.logWarn('idb.read', e);
      return null;
    }
  }

  async function listHistoryProjects() {
    try {
      const db = await getHistoryDb();
      if (!db) return [];
      return await new Promise((resolve) => {
        const tx = db.transaction(STORE_NAME, 'readonly');
        const store = tx.objectStore(STORE_NAME);
        const req = store.getAll();
        req.onsuccess = () => {
          const items = Array.isArray(req.result) ? req.result.slice() : [];
          items.sort((a, b) => (Number(b && b.updatedAt) || 0) - (Number(a && a.updatedAt) || 0));
          resolve(items);
        };
        req.onerror = () => resolve([]);
      });
    } catch (e) {
      diag.logWarn('idb.list', e);
      return [];
    }
  }

  async function deleteProjectFromHistory(fileSig) {
    if (!fileSig) return false;
    try {
      const db = await getHistoryDb();
      if (!db) return false;
      return await new Promise((resolve) => {
        const tx = db.transaction(STORE_NAME, 'readwrite');
        const store = tx.objectStore(STORE_NAME);
        store.delete(fileSig);
        tx.oncomplete = () => {
          notifyHistoryUpdated();
          resolve(true);
        };
        tx.onerror = () => resolve(false);
        tx.onabort = () => resolve(false);
      });
    } catch (e) {
      diag.logWarn('idb.delete', e);
      return false;
    }
  }

  async function clearHistoryProjects() {
    try {
      const db = await getHistoryDb();
      if (!db) return false;
      return await new Promise((resolve) => {
        const tx = db.transaction(STORE_NAME, 'readwrite');
        const store = tx.objectStore(STORE_NAME);
        store.clear();
        tx.oncomplete = () => {
          notifyHistoryUpdated();
          resolve(true);
        };
        tx.onerror = () => resolve(false);
        tx.onabort = () => resolve(false);
      });
    } catch (e) {
      diag.logWarn('idb.clear', e);
      return false;
    }
  }

  root.storage = {
    safeGetStorage,
    safeSetStorage,
    parseKeyList,
    saveGroqApiKeys,
    getGroqApiKeysFromKeystore,
    saveGeminiKeyToKeystore,
    getGeminiKeyFromKeystore,
    saveProjectToHistory,
    getProjectFromHistory,
    listHistoryProjects,
    deleteProjectFromHistory,
    clearHistoryProjects
  };
})();
