import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { act, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ThemeProvider } from '@mui/material/styles';
import { buildTheme } from '../theme';
import WorkspaceTabBar from '../components/WorkspaceTabBar';
import AppBar from '../components/AppBar';
import { useDashboardStore, type Workspace } from '../store';
import {
  _resetBreakpointCallbackClient,
  getBreakpointCallbackClient,
  releaseBreakpointCallbackClientsExcept,
} from '../lib/breakpointCallbackClient';

function workspace(id: string, name: string, overrides: Partial<Workspace> = {}): Workspace {
  return {
    id,
    name,
    view: 'get-started',
    logSearch: '',
    expectationSearch: '',
    receivedSearch: '',
    proxiedSearch: '',
    trafficSearch: '',
    connection: null,
    ...overrides,
  };
}

function renderTabBar() {
  return render(
    <ThemeProvider theme={buildTheme('dark')}>
      <WorkspaceTabBar />
    </ThemeProvider>,
  );
}

function bar() {
  return within(screen.getByTestId('workspace-tab-bar'));
}

describe('WorkspaceTabBar', () => {
  beforeEach(() => {
    globalThis.localStorage.clear();
    globalThis.location.hash = '';
    useDashboardStore.setState({
      view: 'get-started',
      logSearch: '',
      expectationSearch: '',
      receivedSearch: '',
      proxiedSearch: '',
      trafficSearch: '',
      selectedTrafficKey: null,
      workspaces: [workspace('workspace-1', 'Workspace 1')],
      activeWorkspaceId: 'workspace-1',
    });
  });

  afterEach(() => {
    globalThis.localStorage.clear();
    globalThis.location.hash = '';
  });

  // The reverted d536433f1 crowded the app bar. The switcher must cost nothing
  // until the user has actually opted into a second workspace.
  it('renders nothing at all with a single workspace', () => {
    const { container } = renderTabBar();
    expect(container).toBeEmptyDOMElement();
    expect(screen.queryByTestId('workspace-tab-bar')).toBeNull();
  });

  it('appears once a second workspace exists, listing every workspace', () => {
    useDashboardStore.setState({
      workspaces: [workspace('a', 'Alpha'), workspace('b', 'Beta')],
      activeWorkspaceId: 'a',
    });
    renderTabBar();

    expect(screen.getByTestId('workspace-tab-bar')).toBeInTheDocument();
    expect(bar().getByLabelText('Workspace Alpha')).toHaveAttribute('aria-current', 'true');
    expect(bar().getByLabelText('Workspace Beta')).not.toHaveAttribute('aria-current');
  });

  it('switches workspace on click, loading that workspace’s view and searches', async () => {
    const user = userEvent.setup();
    useDashboardStore.setState({
      workspaces: [
        workspace('a', 'Alpha'),
        workspace('b', 'Beta', { view: 'chaos', logSearch: 'beta-only' }),
      ],
      activeWorkspaceId: 'a',
    });
    renderTabBar();

    await user.click(bar().getByLabelText('Workspace Beta'));

    const state = useDashboardStore.getState();
    expect(state.activeWorkspaceId).toBe('b');
    expect(state.view).toBe('chaos');
    expect(state.logSearch).toBe('beta-only');
  });

  it('closes a workspace from its close button', async () => {
    const user = userEvent.setup();
    useDashboardStore.setState({
      workspaces: [workspace('a', 'Alpha'), workspace('b', 'Beta')],
      activeWorkspaceId: 'b',
    });
    renderTabBar();

    await user.click(bar().getByLabelText('Close workspace Alpha'));

    expect(useDashboardStore.getState().workspaces.map((w) => w.id)).toEqual(['b']);
    expect(useDashboardStore.getState().activeWorkspaceId).toBe('b');
  });

  it('adds a workspace from the trailing plus button', async () => {
    const user = userEvent.setup();
    useDashboardStore.setState({
      workspaces: [workspace('a', 'Alpha'), workspace('b', 'Beta')],
      activeWorkspaceId: 'a',
    });
    renderTabBar();

    await user.click(bar().getByLabelText('New workspace'));

    expect(useDashboardStore.getState().workspaces).toHaveLength(3);
  });

  it('renames a workspace inline on double-click', async () => {
    const user = userEvent.setup();
    useDashboardStore.setState({
      workspaces: [workspace('a', 'Alpha'), workspace('b', 'Beta')],
      activeWorkspaceId: 'a',
    });
    renderTabBar();

    await user.dblClick(bar().getByLabelText('Workspace Alpha'));
    const field = bar().getByLabelText('Rename workspace Alpha');
    await user.clear(field);
    await user.type(field, 'Payments{Enter}');

    expect(useDashboardStore.getState().workspaces[0]!.name).toBe('Payments');
  });

  it('abandons an inline rename on Escape', async () => {
    const user = userEvent.setup();
    useDashboardStore.setState({
      workspaces: [workspace('a', 'Alpha'), workspace('b', 'Beta')],
      activeWorkspaceId: 'a',
    });
    renderTabBar();

    await user.dblClick(bar().getByLabelText('Workspace Alpha'));
    const field = bar().getByLabelText('Rename workspace Alpha');
    await user.clear(field);
    await user.type(field, 'Discarded{Escape}');

    expect(useDashboardStore.getState().workspaces[0]!.name).toBe('Alpha');
    expect(bar().getByLabelText('Workspace Alpha')).toBeInTheDocument();
  });
});

