import test from 'node:test';
import assert from 'node:assert/strict';
import subtitles from '../app/src/main/assets/js/subtitles.js';

const {
  parseSubtitleText,
  distributeWords,
  buildCues,
  findActiveCueIndex,
  mergeChunkResults,
  isWhisperHallucination,
  generateVTT,
  generateSRT,
  generateASS,
  generateTXT
} = subtitles;

test('parseSubtitleText parses VTT and SRT cues cleanly', () => {
  const srt = `1
00:00:01,200 --> 00:00:03,500
Hello <b>world</b>!

2
00:00:04,000 --> 00:00:06,250
Second line
`;
  const parsed = parseSubtitleText(srt);
  assert.equal(parsed.length, 2);
  assert.equal(parsed[0].text, 'Hello world!');
  assert.ok(Math.abs(parsed[0].start - 1.2) < 1e-6);
  assert.ok(Math.abs(parsed[0].end - 3.5) < 1e-6);
  assert.equal(parsed[0].words.length, 2);
});

test('buildCues aligns words to segments in O(W + S) even on large inputs', () => {
  const segCount = 2000;
  const rawSegments = [];
  const rawWords = [];
  for (let i = 0; i < segCount; i++) {
    const start = i * 2.0;
    const end = start + 1.8;
    const words = [];
    for (let j = 0; j < 10; j++) {
      const ws = start + j * 0.16;
      const we = ws + 0.14;
      const w = `w${i}_${j}`;
      words.push(w);
      rawWords.push({ word: w, start: ws, end: we });
    }
    rawSegments.push({ start, end, text: words.join(' ') });
  }

  const t0 = performance.now();
  const cues = buildCues(rawSegments, rawWords, true);
  const elapsedMs = performance.now() - t0;

  assert.equal(cues.length, segCount);
  assert.equal(cues[0].words[0].word, 'w0_0');
  assert.equal(cues[segCount - 1].words[9].word, `w${segCount - 1}_9`);
  assert.ok(elapsedMs < 1500, `Expected fast O(W+S) alignment, took ${elapsedMs}ms`);
});

test('findActiveCueIndex handles cursor hit, sequential advance, and binary search', () => {
  const cues = [
    { start: 1.0, end: 2.0, text: 'A', words: [] },
    { start: 5.0, end: 6.0, text: 'B', words: [] },
    { start: 10.0, end: 12.0, text: 'C', words: [] }
  ];

  assert.equal(findActiveCueIndex(cues, 0.5, 0, 0, -1), -1);
  assert.equal(findActiveCueIndex(cues, 1.5, 0, 0, -1), 0);
  assert.equal(findActiveCueIndex(cues, 5.2, 0, 0, 0), 1);
  assert.equal(findActiveCueIndex(cues, 11.0, 0, 0, -1), 2);
  assert.equal(findActiveCueIndex(cues, 12.4, 0, 0.4, 2), 2);
  // Offset shifts subtitle 500ms earlier (-0.5)
  assert.equal(findActiveCueIndex(cues, 4.6, -0.5, 0, -1), 1);
});

test('mergeChunkResults stitches overlapping chunks without duplicates', () => {
  const chunk0 = {
    index: 0,
    pos: 0,
    cutoff: 0,
    nextCutoff: 59.5,
    segments: [
      { start: 1.0, end: 2.0, text: 'first' },
      { start: 59.6, end: 60.4, text: 'dup_in_chunk0' }
    ],
    words: [{ word: 'first', start: 1.0, end: 2.0 }]
  };
  const chunk1 = {
    index: 1,
    pos: 59.0,
    cutoff: 59.5,
    nextCutoff: 120.0,
    segments: [
      { start: 0.0, end: 0.4, text: 'old_in_chunk1' },
      { start: 0.6, end: 1.4, text: 'kept_in_chunk1' }
    ],
    words: [{ word: 'kept_in_chunk1', start: 0.6, end: 1.4 }]
  };

  const merged = mergeChunkResults([chunk0, chunk1]);
  assert.deepEqual(merged.allSegments.map((s) => s.text), ['first', 'kept_in_chunk1']);
  assert.deepEqual(merged.allWords.map((w) => w.word), ['first', 'kept_in_chunk1']);
});

test('isWhisperHallucination filters known spam and keeps real speech', () => {
  assert.equal(isWhisperHallucination({ text: 'Субтитры сделал DimaTorzok' }, true), true);
  assert.equal(isWhisperHallucination({ text: 'Подписывайтесь на канал' }, true), true);
  assert.equal(isWhisperHallucination({ text: 'Обычный диалог', no_speech_prob: 0.1 }, true), false);
  assert.equal(isWhisperHallucination({ text: 'Субтитры сделал кто-то' }, false), false);
});

test('VTT, SRT, ASS, and TXT generators output valid format markers', () => {
  const cues = [
    {
      start: 1.0,
      end: 3.0,
      text: 'Hello --> world',
      words: [
        { word: 'Hello', start: 1.0, end: 1.8 },
        { word: 'world', start: 1.9, end: 3.0 }
      ]
    }
  ];
  const vtt = generateVTT(cues);
  const srt = generateSRT(cues);
  const ass = generateASS(cues, { size: 20, bottom: 10 });
  const txt = generateTXT(cues);

  assert.ok(vtt.startsWith('WEBVTT\n\n'));
  assert.ok(vtt.includes('Hello —> world'));
  assert.ok(srt.includes('00:00:01,000 --> 00:00:03,000'));
  assert.ok(ass.includes('[Script Info]') && ass.includes('{\\kf'));
  assert.equal(txt, 'Hello --> world');
});

test('parseSubtitleText ignores VTT cue settings after end timestamp', () => {
  const vtt = `WEBVTT

00:00:01.000 --> 00:00:03.000 align:start position:10% line:85%
Positioned caption
`;
  const parsed = parseSubtitleText(vtt);
  assert.equal(parsed.length, 1);
  assert.ok(Math.abs(parsed[0].start - 1.0) < 1e-6);
  assert.ok(Math.abs(parsed[0].end - 3.0) < 1e-6);
  assert.equal(parsed[0].text, 'Positioned caption');
});

test('buildCues clamps overlapping segment timestamps monotonically', () => {
  const cues = buildCues(
    [
      { start: 1.0, end: 4.5, text: 'First overlapping' },
      { start: 3.0, end: 5.0, text: 'Second' },
      { start: 4.0, end: 6.0, text: 'Third' }
    ],
    [],
    false
  );
  assert.equal(cues.length, 3);
  assert.ok(cues[0].end <= cues[1].start);
  assert.ok(cues[1].end <= cues[2].start);
  assert.ok(cues[0].end > cues[0].start);
  assert.ok(cues[1].end > cues[1].start);
});

test('distributeWords weights Cyrillic words with punctuation properly', () => {
  const cue = { start: 10, end: 14, text: 'Привет, мир — длинноеслово!' };
  distributeWords(cue);
  assert.equal(cue.words.length, 4);
  assert.equal(cue.words[0].word, 'Привет,');
  assert.equal(cue.words[2].word, '—');
  assert.equal(cue.words[3].word, 'длинноеслово!');
  for (let i = 0; i < cue.words.length; i++) {
    assert.ok(cue.words[i].end > cue.words[i].start);
    if (i > 0) {
      assert.ok(Math.abs(cue.words[i].start - cue.words[i - 1].end) < 1e-9);
    }
  }
  assert.ok(Math.abs(cue.words[0].start - 10) < 1e-9);
  assert.ok(Math.abs(cue.words[3].end - 14) < 1e-9);
});
