import test from 'node:test';
import assert from 'node:assert/strict';
import localWhisper from '../app/src/main/assets/js/local-whisper.js';

const {
  isLocalWhisperModel,
  getLocalModelConfig,
  decodeWavToFloat32,
  normalizeWhisperChunksToSegments
} = localWhisper;

function makeTestWavBuffer(sampleRate, samples) {
  const len = samples.length;
  const buf = new ArrayBuffer(44 + len * 2);
  const view = new DataView(buf);
  const writeStr = (off, s) => {
    for (let i = 0; i < s.length; i++) view.setUint8(off + i, s.charCodeAt(i));
  };
  writeStr(0, 'RIFF');
  view.setUint32(4, 36 + len * 2, true);
  writeStr(8, 'WAVE');
  writeStr(12, 'fmt ');
  view.setUint32(16, 16, true);
  view.setUint16(20, 1, true);
  view.setUint16(22, 1, true);
  view.setUint32(24, sampleRate, true);
  view.setUint32(28, sampleRate * 2, true);
  view.setUint16(32, 2, true);
  view.setUint16(34, 16, true);
  writeStr(36, 'data');
  view.setUint32(40, len * 2, true);
  for (let i = 0; i < len; i++) {
    view.setInt16(44 + i * 2, Math.round(samples[i] * 32767), true);
  }
  return buf;
}

test('local-whisper registers base and small ONNX models with clean metadata', () => {
  assert.equal(isLocalWhisperModel('local-whisper-base'), true);
  assert.equal(isLocalWhisperModel('local-whisper-small'), true);
  assert.equal(isLocalWhisperModel('whisper-large-v3'), false);
  assert.equal(getLocalModelConfig('local-whisper-base').repoId, 'onnx-community/whisper-base');
  assert.equal(getLocalModelConfig('local-whisper-small').repoId, 'onnx-community/whisper-small');
});

test('decodeWavToFloat32 decodes 16-bit PCM WAV and resamples 8 kHz to 16 kHz Float32Array', () => {
  const wav8k = makeTestWavBuffer(8000, [0, 0.5, -0.5, 0.25]);
  const pcm16k = decodeWavToFloat32(wav8k);
  assert.ok(pcm16k instanceof Float32Array);
  assert.equal(pcm16k.length, 8);
  assert.ok(Math.abs(pcm16k[2] - 0.5) < 0.01);
});

test('normalizeWhisperChunksToSegments converts chunks and null end timestamps monotonically', () => {
  const res = normalizeWhisperChunksToSegments({
    chunks: [
      { timestamp: [0, 2.5], text: ' First line ' },
      { timestamp: [2.2, null], text: 'Second line here' }
    ]
  });
  assert.equal(res.segments.length, 2);
  assert.deepEqual(res.segments[0], { start: 0, end: 2.5, text: 'First line' });
  assert.equal(res.segments[1].start, 2.5);
  assert.ok(res.segments[1].end > 2.5);
  assert.equal(res.segments[1].text, 'Second line here');
});
