import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { useDashboardStore, type Workspace } from '../store';
import { useConnectionParams } from '../hooks/useConnectionParams';
import { useWithWholeExpectation } from '../hooks/useWithWholeExpectation';
import type { WorkspaceConnection } from '../lib/connectionTarget';
import type { JsonListItem, LogMessage } from '../types';

const B: WorkspaceConnection = { host: 'other-host', port: '1081', secure: false, basePath: '' };
const C: WorkspaceConnection = { host: 'third-host', port: '1082', secure: true, basePath: '/m' };

function workspace(id: string, name: string, overrides: Partial<Workspace> = {}): Workspace {
  return {
    id,
    name,
    view: 'dashboard',
    logSearch: '',
    expectationSearch: '',
    receivedSearch: '',
    proxiedSearch: '',
    trafficSearch: '',
    connection: null,
    ...overrides,
  };
}

const row = (key: string): JsonListItem => ({ key, value: { path: `/${key}` } });

/** Fill the store with data as if it had been pushed from the live server. */
function loadServerData(): void {
  useDashboardStore.setState({
    logMessages: [{ key: 'log-a' } as unknown as LogMessage],
    activeExpectations: [row('exp-a')],
    activeExpectationsTotal: 7,
    recordedRequests: [row('rec-a')],
    proxiedRequests: [row('prox-a')],
    fullMessages: { 'rec-a': {} },
    selectedTrafficKey: 'rec-a',
    serverConfiguration: { maxExpectations: 5 },
    pendingEditExpectation: { id: 'from-a' },
    error: 'from a',
    errorSource: 'push',
  });
}

function expectServerDataDropped(): void {
  const s = useDashboardStore.getState();
  expect(s.logMessages).toEqual([]);
  expect(s.activeExpectations).toEqual([]);
  expect(s.activeExpectationsTotal).toBe(0);
  expect(s.recordedRequests).toEqual([]);
  expect(s.proxiedRequests).toEqual([]);
  expect(s.fullMessages).toEqual({});
  expect(s.selectedTrafficKey).toBeNull();
  expect(s.serverConfiguration).toBeNull();
  expect(s.pendingEditExpectation).toBeNull();
  expect(s.error).toBeNull();
}

function storedBlob(): { activeWorkspaceId: string; workspaces: Workspace[] } {
  return JSON.parse(globalThis.localStorage.getItem('mockserver-workspaces')!) as { activeWorkspaceId: string; workspaces: Workspace[] };
}

function reset(workspaces: Workspace[], activeId: string): void {
  const active = workspaces.find((w) => w.id === activeId)!;
  useDashboardStore.setState({
    workspaces,
    activeWorkspaceId: activeId,
    view: active.view,
    logSearch: active.logSearch,
    expectationSearch: '',
    receivedSearch: '',
    proxiedSearch: '',
    trafficSearch: '',
    connection: active.connection,
    connectionEpoch: 0,
  });
}