describe('AppBar workspace entry point', () => {
  beforeEach(() => {
    globalThis.localStorage.clear();
    useDashboardStore.setState({
      connectionStatus: 'connected',
      themeMode: 'dark',
      workspaces: [workspace('workspace-1', 'Workspace 1')],
      activeWorkspaceId: 'workspace-1',
      view: 'get-started',
      logSearch: '',
      expectationSearch: '',
      receivedSearch: '',
      proxiedSearch: '',
      trafficSearch: '',
    });
  });

  afterEach(() => {
    globalThis.localStorage.clear();
  });

  it('creates the second workspace from a single icon button in the app bar', async () => {
    const user = userEvent.setup();
    render(
      <ThemeProvider theme={buildTheme('dark')}>
        <AppBar
          onClearServer={vi.fn().mockResolvedValue(undefined)}
          onClearLogs={vi.fn().mockResolvedValue(undefined)}
          onClearExpectations={vi.fn().mockResolvedValue(undefined)}
          onShowShortcuts={vi.fn()}
        />
      </ThemeProvider>,
    );

    await user.click(screen.getByLabelText('New workspace'));

    expect(useDashboardStore.getState().workspaces).toHaveLength(2);
  });

  it('adds no text to the navigation region — one icon button only', () => {
    render(
      <ThemeProvider theme={buildTheme('dark')}>
        <AppBar
          onClearServer={vi.fn().mockResolvedValue(undefined)}
          onClearLogs={vi.fn().mockResolvedValue(undefined)}
          onClearExpectations={vi.fn().mockResolvedValue(undefined)}
          onShowShortcuts={vi.fn()}
        />
      </ThemeProvider>,
    );

    // The reverted recent-view tabs added labelled text buttons that competed
    // with the nav groups for horizontal space. This control must stay a bare
    // icon: no visible text, and exactly one of them.
    const buttons = screen.getAllByLabelText('New workspace');
    expect(buttons).toHaveLength(1);
    expect(buttons[0]).toHaveTextContent('');
  });
});

