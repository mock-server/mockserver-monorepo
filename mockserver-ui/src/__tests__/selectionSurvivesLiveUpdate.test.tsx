import { describe, it, expect, beforeEach, afterAll } from 'vitest';
import { render, screen, act, fireEvent } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ThemeProvider } from '@mui/material/styles';
import { buildTheme } from '../theme';
import Panel from '../components/Panel';
import RequestPanel from '../components/RequestPanel';
import LogPanel from '../components/LogPanel';
import { useDashboardStore } from '../store';

// Regression coverage for the "opened dashboard item closes as soon as new data
// arrives" report on the Log Messages / Received Requests panels.
//
// Two independent things are pinned here:
//
//  1. EXPANSION STATE is held ABOVE the list and keyed by each row's stable id,
//     so it survives a live push (re-render) — proven with windowing active.
//     This was already correct; these are guards against a regression back to
//     row-local or index-keyed state.
//
//  2. AUTO-SCROLL is TAIL-FOLLOWING: a push only snaps the panel back to the top
//     when the user is already at the top. If they have scrolled down to open a
//     row, the push must leave their scroll position alone — otherwise every
//     ~1/sec push scrolls that row out of view (and virtualization unmounts it),
//     which is exactly the "opened item closes" the user saw. This is the fix.

const realGetComputedStyle = globalThis.getComputedStyle;
let heightPatched = false;

// Give jsdom just enough layout for ProgressiveList to resolve a scroll ancestor
// with a real height, so its WINDOWED branch runs (matching the real browser),
// rather than its "no usable viewport" fallback that renders every row. The
// virtualizer sizes its viewport from clientHeight and its rows from offsetHeight.
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

// NOTE: these prove expansion STATE is retained across a push. They do NOT prove
// the opened row stays on screen: that depends on real scroll and windowing,
// which jsdom cannot model (the stubbed offsetHeight never scrolls). The
// on-screen invariant is covered in a real browser by e2e/scroll-anchor.pw.ts
// and, against a real server, e2e/dashboard-live-scroll.spec.ts.
describe('dashboard row expansion survives a live update (windowed)', () => {
  beforeEach(() => {
    useDashboardStore.getState().clearUI();
    useDashboardStore.setState({
      logMessages: [],
      logSearch: '',
      autoScroll: true,
      logShowForwarded: true,
    });
  });

  it('RequestPanel keeps an expanded row open when a new request is prepended', async () => {
    enableLayout();
    const user = userEvent.setup();
    const items = [{ key: 'r1', value: { method: 'GET', path: '/first' } }];

    const { rerender } = render(
      <ThemeProvider theme={buildTheme('dark')}>
        <div data-scrollhost style={{ height: 600, overflowY: 'auto' }}>
          <RequestPanel title="Received Requests" items={items} searchValue="" onSearchChange={() => {}} />
        </div>
      </ThemeProvider>,
    );

    await user.click(screen.getByLabelText('Expand'));
    expect(screen.getByLabelText('Collapse')).toBeInTheDocument();

    const updated = [
      { key: 'r2', value: { method: 'POST', path: '/second' } },
      { key: 'r1', value: { method: 'GET', path: '/first' } },
    ];
    rerender(
      <ThemeProvider theme={buildTheme('dark')}>
        <div data-scrollhost style={{ height: 600, overflowY: 'auto' }}>
          <RequestPanel title="Received Requests" items={updated} searchValue="" onSearchChange={() => {}} />
        </div>
      </ThemeProvider>,
    );

    expect(screen.getByLabelText('Collapse')).toBeInTheDocument();
  });

  it('LogPanel keeps an expanded entry open when a new log message is prepended', async () => {
    enableLayout();
    const user = userEvent.setup();
    useDashboardStore.setState({
      logMessages: [
        { key: 'log1', value: { messageParts: [{ key: 'm1', value: 'first log entry body' }] } },
      ],
    });

    render(
      <ThemeProvider theme={buildTheme('dark')}>
        <div data-scrollhost style={{ height: 600, overflowY: 'auto' }}>
          <LogPanel />
        </div>
      </ThemeProvider>,
    );

    await user.click(screen.getByLabelText('Expand'));
    expect(screen.getByLabelText('Collapse')).toBeInTheDocument();

    act(() => {
      useDashboardStore.getState().applyMessage({
        logMessages: [
          { key: 'log2', value: { messageParts: [{ key: 'm2', value: 'second log entry body' }] } },
          { key: 'log1', value: { messageParts: [{ key: 'm1', value: 'first log entry body' }] } },
        ],
        activeExpectations: [],
        recordedRequests: [],
        proxiedRequests: [],
      });
    });

    expect(screen.getByLabelText('Collapse')).toBeInTheDocument();
  });
});