describe('workspace targets — what changes when the target does', () => {
  beforeEach(() => {
    globalThis.localStorage.clear();
    globalThis.location.hash = '';
    reset([workspace('a', 'Alpha'), workspace('b', 'Beta', { connection: B })], 'a');
  });

  afterEach(() => {
    globalThis.localStorage.clear();
    globalThis.location.hash = '';
  });

  it('switching to a workspace on another server drops everything held from the previous one', () => {
    loadServerData();
    useDashboardStore.getState().switchWorkspace('b');
    expectServerDataDropped();
    expect(useDashboardStore.getState().connection).toEqual(B);
    expect(useDashboardStore.getState().connectionEpoch).toBe(1);
  });

  it('switching between workspaces on the same server keeps the data', () => {
    reset([workspace('a', 'Alpha', { connection: B }), workspace('b', 'Beta', { connection: { ...B } })], 'a');
    loadServerData();
    useDashboardStore.getState().switchWorkspace('b');
    expect(useDashboardStore.getState().recordedRequests).toHaveLength(1);
    expect(useDashboardStore.getState().connectionEpoch).toBe(0);
  });

  it('retargeting the active workspace drops the data; retargeting another workspace does not', () => {
    loadServerData();
    useDashboardStore.getState().setWorkspaceConnection('b', C);
    expect(useDashboardStore.getState().recordedRequests).toHaveLength(1);
    expect(useDashboardStore.getState().workspaces.find((w) => w.id === 'b')!.connection).toEqual(C);

    useDashboardStore.getState().setWorkspaceConnection('a', C);
    expectServerDataDropped();
    expect(useDashboardStore.getState().connection).toEqual(C);
  });

  it('closing the active workspace onto a neighbour with another target drops the data', () => {
    reset([workspace('a', 'Alpha'), workspace('b', 'Beta', { connection: B })], 'b');
    loadServerData();
    useDashboardStore.getState().closeWorkspace('b');
    expectServerDataDropped();
    expect(useDashboardStore.getState().connection).toBeNull();
  });

  it('a new workspace follows the dashboard URL, so adding one from a retargeted workspace drops the data', () => {
    reset([workspace('a', 'Alpha'), workspace('b', 'Beta', { connection: B })], 'b');
    loadServerData();
    useDashboardStore.getState().addWorkspace();
    expectServerDataDropped();
    expect(useDashboardStore.getState().connection).toBeNull();
  });

  it('useConnectionParams merges the active workspace target over the URL one, for every consumer', () => {
    const { result } = renderHook(() => useConnectionParams());
    const urlTarget = result.current;
    expect(urlTarget.host).toBe(globalThis.location.hostname);

    act(() => useDashboardStore.getState().switchWorkspace('b'));
    expect(result.current).toEqual(B);

    act(() => useDashboardStore.getState().setWorkspaceConnection('b', null));
    expect(result.current).toEqual(urlTarget);
  });
});

