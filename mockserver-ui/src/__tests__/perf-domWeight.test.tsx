/**
 * REGRESSION GUARD: how much DOM the dashboard mounts for a full dataset.
 *
 * WHY THIS IS THE MEASUREMENT THAT MATTERS. The server caps each panel at 100
 * items and throttles to ~1 push/sec, so the client never accumulates an
 * unbounded list and the store's per-push CPU is bounded (measured in
 * `src/__bench__/storePush.bench.ts`: 1.8 ms at REST body sizes, 12.5 ms at
 * 8 kB bodies). What is NOT bounded is the DOM: a panel that mounts one row
 * element per item mounts ~11-22 elements per row, and re-renders all of them
 * whenever the items change. At 200 rows that is ~2,200 elements rebuilt on
 * every push under load — an order of magnitude more work than the store does.
 *
 * DOM ELEMENT COUNT, not milliseconds, is what this file asserts on. Wall-clock
 * numbers under jsdom on a shared CI agent are not reproducible enough to gate;
 * element counts are exact, deterministic, and are the thing that makes the
 * page heavy. The timings below are recorded for orientation only and are NOT
 * asserted.
 *
 * MEASURED (2026-09-21, macOS arm64, node v22.21.1, jsdom, 400 B bodies; mean
 * ms for one `applyMessage` with the panel mounted):
 *
 *   Panel                        rows   DOM elements   1 new   all new
 *   TrafficInspector, un-windowed 200   2,234          37.7    126.0
 *   TrafficInspector, windowed     50   145            -       -
 *   TrafficInspector, windowed    200   145            -       -
 *   LogPanel, all rows mounted    100   2,220          59.0    190.8
 *   LogPanel, windowed            100   220            16.1     41.0
 *
 * Both panels now render their store array through `ProgressiveList`, so the
 * windowed row markup is the SAME data rendered by the SAME component; the only
 * difference is whether `ProgressiveList` could resolve a scroll viewport. That
 * is the size of the virtualization lever: for TrafficInspector the DOM is now
 * FLAT in the dataset — 145 elements at 50 rows and at 200 rows alike — against
 * ~2,234 un-windowed at 200. (`TrafficInspector` was virtualized 2026-09-21;
 * before that it rendered `filtered.map(...)` directly and this file pinned only
 * a per-row budget, since the windowing invariant did not yet apply.)
 *
 * THE jsdom LAYOUT FACT this file works around: jsdom DOES resolve the emotion
 * `overflow-y: auto` on each panel's internal scroll Box, so
 * `ProgressiveList.findScrollParent` FINDS a scroll ancestor — but jsdom reports
 * its `offsetHeight` as 0, a zero-height viewport, so `ProgressiveList` takes its
 * "no usable viewport" branch and renders EVERY row (the full set stays
 * reachable when windowing cannot run). That is why, with no stub, both panels
 * mount their whole dataset in jsdom — exactly as they did before virtualization,
 * which is why every other test in the suite is unaffected. The per-row budget
 * test below relies on it: with the whole dataset mounted, per-mounted-row cost
 * is (elements delta) / (mounted-row delta).
 *
 * To exercise the WINDOWED branch, the guards stub `offsetHeight` and `clientHeight`
 * to 600 px for every element (the virtualizer sizes its viewport from
 * `clientHeight`), so the discovered ancestor has a real viewport; the extra
 * `data-scrollhost` -> `overflow-y: auto` override is a harmless belt-and-braces
 * anchor. The stub is scoped to this file and removed in `afterAll`.
 *
 * NOTE the windowed row carries ONE extra wrapper `<div data-vrow>` that
 * `ProgressiveList` adds per mounted row, so the per-mounted-row budget below
 * (12.0, was ~11) includes it. That is bounded overhead on a handful of mounted
 * rows, not on the whole dataset — which is the entire point of windowing.
 */
import { describe, it, expect, beforeEach, afterEach, afterAll } from 'vitest';
import { render, act, cleanup, fireEvent, screen } from '@testing-library/react';
import { ThemeProvider } from '@mui/material/styles';
import { buildTheme } from '../theme';
import LogPanel from '../components/LogPanel';
import TrafficInspector from '../components/TrafficInspector';
import { useDashboardStore } from '../store';
import type { WebSocketMessage } from '../types';
import { makeFrame, REST_BODY_BYTES, UI_UPDATE_ITEM_LIMIT } from '../__bench__/fixtures';

function elementCount(container: HTMLElement): number {
  return container.querySelectorAll('*').length;
}

function applyFrame(generation: number, count: number): void {
  act(() => {
    useDashboardStore
      .getState()
      .applyMessage(
        makeFrame(generation, 'all', REST_BODY_BYTES, count) as unknown as WebSocketMessage,
      );
  });
}

beforeEach(() => {
  useDashboardStore.getState().clearUI();
});
afterEach(() => cleanup());

function vrowCount(container: HTMLElement): number {
  return container.querySelectorAll('[data-vrow]').length;
}

