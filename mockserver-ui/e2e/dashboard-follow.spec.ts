import { test, expect } from '@playwright/test';

// The Follow control, against a real server under real load.
//
// WHY THIS CANNOT BE A jsdom TEST. The unit tests for following were green,
// degrade-confirmed red, and certified a control that did not work at all — the
// repo owner reported it immediately. jsdom computes no layout, so it has no
// scroll geometry, no reflow, and no virtualizer measurement. Every mechanism
// that actually broke Follow is invisible there:
//
//   1. The lists are VIRTUALIZED, so rows are measured after they render and the
//      content height keeps changing. Measured here: scrollHeight went 4656 ->
//      4840 -> 10440 in the seconds after a pin. Each reflow fires a `scroll`
//      event, and reading "not at the bottom" as "the reader scrolled away" turned
//      Follow off about a second after it was turned on.
//   2. ProgressiveList's scroll anchoring restores a captured offset on every
//      commit. Anchoring exists to hold a reading position against eviction; while
//      pinned to the tail it is the opposite of what is wanted, and it won — the
//      panel sat ~3,300px short of the bottom indefinitely while claiming to follow.
//   3. Listeners attached from an effect that merely READS a ref stay bound to a
//      detached node when the scroll container is replaced, so the wheel gesture
//      was silently lost on roughly half of runs.
//
// None of those can be reproduced without a layout engine. So this test drives the
// real dashboard, over the real WebSocket, against traffic it generates itself.

const BASE = process.env.E2E_MS_HOST
  ? `http://${process.env.E2E_MS_HOST}:${process.env.E2E_MS_PORT || '1080'}`
  : 'http://127.0.0.1:1084';

const SEED_REQUESTS = 160; // past the 100-row cap, so the window is evicting
const TRAFFIC_INTERVAL_MS = 200;
const AT_TAIL_PX = 40; // generous: the assertion is "pinned", not "pinned to the pixel"

test.beforeEach(async ({ request }) => {
  expect((await request.put(`${BASE}/mockserver/reset`)).ok()).toBeTruthy();
});

/** Keep traffic flowing for the life of the assertion, as a real busy server would. */
function startTraffic(request: { get: (u: string) => Promise<unknown> }) {
  let stop = false;
  const pump = (async () => {
    for (let i = 0; !stop; i++) {
      await request.get(`${BASE}/live/${i}`).catch(() => {});
      await new Promise((r) => setTimeout(r, TRAFFIC_INTERVAL_MS));
    }
  })();
  return async () => {
    stop = true;
    await pump;
  };
}

// Rows arrive over the WebSocket and ProgressiveList paints them over several
// frames, so a scroll box can exist, with rows in it, before it is tall enough to
// scroll. The helpers below therefore POLL until the box is scrollable rather
// than checking once; nothing is clicked while they wait.
const SETUP_TIMEOUT_MS = 30_000;

async function openPanel(page: import('@playwright/test').Page, title: string) {
  await page.goto(`${BASE}/mockserver/dashboard/#/dashboard`);
  await expect
    .poll(
      () =>
        page.evaluate((t) => {
          if (document.querySelectorAll('[data-vrow]').length <= 5) return false;
          const heading = Array.from(document.querySelectorAll('*')).find(
            (el) => el.children.length === 0 && el.textContent?.trim() === t,
          );
          const paper = heading?.closest('.MuiPaper-root');
          if (!paper) return false;
          const box = Array.from(paper.querySelectorAll<HTMLElement>('*')).find((el) => {
            const oy = getComputedStyle(el).overflowY;
            return (oy === 'auto' || oy === 'scroll') && el.scrollHeight > el.clientHeight + 1;
          });
          if (!box) return false;
          box.setAttribute('data-follow-scroller', '');
          paper.setAttribute('data-follow-panel', '');
          return true;
        }, title),
      { message: `found the ${title} scroll box`, timeout: SETUP_TIMEOUT_MS },
    )
    .toBe(true);
}

// The Traffic inspector is a separate VIEW (#/traffic), not a panel on the
// dashboard grid, and it does NOT use Panel: it rolls its own scroll container
// (data-testid="traffic-scroll-region") and wires the shared useTailFollow hook
// itself. That is a genuinely different code path from the panels above, and it
// is the one that carried two of the original defects (follow wired to nothing,
// and no Follow control rendered at all), so it needs its own cover. Tagging is
// done off the testid rather than by hunting a heading, and it asserts the region
// is actually scrollable — so a view that never rendered, or a testid that moved,
// fails HERE as inconclusive harness setup, distinct from the product failing a
// follow assertion later.
async function openTraffic(page: import('@playwright/test').Page) {
  await page.goto(`${BASE}/mockserver/dashboard/#/traffic`);
  await expect
    .poll(
      () =>
        page.evaluate(() => {
          const box = document.querySelector<HTMLElement>('[data-testid="traffic-scroll-region"]');
          if (!box || document.querySelectorAll('[data-vrow]').length <= 5) return false;
          if (box.scrollHeight <= box.clientHeight + 1) return false;
          const paper = box.closest('.MuiPaper-root');
          if (!paper) return false;
          box.setAttribute('data-follow-scroller', '');
          paper.setAttribute('data-follow-panel', '');
          return true;
        }),
      {
        message: 'found the Traffic inspector scroll region and it is scrollable',
        timeout: SETUP_TIMEOUT_MS,
      },
    )
    .toBe(true);
}