describe('Panel following is console-style: it follows the BOTTOM', () => {
  // These pin the CONSOLE model that replaced top-following.
  //
  // The panels render oldest-first with the newest appended at the BOTTOM, and
  // following is an explicit choice (the Follow control) rather than something
  // inferred from scroll position. That is what makes them stable to read: an
  // arriving row lands BELOW the viewport, so a reader who is not following sees
  // nothing move and no scroll compensation is needed. The previous design
  // prepended above the reader and then scrolled to compensate, which kept the row
  // under the eye but meant the panel was scrolling continuously.
  function renderFollowing(
    count: number,
    follow: boolean,
    onFollowChange: (follow: boolean) => void = () => {},
  ) {
    return render(
      <Panel
        title="Log Messages"
        count={count}
        follow={follow}
        onFollowChange={onFollowChange}
        searchValue=""
        onSearchChange={() => {}}
        liveRegion
      >
        <div style={{ height: 5000 }}>rows</div>
      </Panel>,
    );
  }

  it('sticks to the BOTTOM on new data while following', () => {
    const { rerender } = renderFollowing(10, true);
    const region = screen.getByRole('log');
    region.scrollTop = 0;

    rerender(
      <Panel
        title="Log Messages"
        count={11}
        follow
        onFollowChange={() => {}}
        searchValue=""
        onSearchChange={() => {}}
        liveRegion
      >
        <div style={{ height: 5000 }}>rows</div>
      </Panel>,
    );
    // Pinned to the tail, where the newest row is.
    expect(region.scrollTop).toBe(region.scrollHeight);
  });

  it('does not move the viewport on new data when NOT following', () => {
    const { rerender } = renderFollowing(10, false);
    const region = screen.getByRole('log');
    region.scrollTop = 500;

    rerender(
      <Panel
        title="Log Messages"
        count={11}
        follow={false}
        onFollowChange={() => {}}
        searchValue=""
        onSearchChange={() => {}}
        liveRegion
      >
        <div style={{ height: 5000 }}>rows</div>
      </Panel>,
    );
    // Untouched. This is the whole point of the console model: the reader is
    // reading, and nothing is inserted above them to compensate for.
    expect(region.scrollTop).toBe(500);
  });

  // Stopping is driven by the GESTURE, not by the scroll event it produces.
  // Against a live server a scroll event turned out to be no evidence at all of
  // who caused it: these lists are virtualized, so rows being measured reflow the
  // content constantly, and a handler that read "not at the bottom" as "the
  // reader scrolled away" switched Follow off about a second after it was
  // switched on. A wheel-up, by contrast, can only have come from a person.
  it('turns Follow OFF when the reader wheels up, away from the tail', () => {
    const changes: boolean[] = [];
    renderFollowing(10, true, (f: boolean) => { changes.push(f); });
    const region = screen.getByRole('log');

    fireEvent.wheel(region, { deltaY: -400 });

    expect(changes).toContain(false);
  });

  it('does NOT turn Follow off for a scroll with no gesture behind it', () => {
    const changes: boolean[] = [];
    renderFollowing(10, true, (f: boolean) => { changes.push(f); });
    const region = screen.getByRole('log');

    // jsdom has no layout, so give the element a scrollable shape explicitly.
    Object.defineProperty(region, 'scrollHeight', { value: 5000, configurable: true });
    Object.defineProperty(region, 'clientHeight', { value: 500, configurable: true });
    // Exactly what the virtualizer does when it measures a row: the content moves
    // under a reader who did nothing. This must not be mistaken for them leaving.
    region.scrollTop = 1000;
    fireEvent.scroll(region);

    expect(changes).not.toContain(false);
  });

  it('turns Follow back ON when the reader returns to the tail', () => {
    const changes: boolean[] = [];
    renderFollowing(10, false, (f: boolean) => { changes.push(f); });
    const region = screen.getByRole('log');

    Object.defineProperty(region, 'scrollHeight', { value: 5000, configurable: true });
    Object.defineProperty(region, 'clientHeight', { value: 500, configurable: true });
    region.scrollTop = 4500; // scrollHeight - clientHeight: at the tail
    // Resuming needs downward intent, for the mirror-image reason: eviction
    // shrinks the content, which can leave a motionless reader at the bottom.
    fireEvent.wheel(region, { deltaY: 400 });
    fireEvent.scroll(region);

    expect(changes).toContain(true);
  });

});