// ---------------------------------------------------------------------------
// 1. Per-MOUNTED-row weight: each row that IS mounted stays lean
// ---------------------------------------------------------------------------

describe('DOM weight — TrafficInspector per-mounted-row cost', () => {
  /**
   * The windowing invariant (section 3) proves the DOM stays bounded across the
   * dataset. This test guards the OTHER axis: that each row `ProgressiveList`
   * DOES mount stays lean, so windowing a heavy row cannot quietly regress into
   * mounting a heavy row.
   *
   * It runs WITHOUT the layout stub, so `ProgressiveList` finds the internal
   * scroll ancestor but sees a zero-height viewport and renders EVERY row (see
   * the file header). The per-mounted-row cost is therefore the difference in
   * element count divided by the difference in mounted rows (`data-vrow`), which
   * cancels the fixed panel chrome. Datasets are kept small only to keep the
   * render cheap; the `fewVrows`/`moreVrows` premise checks assert that every
   * seeded row really did mount, so the denominator is real rows.
   */
  it('mounts a bounded number of elements PER MOUNTED ROW', () => {
    const fewRows = 3; // -> 6 mounted (recorded + proxied), under the 20 slice
    applyFrame(0, fewRows);
    const few = render(
      <ThemeProvider theme={buildTheme('dark')}>
        <TrafficInspector />
      </ThemeProvider>,
    );
    const fewElements = elementCount(few.container);
    const fewVrows = vrowCount(few.container);
    cleanup();

    useDashboardStore.getState().clearUI();
    const moreRows = 8; // -> 16 mounted, still under the 20 slice
    applyFrame(0, moreRows);
    const more = render(
      <ThemeProvider theme={buildTheme('dark')}>
        <TrafficInspector />
      </ThemeProvider>,
    );
    const moreElements = elementCount(more.container);
    const moreVrows = vrowCount(more.container);

    // Guard the premise: every seeded row really mounted (both under the slice),
    // so the denominator is real rows and not a windowing artefact.
    expect(fewVrows).toBe(fewRows * 2);
    expect(moreVrows).toBe(moreRows * 2);

    const perRow = (moreElements - fewElements) / (moreVrows - fewVrows);

    // Measured 2026-09-21: 12.0 elements per mounted row (107 elements at 6
    // rows, 227 at 16). That is ~11 for the row itself plus the one
    // `<div data-vrow>` wrapper ProgressiveList adds per mounted row. Budget
    // just above measured — catches a row gaining structure.
    expect(perRow).toBeLessThanOrEqual(13);
    expect(perRow).toBeGreaterThan(0); // the rows really are being mounted
  });
});

// ---------------------------------------------------------------------------
// 2. Virtualized list: DOM does NOT grow with the dataset
// ---------------------------------------------------------------------------

const realGetComputedStyle = globalThis.getComputedStyle;
let heightPatched = false;

/**
 * Give jsdom just enough layout for `ProgressiveList` to find a scroll ancestor
 * with a real height, so its windowed branch runs. Without this it takes its
 * documented "no usable viewport" fallback and renders every row, and a DOM
 * budget asserted here would silently be a budget on the FALLBACK — a guard
 * that passes while proving nothing about virtualization.
 */
function enableLayout(): void {
  if (!heightPatched) {
    for (const prop of ['offsetHeight', 'clientHeight'] as const) {
      Object.defineProperty(HTMLElement.prototype, prop, {
        configurable: true,
        get() {
          return 600;
        },
      });
    }
    heightPatched = true;
  }
  globalThis.getComputedStyle = ((element: Element, pseudo?: string | null) => {
    const style = realGetComputedStyle(element, pseudo ?? undefined);
    if ((element as HTMLElement).dataset?.['scrollhost'] !== undefined) {
      return { ...style, overflowY: 'auto' } as CSSStyleDeclaration;
    }
    return style;
  }) as typeof globalThis.getComputedStyle;
}

afterAll(() => {
  globalThis.getComputedStyle = realGetComputedStyle;
  if (heightPatched) {
    for (const prop of ['offsetHeight', 'clientHeight']) {
      delete (HTMLElement.prototype as unknown as Record<string, unknown>)[prop];
    }
  }
});

function renderWindowedLogPanel(): HTMLElement {
  return render(
    <ThemeProvider theme={buildTheme('dark')}>
      <div data-scrollhost style={{ height: 600, overflowY: 'auto' }}>
        <LogPanel />
      </div>
    </ThemeProvider>,
  ).container;
}