describe('workspace targets — persistence and two browser tabs', () => {
  beforeEach(() => {
    globalThis.localStorage.clear();
    globalThis.location.hash = '';
    reset([workspace('a', 'Alpha'), workspace('b', 'Beta', { connection: B })], 'a');
  });

  afterEach(() => {
    globalThis.localStorage.clear();
    globalThis.location.hash = '';
    vi.resetModules();
  });

  it('persists each workspace target and restores it on reload, dropping an invalid one', async () => {
    useDashboardStore.getState().setWorkspaceConnection('a', C);
    expect(storedBlob().workspaces.map((w) => w.connection)).toEqual([C, B]);

    const blob = storedBlob();
    blob.workspaces[1]!.connection = { host: 'http://bad', port: '1', secure: false, basePath: '' };
    globalThis.localStorage.setItem('mockserver-workspaces', JSON.stringify(blob));

    vi.resetModules();
    const reloaded = (await import('../store')).useDashboardStore.getState();
    expect(reloaded.connection).toEqual(C);
    expect(reloaded.workspaces[1]!.connection).toBeNull();
  });

  it('writes each live search and view change through to the blob under this tab’s active id', () => {
    useDashboardStore.getState().switchWorkspace('b');
    useDashboardStore.getState().setLogSearch('status:500');
    useDashboardStore.getState().setView('traffic');

    const blob = storedBlob();
    expect(blob.activeWorkspaceId).toBe('b');
    expect(blob.workspaces.find((w) => w.id === 'b')).toMatchObject({ view: 'traffic', logSearch: 'status:500', connection: B });
  });

  // The defect the old single-writer note described: tab 2 makes workspace b active, then
  // tab 1 (still on a) edits a search. Before, the reload put a's search into b.
  it('a reload after two tabs wrote restores each workspace with its own state', async () => {
    useDashboardStore.getState().renameWorkspace('a', 'Alpha');
    useDashboardStore.getState().setLogSearch('tab-1 on a');
    const tab2 = storedBlob();
    tab2.activeWorkspaceId = 'b';
    tab2.workspaces = tab2.workspaces.map((w) => (w.id === 'b' ? { ...w, logSearch: 'tab-2 on b' } : w));
    globalThis.localStorage.setItem('mockserver-workspaces', JSON.stringify(tab2));
    useDashboardStore.getState().adoptWorkspacesFromOtherTab(JSON.stringify(tab2));

    useDashboardStore.getState().setLogSearch('tab-1 on a, later');

    vi.resetModules();
    const reloaded = (await import('../store')).useDashboardStore.getState();
    const byId = Object.fromEntries(reloaded.workspaces.map((w) => [w.id, w]));
    expect(byId['a']).toMatchObject({ logSearch: 'tab-1 on a, later', connection: null });
    expect(byId['b']).toMatchObject({ logSearch: 'tab-2 on b', connection: B });
  });

  it('adopting another tab’s write never changes this tab’s live workspace or its target', () => {
    loadServerData();
    const other = {
      activeWorkspaceId: 'b',
      workspaces: [
        workspace('a', 'Alpha renamed', { connection: C, logSearch: 'theirs' }),
        workspace('b', 'Beta', { connection: C }),
        workspace('c', 'Gamma'),
      ],
    };
    useDashboardStore.getState().adoptWorkspacesFromOtherTab(JSON.stringify(other));

    const s = useDashboardStore.getState();
    expect(s.activeWorkspaceId).toBe('a');
    expect(s.connection).toBeNull();
    expect(s.recordedRequests).toHaveLength(1);
    expect(s.workspaces.map((w) => w.name)).toEqual(['Alpha renamed', 'Beta', 'Gamma']);
    expect(s.workspaces[0]).toMatchObject({ connection: null, logSearch: '' });
    expect(s.workspaces[1]!.connection).toEqual(C);
  });

  it('keeps this tab’s live workspace when another tab closed it, and ignores a malformed write', () => {
    useDashboardStore.getState().adoptWorkspacesFromOtherTab(JSON.stringify({
      activeWorkspaceId: 'b',
      workspaces: [workspace('b', 'Beta', { connection: B })],
    }));
    expect(useDashboardStore.getState().workspaces.map((w) => w.id)).toEqual(['a', 'b']);

    const before = useDashboardStore.getState().workspaces;
    useDashboardStore.getState().adoptWorkspacesFromOtherTab('{nope');
    useDashboardStore.getState().adoptWorkspacesFromOtherTab(null);
    expect(useDashboardStore.getState().workspaces).toBe(before);
  });
});

