(function (root, factory) {
  const api = factory();
  if (typeof module !== 'undefined' && module.exports) {
    module.exports = api;
  }
  root.SubLyter = root.SubLyter || {};
  root.SubLyter.diagnostics = api;
})(typeof globalThis !== 'undefined' ? globalThis : window, function () {
  'use strict';

  const MAX_LOGS = 120;
  const entries = [];

  function redactSecrets(input) {
    return String(input || '')
      .replace(/gsk_[A-Za-z0-9_\-]+/g, 'gsk_***')
      .replace(/AIza[0-9A-Za-z\-_]+/g, 'AIza***')
      .replace(/(Bearer\s+)[A-Za-z0-9._\-]+/gi, '$1***')
      .replace(/([?&]key=)[^&\s]+/gi, '$1***');
  }

  function record(level, scope, errOrMsg) {
    const raw = errOrMsg && errOrMsg.message ? errOrMsg.message : String(errOrMsg || 'Unknown error');
    entries.push({
      ts: new Date().toISOString(),
      level,
      scope: String(scope || 'app'),
      msg: redactSecrets(raw)
    });
    if (entries.length > MAX_LOGS) {
      entries.splice(0, entries.length - MAX_LOGS);
    }
  }

  function formatLogsText() {
    return entries.map((e) => `[${e.ts}] [${e.level.toUpperCase()}] [${e.scope}] ${e.msg}`).join('\n');
  }

  return {
    redactSecrets,
    logWarn(scope, errOrMsg) { record('warn', scope, errOrMsg); },
    logError(scope, errOrMsg) { record('error', scope, errOrMsg); },
    getLogs() { return entries.slice(); },
    formatLogsText,
    clearLogs() { entries.length = 0; }
  };
});
