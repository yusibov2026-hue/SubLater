import test from 'node:test';
import assert from 'node:assert/strict';
import gemini from '../app/src/main/assets/js/gemini-qa.js';

const { formatBotMessageHTML } = gemini;

test('formatBotMessageHTML escapes XSS HTML payloads', () => {
  const html = formatBotMessageHTML('<script>alert("xss")</script> & <img src=x onerror=alert(1)>');
  assert.ok(!html.includes('<script>'));
  assert.ok(!html.includes('<img'));
  assert.ok(html.includes('&lt;script&gt;'));
  assert.ok(html.includes('&amp;'));
});

test('formatBotMessageHTML renders bold markdown and clickable timestamps', () => {
  const html = formatBotMessageHTML('**Intro** starts at [01:25] and chapter at 1:02:03.');
  assert.ok(html.includes('<strong>Intro</strong>'));
  assert.ok(html.includes('<button type="button" class="chapter-time" data-sec="85">01:25</button>'));
  assert.ok(html.includes('<button type="button" class="chapter-time" data-sec="3723">1:02:03</button>'));
});
