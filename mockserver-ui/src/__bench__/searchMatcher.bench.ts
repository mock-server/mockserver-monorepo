/**
 * Item 5 of commit e09495682: the `searchTextCache` WeakMap behind
 * `cachedJsonText`, used by the deep-search fallback of `matchesItemSearch`.
 *
 * When a search term is not satisfied by an extracted field, the matcher falls
 * back to scanning `JSON.stringify(value)`. Without a cache that ran once per
 * row on every keystroke AND on every ~1/sec push while a term is active.
 *
 * The bench forces the fallback path: the term `needle` only appears deep inside
 * the response body (never in an extracted field), so every item must be
 * serialized. OLD serializes on every call; NEW serializes each stable
 * reference at most once (steady-state cache hits — the keystroke/re-push case).
 */
import { test } from 'vitest';
import { matchesItemSearch as matchesItemSearchNew } from '../lib/searchMatcher';
import { matchesItemSearch as matchesItemSearchOld } from './legacy/searchMatcher.old';
import {
  makeItems,
  type BenchItem,
  PANEL_SIZE,
  SMALL_BODY_BYTES,
  LARGE_BODY_BYTES,
} from './fixtures';

const TERM = 'needle'; // matches only the deep body needle → forces JSON fallback

for (const [scale, bytes] of [
  ['small', SMALL_BODY_BYTES],
  ['large', LARGE_BODY_BYTES],
] as const) {
  const items: BenchItem[] = makeItems(PANEL_SIZE, bytes);
  // Pre-warm the production WeakMap so NEW measures steady-state hits.
  for (const it of items) matchesItemSearchNew(it.value, TERM);

  test(`matchesItemSearch · ${scale} (100 × ${bytes}B) · deep-fallback keystroke`, async ({ bench }) => {
    await bench.compare(
      bench('OLD (JSON.stringify each call)', () => {
        for (const it of items) matchesItemSearchOld(it.value, TERM);
      }),
      bench('NEW (cachedJsonText)', () => {
        for (const it of items) matchesItemSearchNew(it.value, TERM);
      }),
    );
  });
}