describe('workspace targets — a dashboard link that names a server', () => {
  const original = globalThis.location.pathname + globalThis.location.search;

  /** Store what a previous visit left: workspaces, the active one, and its legacy view/search keys. */
  function previousVisit(workspaces: Workspace[], activeId: string, view = 'traffic', logSearch = 'mine'): void {
    globalThis.localStorage.setItem('mockserver-workspaces', JSON.stringify({ activeWorkspaceId: activeId, workspaces }));
    globalThis.localStorage.setItem('mockserver-view', view);
    globalThis.localStorage.setItem('mockserver-search', JSON.stringify({ logSearch }));
  }

  async function openLink(url: string) {
    globalThis.history.replaceState(null, '', url);
    vi.resetModules();
    return (await import('../store')).useDashboardStore.getState();
  }

  beforeEach(() => {
    globalThis.localStorage.clear();
    globalThis.location.hash = '';
  });

  afterEach(() => {
    globalThis.history.replaceState(null, '', original);
    globalThis.localStorage.clear();
    globalThis.location.hash = '';
    vi.resetModules();
  });

  it('opens a workspace that follows the link instead of the active workspace’s own server, keeping that one', async () => {
    previousVisit([workspace('a', 'Alpha', { connection: B }), workspace('b', 'Beta', { view: 'chaos', logSearch: 'beta' })], 'a');

    const s = await openLink('/?host=linked-host&port=1090#/verification');

    expect(s.activeWorkspaceId).toBe('b');
    expect(s.connection).toBeNull();
    expect(s.view).toBe('verification');
    expect(s.logSearch).toBe('beta');
    // the workspace with its own server is untouched, with the view and search it was left on
    expect(s.workspaces.find((w) => w.id === 'a')).toMatchObject({ connection: B, view: 'traffic', logSearch: 'mine' });

    const reloadedHook = (await import('../hooks/useConnectionParams')).useConnectionParams;
    expect(renderHook(() => reloadedHook()).result.current).toMatchObject({ host: 'linked-host', port: '1090' });

    // persisted at once, so a reload of the same link lands in the same place
    expect(storedBlob().activeWorkspaceId).toBe('b');
    expect(globalThis.localStorage.getItem('mockserver-view')).toBe('verification');
    expect(JSON.parse(globalThis.localStorage.getItem('mockserver-search')!)).toMatchObject({ logSearch: 'beta' });
    const reloaded = await openLink('/?host=linked-host&port=1090#/verification');
    expect(reloaded.activeWorkspaceId).toBe('b');
    expect(reloaded.workspaces).toHaveLength(2);
  });

  it('adds a workspace for the link when every workspace has its own server', async () => {
    previousVisit([workspace('a', 'Alpha', { connection: B }), workspace('b', 'Beta', { connection: C })], 'a');

    const s = await openLink('/?port=1090');

    expect(s.workspaces.map((w) => w.connection)).toEqual([B, C, null]);
    const linked = s.workspaces[2]!;
    expect(s.activeWorkspaceId).toBe(linked.id);
    expect(linked).toMatchObject({ name: 'Workspace 3', view: 'dashboard', logSearch: '' });
    expect(s.logSearch).toBe('');
  });

  it('keeps the active workspace when the link names its own server, or names no server', async () => {
    const linked: WorkspaceConnection = { host: 'linked-host', port: '1090', secure: false, basePath: '' };
    previousVisit([workspace('a', 'Alpha', { connection: linked }), workspace('b', 'Beta')], 'a');
    expect((await openLink('/?host=LINKED-HOST&port=1090')).activeWorkspaceId).toBe('a');

    expect((await openLink('/')).activeWorkspaceId).toBe('a');
    // the hash alone picks a view, not a server
    const viewOnly = await openLink('/#/chaos');
    expect(viewOnly.activeWorkspaceId).toBe('a');
    expect(viewOnly.connection).toEqual(linked);
  });

  it('changes nothing for a workspace that already follows the URL', async () => {
    previousVisit([workspace('a', 'Alpha'), workspace('b', 'Beta', { connection: B })], 'a');
    const s = await openLink('/?host=linked-host');
    expect(s.activeWorkspaceId).toBe('a');
    expect(s.workspaces).toHaveLength(2);
    expect(s.logSearch).toBe('mine');
  });
});

describe('workspace targets — work in flight when the target changes', () => {
  beforeEach(() => {
    reset([workspace('a', 'Alpha'), workspace('b', 'Beta', { connection: B })], 'a');
  });

  afterEach(() => vi.unstubAllGlobals());

  it('does not hand an expectation loaded from the previous server to an action', async () => {
    let answer: (r: Response) => void = () => {};
    vi.stubGlobal('fetch', vi.fn(() => new Promise<Response>((resolve) => { answer = resolve; })));
    const act1 = vi.fn();
    const { result } = renderHook(() => useWithWholeExpectation());

    result.current(
      { key: 'e', value: { id: 'e' }, truncatedExpectation: { expectationId: 'e' } } as unknown as JsonListItem,
      act1,
    );
    act(() => useDashboardStore.getState().switchWorkspace('b'));
    answer(new Response(JSON.stringify([{ id: 'e', httpRequest: {} }]), { status: 200 }));
    await new Promise((r) => setTimeout(r, 0));

    expect(act1).not.toHaveBeenCalled();
  });
});
