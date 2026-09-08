/**
 * Item 4 of commit e09495682: the `parseTrafficCache` WeakMap behind
 * `cachedParseTraffic`, and its use inside `groupBySession`.
 *
 * `parseTraffic` fully classifies a request/response pair (and for streamed
 * bodies reassembles SSE + base64-decodes) — expensive to re-run for every item
 * on every ~1/sec push and every render that groups or summarises traffic.
 * `reconcileByKey` preserves each unchanged item's `value` reference across
 * pushes, so a WeakMap keyed on that reference returns the previous parse until
 * the object actually changes.
 *
 * OLD = uncached `parseTraffic` (re-parse every call, the pre-optimization path).
 * NEW = `cachedParseTraffic` (steady-state cache hits over stable references).
 * The bench measures the STEADY-STATE cost (vitest discards warmup iterations),
 * which is exactly the idle-re-push / re-render scenario the cache targets.
 */
import { test } from 'vitest';
import { parseTraffic, cachedParseTraffic } from '../lib/llmTraffic';
import { groupBySession } from '../lib/sessionGrouping';
import { groupBySession as groupBySessionOld } from './legacy/sessionGrouping.old';
import {
  makeItems,
  type BenchItem,
  PANEL_SIZE,
  SMALL_BODY_BYTES,
  LARGE_BODY_BYTES,
} from './fixtures';

for (const [scale, bytes] of [
  ['small', SMALL_BODY_BYTES],
  ['large', LARGE_BODY_BYTES],
] as const) {
  const items: BenchItem[] = makeItems(PANEL_SIZE, bytes);
  // Pre-warm the production WeakMap so the NEW benches measure steady-state hits.
  for (const it of items) cachedParseTraffic(it.value);

  test(`parseTraffic · ${scale} (100 × ${bytes}B) · parse all 100 items`, async ({ bench }) => {
    await bench.compare(
      bench('OLD parseTraffic (uncached)', () => {
        for (const it of items) parseTraffic(it.value);
      }),
      bench('NEW cachedParseTraffic', () => {
        for (const it of items) cachedParseTraffic(it.value);
      }),
    );
  });

  test(`groupBySession · ${scale} (100 × ${bytes}B) · group all 100 items`, async ({ bench }) => {
    await bench.compare(
      bench('OLD (parseTraffic)', () => {
        groupBySessionOld(items, []);
      }),
      bench('NEW (cachedParseTraffic)', () => {
        groupBySession(items, []);
      }),
    );
  });
}