describe('WorkspaceTabBar — per-workspace MockServer target', () => {
  const B = { host: 'other-host', port: '1081', secure: false, basePath: '' };

  beforeEach(() => {
    globalThis.localStorage.clear();
    useDashboardStore.setState({
      workspaces: [workspace('a', 'Alpha'), workspace('b', 'Beta')],
      activeWorkspaceId: 'a',
      connection: null,
      connectionEpoch: 0,
    });
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    globalThis.localStorage.clear();
  });

  it('shows no target until a workspace has one, then shows every workspace’s target', () => {
    const { rerender } = renderTabBar();
    expect(screen.queryByTestId('workspace-target-a')).toBeNull();

    useDashboardStore.setState({ workspaces: [workspace('a', 'Alpha'), workspace('b', 'Beta', { connection: B })] });
    rerender(
      <ThemeProvider theme={buildTheme('dark')}>
        <WorkspaceTabBar />
      </ThemeProvider>,
    );
    expect(screen.getByTestId('workspace-target-b')).toHaveTextContent('other-host:1081');
    expect(screen.getByTestId('workspace-target-a')).toHaveTextContent(`${globalThis.location.hostname}:`);
  });

  it('sets a workspace’s target once the target answers the status check', async () => {
    const fetchMock = vi.fn(async () => new Response('{}', { status: 200 }));
    vi.stubGlobal('fetch', fetchMock);
    const user = userEvent.setup();
    renderTabBar();

    await user.click(bar().getByLabelText('Set MockServer for workspace Beta'));
    const dialog = screen.getByRole('dialog', { name: 'MockServer for Beta' });
    const host = within(dialog).getByLabelText('Host');
    await user.clear(host);
    await user.type(host, 'other-host');
    const port = within(dialog).getByLabelText('Port');
    await user.clear(port);
    await user.type(port, '1081');
    await user.click(within(dialog).getByRole('button', { name: 'Save' }));

    expect(fetchMock).toHaveBeenCalledWith('http://other-host:1081/mockserver/status', expect.objectContaining({ method: 'PUT' }));
    expect(useDashboardStore.getState().workspaces.find((w) => w.id === 'b')!.connection).toEqual(B);
    expect(useDashboardStore.getState().connection).toBeNull();
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('explains a failed check and saves only on Save anyway', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => { throw new TypeError('Failed to fetch'); }));
    const user = userEvent.setup();
    renderTabBar();

    await user.click(bar().getByLabelText('Set MockServer for workspace Alpha'));
    const dialog = screen.getByRole('dialog');
    const port = within(dialog).getByLabelText('Port');
    await user.clear(port);
    await user.type(port, '1099');
    await user.click(within(dialog).getByRole('button', { name: 'Save' }));

    expect(await within(dialog).findByTestId('workspace-target-probe-failure')).toHaveTextContent(/Could not reach MockServer/);
    expect(useDashboardStore.getState().connection).toBeNull();

    await user.click(within(dialog).getByRole('button', { name: 'Save anyway' }));
    expect(useDashboardStore.getState().connection).toMatchObject({ port: '1099' });
  });

  it('refuses a URL typed as the host before checking anything', async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    const user = userEvent.setup();
    renderTabBar();

    await user.click(bar().getByLabelText('Set MockServer for workspace Alpha'));
    const dialog = screen.getByRole('dialog');
    const host = within(dialog).getByLabelText('Host');
    await user.clear(host);
    await user.type(host, 'http://other-host');

    expect(within(dialog).getByText(/without http:\/\/ or a path/)).toBeInTheDocument();
    expect(within(dialog).getByRole('button', { name: 'Save' })).toBeDisabled();
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('does not save a target whose check finishes after Cancel', async () => {
    let answer: (r: Response) => void = () => {};
    vi.stubGlobal('fetch', vi.fn(() => new Promise<Response>((resolve) => { answer = resolve; })));
    const user = userEvent.setup();
    renderTabBar();

    await user.click(bar().getByLabelText('Set MockServer for workspace Beta'));
    const dialog = screen.getByRole('dialog');
    await user.click(within(dialog).getByRole('button', { name: 'Save' }));
    await user.click(within(dialog).getByRole('button', { name: 'Cancel' }));
    answer(new Response('{}', { status: 200 }));
    await new Promise((r) => setTimeout(r, 0));

    expect(useDashboardStore.getState().workspaces.find((w) => w.id === 'b')!.connection).toBeNull();
  });

  it('puts a workspace back on the dashboard URL’s server', async () => {
    useDashboardStore.setState({
      workspaces: [workspace('a', 'Alpha', { connection: B }), workspace('b', 'Beta')],
      connection: B,
    });
    const user = userEvent.setup();
    renderTabBar();

    await user.click(bar().getByLabelText('Set MockServer for workspace Alpha'));
    await user.click(screen.getByRole('button', { name: "Use the dashboard URL's server" }));
    expect(useDashboardStore.getState().connection).toBeNull();
  });

  it('keeps a sole workspace with its own target visible and resettable after closing down to it', async () => {
    useDashboardStore.setState({
      workspaces: [workspace('a', 'Alpha'), workspace('b', 'Beta', { connection: B })],
      activeWorkspaceId: 'b',
      connection: B,
    });
    const user = userEvent.setup();
    renderTabBar();

    await user.click(bar().getByLabelText('Close workspace Alpha'));

    expect(useDashboardStore.getState().workspaces).toHaveLength(1);
    expect(useDashboardStore.getState().connection).toEqual(B);
    expect(screen.getByTestId('workspace-tab-bar')).toBeInTheDocument();
    expect(screen.getByTestId('workspace-target-b')).toHaveTextContent('other-host:1081');
    expect(bar().queryByLabelText('Close workspace Beta')).toBeNull();

    await user.click(bar().getByLabelText('Set MockServer for workspace Beta'));
    await user.click(screen.getByRole('button', { name: "Use the dashboard URL's server" }));

    expect(useDashboardStore.getState().connection).toBeNull();
    expect(screen.queryByTestId('workspace-tab-bar')).toBeNull();
  });
});