const chipText = (page: import('@playwright/test').Page) =>
  page.evaluate(() => {
    const paper = document.querySelector('[data-follow-panel]');
    const chip = Array.from(paper?.querySelectorAll('.MuiChip-root') ?? []).find((c) =>
      ['Follow', 'Following'].includes(c.textContent?.trim() ?? ''),
    );
    return chip?.textContent?.trim() ?? null;
  });

const distanceFromTail = (page: import('@playwright/test').Page) =>
  page.evaluate(() => {
    const el = document.querySelector('[data-follow-scroller]') as HTMLElement;
    return Math.round(el.scrollHeight - el.scrollTop - el.clientHeight);
  });

const clickChip = (page: import('@playwright/test').Page) =>
  page.evaluate(() => {
    const paper = document.querySelector('[data-follow-panel]');
    const chip = Array.from(paper?.querySelectorAll('.MuiChip-root') ?? []).find((c) =>
      ['Follow', 'Following'].includes(c.textContent?.trim() ?? ''),
    );
    (chip as HTMLElement | undefined)?.click();
  });

for (const panel of ['Log Messages', 'Received Requests']) {
  // THE DEFAULT PATH, and the one a user actually meets. `autoScroll` is true in
  // the store, so every panel is already following at mount and nobody clicks
  // anything. It needs its own test because it is a DIFFERENT code path from
  // clicking Follow: ProgressiveList paints plain row divs first and only swaps
  // in its virtualized sizing spacer once it has found a scroll parent. Clicking
  // Follow happens after that swap; mounting spans it. A re-pin bound to the
  // children observed at mount is therefore watching nodes that no longer exist,
  // and the panel silently stops holding the tail — which is exactly the shape of
  // bug this whole hook exists to remove, reachable only on the path no test
  // covered.
  test(`${panel}: follows from mount, without anyone clicking Follow`, async ({ page, request }) => {
    for (let i = 0; i < SEED_REQUESTS; i++) await request.get(`${BASE}/seed/${i}`);
    const stopTraffic = startTraffic(request);
    try {
      await openPanel(page, panel);
      // Deliberately no click: whatever the store's default is, is what we test.
      expect(await chipText(page), 'follows by default').toBe('Following');
      for (let s = 0; s < 8; s++) {
        await page.waitForTimeout(1000);
        expect(await chipText(page), `still Following at t+${s + 1}s`).toBe('Following');
        expect(await distanceFromTail(page), `held the tail at t+${s + 1}s`)
          .toBeLessThanOrEqual(AT_TAIL_PX);
      }
    } finally {
      await stopTraffic();
    }
  });

  test(`${panel}: Follow stays following while traffic keeps arriving`, async ({ page, request }) => {
    for (let i = 0; i < SEED_REQUESTS; i++) await request.get(`${BASE}/seed/${i}`);
    const stopTraffic = startTraffic(request);
    try {
      await openPanel(page, panel);
      if ((await chipText(page)) === 'Following') await clickChip(page);
      await expect.poll(() => chipText(page), { message: 'starts not following' }).toBe('Follow');

      await clickChip(page); // the gesture under test

      // The original defect: it followed for about a second and then cancelled
      // itself as the virtualizer measured rows and reflowed the content.
      for (let s = 0; s < 10; s++) {
        await page.waitForTimeout(1000);
        expect(await chipText(page), `still Following at t+${s + 1}s`).toBe('Following');
        expect(await distanceFromTail(page), `pinned to the tail at t+${s + 1}s`)
          .toBeLessThanOrEqual(AT_TAIL_PX);
      }
    } finally {
      await stopTraffic();
    }
  });

  test(`${panel}: scrolling up stops following, and it stays stopped`, async ({ page, request }) => {
    for (let i = 0; i < SEED_REQUESTS; i++) await request.get(`${BASE}/seed/${i}`);
    const stopTraffic = startTraffic(request);
    try {
      await openPanel(page, panel);
      if ((await chipText(page)) === 'Follow') await clickChip(page);
      await expect.poll(() => chipText(page), { message: 'starts following' }).toBe('Following');

      // A real wheel gesture over the panel — not a synthetic scrollTop write.
      const box = await page.locator('[data-follow-scroller]').boundingBox();
      expect(box).not.toBeNull();
      await page.mouse.move(box!.x + box!.width / 2, box!.y + box!.height / 2);
      await page.mouse.wheel(0, -600);
      await page.waitForTimeout(800);

      expect(await chipText(page), 'a real scroll stops following').toBe('Follow');

      // And it must STAY stopped. Eviction shrinks the content, which once left a
      // motionless reader at the bottom and switched following back on under them.
      for (let s = 0; s < 5; s++) {
        await page.waitForTimeout(1000);
        expect(await chipText(page), `still stopped at t+${s + 1}s`).toBe('Follow');
      }

      // Pressing Follow again resumes and re-pins.
      await clickChip(page);
      await page.waitForTimeout(1200);
      expect(await chipText(page)).toBe('Following');
      expect(await distanceFromTail(page), 'resumed and re-pinned').toBeLessThanOrEqual(AT_TAIL_PX);
    } finally {
      await stopTraffic();
    }
  });
}

