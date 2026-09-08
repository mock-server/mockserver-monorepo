/**
 * Item 1 (L1 array-identity) + Item 2 (p===n pre-check before stringify) of
 * commit e09495682.
 *
 * IMPORTANT interpretation note (reported honestly in the results table):
 * for the realistic idle case — a fresh WebSocket push of byte-identical
 * content (brand-new object references) — OLD and NEW do the SAME per-entry
 * `JSON.stringify` work. NEW's only extra work is `changed` bookkeeping; its
 * benefit is returning the PREVIOUS array identity so subscribed panels skip
 * re-rendering. That downstream render-skip is NOT visible in this microbench —
 * it is measured separately in `src/__tests__/perf-renderCount.test.tsx`.
 *
 * The `p === n` pre-check (Item 2) only saves work when the SAME reference
 * appears in both prev and next, which does not happen on a real push (every
 * push is a fresh JSON.parse). It is benchmarked here as a synthetic scenario
 * and labelled as such.
 */
import { test } from 'vitest';
import { reconcileByKeyOld, type ReconcileCache } from './legacy/reconcile.old';
import { reconcileByKeyNew } from './legacy/reconcile.new';
import {
  makeItems,
  deepCloneItems,
  cloneWithOneChanged,
  type BenchItem,
  PANEL_SIZE,
  SMALL_BODY_BYTES,
  LARGE_BODY_BYTES,
} from './fixtures';

type Reconcile = (prev: BenchItem[], next: BenchItem[], cache: ReconcileCache) => BenchItem[];

/** Warm a cache exactly as the store would after the first push, then return it. */
function warm(reconcile: Reconcile, items: BenchItem[]): ReconcileCache {
  const cache: ReconcileCache = new Map();
  reconcile([], items, cache);
  return cache;
}

for (const [scale, bytes] of [
  ['small', SMALL_BODY_BYTES],
  ['large', LARGE_BODY_BYTES],
] as const) {
  const items = makeItems(PANEL_SIZE, bytes);
  const nextIdentical = deepCloneItems(items); // fresh refs, identical content
  const nextChanged = cloneWithOneChanged(items, bytes); // 1 of 100 differs

  test(`reconcile · ${scale} (100 × ${bytes}B) · idle identical push`, async ({ bench }) => {
    const cacheOld = warm(reconcileByKeyOld, items);
    const cacheNew = warm(reconcileByKeyNew, items);
    await bench.compare(
      bench('OLD', () => {
        reconcileByKeyOld(items, nextIdentical, cacheOld);
      }),
      bench('NEW', () => {
        reconcileByKeyNew(items, nextIdentical, cacheNew);
      }),
    );
  });

  test(`reconcile · ${scale} (100 × ${bytes}B) · 1 row changed`, async ({ bench }) => {
    const cacheOld = warm(reconcileByKeyOld, items);
    const cacheNew = warm(reconcileByKeyNew, items);
    await bench.compare(
      bench('OLD', () => {
        reconcileByKeyOld(items, nextChanged, cacheOld);
      }),
      bench('NEW', () => {
        reconcileByKeyNew(items, nextChanged, cacheNew);
      }),
    );
  });

  test(`reconcile · ${scale} (100 × ${bytes}B) · SYNTHETIC same-ref re-push (p===n)`, async ({ bench }) => {
    const cacheOld = warm(reconcileByKeyOld, items);
    const cacheNew = warm(reconcileByKeyNew, items);
    await bench.compare(
      bench('OLD', () => {
        reconcileByKeyOld(items, items, cacheOld);
      }),
      bench('NEW', () => {
        reconcileByKeyNew(items, items, cacheNew);
      }),
    );
  });
}