describe('WorkspaceTabBar — items paused on a background workspace’s server', () => {
  const B = { host: 'other-host', port: '1081', secure: false, basePath: '' };

  class FakeSocket {
    static instances: FakeSocket[] = [];
    static OPEN = 1;
    readyState = 0;
    onopen: (() => void) | null = null;
    onmessage: ((event: { data: string }) => void) | null = null;
    onclose: (() => void) | null = null;
    onerror: (() => void) | null = null;
    constructor(public url: string) { FakeSocket.instances.push(this); }
    send() { /* decisions are not under test */ }
    close() { this.readyState = 3; this.onclose?.(); }
    open(clientId: string) {
      this.readyState = 1;
      this.onopen?.();
      this.push('org.mockserver.serialization.model.WebSocketClientIdDTO', { clientId });
    }
    push(type: string, value: unknown) {
      this.onmessage?.({ data: JSON.stringify({ type, value: JSON.stringify(value) }) });
    }
    pause(correlationId: string) {
      this.push('org.mockserver.model.HttpRequest', {
        method: 'GET', path: `/${correlationId}`, headers: { WebSocketCorrelationId: [correlationId] },
      });
    }
  }

  /** Open B's breakpoint session (as visiting Breakpoints in Beta would) and pause `n` requests on it. */
  function pauseOnB(n: number): FakeSocket {
    getBreakpointCallbackClient(B).connect(B);
    const socket = FakeSocket.instances[FakeSocket.instances.length - 1]!;
    socket.open('client-b');
    for (let i = 0; i < n; i++) socket.pause(`corr-${i}`);
    return socket;
  }

  beforeEach(() => {
    FakeSocket.instances = [];
    vi.stubGlobal('WebSocket', FakeSocket as unknown as typeof WebSocket);
    _resetBreakpointCallbackClient();
    globalThis.localStorage.clear();
    globalThis.location.hash = '';
    useDashboardStore.setState({
      view: 'traffic',
      workspaces: [workspace('a', 'Alpha'), workspace('b', 'Beta', { connection: B, view: 'traffic' })],
      activeWorkspaceId: 'a',
      connection: null,
      connectionEpoch: 0,
    });
  });

  afterEach(() => {
    _resetBreakpointCallbackClient();
    vi.unstubAllGlobals();
    globalThis.localStorage.clear();
    globalThis.location.hash = '';
  });

  it('shows nothing while nothing is paused, or before the server has a breakpoint session', () => {
    renderTabBar();
    expect(screen.queryByTestId('workspace-paused-b')).toBeNull();

    act(() => { pauseOnB(0); });
    expect(screen.queryByTestId('workspace-paused-b')).toBeNull();
  });

  it('counts items paused on a background workspace’s server as they arrive and are released', () => {
    renderTabBar();
    let socket!: FakeSocket;
    act(() => { socket = pauseOnB(2); });
    const badge = screen.getByTestId('workspace-paused-b');
    expect(badge).toHaveTextContent('2');
    expect(badge).toHaveAccessibleName('2 items paused at a breakpoint on other-host:1081: open Breakpoints in workspace Beta');

    act(() => {
      socket.push('org.mockserver.serialization.model.BreakpointReleasedDTO', { correlationId: 'corr-0', reason: 'TIMEOUT' });
    });
    expect(screen.getByTestId('workspace-paused-b')).toHaveTextContent('1');

    // a lost session cannot resolve what it held, so the count goes with it
    act(() => { socket.close(); });
    expect(screen.queryByTestId('workspace-paused-b')).toBeNull();
  });

  it('never badges the active workspace, whose own view is the place to act', () => {
    useDashboardStore.setState({ activeWorkspaceId: 'b', connection: B, view: 'traffic' });
    renderTabBar();
    act(() => { pauseOnB(1); });
    expect(screen.queryByTestId('workspace-paused-b')).toBeNull();
  });

  it('does not badge a background tab on the active workspace’s own server', () => {
    useDashboardStore.setState({
      workspaces: [workspace('a', 'Alpha', { connection: B }), workspace('b', 'Beta', { connection: B, view: 'traffic' })],
      activeWorkspaceId: 'a',
      connection: B,
    });
    renderTabBar();
    act(() => { pauseOnB(2); });
    expect(screen.queryByTestId('workspace-paused-b')).toBeNull();
  });

  it('drops the count when the last workspace on that server is retargeted and its session released', () => {
    renderTabBar();
    act(() => { pauseOnB(1); });
    expect(screen.getByTestId('workspace-paused-b')).toBeInTheDocument();

    act(() => { releaseBreakpointCallbackClientsExcept(new Set()); });
    expect(screen.queryByTestId('workspace-paused-b')).toBeNull();
  });

  it('opens the Breakpoints view of that workspace on click', async () => {
    const user = userEvent.setup();
    renderTabBar();
    act(() => { pauseOnB(1); });

    await user.click(screen.getByTestId('workspace-paused-b'));
    const s = useDashboardStore.getState();
    expect(s.activeWorkspaceId).toBe('b');
    expect(s.connection).toEqual(B);
    expect(s.view).toBe('breakpoints');
  });
});
