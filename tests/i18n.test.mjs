import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import '../app/src/main/assets/js/i18n.js';

const i18n = globalThis.SubLyter.i18n;
const __dirname = path.dirname(fileURLToPath(import.meta.url));
const I18N_DIR = path.resolve(__dirname, '../app/src/main/assets/i18n');

function extractPlaceholders(str) {
  return [...String(str || '').matchAll(/\{([a-zA-Z0-9_]+)\}/g)].map((m) => m[1]).sort();
}

test('i18n defines exactly 54 unique languages', () => {
  assert.equal(i18n.LANGUAGES.length, 54);
  const codes = new Set(i18n.LANGUAGES.map((l) => l.code));
  assert.equal(codes.size, 54);
});

test('i18n resolves BCP-47 and legacy Android locale tags cleanly', () => {
  assert.equal(i18n.resolveLanguageCode('ru-RU'), 'ru');
  assert.equal(i18n.resolveLanguageCode('ru_UA'), 'ru');
  assert.equal(i18n.resolveLanguageCode('zh-Hant-TW'), 'zh-TW');
  assert.equal(i18n.resolveLanguageCode('zh-HK'), 'zh-TW');
  assert.equal(i18n.resolveLanguageCode('zh-Hans-CN'), 'zh-CN');
  assert.equal(i18n.resolveLanguageCode('iw-IL'), 'he');
  assert.equal(i18n.resolveLanguageCode('in-ID'), 'id');
  assert.equal(i18n.resolveLanguageCode('tl-PH'), 'fil');
  assert.equal(i18n.resolveLanguageCode('no-NO'), 'nb');
  assert.equal(i18n.resolveLanguageCode('tr-TR'), 'tr');
  assert.equal(i18n.resolveLanguageCode('tk-TM'), 'tk');
  assert.equal(i18n.resolveLanguageCode('xx-YY'), 'en');
});

test('i18n auto mode detects phone system locale and updates AI output language', async () => {
  const prevWin = globalThis.window;
  try {
    globalThis.window = { AndroidBridge: { getSystemLanguageTag: () => 'tr-TR' } };
    assert.equal(i18n.detectSystemLang(), 'tr');
    await i18n.setLang('auto');
    assert.equal(i18n.getPref(), 'auto');
    assert.equal(i18n.getLang(), 'tr');
    assert.equal(i18n.getAiOutputLanguageName(''), 'Turkish');
    assert.equal(i18n.getAiOutputLanguageName('Japanese'), 'Japanese');
    assert.equal(i18n.isRtl('ar'), true);
    assert.equal(i18n.isRtl('tr'), false);
  } finally {
    globalThis.window = prevWin;
    await i18n.setLang('en');
  }
});

test('all 54 locale JSON files exist, parse validly, match en.json keys, and preserve placeholders', () => {
  const enPath = path.join(I18N_DIR, 'en.json');
  const enJson = JSON.parse(fs.readFileSync(enPath, 'utf8'));
  const enKeys = Object.keys(enJson).sort();

  for (const lang of i18n.LANGUAGES) {
    const filePath = path.join(I18N_DIR, `${lang.code}.json`);
    assert.ok(fs.existsSync(filePath), `Missing locale file: ${lang.code}.json`);
    const data = JSON.parse(fs.readFileSync(filePath, 'utf8'));
    const keys = Object.keys(data).sort();
    assert.deepEqual(keys, enKeys, `Key mismatch in ${lang.code}.json`);
    for (const k of enKeys) {
      assert.equal(typeof data[k], 'string', `Non-string value for ${k} in ${lang.code}.json`);
      assert.ok(data[k].trim().length > 0, `Empty value for ${k} in ${lang.code}.json`);
      assert.deepEqual(
        extractPlaceholders(data[k]),
        extractPlaceholders(enJson[k]),
        `Placeholder mismatch for ${k} in ${lang.code}.json`
      );
    }
  }
});