describe('DOM weight — LogPanel is windowed', () => {
  it('mounts roughly the same number of elements for 25 rows as for 100', () => {
    enableLayout();

    applyFrame(0, 25);
    const fewCount = elementCount(renderWindowedLogPanel());
    cleanup();

    useDashboardStore.getState().clearUI();
    applyFrame(0, UI_UPDATE_ITEM_LIMIT);
    const manyCount = elementCount(renderWindowedLogPanel());

    // Measured 2026-09-21: 220 elements at 100 rows with windowing active,
    // against 2,220 without it. Windowing means the count is driven by the
    // VIEWPORT, not the dataset, so growing the dataset 4x must not grow the
    // DOM 4x.
    //
    // The assertion is a RATIO, not an absolute count, so it survives row
    // markup changing; what it cannot survive is the list mounting every row.
    // Un-windowed, `manyCount / fewCount` is ~4 (the dataset ratio).
    expect(manyCount / fewCount).toBeLessThan(2);

    // And an absolute ceiling, so "windowing" that mounts a 400-row overscan
    // still fails. 100 rows x ~22 elements = 2,200 un-windowed.
    expect(manyCount).toBeLessThan(900);
  });
});

// ---------------------------------------------------------------------------
// 3. TrafficInspector is windowed too — the before/after instrument
// ---------------------------------------------------------------------------

/**
 * TrafficInspector's own scroll container is INTERNAL (a `flex:1; overflowY:auto`
 * Box), which jsdom does resolve, so `findScrollParent` discovers it; the stub's
 * 600 px height stubs give it a real viewport so the windowed branch runs. The
 * `data-scrollhost` wrapper is a harmless outer anchor, mirroring the LogPanel
 * guard above.
 */
function renderWindowedTraffic(): HTMLElement {
  return render(
    <ThemeProvider theme={buildTheme('dark')}>
      <div data-scrollhost style={{ height: 600, overflowY: 'auto' }}>
        <TrafficInspector />
      </div>
    </ThemeProvider>,
  ).container;
}

describe('DOM weight — TrafficInspector is windowed', () => {
  /**
   * THE before/after instrument for virtualizing the traffic list. Before the
   * 2026-09-21 change TrafficInspector rendered `filtered.map(...)` directly and
   * mounted ~2,234 elements at 200 rows; this pins the invariant that made that
   * unnecessary: TOTAL DOM is bounded REGARDLESS of row count. 200 rows must not
   * mount materially more than ~50 do.
   */
  it('mounts the same DOM for 200 rows as for 50 (total DOM is dataset-independent)', () => {
    enableLayout();

    // 25 recorded + 25 proxied = 50 rows in the merged list.
    applyFrame(0, 25);
    const fewContainer = renderWindowedTraffic();
    const fewCount = elementCount(fewContainer);
    const fewVrows = vrowCount(fewContainer);
    cleanup();

    useDashboardStore.getState().clearUI();
    // 100 + 100 = 200 rows in the merged list — 4x the data.
    applyFrame(0, UI_UPDATE_ITEM_LIMIT);
    const manyContainer = renderWindowedTraffic();
    const manyCount = elementCount(manyContainer);
    const manyVrows = vrowCount(manyContainer);

    // The dataset grew 4x (50 -> 200 rows) but the mounted window did not:
    // measured 2026-09-21, 145 elements and 9 mounted rows for BOTH. Windowing
    // means the count is driven by the VIEWPORT, not the dataset.
    //
    // Guard the premise first: far fewer rows are mounted than exist, so the
    // list really is windowed and not merely small.
    expect(manyVrows).toBeLessThan(50);
    expect(fewVrows).toBeLessThan(50);

    // A RATIO, so it survives row markup changing; what it cannot survive is the
    // list mounting every row (un-windowed that ratio is ~4, the dataset ratio).
    expect(manyCount / fewCount).toBeLessThan(2);

    // An absolute ceiling, so a huge overscan still fails. 200 rows x ~12
    // elements = ~2,400 un-windowed.
    expect(manyCount).toBeLessThan(900);
  });

  /**
   * Windowing unmounts rows, so a naive "select every mounted checkbox" would
   * silently select only the visible window. This proves bulk-select is DATA-
   * driven instead: "Select all" selects every filtered request (via
   * `filteredKeys`), and the "Clear (N)" count is the whole dataset even though
   * only a handful of rows are mounted. Same guarantee protects compare mode,
   * whose pool is likewise keyed by item key, not by mounted rows.
   */
  it('bulk-select "Select all" counts every row, not just the mounted window', () => {
    enableLayout();
    applyFrame(0, UI_UPDATE_ITEM_LIMIT); // 200 rows in the merged list
    const container = renderWindowedTraffic();

    const mountedRows = vrowCount(container);
    expect(mountedRows).toBeLessThan(50); // windowed: far fewer mounted than exist

    // Enter select mode, then "Select all".
    fireEvent.click(screen.getByRole('button', { name: 'Select requests' }));
    fireEvent.click(screen.getByLabelText('Select all requests'));

    // "Clear (N)" reflects EVERY selected row. If selection were DOM-driven it
    // would read ~9 (the mounted window); data-driven it reads the full dataset.
    const clear = screen.getByRole('button', { name: /^Clear \(\d+\)$/ });
    const selected = Number(/\((\d+)\)/.exec(clear.textContent ?? '')?.[1]);
    expect(selected).toBeGreaterThan(mountedRows);
    expect(selected).toBeGreaterThanOrEqual(UI_UPDATE_ITEM_LIMIT);
  });
});
