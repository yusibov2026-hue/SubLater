import test from 'node:test';
import assert from 'node:assert/strict';
import gemini from '../app/src/main/assets/js/gemini-qa.js';

const {
  formatBotMessageHTML,
  shouldAttachVideoFrame,
  buildTranscriptContext,
  buildGeminiRequestBody,
  extractCandidateText
} = gemini;

test('formatBotMessageHTML escapes XSS HTML payloads', () => {
  const html = formatBotMessageHTML('<script>alert("xss")</script> & <img src=x onerror=alert(1)>');
  assert.ok(!html.includes('<script>'));
  assert.ok(!html.includes('<img'));
  assert.ok(html.includes('&lt;script&gt;'));
  assert.ok(html.includes('&amp;'));
});

test('formatBotMessageHTML renders markdown lists, inline code, and clickable timestamps', () => {
  const html = formatBotMessageHTML('**Intro** starts at [01:25] using `whisper-base` and chapter at 1:02:03.\n\n- Point A [02:00]\n- Point B');
  assert.ok(html.includes('<strong>Intro</strong>'));
  assert.ok(html.includes('<code>whisper-base</code>'));
  assert.ok(html.includes('<button type="button" class="chapter-time" data-sec="85">01:25</button>'));
  assert.ok(html.includes('<button type="button" class="chapter-time" data-sec="3723">1:02:03</button>'));
  assert.ok(html.includes('<ul><li>Point A <button type="button" class="chapter-time" data-sec="120">02:00</button></li><li>Point B</li></ul>'));
});

test('shouldAttachVideoFrame only requests frames for visual questions or visual chips', () => {
  assert.equal(shouldAttachVideoFrame('Summarize this video', false), false);
  assert.equal(shouldAttachVideoFrame('What is the main topic?', false), false);
  assert.equal(shouldAttachVideoFrame('What is in frame right now?', false), true);
  assert.equal(shouldAttachVideoFrame('Что сейчас написано на экране?', false), true);
  assert.equal(shouldAttachVideoFrame('Any text', true), true);
});

test('buildTranscriptContext preserves full cues within limit and buildGeminiRequestBody uses Hydrogen minimal thinking', () => {
  const cues = Array.from({ length: 10 }, (_, i) => ({ start: i * 5, text: `Line ${i}` }));
  const ctx = buildTranscriptContext(cues, (s) => `0:${String(s).padStart(2, '0')}`);
  assert.ok(ctx.includes('[0:00] Line 0'));
  assert.ok(ctx.includes('[0:45] Line 9'));

  const hBody = buildGeminiRequestBody('gemini-4-hydrogen', 'sys', [{ role: 'user', parts: [{ text: 'hi' }] }]);
  assert.equal(hBody.generationConfig.thinkingConfig.thinkingLevel, 'LOW');
  assert.equal(hBody.generationConfig.thinkingConfig.includeThoughts, false);

  const fBody = buildGeminiRequestBody('gemini-2.5-flash', 'sys', [{ role: 'user', parts: [{ text: 'hi' }] }]);
  assert.equal(fBody.generationConfig.thinkingConfig, undefined);

  const extracted = extractCandidateText({
    candidates: [{ content: { parts: [{ thought: true, text: 'secret' }, { text: 'Answer' }] } }]
  });
  assert.equal(extracted, 'Answer');
});