// The Traffic inspector (Observe -> Traffic). Same three properties as the panels
// above, but against Traffic's own scroll container and its own useTailFollow
// wiring — the surface that shipped broken twice and whose migration to the shared
// hook had never been checked against a real browser.
test.describe('Traffic inspector', () => {
  // THE DEFAULT PATH, and the only one most users ever meet: autoScroll is true in
  // the store, so Traffic is already following at mount with nobody clicking
  // anything. It is a DIFFERENT path from clicking Follow — the pin fires from the
  // layout effect as the view first renders, spanning ProgressiveList's swap from
  // plain row divs to its virtualized sizing spacer — and it is exactly the path
  // where Traffic had `follow` wired to nothing and never held the tail at all.
  test('Traffic inspector: follows from mount, without anyone clicking Follow', async ({ page, request }) => {
    for (let i = 0; i < SEED_REQUESTS; i++) await request.get(`${BASE}/seed/${i}`);
    const stopTraffic = startTraffic(request);
    try {
      await openTraffic(page);
      // Deliberately no click: whatever the store's default is, is what we test.
      expect(await chipText(page), 'follows by default').toBe('Following');
      for (let s = 0; s < 8; s++) {
        await page.waitForTimeout(1000);
        expect(await chipText(page), `still Following at t+${s + 1}s`).toBe('Following');
        expect(await distanceFromTail(page), `held the tail at t+${s + 1}s`)
          .toBeLessThanOrEqual(AT_TAIL_PX);
      }
    } finally {
      await stopTraffic();
    }
  });

  test('Traffic inspector: Follow stays following while traffic keeps arriving', async ({ page, request }) => {
    for (let i = 0; i < SEED_REQUESTS; i++) await request.get(`${BASE}/seed/${i}`);
    const stopTraffic = startTraffic(request);
    try {
      await openTraffic(page);
      if ((await chipText(page)) === 'Following') await clickChip(page);
      await expect.poll(() => chipText(page), { message: 'starts not following' }).toBe('Follow');

      await clickChip(page); // the gesture under test

      // The original Traffic defect: it pinned and then cancelled itself within
      // about a second as the virtualizer measured rows and reflowed the content,
      // each reflow firing a `scroll` event read as "the reader left".
      for (let s = 0; s < 10; s++) {
        await page.waitForTimeout(1000);
        expect(await chipText(page), `still Following at t+${s + 1}s`).toBe('Following');
        expect(await distanceFromTail(page), `pinned to the tail at t+${s + 1}s`)
          .toBeLessThanOrEqual(AT_TAIL_PX);
      }
    } finally {
      await stopTraffic();
    }
  });

  test('Traffic inspector: scrolling up stops following, and it stays stopped', async ({ page, request }) => {
    for (let i = 0; i < SEED_REQUESTS; i++) await request.get(`${BASE}/seed/${i}`);
    const stopTraffic = startTraffic(request);
    try {
      await openTraffic(page);
      if ((await chipText(page)) === 'Follow') await clickChip(page);
      await expect.poll(() => chipText(page), { message: 'starts following' }).toBe('Following');

      // A real wheel gesture over the region — not a synthetic scrollTop write.
      const box = await page.locator('[data-follow-scroller]').boundingBox();
      expect(box).not.toBeNull();
      await page.mouse.move(box!.x + box!.width / 2, box!.y + box!.height / 2);
      await page.mouse.wheel(0, -600);
      await page.waitForTimeout(800);

      expect(await chipText(page), 'a real scroll stops following').toBe('Follow');

      // And it must STAY stopped. Eviction shrinks the content, which once left a
      // motionless reader at the bottom and switched following back on under them.
      for (let s = 0; s < 5; s++) {
        await page.waitForTimeout(1000);
        expect(await chipText(page), `still stopped at t+${s + 1}s`).toBe('Follow');
      }

      // Pressing Follow again resumes and re-pins.
      await clickChip(page);
      await page.waitForTimeout(1200);
      expect(await chipText(page)).toBe('Following');
      expect(await distanceFromTail(page), 'resumed and re-pinned').toBeLessThanOrEqual(AT_TAIL_PX);
    } finally {
      await stopTraffic();
    }
  });
});
