import test from 'node:test';
import assert from 'node:assert/strict';
import groqApi from '../app/src/main/assets/js/groq-api.js';

const { KeyPool, runWithConcurrency, normalizeWavBlob, fetchGroqWithRotation, sendToGroq } = groqApi;

function makePcmWavBlob(amplitude, sampleRate = 16000, samplesCount = 400) {
  const buf = new ArrayBuffer(44 + samplesCount * 2);
  const dv = new DataView(buf);
  const writeStr = (off, s) => {
    for (let i = 0; i < s.length; i++) dv.setUint8(off + i, s.charCodeAt(i));
  };
  writeStr(0, 'RIFF');
  dv.setUint32(4, 36 + samplesCount * 2, true);
  writeStr(8, 'WAVE');
  writeStr(12, 'fmt ');
  dv.setUint32(16, 16, true);
  dv.setUint16(20, 1, true);
  dv.setUint16(22, 1, true);
  dv.setUint32(24, sampleRate, true);
  dv.setUint32(28, sampleRate * 2, true);
  dv.setUint16(32, 2, true);
  dv.setUint16(34, 16, true);
  writeStr(36, 'data');
  dv.setUint32(40, samplesCount * 2, true);
  const pcm = new Int16Array(buf, 44, samplesCount);
  for (let i = 0; i < samplesCount; i++) {
    pcm[i] = i % 2 === 0 ? amplitude : -amplitude;
  }
  return new Blob([buf], { type: 'audio/wav' });
}

test('KeyPool rotates round-robin and skips rate-limited keys', () => {
  const pool = new KeyPool(() => ['k1', 'k2', 'k3']);
  const now = 100_000;

  assert.equal(pool.pickKey(now).key, 'k1');
  assert.equal(pool.pickKey(now).key, 'k2');

  pool.markRateLimited('k3', 10, now);
  const picked = pool.pickKey(now + 1000);
  assert.equal(picked.key, 'k1');
  assert.equal(picked.allLimited, false);

  // If all keys are cooled down, pick earliest-to-recover with positive waitMs
  pool.markRateLimited('k1', 5, now + 1000);
  pool.markRateLimited('k2', 20, now + 1000);
  const all = pool.pickKey(now + 1000);
  assert.equal(all.allLimited, true);
  assert.equal(all.key, 'k1');
  assert.equal(all.waitMs, 5000);
});

test('runWithConcurrency preserves item order and respects concurrency cap', async () => {
  let active = 0;
  let maxActive = 0;
  const items = [10, 20, 30, 40, 50];
  const res = await runWithConcurrency(items, 2, async (x) => {
    active++;
    maxActive = Math.max(maxActive, active);
    await new Promise((r) => setTimeout(r, 10));
    active--;
    return x * 2;
  });
  assert.deepEqual(res, [20, 40, 60, 80, 100]);
  assert.ok(maxActive <= 2);
});

test('normalizeWavBlob amplifies quiet PCM16 and skips already-loud audio', async () => {
  const quiet = makePcmWavBlob(500);
  const normalized = await normalizeWavBlob(quiet);
  const dv = new DataView(await normalized.arrayBuffer());
  const boostedSample = Math.abs(dv.getInt16(44, true));
  assert.ok(boostedSample >= 3500, `Expected amplified sample >= 3500, got ${boostedSample}`);

  const loud = makePcmWavBlob(12000);
  const untouched = await normalizeWavBlob(loud);
  assert.equal(untouched, loud);
});

test('KeyPool.markInvalid excludes invalid keys and reset restores them', () => {
  const pool = new KeyPool(() => ['k1', 'k2']);
  assert.equal(pool.hasValidKeys(), true);

  pool.markInvalid('k1');
  assert.equal(pool.hasValidKeys(), true);
  assert.equal(pool.pickKey(1000).key, 'k2');
  assert.equal(pool.pickKey(1000).key, 'k2');

  pool.markInvalid('k2');
  assert.equal(pool.hasValidKeys(), false);
  const allBad = pool.pickKey(1000);
  assert.equal(allBad.allInvalid, true);
  assert.equal(allBad.key, '');

  pool.reset();
  assert.equal(pool.hasValidKeys(), true);
  assert.equal(pool.pickKey(1000).allInvalid, false);
});

