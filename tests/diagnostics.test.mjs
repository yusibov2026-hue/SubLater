import test from 'node:test';
import assert from 'node:assert/strict';
import diag from '../app/src/main/assets/js/diagnostics.js';

const { redactSecrets, logWarn, logError, getLogs, formatLogsText, clearLogs } = diag;

test('redactSecrets strips Groq, Gemini, Bearer, and URL query keys', () => {
  const sample =
    'Failed gsk_AbC123_xyz and AIzaSyD-1234567890_key with Bearer eyJhbGciOi.tok?key=secretVal&foo=1&key=anotherSecret';
  const redacted = redactSecrets(sample);

  assert.ok(!redacted.includes('gsk_AbC123_xyz'));
  assert.ok(!redacted.includes('AIzaSyD-1234567890_key'));
  assert.ok(!redacted.includes('eyJhbGciOi.tok'));
  assert.ok(!redacted.includes('secretVal'));
  assert.ok(!redacted.includes('anotherSecret'));
  assert.ok(redacted.includes('gsk_***'));
  assert.ok(redacted.includes('AIza***'));
  assert.ok(redacted.includes('Bearer ***'));
  assert.ok(redacted.includes('?key=***'));
  assert.ok(redacted.includes('&key=***'));
});

test('diagnostics ring buffer records warnings/errors, formats text, caps at 120, and clears cleanly', () => {
  clearLogs();
  logWarn('player.test', 'Minor warning with gsk_secret_123');
  logError('groq.test', new Error('Boom with ?key=topsecret'));

  const logs = getLogs();
  assert.equal(logs.length, 2);
  assert.equal(logs[0].level, 'warn');
  assert.equal(logs[0].scope, 'player.test');
  assert.ok(logs[0].msg.includes('gsk_***'));
  assert.equal(logs[1].level, 'error');
  assert.ok(logs[1].msg.includes('?key=***'));

  const formatted = formatLogsText();
  assert.ok(formatted.includes('[WARN] [player.test]'));
  assert.ok(formatted.includes('[ERROR] [groq.test]'));

  clearLogs();
  for (let i = 0; i < 150; i++) {
    logWarn('cap.test', `entry-${i}`);
  }
  const capped = getLogs();
  assert.equal(capped.length, 120);
  assert.equal(capped[0].msg, 'entry-30');
  assert.equal(capped[119].msg, 'entry-149');

  clearLogs();
  assert.equal(getLogs().length, 0);
  assert.equal(formatLogsText(), '');
});
