import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, act, waitFor } from '@testing-library/react';
import { useDashboardStore } from '../store';

const start = vi.fn();
const release = vi.hoisted(() => vi.fn<(keys: ReadonlySet<string>) => void>());
vi.mock('../lib/breakpointCallbackClient', () => ({
  releaseBreakpointCallbackClientsExcept: release,
  findBreakpointCallbackClient: () => undefined,
  subscribeBreakpointCallbackClients: () => () => {},
}));
const initAnalytics = vi.hoisted(() => vi.fn());
vi.mock('../lib/analytics', async (importOriginal) => ({ ...(await importOriginal<object>()), initAnalytics }));
vi.mock('../lib/configuration', async (importOriginal) => ({
  ...(await importOriginal<object>()),
  getConfiguration: vi.fn(async () => ({})),
}));
vi.mock('../hooks/useWebSocket', () => ({
  useWebSocket: () => ({
    connect: vi.fn(),
    start,
    sendFilter: vi.fn(),
    clearServer: vi.fn().mockResolvedValue(undefined),
  }),
}));
vi.mock('../hooks/useDebugMismatch', () => ({ useDebugMismatch: () => ({ debugMismatch: null }) }));
vi.mock('../hooks/useGenerateStub', () => ({ useGenerateStub: () => ({ generateStub: null }) }));
vi.mock('../components/AppBar', () => ({ default: () => <header data-testid="app-bar" />, NAV_TAB_DESCRIPTIONS: {} }));
const onboardingMounts = vi.fn();
vi.mock('../components/OnboardingPanel', async () => {
  const { useEffect } = await import('react');
  function OnboardingStub() {
    useEffect(() => { onboardingMounts(); }, []);
    return <div data-testid="onboarding" />;
  }
  return { default: OnboardingStub };
});

import App from '../App';
import { resolveUrlConnection, type WorkspaceConnection } from '../lib/connectionTarget';

const B: WorkspaceConnection = { host: 'other-host', port: '1081', secure: false, basePath: '' };
const C: WorkspaceConnection = { host: 'third-host', port: '1082', secure: false, basePath: '' };
const ws = (id: string, connection: WorkspaceConnection | null) => ({
  id, name: id, view: 'get-started' as const, connection,
  logSearch: '', expectationSearch: '', receivedSearch: '', proxiedSearch: '', trafficSearch: '',
});
const lastKeptTargets = () => [...(release.mock.lastCall?.[0] ?? [])].sort();

describe('App on a workspace target change', () => {
  beforeEach(() => {
    onboardingMounts.mockClear();
    useDashboardStore.setState({
      view: 'get-started',
      connection: null,
      connectionEpoch: 0,
      workspaces: [{
        id: 'only', name: 'Only', view: 'get-started', connection: null,
        logSearch: '', expectationSearch: '', receivedSearch: '', proxiedSearch: '', trafficSearch: '',
      }],
      activeWorkspaceId: 'only',
    });
  });

  it('remounts the app bar and the view exactly once each, so nothing from the previous server survives', async () => {
    render(<App />);
    expect(await screen.findByTestId('onboarding')).toBeInTheDocument();
    expect(onboardingMounts).toHaveBeenCalledTimes(1);

    act(() => {
      useDashboardStore.getState().setWorkspaceConnection('only', { host: 'other-host', port: '1081', secure: false, basePath: '' });
    });

    expect(await screen.findByTestId('onboarding')).toBeInTheDocument();
    expect(onboardingMounts).toHaveBeenCalledTimes(2);
    expect(screen.getAllByTestId('app-bar')).toHaveLength(1);
    expect(screen.getAllByTestId('onboarding')).toHaveLength(1);
  });

  it('keeps the breakpoint sessions of every server a workspace targets, and releases the rest', () => {
    const url = `http://${globalThis.location.hostname.toLowerCase()}:${globalThis.location.port || '80'}`;
    useDashboardStore.setState({ workspaces: [ws('a', null), ws('b', B)], activeWorkspaceId: 'a', connection: null });
    render(<App />);
    expect(lastKeptTargets()).toEqual([url, 'http://other-host:1081'].sort());

    act(() => useDashboardStore.getState().setWorkspaceConnection('a', C));
    expect(lastKeptTargets()).toEqual(['http://other-host:1081', 'http://third-host:1082']);

    act(() => useDashboardStore.getState().closeWorkspace('b'));
    expect(lastKeptTargets()).toEqual(['http://third-host:1082']);
  });

  it('lets only the dashboard URL’s server decide analytics, not a workspace’s other target', async () => {
    initAnalytics.mockClear();
    useDashboardStore.setState({ workspaces: [ws('a', null), ws('b', B)], activeWorkspaceId: 'b', connection: B });
    render(<App />);
    await waitFor(() => expect(useDashboardStore.getState().serverConfiguration).toEqual({}));
    expect(initAnalytics).not.toHaveBeenCalled();

    act(() => useDashboardStore.getState().switchWorkspace('a'));
    await waitFor(() => expect(initAnalytics).toHaveBeenCalledTimes(1));
  });

  it('never lets a server on another host named by the link decide analytics; the page origin’s server still does', async () => {
    const original = globalThis.location.href;
    globalThis.history.replaceState(null, '', '?host=attacker.example&port=443');
    try {
      const { page, foreignHost } = resolveUrlConnection();
      expect(foreignHost).toBe(true);
      const pageOrigin: WorkspaceConnection = { host: page.host, port: page.port, secure: page.secure, basePath: page.basePath ?? '' };
      initAnalytics.mockClear();
      useDashboardStore.setState({ workspaces: [ws('a', null), ws('b', pageOrigin)], activeWorkspaceId: 'a', connection: null });
      render(<App />);
      await waitFor(() => expect(useDashboardStore.getState().serverConfiguration).toEqual({}));
      expect(initAnalytics).not.toHaveBeenCalled();

      act(() => useDashboardStore.getState().switchWorkspace('b'));
      await waitFor(() => expect(initAnalytics).toHaveBeenCalledTimes(1));
    } finally {
      globalThis.history.replaceState(null, '', original);
    }
  });
});