test('fetchGroqWithRotation fails over on 401 and switches immediately on 429', async () => {
  const originalFetch = globalThis.fetch;
  try {
    // 1) 401 failover from badKey -> goodKey
    const authAttempts = [];
    globalThis.fetch = async (_url, opts) => {
      const key = opts.headers.Authorization.replace('Bearer ', '');
      authAttempts.push(key);
      if (key === 'gsk_bad') {
        return new Response('unauthorized', { status: 401 });
      }
      return new Response('{"ok":true}', { status: 200 });
    };

    const pool401 = new KeyPool(() => ['gsk_bad', 'gsk_good']);
    const statuses401 = [];
    const res401 = await fetchGroqWithRotation(
      'https://example.test/v1/audio',
      (k) => ({ method: 'POST', headers: { Authorization: 'Bearer ' + k } }),
      null,
      3,
      { keyPool: pool401, onStatus: (s) => statuses401.push(s) }
    );
    assert.equal(res401.status, 200);
    assert.deepEqual(authAttempts, ['gsk_bad', 'gsk_good']);
    assert.equal(pool401.hasValidKeys(), true);
    assert.equal(pool401.invalidKeys.has('gsk_bad'), true);
    assert.ok(statuses401.some((s) => s.includes('401')));

    // 2) 429 failover from limitedKey -> freshKey using retry-after
    const rateAttempts = [];
    globalThis.fetch = async (_url, opts) => {
      const key = opts.headers.Authorization.replace('Bearer ', '');
      rateAttempts.push(key);
      if (key === 'gsk_limited') {
        return new Response('rate limited', {
          status: 429,
          headers: { 'retry-after': '5' }
        });
      }
      return new Response('{"ok":true}', { status: 200 });
    };

    const pool429 = new KeyPool(() => ['gsk_limited', 'gsk_fresh']);
    const statuses429 = [];
    const res429 = await fetchGroqWithRotation(
      'https://example.test/v1/audio',
      (k) => ({ method: 'POST', headers: { Authorization: 'Bearer ' + k } }),
      null,
      3,
      { keyPool: pool429, onStatus: (s) => statuses429.push(s) }
    );
    assert.equal(res429.status, 200);
    assert.deepEqual(rateAttempts, ['gsk_limited', 'gsk_fresh']);
    assert.ok(pool429.cooldowns.get('gsk_limited') > Date.now());
    assert.ok(statuses429.some((s) => s.includes('rate-limited')));
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test('sendToGroq skips normalizeWavBlob when chunkPeak >= 3500', async () => {
  const originalFetch = globalThis.fetch;
  let arrayBufferReads = 0;
  const baseBlob = makePcmWavBlob(500);
  const trackedBlob = {
    size: baseBlob.size,
    type: baseBlob.type,
    async arrayBuffer() {
      arrayBufferReads++;
      return baseBlob.arrayBuffer();
    }
  };
  // Pass real Blob for FormData compatibility while overriding arrayBuffer on a proxy/wrapper
  Object.defineProperty(baseBlob, 'arrayBuffer', {
    value: () => {
      arrayBufferReads++;
      return Blob.prototype.arrayBuffer.call(baseBlob);
    }
  });
  void trackedBlob;

  globalThis.fetch = async () => ({
    ok: true,
    status: 200,
    async json() {
      return { text: 'ok', segments: [], words: [] };
    }
  });

  try {
    const pool = new KeyPool(() => ['gsk_test_key']);
    await sendToGroq(baseBlob, 'c0.wav', 'whisper-large-v3-turbo', '', null, {
      keyPool: pool,
      chunkPeak: 4200
    });
    assert.equal(arrayBufferReads, 0, 'normalizeWavBlob should be skipped when chunkPeak >= 3500');

    await sendToGroq(baseBlob, 'c1.wav', 'whisper-large-v3-turbo', '', null, {
      keyPool: pool,
      chunkPeak: 120
    });
    assert.equal(arrayBufferReads, 1, 'normalizeWavBlob should run when chunkPeak is low');
  } finally {
    globalThis.fetch = originalFetch;
  }
});
