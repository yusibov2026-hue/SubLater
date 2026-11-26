import test from 'node:test';
import assert from 'node:assert/strict';
import '../app/src/main/assets/js/subtitles.js';
import studio from '../app/src/main/assets/js/studio.js';

const {
  cleanTappedWord,
  normalizeFlashcard,
  reviewFlashcard,
  getDueFlashcards,
  buildHistoryPreview,
  getHistoryDurationSec,
  getHistoryLanguages,
  filterHistoryProjects,
  createVoiceNote,
  filterVoiceNotes,
  findOriginalCueForTime
} = studio;

test('cleanTappedWord strips surrounding punctuation while preserving hyphenated words', () => {
  assert.equal(cleanTappedWord('  "rely," '), 'rely');
  assert.equal(cleanTappedWord('«привет!»'), 'привет');
  assert.equal(cleanTappedWord('well-known.'), 'well-known');
});

test('reviewFlashcard schedules spaced intervals for again, good, and easy', () => {
  const now = 1_700_000_000_000;
  const card = normalizeFlashcard({ word: 'resilient', translation: 'устойчивый' }, now);

  const afterAgain = reviewFlashcard(card, 'again', now);
  assert.equal(afterAgain.reps, 0);
  assert.equal(afterAgain.dueAt, now + 10 * 60 * 1000);

  const afterGood = reviewFlashcard(card, 'good', now);
  assert.equal(afterGood.reps, 1);
  assert.equal(afterGood.dueAt, now + 24 * 60 * 60 * 1000);

  const afterEasy = reviewFlashcard(card, 'easy', now);
  assert.equal(afterEasy.reps, 2);
  assert.ok(afterEasy.dueAt >= now + 4 * 24 * 60 * 60 * 1000);

  const due = getDueFlashcards([afterGood, afterAgain], now + 15 * 60 * 1000);
  assert.deepEqual(due.map((c) => c.id), [afterAgain.id]);
});

test('Subtitle History helpers build preview, extract duration/languages, and filter projects', () => {
  const p1 = {
    fileSig: 'sig_1',
    fileName: 'lecture_intro.mp4',
    model: 'whisper-large-v3-turbo',
    isComplete: true,
    activeLang: 'Russian',
    translations: {
      Russian: [
        { start: 0.5, end: 4.2, text: 'Привет всем!' },
        { start: 5.0, end: 12.6, text: 'Сегодня изучаем квантовую физику.' }
      ]
    },
    rawSegments: [
      { start: 0.5, end: 4.2, text: 'Hello everyone!' },
      { start: 5.0, end: 12.6, text: 'Today we study quantum physics.' }
    ]
  };
  const p2 = {
    fileSig: 'sig_2',
    fileName: 'podcast_draft.mp3',
    model: 'whisper-large-v3',
    isComplete: false,
    partialChunks: {
      '0': {
        segments: [
          { start: 0, end: 8.4, text: 'Welcome to the morning show.' }
        ]
      }
    }
  };
  const p3 = {
    fileSig: 'sig_3',
    fileName: 'interview.mkv',
    model: 'whisper-large-v3-turbo',
    isComplete: true,
    activeLang: '',
    cues: [
      { start: 1.0, end: 64.2, text: 'First question about rockets.' }
    ]
  };

  assert.equal(buildHistoryPreview(p1, 200), 'Привет всем! Сегодня изучаем квантовую физику.');
  assert.equal(buildHistoryPreview(p2, 20), 'Welcome to the morni…');
  assert.equal(getHistoryDurationSec(p1), 13);
  assert.equal(getHistoryDurationSec(p3), 64);
  assert.deepEqual(getHistoryLanguages(p1), ['Russian']);
  assert.deepEqual(getHistoryLanguages(p3), []);

  assert.equal(filterHistoryProjects([p1, p2, p3], '', 'all').length, 3);
  assert.deepEqual(filterHistoryProjects([p1, p2, p3], '', 'translated').map((p) => p.fileSig), ['sig_1']);
  assert.deepEqual(filterHistoryProjects([p1, p2, p3], '', 'incomplete').map((p) => p.fileSig), ['sig_2']);
  assert.deepEqual(filterHistoryProjects([p1, p2, p3], 'квантовую', 'all').map((p) => p.fileSig), ['sig_1']);
  assert.deepEqual(filterHistoryProjects([p1, p2, p3], 'rockets', 'all').map((p) => p.fileSig), ['sig_3']);
});

test('Voice Notes archive supports creation and search across action items and transcripts', () => {
  const v1 = createVoiceNote({
    title: 'Product Sync',
    durationSec: 42,
    summary: 'Discussed launch checklist',
    actionItems: ['Update privacy policy URL', 'Prepare Play screenshots'],
    transcript: 'Let us upload the AAB build on Friday'
  });
  const v2 = createVoiceNote({
    title: 'Grocery List',
    durationSec: 12,
    summary: 'Milk and coffee',
    transcript: 'Buy espresso beans'
  });

  assert.equal(filterVoiceNotes([v1, v2], 'privacy').length, 1);
  assert.equal(filterVoiceNotes([v1, v2], 'aab')[0].id, v1.id);
  assert.equal(filterVoiceNotes([v1, v2], '').length, 2);
});

test('findOriginalCueForTime aligns original subtitle cue to translated playback timestamp', () => {
  const orig = [
    { start: 1.0, end: 3.0, text: 'Hello everyone' },
    { start: 5.0, end: 7.5, text: 'Welcome back' }
  ];
  assert.equal(findOriginalCueForTime(orig, 2.0).text, 'Hello everyone');
  assert.equal(findOriginalCueForTime(orig, 6.1).text, 'Welcome back');
  assert.equal(findOriginalCueForTime(orig, 4.0), null);
});
