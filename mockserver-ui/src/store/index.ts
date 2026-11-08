import { create } from 'zustand';
import type {
  ConnectionStatus,
  DebugMismatchResult,
  JsonListItem,
  LogMessage,
  RequestFilter,
  ThemeMode,
  WebSocketMessage,
} from '../types';
import { ACTION_TYPES, LLM_PROVIDERS } from '../lib/clientFilters';
import { withFullMessages, type FullMessages } from '../lib/fullBody';
import { isShortenedExpectation, rememberShortenedExpectations } from '../lib/fullExpectation';
import {
  coerceConnection,
  connectionKey,
  effectiveConnection,
  urlConnectionParams,
  urlNamesTarget,
  type WorkspaceConnection,
} from '../lib/connectionTarget';

export type ViewMode = 'dashboard' | 'traffic' | 'sessions' | 'composer' | 'library' | 'chaos' | 'performance' | 'metrics' | 'drift' | 'verification' | 'slo' | 'async' | 'grpc' | 'breakpoints' | 'contract' | 'cluster' | 'optimise' | 'mcp-health' | 'scenarios' | 'audit' | 'get-started';

/** Map legacy/removed ViewMode values to their replacement. */
const VIEW_MIGRATION: Record<string, ViewMode> = {
  'mcp-tools': 'composer',
};

/** Every valid ViewMode, used to validate persisted/hash-derived values. */
export const ALL_VIEWS: readonly ViewMode[] = [
  'dashboard', 'traffic', 'sessions', 'composer', 'library', 'chaos', 'performance',
  'metrics', 'drift', 'verification', 'slo', 'async', 'grpc', 'breakpoints', 'contract',
  'cluster', 'optimise', 'mcp-health', 'scenarios', 'audit', 'get-started',
];

const VIEW_STORAGE_KEY = 'mockserver-view';
const SEARCH_STORAGE_KEY = 'mockserver-search';
const WORKSPACES_STORAGE_KEY = 'mockserver-workspaces';

/**
 * Coerce an arbitrary string into a valid ViewMode, applying legacy migrations.
 * Returns null when the value is unknown — callers fall back to their default,
 * so a stale or hand-edited persisted/hash value can never poison the view.
 */
export function coerceView(value: string | null | undefined): ViewMode | null {
  if (!value) return null;
  const migrated = VIEW_MIGRATION[value] ?? value;
  return (ALL_VIEWS as readonly string[]).includes(migrated) ? (migrated as ViewMode) : null;
}

/** Read the view encoded in the URL hash (e.g. `#/contract`), if any and valid. */
function viewFromHash(): ViewMode | null {
  try {
    const hash = globalThis.location?.hash ?? '';
    const match = /^#\/?(.+)$/.exec(hash);
    return match ? coerceView(match[1]) : null;
  } catch {
    return null;
  }
}

/**
 * Resolve the view to land on at startup. Precedence:
 *   1. a valid view in the URL hash (linkable/deep-link),
 *   2. else a previously-persisted view from localStorage,
 *   3. else 'get-started' for a genuine first visit (nothing persisted).
 */
function getInitialView(): ViewMode {
  return viewFromHash() ?? storedView() ?? 'get-started';
}

/** The view persisted in localStorage, if any and valid. */
function storedView(): ViewMode | null {
  try {
    return coerceView(globalThis.localStorage?.getItem(VIEW_STORAGE_KEY));
  } catch {
    // localStorage may not be available in test/SSR environments
    return null;
  }
}

/** Persist the active view to localStorage and reflect it in the URL hash. */
function persistView(view: ViewMode): void {
  try { globalThis.localStorage?.setItem(VIEW_STORAGE_KEY, view); } catch { /* noop */ }
  try {
    // Only assign when the hash actually differs so this can never trigger a
    // hashchange event (and thus a feedback loop with App's hashchange listener),
    // regardless of how the browser coalesces identical assignments.
    const next = `#/${view}`;
    if (globalThis.location && globalThis.location.hash !== next) {
      globalThis.location.hash = next;
    }
  } catch { /* noop */ }
}

interface PersistedSearch {
  logSearch: string;
  expectationSearch: string;
  receivedSearch: string;
  proxiedSearch: string;
  trafficSearch: string;
}

const EMPTY_SEARCH: PersistedSearch = {
  logSearch: '', expectationSearch: '', receivedSearch: '', proxiedSearch: '', trafficSearch: '',
};

/** Restore the persisted per-panel search/filter terms; defaults to empty. */
function getInitialSearch(): PersistedSearch {
  try {
    const raw = globalThis.localStorage?.getItem(SEARCH_STORAGE_KEY);
    if (raw) {
      const parsed = JSON.parse(raw) as Partial<PersistedSearch>;
      return {
        logSearch: typeof parsed.logSearch === 'string' ? parsed.logSearch : '',
        expectationSearch: typeof parsed.expectationSearch === 'string' ? parsed.expectationSearch : '',
        receivedSearch: typeof parsed.receivedSearch === 'string' ? parsed.receivedSearch : '',
        proxiedSearch: typeof parsed.proxiedSearch === 'string' ? parsed.proxiedSearch : '',
        trafficSearch: typeof parsed.trafficSearch === 'string' ? parsed.trafficSearch : '',
      };
    }
  } catch {
    // unavailable or malformed — fall through to empty defaults
  }
  return { ...EMPTY_SEARCH };
}

/** Persist the current set of per-panel search/filter terms to localStorage. */
function persistSearch(search: PersistedSearch): void {
  try { globalThis.localStorage?.setItem(SEARCH_STORAGE_KEY, JSON.stringify(search)); } catch { /* noop */ }
}

/* ------------------------------------------------------------------------- *
 * Workspaces
 *
 * A workspace is an independent investigation context: its own view and its own
 * per-panel search terms. Multiple workspaces let one window hold two separate
 * lines of enquiry without one clobbering the other's filters.
 *
 * DESIGN — "live state + snapshots", deliberately NOT a nested per-workspace
 * store. The ACTIVE workspace's state remains the existing top-level store
 * fields (`view`, `logSearch`, …) exactly as before, so every panel keeps its
 * current selector and every existing setter (`setView`, `setLogSearch`, …) is
 * untouched. `workspaces` holds a *snapshot* of each workspace; the snapshot for
 * the active workspace is only refreshed when the active workspace changes
 * (switch / add / close). This means:
 *   - with a single workspace nothing ever reads or writes a snapshot, so the
 *     behaviour is byte-for-byte what it was before workspaces existed;
 *   - no consumer of the store changes, so a view switch cannot silently reset
 *     filter state — `setView` still only writes `view`.
 *
 * PERSISTENCE — `mockserver-view` and `mockserver-search` continue to hold the
 * ACTIVE workspace's state (unchanged keys, unchanged format), and the new
 * `mockserver-workspaces` key holds the workspace list. On load the active
 * workspace's snapshot is overwritten from those two legacy keys, so an existing
 * user upgrading (who has no `mockserver-workspaces` entry at all) lands in a
 * single workspace carrying exactly their previously-persisted view and searches.
 *
 * TWO BROWSER TABS share one `localStorage`. Once the workspaces key is in use,
 * every live change is written through to it together with the legacy keys and
 * the writer's active id, so the legacy keys always describe the workspace the
 * blob names as active. Each tab also adopts the other tabs' writes
 * ({@link DashboardState.adoptWorkspacesFromOtherTab}) for every workspace except
 * its own active one, so a tab never writes back a stale copy of a workspace it
 * is not using. A workspace live in two tabs at once is last-writer-wins.
 * ------------------------------------------------------------------------- */

/** The slice of store state that belongs to one workspace rather than the app. */
export interface WorkspaceState {
  view: ViewMode;
  logSearch: string;
  expectationSearch: string;
  receivedSearch: string;
  proxiedSearch: string;
  trafficSearch: string;
  /** The MockServer this workspace targets; null follows the dashboard URL. */
  connection: WorkspaceConnection | null;
}

/** A named workspace: an id, a user-editable label, and its own state. */
export interface Workspace extends WorkspaceState {
  id: string;
  name: string;
}

/**
 * The view a newly-created workspace opens on. Deliberately NOT 'get-started':
 * a user creating a second workspace is past onboarding, and the dashboard is
 * the primary data view. (A genuine first visit still lands on Get Started —
 * that is {@link getInitialView}, which is unchanged.)
 */
const NEW_WORKSPACE_VIEW: ViewMode = 'dashboard';

/** Longest accepted workspace name — keeps one tab from monopolising the bar. */
const MAX_WORKSPACE_NAME_LENGTH = 40;

/** Extract the per-workspace slice from anything carrying those fields. */
function workspaceStateOf(state: WorkspaceState): WorkspaceState {
  return {
    view: state.view,
    logSearch: state.logSearch,
    expectationSearch: state.expectationSearch,
    receivedSearch: state.receivedSearch,
    proxiedSearch: state.proxiedSearch,
    trafficSearch: state.trafficSearch,
    connection: state.connection,
  };
}

/** The five persisted search terms of a workspace, for {@link persistSearch}. */
function searchOf(state: WorkspaceState): PersistedSearch {
  return {
    logSearch: state.logSearch,
    expectationSearch: state.expectationSearch,
    receivedSearch: state.receivedSearch,
    proxiedSearch: state.proxiedSearch,
    trafficSearch: state.trafficSearch,
  };
}

/** Coerce an unknown value to a string, defaulting to empty. */
function asString(value: unknown): string {
  return typeof value === 'string' ? value : '';
}

/**
 * Monotonic counter behind {@link nextWorkspaceId}. Ids only need to be unique
 * within one browser session's workspace list; collisions with rehydrated ids
 * are resolved by the uniqueness loop below rather than by seeding the counter.
 */
let workspaceIdCounter = 0;

function nextWorkspaceId(existing: readonly Workspace[]): string {
  let id: string;
  do {
    workspaceIdCounter += 1;
    // The random part keeps two browser tabs creating a workspace at once from picking the same id.
    id = `workspace-${workspaceIdCounter}-${Math.random().toString(36).slice(2, 8)}`;
  } while (existing.some((w) => w.id === id));
  return id;
}

function nextWorkspaceName(existing: readonly Workspace[]): string {
  const taken = new Set(existing.map((w) => w.name));
  let n = existing.length + 1;
  while (taken.has(`Workspace ${n}`)) n += 1;
  return `Workspace ${n}`;
}

interface PersistedWorkspaces {
  activeWorkspaceId: string;
  workspaces: Workspace[];
}

/**
 * Restore the workspace list. Every field is validated: an unknown/hand-edited
 * view falls back to the new-workspace default rather than poisoning the store,
 * non-string searches become empty, entries without a usable id are dropped, and
 * duplicate ids are discarded. Any failure (absent key, malformed JSON, wrong
 * shape) degrades to a single workspace carrying `live` — which is precisely the
 * upgrade path for a user who has `mockserver-view`/`mockserver-search` but has
 * never seen workspaces.
 *
 * The active workspace's snapshot is always replaced by `live`, because the two
 * legacy keys — not the workspaces blob — are authoritative for whichever
 * workspace was active when the page was last open.
 */
/** Parse a stored workspaces blob, field by field; null when it holds no usable workspace. */
function parseWorkspaces(raw: string | null | undefined): PersistedWorkspaces | null {
  if (!raw) return null;
  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    return null;
  }
  if (typeof parsed !== 'object' || parsed === null) return null;
  const candidate = parsed as Partial<PersistedWorkspaces>;
  if (!Array.isArray(candidate.workspaces)) return null;

  const seen = new Set<string>();
  const workspaces: Workspace[] = [];
  for (const entry of candidate.workspaces) {
    if (typeof entry !== 'object' || entry === null) continue;
    const e = entry as Partial<Workspace>;
    if (typeof e.id !== 'string' || e.id === '' || seen.has(e.id)) continue;
    seen.add(e.id);
    workspaces.push({
      id: e.id,
      name: typeof e.name === 'string' && e.name.trim() !== ''
        ? e.name.trim().slice(0, MAX_WORKSPACE_NAME_LENGTH)
        : `Workspace ${workspaces.length + 1}`,
      view: coerceView(typeof e.view === 'string' ? e.view : null) ?? NEW_WORKSPACE_VIEW,
      logSearch: asString(e.logSearch),
      expectationSearch: asString(e.expectationSearch),
      receivedSearch: asString(e.receivedSearch),
      proxiedSearch: asString(e.proxiedSearch),
      trafficSearch: asString(e.trafficSearch),
      // An invalid stored target is dropped (the workspace follows the URL) rather than kept half-valid.
      connection: coerceConnection(e.connection),
    });
  }
  if (workspaces.length === 0) return null;

  const activeWorkspaceId = typeof candidate.activeWorkspaceId === 'string'
    && workspaces.some((w) => w.id === candidate.activeWorkspaceId)
    ? candidate.activeWorkspaceId
    : workspaces[0]!.id;
  return { activeWorkspaceId, workspaces };
}

/** Whether the workspaces key is in use, so live changes must be written through to it. */
let workspacesPersisted = false;

function getInitialWorkspaces(search: PersistedSearch): PersistedWorkspaces {
  let raw: string | null | undefined;
  try {
    raw = globalThis.localStorage?.getItem(WORKSPACES_STORAGE_KEY);
  } catch {
    raw = null;
  }
  const stored = parseWorkspaces(raw);
  if (!stored) {
    const only: Workspace = { id: 'workspace-1', name: 'Workspace 1', view: getInitialView(), ...search, connection: null };
    return { activeWorkspaceId: only.id, workspaces: [only] };
  }
  workspacesPersisted = true;
  const active = stored.workspaces.find((w) => w.id === stored.activeWorkspaceId)!;
  if (linkOverridesTarget(active.connection)) return openLinkedWorkspace(stored, search);
  return {
    activeWorkspaceId: stored.activeWorkspaceId,
    // The legacy keys win for the active workspace's view and searches — see the doc comment above.
    // They carry no target, so the stored one is kept.
    workspaces: stored.workspaces.map((w) => (w.id === stored.activeWorkspaceId ? { ...w, view: getInitialView(), ...search } : w)),
  };
}

/** Whether the dashboard URL names a server other than this workspace's own target. */
function linkOverridesTarget(connection: WorkspaceConnection | null): boolean {
  return connection !== null && urlNamesTarget() && connectionKey(connection) !== connectionKey(urlConnectionParams());
}

/**
 * A dashboard link that names a server wins over the active workspace's own target. Rather than
 * overwrite that target, the page opens on a workspace that follows the link (the first without a
 * target, else a new one), with the link's `#/<view>`, and the targeted workspace is kept one tab away.
 * Persisted at once: the legacy view and search keys must describe the workspace now active.
 */
function openLinkedWorkspace(stored: PersistedWorkspaces, search: PersistedSearch): PersistedWorkspaces {
  const linkedView = viewFromHash();
  const existing = stored.workspaces.find((w) => w.connection === null);
  const follower: Workspace = existing
    ? { ...existing, view: linkedView ?? existing.view }
    : {
      id: nextWorkspaceId(stored.workspaces),
      name: nextWorkspaceName(stored.workspaces),
      connection: null,
      view: linkedView ?? NEW_WORKSPACE_VIEW,
      ...EMPTY_SEARCH,
    };
  const workspaces = stored.workspaces.map((w) => {
    if (w.id === stored.activeWorkspaceId) return { ...w, view: storedView() ?? w.view, ...search };
    return w.id === follower.id ? follower : w;
  });
  if (!existing) workspaces.push(follower);
  persistWorkspaces(workspaces, follower.id);
  activateWorkspace(follower);
  return { activeWorkspaceId: follower.id, workspaces };
}

/** Persist the workspace list. During store init, only when a link opens another workspace ({@link openLinkedWorkspace}). */
function persistWorkspaces(workspaces: Workspace[], activeWorkspaceId: string): void {
  workspacesPersisted = true;
  try {
    globalThis.localStorage?.setItem(
      WORKSPACES_STORAGE_KEY,
      JSON.stringify({ activeWorkspaceId, workspaces } satisfies PersistedWorkspaces),
    );
  } catch { /* noop */ }
}

/**
 * Make `next` the live state: persist it through the SAME keys a manual
 * navigation/search would use, so a reload restores the newly-activated
 * workspace exactly as if the user had navigated there by hand.
 */
function activateWorkspace(next: Workspace): WorkspaceState {
  persistView(next.view);
  persistSearch(searchOf(next));
  return workspaceStateOf(next);
}

/** Whether moving from one workspace target to another reaches a different server. */
function targetChanges(from: WorkspaceConnection | null, to: WorkspaceConnection | null): boolean {
  if (from === to) return false;
  const base = urlConnectionParams();
  return connectionKey(effectiveConnection(from, base)) !== connectionKey(effectiveConnection(to, base));
}

/**
 * Per-panel cache mapping each `key` to the reference currently held for it and
 * that reference's serialized form. Lets {@link reconcileByKey} compare a
 * freshly-parsed entry against the previous one with a single `JSON.stringify`
 * of the *new* entry, instead of re-serializing both sides on every push.
 *
 * One cache instance is passed per entity array (the four panels never collide).
 * The stored `str` is trusted only when the stored `ref` is identical to the
 * previous entry being compared, so a stale cache (e.g. after a direct
 * `setState` that bypasses this function) can never cause a wrong comparison.
 */
type ReconcileCache = Map<string, { ref: unknown; str: string }>;

const logMessagesCache: ReconcileCache = new Map();
const activeExpectationsCache: ReconcileCache = new Map();
const recordedRequestsCache: ReconcileCache = new Map();
const proxiedRequestsCache: ReconcileCache = new Map();

/**
 * Reconcile a freshly-parsed array against the previous one, preserving object
 * identity for entries whose content is unchanged.
 *
 * Every WebSocket push delivers brand-new parsed objects, so without this each
 * row would be a new reference on every tick — and `React.memo` on the row
 * components could never skip a re-render. Entries are matched by their stable
 * `key`; equality is a structural (JSON) compare. Unchanged entries keep their
 * previous reference, which keeps memoized rows and their `useMemo([item.value])`
 * hooks valid across pushes. The reused reference is the whole entry, so nested
 * children (e.g. a log group's entries) are preserved for free.
 *
 * Equality semantics are identical to a deep JSON compare, but each entry is
 * serialized at most once per lifetime: the previous reference's string is read
 * from {@link cache}, and only the new entry is stringified per push (the
 * previous code stringified both sides for every matched row, ~100 rows × 4
 * panels × ~1/sec). The cache stores both the kept reference and its string and
 * the cached string is only trusted when its `ref` is *identical* to the
 * previous entry — so a stale cache (e.g. after a direct `setState`) is detected
 * and the previous entry is re-serialized rather than mis-compared. When an
 * entry is unchanged we return — and keep cached — the previous reference and
 * its string; when it changes we cache the new string. Keys absent from `next`
 * are pruned so the cache cannot grow unbounded.
 *
 * ARRAY identity (L1): when the reconciled result is element-for-element
 * identical to `prev` (same references, same order, same length) we return the
 * ORIGINAL `prev` array rather than the freshly-mapped copy. The server re-sends
 * the full list roughly once a second even when nothing has changed; without
 * this, every push replaced all four array identities and re-rendered every
 * subscribed panel (and invalidated every `useMemo([array])` downstream) for no
 * visible change. Returning `prev` lets a Zustand selector on the array short-
 * circuit the whole subtree. The cache is left untouched in this case because it
 * is already valid for the preserved references.
 *
 * Serialization cost (L2): the dominant cost is `JSON.stringify(n)` on each new
 * entry, which is irreducible for a structural change-detection compare without
 * a protocol change (the server would have to send a per-entry hash/version) —
 * explicitly out of scope. The only safe, clear pre-`stringify` discriminator
 * across these heterogeneous entry shapes is reference identity, so we check
 * `p === n` FIRST and reuse the cached string for that reference instead of
 * re-serializing it. No speculative field-level discriminator is added: the
 * measured throughput win comes from L1 (skipping re-renders on idle re-pushes),
 * not from trying to eliminate a stringify that is genuinely needed whenever an
 * entry's content actually differs.
 */
function reconcileByKey<T extends { key: string }>(prev: T[], next: T[], cache: ReconcileCache): T[] {
  if (next.length === 0) {
    cache.clear();
    return next;
  }
  if (prev.length === 0) {
    cache.clear();
    for (const n of next) cache.set(n.key, { ref: n, str: JSON.stringify(n) });
    return next;
  }
  const prevByKey = new Map(prev.map((p) => [p.key, p] as const));
  const nextCache: ReconcileCache = new Map();
  // Tracks whether the reconciled array diverges from `prev` in any position
  // (different length, a new/changed entry, or a reorder). Stays false only when
  // every element is the SAME reference at the SAME index as in `prev`.
  let changed = next.length !== prev.length;
  const result = next.map((n, i) => {
    const p = prevByKey.get(n.key);
    if (!p) {
      changed = true;
      nextCache.set(n.key, { ref: n, str: JSON.stringify(n) });
      return n;
    }
    if (p === n) {
      // Same reference already held — reuse its cached string (only when it was
      // recorded for THIS reference) instead of re-serializing an unchanged entry.
      const cached = cache.get(n.key);
      const str = cached && cached.ref === n ? cached.str : JSON.stringify(n);
      nextCache.set(n.key, { ref: n, str });
      if (prev[i] !== n) changed = true; // same ref but reordered
      return n;
    }
    const nStr = JSON.stringify(n);
    // Trust the cached string only when it was recorded for *this* reference;
    // otherwise (cold/stale cache after a direct setState) re-serialize `p`.
    const cached = cache.get(n.key);
    const pStr = cached && cached.ref === p ? cached.str : JSON.stringify(p);
    if (pStr === nStr) {
      // Semantically unchanged — preserve the previous reference and its string.
      nextCache.set(n.key, { ref: p, str: pStr });
      if (prev[i] !== p) changed = true; // preserved ref but reordered
      return p;
    }
    changed = true;
    nextCache.set(n.key, { ref: n, str: nStr });
    return n;
  });
  if (!changed) {
    // Element-for-element identical to `prev`: keep the previous array identity
    // so subscribed panels skip re-rendering. The existing cache already holds
    // valid { ref, str } entries for these preserved references, so leave it.
    return prev;
  }
  cache.clear();
  for (const [k, v] of nextCache) cache.set(k, v);
  return result;
}

function rememberedShortened(items: JsonListItem[]): JsonListItem[] {
  rememberShortenedExpectations(items);
  return items;
}

function withLoadedMessages(items: JsonListItem[], fullMessages: Record<string, FullMessages>): JsonListItem[] {
  if (Object.keys(fullMessages).length === 0) return items;
  return items.map((item) => {
    const full = item.truncatedBodies ? fullMessages[item.key] : undefined;
    return full ? withFullMessages(item, full) : item;
  });
}

// Drop loaded messages for rows that have left the window, so they do not accumulate.
function keepLoadedMessagesFor(
  fullMessages: Record<string, FullMessages>,
  ...sections: JsonListItem[][]
): Record<string, FullMessages> {
  const keys = Object.keys(fullMessages);
  if (keys.length === 0) return fullMessages;
  const present = new Set(sections.flatMap((items) => items.map((item) => item.key)));
  if (keys.every((key) => present.has(key))) return fullMessages;
  return Object.fromEntries(Object.entries(fullMessages).filter(([key]) => present.has(key)));
}

interface DashboardState {
  logMessages: LogMessage[];
  activeExpectations: JsonListItem[];
  /**
   * How many expectations the SERVER holds, which is not the length of
   * `activeExpectations` — that is a capped page. Sent alongside it so the panel
   * can show a real count instead of one pinned at the cap.
   */
  activeExpectationsTotal: number;
  /**
   * Whether ANY expectation the SERVER holds is an LLM expectation, computed
   * server-side over the whole matcher set — see `WebSocketMessage`. This is the
   * authoritative source for offering the LLM Provider filter, because the
   * `activeExpectations` page is capped and so cannot reveal an LLM expectation
   * that sits beyond the cap. `undefined` until the first push carries it, and it
   * stays `undefined` against an older server that never sends it — FilterPanel
   * then falls back to inspecting the page.
   */
  activeExpectationsIncludeLlm: boolean | undefined;
  recordedRequests: JsonListItem[];
  proxiedRequests: JsonListItem[];
  /** The last update reached its size limit, so older request rows were left out of it. */
  frameLimitReached: boolean;
  /** The last update's log messages reached their share of its size limit, so older log messages were left out. */
  logMessagesLimitReached: boolean;
  /**
   * The server's configuration (`GET /mockserver/configuration`), loaded at startup and again whenever
   * the Configuration dialog loads it. `null` until loaded, or when it could not be loaded.
   */
  serverConfiguration: Record<string, unknown> | null;
  /** The configuration could not be loaded (for example control-plane authentication refused it). */
  serverConfigurationUnavailable: boolean;
  /**
   * Request and response messages loaded whole for rows whose bodies the server shortened, by row
   * key. Applied to the row on every update until the row leaves the window, so every panel and
   * action sees the whole body once the user has loaded it.
   */
  fullMessages: Record<string, FullMessages>;

  view: ViewMode;
  requestFilter: RequestFilter;
  filterEnabled: boolean;
  filterExpanded: boolean;

  /**
   * Every workspace, in tab order. The entry whose id is
   * {@link DashboardState.activeWorkspaceId} is the one currently live — its
   * authoritative state is the top-level `view`/`*Search` fields above, and its
   * snapshot here is only refreshed when the active workspace changes. Read this
   * for the tab bar; read the top-level fields for everything else.
   */
  workspaces: Workspace[];
  activeWorkspaceId: string;

  /**
   * The active workspace's own MockServer target, or null to follow the dashboard URL.
   * Read it through `useConnectionParams`, which merges it over the URL-derived target.
   */
  connection: WorkspaceConnection | null;
  /**
   * Incremented whenever the active target changes. An async flow captures it before it
   * starts and drops its result if it has moved on, so nothing loaded from one MockServer
   * lands in the store after the dashboard has switched to another.
   */
  connectionEpoch: number;

  connectionStatus: ConnectionStatus;
  themeMode: ThemeMode;
  autoScroll: boolean;

  logSearch: string;
  expectationSearch: string;
  receivedSearch: string;
  proxiedSearch: string;
  trafficSearch: string;

  /** When false, FORWARDED_REQUEST log entries are hidden from the Log panel. */
  logShowForwarded: boolean;

  error: string | null;

  /**
   * Where the current {@link error} came from, so a data push only supersedes a
   * banner it is entitled to. `'push'` = carried in a WebSocket message
   * (`message.error`); `'client'` = set locally via {@link setError} (a
   * user-action failure such as a failed clear, a parse failure, or a
   * connection-lost notice). A clean data push clears a `'push'` error but must
   * leave a `'client'` error standing until it is dismissed or superseded.
   */
  errorSource: 'push' | 'client' | null;

  /** Transient toast/snackbar for action feedback (success/info/warning/error). */
  notification: { message: string; severity: 'success' | 'info' | 'warning' | 'error' } | null;

  debugMismatchOpen: boolean;
  debugMismatchLoading: boolean;
  debugMismatchResult: DebugMismatchResult | null;
  debugMismatchError: string | null;

  generateStubOpen: boolean;
  generateStubLoading: boolean;
  generateStubSuggestions: Record<string, unknown>[];
  generateStubConfidence: number;
  generateStubError: string | null;

  selectedTrafficKey: string | null;

  /**
   * An expectation handed off from the Active Expectations panel's "Edit"
   * action for the Composer to load into its form. Null when there is no
   * pending edit. The raw expectation value (the matcher + action JSON) is
   * stored as-is; the Composer consumes and then clears it.
   */
  pendingEditExpectation: Record<string, unknown> | null;

  actionTypeFilter: string[];
  llmProviderFilter: string[];

  setActionTypeFilter: (types: string[]) => void;
  setLlmProviderFilter: (providers: string[]) => void;

  /** Load an expectation into the Composer for editing and switch to that view. */
  editExpectation: (expectation: Record<string, unknown>) => void;
  /** Clear the pending edit handoff (called by the Composer once consumed). */
  clearPendingEditExpectation: () => void;

  /**
   * Pre-filled method/path to seed a new breakpoint matcher form, set when the
   * user clicks "Set breakpoint" on a log row. Consumed (and cleared) by the
   * Breakpoints panel once it has applied it to the registration form.
   */
  pendingBreakpointPrefill: { method?: string; path?: string } | null;
  /** Seed the breakpoint matcher form from a request and switch to that view. */
  setBreakpointPrefill: (prefill: { method?: string; path?: string }) => void;
  /** Clear the pending breakpoint prefill (called by the panel once consumed). */
  clearPendingBreakpointPrefill: () => void;

  /**
   * Pre-filled method/path to seed the Verification form, set when the user
   * picks "Verify This Request" from a flow's Create-From menu. Consumed (and
   * cleared) by VerificationView once it has applied it to the single-request
   * matcher. Defaults elsewhere (times mode "atLeast", count 1) are the form's.
   */
  pendingVerificationDraft: { method?: string; path?: string } | null;
  /** Seed the verification form from a request and switch to that view. */
  setVerificationDraft: (prefill: { method?: string; path?: string }) => void;
  /** Clear the pending verification draft (called by the view once consumed). */
  clearPendingVerificationDraft: () => void;

  /**
   * Pre-filled scope (host and/or path) to seed the HTTP chaos rule form, set
   * when the user picks "Add Chaos For This Host/Path" from a flow's
   * Create-From menu. Consumed (and cleared) by ServiceChaosPanel once it has
   * applied it to the register form and expanded that card.
   */
  pendingChaosDraft: { host?: string; path?: string } | null;
  /** Seed the HTTP chaos form scope from a request and switch to that view. */
  setChaosDraft: (prefill: { host?: string; path?: string }) => void;
  /** Clear the pending chaos draft (called by the panel once consumed). */
  clearPendingChaosDraft: () => void;

  /**
   * Create a new workspace (default view, empty searches) and switch to it. The
   * outgoing workspace's live state is snapshotted first so nothing is lost.
   */
  addWorkspace: () => void;
  /**
   * Switch to `id`. Snapshots the outgoing workspace's live view/searches, then
   * makes the incoming workspace's snapshot live. A no-op for the already-active
   * or an unknown id.
   */
  switchWorkspace: (id: string) => void;
  /**
   * Close `id`. Closing an inactive workspace leaves the live state untouched;
   * closing the active one activates its left neighbour (or the right one when
   * closing the first). Closing the last remaining workspace is a no-op.
   */
  closeWorkspace: (id: string) => void;
  /** Rename `id`. Blank names are rejected; long names are truncated. */
  renameWorkspace: (id: string, name: string) => void;
  /**
   * Point workspace `id` at another MockServer, or back at the dashboard URL's with null.
   * When that changes the live target, everything held from the previous server is dropped.
   */
  setWorkspaceConnection: (id: string, connection: WorkspaceConnection | null) => void;
  /**
   * Take in a workspaces list another browser tab wrote. This tab's active workspace keeps
   * its own live state (including its target), so nothing changes under the user's feet.
   */
  adoptWorkspacesFromOtherTab: (raw: string | null) => void;

  applyMessage: (message: WebSocketMessage) => void;
  /** Keep the whole request/response loaded for the row `key` and show it in place of the shortened one. */
  applyFullMessages: (key: string, full: FullMessages) => void;
  clearUI: () => void;
  setView: (view: ViewMode) => void;
  setRequestFilter: (filter: RequestFilter) => void;
  setFilterEnabled: (enabled: boolean) => void;
  setFilterExpanded: (expanded: boolean) => void;
  toggleFilterExpanded: () => void;
  setConnectionStatus: (status: ConnectionStatus) => void;
  setServerConfiguration: (configuration: Record<string, unknown> | null) => void;
  setServerConfigurationUnavailable: () => void;
  setThemeMode: (mode: ThemeMode) => void;
  toggleThemeMode: () => void;
  setAutoScroll: (enabled: boolean) => void;
  toggleAutoScroll: () => void;
  setLogSearch: (term: string) => void;
  setLogShowForwarded: (show: boolean) => void;
  setExpectationSearch: (term: string) => void;
  setReceivedSearch: (term: string) => void;
  setProxiedSearch: (term: string) => void;
  setTrafficSearch: (term: string) => void;
  setSelectedTrafficKey: (key: string | null) => void;
  setError: (error: string | null) => void;
  setNotification: (notification: { message: string; severity: 'success' | 'info' | 'warning' | 'error' } | null) => void;
  openDebugMismatch: (result: DebugMismatchResult) => void;
  closeDebugMismatch: () => void;
  setDebugMismatchLoading: (loading: boolean) => void;
  setDebugMismatchError: (error: string | null) => void;

  openGenerateStub: (suggestions: Record<string, unknown>[], confidence: number) => void;
  closeGenerateStub: () => void;
  setGenerateStubLoading: (loading: boolean) => void;
  setGenerateStubError: (error: string | null) => void;
}

function getInitialTheme(): ThemeMode {
  try {
    const stored = globalThis.localStorage?.getItem('mockserver-theme');
    if (stored === 'dark' || stored === 'light') return stored;
  } catch {
    // localStorage may not be available in test/SSR environments
  }
  return 'dark';
}

const initialSearch = getInitialSearch();
// The active workspace's view (URL hash wins over localStorage, a genuine first visit lands on
// 'get-started') and searches, read once so the live state and its snapshot carry the same values.
const initialWorkspaces = getInitialWorkspaces(initialSearch);
const initialActive = initialWorkspaces.workspaces.find((w) => w.id === initialWorkspaces.activeWorkspaceId)!;

/**
 * Everything the store holds that came from the server being left: the four data arrays and
 * their caches, loaded bodies, flags, the configuration, hand-offs and the dialogs fed by them.
 */
function droppedForTargetChange(s: { connectionEpoch: number }): Partial<DashboardState> {
  logMessagesCache.clear();
  activeExpectationsCache.clear();
  recordedRequestsCache.clear();
  proxiedRequestsCache.clear();
  return {
    logMessages: [],
    activeExpectations: [],
    activeExpectationsTotal: 0,
    activeExpectationsIncludeLlm: undefined,
    recordedRequests: [],
    proxiedRequests: [],
    frameLimitReached: false,
    logMessagesLimitReached: false,
    fullMessages: {},
    serverConfiguration: null,
    serverConfigurationUnavailable: false,
    selectedTrafficKey: null,
    pendingEditExpectation: null,
    pendingBreakpointPrefill: null,
    pendingVerificationDraft: null,
    pendingChaosDraft: null,
    error: null,
    errorSource: null,
    debugMismatchOpen: false,
    debugMismatchLoading: false,
    debugMismatchResult: null,
    debugMismatchError: null,
    generateStubOpen: false,
    generateStubLoading: false,
    generateStubSuggestions: [],
    generateStubConfidence: 0,
    generateStubError: null,
    connectionEpoch: s.connectionEpoch + 1,
  };
}

/** The state that makes `next` live, dropping the previous server's data when the target changes. */
function liveStateFor(s: DashboardState, next: Workspace): Partial<DashboardState> {
  const live = activateWorkspace(next);
  return targetChanges(s.connection, next.connection)
    ? { ...droppedForTargetChange(s), ...live }
    : live;
}

export const useDashboardStore = create<DashboardState>()((set) => ({
  logMessages: [],
  activeExpectations: [],
  activeExpectationsTotal: 0,
  activeExpectationsIncludeLlm: undefined,
  recordedRequests: [],
  proxiedRequests: [],
  frameLimitReached: false,
  logMessagesLimitReached: false,
  serverConfiguration: null,
  serverConfigurationUnavailable: false,
  fullMessages: {},

  view: initialActive.view,
  requestFilter: {},
  filterEnabled: false,
  filterExpanded: false,

  workspaces: initialWorkspaces.workspaces,
  activeWorkspaceId: initialWorkspaces.activeWorkspaceId,
  connection: initialActive.connection,
  connectionEpoch: 0,

  connectionStatus: 'disconnected',
  themeMode: getInitialTheme(),
  autoScroll: true,

  logSearch: initialActive.logSearch,
  expectationSearch: initialActive.expectationSearch,
  receivedSearch: initialActive.receivedSearch,
  proxiedSearch: initialActive.proxiedSearch,
  trafficSearch: initialActive.trafficSearch,

  logShowForwarded: true,

  error: null,
  errorSource: null,
  notification: null,

  debugMismatchOpen: false,
  debugMismatchLoading: false,
  debugMismatchResult: null,
  debugMismatchError: null,

  generateStubOpen: false,
  generateStubLoading: false,
  generateStubSuggestions: [],
  generateStubConfidence: 0,
  generateStubError: null,

  selectedTrafficKey: null,

  pendingEditExpectation: null,
  pendingBreakpointPrefill: null,
  pendingVerificationDraft: null,
  pendingChaosDraft: null,

  actionTypeFilter: [],
  llmProviderFilter: [],

  // Whitelist incoming filter values so a stale URL or serialised state cannot
  // poison the store with a value that silently matches nothing.
  setActionTypeFilter: (types) => set({
    actionTypeFilter: types.filter((t) => (ACTION_TYPES as readonly string[]).includes(t)),
  }),
  setLlmProviderFilter: (providers) => set({
    llmProviderFilter: providers.filter((p) => (LLM_PROVIDERS as readonly string[]).includes(p)),
  }),

  // --- workspaces -----------------------------------------------------------
  // Each action snapshots the OUTGOING workspace's live state before changing
  // which workspace is live, so a switch can never lose the view or the search
  // terms the user had in the workspace they are leaving.

  addWorkspace: () => set((s) => {
    const snapshotted = s.workspaces.map((w) =>
      (w.id === s.activeWorkspaceId ? { ...w, ...workspaceStateOf(s) } : w));
    const created: Workspace = {
      id: nextWorkspaceId(snapshotted),
      name: nextWorkspaceName(snapshotted),
      connection: null,
      view: NEW_WORKSPACE_VIEW,
      ...EMPTY_SEARCH,
    };
    const workspaces = [...snapshotted, created];
    persistWorkspaces(workspaces, created.id);
    return {
      workspaces,
      activeWorkspaceId: created.id,
      ...liveStateFor(s, created),
      selectedTrafficKey: null,
    };
  }),

  switchWorkspace: (id) => set((s) => {
    if (id === s.activeWorkspaceId) return {};
    const workspaces = s.workspaces.map((w) =>
      (w.id === s.activeWorkspaceId ? { ...w, ...workspaceStateOf(s) } : w));
    const target = workspaces.find((w) => w.id === id);
    if (!target) return {};
    persistWorkspaces(workspaces, target.id);
    return {
      workspaces,
      activeWorkspaceId: target.id,
      ...liveStateFor(s, target),
      selectedTrafficKey: null,
    };
  }),

  closeWorkspace: (id) => set((s) => {
    // The last workspace is never closable — there must always be one live.
    if (s.workspaces.length <= 1) return {};
    const index = s.workspaces.findIndex((w) => w.id === id);
    if (index === -1) return {};
    if (id !== s.activeWorkspaceId) {
      // Closing a background workspace: the live state is not involved, so only
      // the active workspace's (possibly stale) snapshot needs refreshing.
      const workspaces = s.workspaces
        .filter((w) => w.id !== id)
        .map((w) => (w.id === s.activeWorkspaceId ? { ...w, ...workspaceStateOf(s) } : w));
      persistWorkspaces(workspaces, s.activeWorkspaceId);
      return { workspaces };
    }
    // Closing the active workspace: its state is being discarded, so there is
    // nothing to snapshot. Activate the left neighbour, or the new first entry
    // when the leftmost workspace was the one closed.
    const workspaces = s.workspaces.filter((w) => w.id !== id);
    const next = workspaces[Math.max(0, index - 1)]!;
    persistWorkspaces(workspaces, next.id);
    return {
      workspaces,
      activeWorkspaceId: next.id,
      ...liveStateFor(s, next),
      selectedTrafficKey: null,
    };
  }),

  renameWorkspace: (id, name) => set((s) => {
    const trimmed = name.trim().slice(0, MAX_WORKSPACE_NAME_LENGTH);
    if (trimmed === '' || !s.workspaces.some((w) => w.id === id)) return {};
    const workspaces = s.workspaces.map((w) => (w.id === id ? { ...w, name: trimmed } : w));
    persistWorkspaces(workspaces, s.activeWorkspaceId);
    return { workspaces };
  }),

  setWorkspaceConnection: (id, connection) => set((s) => {
    const existing = s.workspaces.find((w) => w.id === id);
    if (!existing) return {};
    if (id !== s.activeWorkspaceId) {
      const workspaces = s.workspaces.map((w) => {
        if (w.id === id) return { ...w, connection };
        return w.id === s.activeWorkspaceId ? { ...w, ...workspaceStateOf(s) } : w;
      });
      persistWorkspaces(workspaces, s.activeWorkspaceId);
      return { workspaces };
    }
    const live = { ...workspaceStateOf(s), connection };
    const workspaces = s.workspaces.map((w) => (w.id === id ? { ...w, ...live } : w));
    persistWorkspaces(workspaces, id);
    return targetChanges(s.connection, connection)
      ? { workspaces, ...droppedForTargetChange(s), connection }
      : { workspaces, connection };
  }),

  adoptWorkspacesFromOtherTab: (raw) => set((s) => {
    const incoming = parseWorkspaces(raw);
    if (!incoming) return {};
    workspacesPersisted = true;
    const ownIndex = s.workspaces.findIndex((w) => w.id === s.activeWorkspaceId);
    const own: Workspace = {
      ...(s.workspaces[ownIndex] ?? { id: s.activeWorkspaceId, name: 'Workspace' }),
      ...workspaceStateOf(s),
    };
    const theirs = incoming.workspaces.find((w) => w.id === own.id);
    const workspaces = theirs
      ? incoming.workspaces.map((w) => (w.id === own.id ? { ...own, name: w.name } : w))
      : [
          ...incoming.workspaces.slice(0, Math.max(0, ownIndex)),
          own,
          ...incoming.workspaces.slice(Math.max(0, ownIndex)),
        ];
    return { workspaces };
  }),

  applyMessage: (message) =>
    // The view is never changed here: the user stays on whatever view they are
    // on (the initial view is 'get-started') until they navigate themselves.
    // We deliberately do NOT auto-advance to the dashboard when the first data
    // arrives — landing on Get Started should be sticky.
    set((s) => {
      // Reconcile only the arrays actually present in this push. An error-only
      // message (e.g. a filter that failed to deserialize server-side arrives as
      // `{"error": "..."}` with no data arrays) must NOT blank the four panels —
      // an absent field keeps the previous array untouched until the next
      // event-driven push replaces it.
      const next = {
        logMessages: message.logMessages !== undefined
          ? reconcileByKey(s.logMessages, message.logMessages, logMessagesCache)
          : s.logMessages,
        activeExpectationsTotal:
          message.activeExpectationsTotal !== undefined
            ? message.activeExpectationsTotal
            : s.activeExpectationsTotal,
        // Preserve the previous value when a push omits it (an error-only push, or
        // an older server that never sends it) so the flag is not spuriously reset
        // to undefined between data pushes.
        activeExpectationsIncludeLlm:
          message.activeExpectationsIncludeLlm !== undefined
            ? message.activeExpectationsIncludeLlm
            : s.activeExpectationsIncludeLlm,
        activeExpectations: message.activeExpectations !== undefined
          ? reconcileByKey(s.activeExpectations, rememberedShortened(message.activeExpectations), activeExpectationsCache)
          : s.activeExpectations,
        recordedRequests: message.recordedRequests !== undefined
          ? reconcileByKey(s.recordedRequests, withLoadedMessages(message.recordedRequests, s.fullMessages), recordedRequestsCache)
          : s.recordedRequests,
        proxiedRequests: message.proxiedRequests !== undefined
          ? reconcileByKey(s.proxiedRequests, withLoadedMessages(message.proxiedRequests, s.fullMessages), proxiedRequestsCache)
          : s.proxiedRequests,
        frameLimitReached: message.recordedRequests !== undefined
          ? message.frameLimitReached === true
          : s.frameLimitReached,
        logMessagesLimitReached: message.logMessages !== undefined
          ? message.logMessagesLimitReached === true
          : s.logMessagesLimitReached,
        fullMessages: message.recordedRequests !== undefined && message.proxiedRequests !== undefined
          ? keepLoadedMessagesFor(s.fullMessages, message.recordedRequests, message.proxiedRequests)
          : s.fullMessages,
      };
      if (message.error != null) {
        // A push carried an error — record that it came from a push so a later
        // clean push may clear it.
        return { ...next, error: message.error, errorSource: 'push' as const };
      }
      if (s.errorSource === 'push') {
        // The standing banner was itself set by a push; a clean push supersedes it.
        return { ...next, error: null, errorSource: null };
      }
      // A user-action / connection error (set via setError) survives the push —
      // it stays until dismissed or explicitly superseded.
      return next;
    }),

  applyFullMessages: (key, full) =>
    set((s) => {
      const merged: FullMessages = { ...s.fullMessages[key], ...full };
      const patch = (items: JsonListItem[]) =>
        items.map((item) => (item.key === key ? withFullMessages(item, merged) : item));
      return {
        fullMessages: { ...s.fullMessages, [key]: merged },
        recordedRequests: patch(s.recordedRequests),
        proxiedRequests: patch(s.proxiedRequests),
      };
    }),

  clearUI: () => {
    logMessagesCache.clear();
    activeExpectationsCache.clear();
    recordedRequestsCache.clear();
    proxiedRequestsCache.clear();
    // A server reset returns the user to Get Started and discards all traffic;
    // persist that view AND clear persisted/in-memory search so a subsequent
    // reload doesn't restore the now-stale prior view or filters.
    persistView('get-started');
    persistSearch(EMPTY_SEARCH);
    set({
      logMessages: [],
      activeExpectations: [],
      recordedRequests: [],
      proxiedRequests: [],
      frameLimitReached: false,
      logMessagesLimitReached: false,
      fullMessages: {},
      selectedTrafficKey: null,
      pendingEditExpectation: null,
      pendingBreakpointPrefill: null,
      pendingVerificationDraft: null,
      pendingChaosDraft: null,
      error: null,
      errorSource: null,
      notification: null,
      view: 'get-started' as ViewMode,

      logSearch: '',
      expectationSearch: '',
      receivedSearch: '',
      proxiedSearch: '',
      trafficSearch: '',

      debugMismatchOpen: false,
      debugMismatchLoading: false,
      debugMismatchResult: null,
      debugMismatchError: null,

      generateStubOpen: false,
      generateStubLoading: false,
      generateStubSuggestions: [],
      generateStubConfidence: 0,
      generateStubError: null,
    });
  },

  setView: (view) => {
    const resolved = VIEW_MIGRATION[view as string] ?? view;
    persistView(resolved);
    set({ view: resolved, selectedTrafficKey: null });
  },
  setRequestFilter: (filter) => set({ requestFilter: filter }),
  setFilterEnabled: (enabled) => set({ filterEnabled: enabled }),
  setFilterExpanded: (expanded) => set({ filterExpanded: expanded }),
  toggleFilterExpanded: () => set((s) => ({ filterExpanded: !s.filterExpanded })),
  setConnectionStatus: (status) => set({ connectionStatus: status }),
  setServerConfiguration: (configuration) => set({ serverConfiguration: configuration, serverConfigurationUnavailable: false }),
  setServerConfigurationUnavailable: () => set({ serverConfiguration: null, serverConfigurationUnavailable: true }),
  setThemeMode: (mode) => {
    try { globalThis.localStorage?.setItem('mockserver-theme', mode); } catch { /* noop */ }
    set({ themeMode: mode });
  },
  toggleThemeMode: () =>
    set((s) => {
      const next = s.themeMode === 'dark' ? 'light' : 'dark';
      try { globalThis.localStorage?.setItem('mockserver-theme', next); } catch { /* noop */ }
      return { themeMode: next };
    }),
  setAutoScroll: (enabled) => set({ autoScroll: enabled }),
  toggleAutoScroll: () => set((s) => ({ autoScroll: !s.autoScroll })),
  // Each setter persists ONLY the five search fields (an explicit PersistedSearch
  // slice) — never `{ ...s }`, which would serialize the entire store (logs,
  // expectations, recorded/proxied requests) to localStorage on every keystroke.
  setLogSearch: (term) => set((s) => {
    persistSearch({ logSearch: term, expectationSearch: s.expectationSearch, receivedSearch: s.receivedSearch, proxiedSearch: s.proxiedSearch, trafficSearch: s.trafficSearch });
    return { logSearch: term };
  }),
  setLogShowForwarded: (show) => set({ logShowForwarded: show }),
  setExpectationSearch: (term) => set((s) => {
    persistSearch({ logSearch: s.logSearch, expectationSearch: term, receivedSearch: s.receivedSearch, proxiedSearch: s.proxiedSearch, trafficSearch: s.trafficSearch });
    return { expectationSearch: term };
  }),
  setReceivedSearch: (term) => set((s) => {
    persistSearch({ logSearch: s.logSearch, expectationSearch: s.expectationSearch, receivedSearch: term, proxiedSearch: s.proxiedSearch, trafficSearch: s.trafficSearch });
    return { receivedSearch: term };
  }),
  setProxiedSearch: (term) => set((s) => {
    persistSearch({ logSearch: s.logSearch, expectationSearch: s.expectationSearch, receivedSearch: s.receivedSearch, proxiedSearch: term, trafficSearch: s.trafficSearch });
    return { proxiedSearch: term };
  }),
  setTrafficSearch: (term) => set((s) => {
    persistSearch({ logSearch: s.logSearch, expectationSearch: s.expectationSearch, receivedSearch: s.receivedSearch, proxiedSearch: s.proxiedSearch, trafficSearch: term });
    return { trafficSearch: term };
  }),
  setSelectedTrafficKey: (key) => set({ selectedTrafficKey: key }),
  editExpectation: (expectation) => {
    // A shortened Active Expectations value would be saved back shortened; callers load the whole one first.
    if (isShortenedExpectation(expectation)) {
      set({ notification: { message: 'This expectation was shortened in the live update and could not be opened for editing; try again', severity: 'error' } });
      return;
    }
    persistView('composer');
    set({ pendingEditExpectation: expectation, view: 'composer' as ViewMode, selectedTrafficKey: null });
  },
  clearPendingEditExpectation: () => set({ pendingEditExpectation: null }),
  setBreakpointPrefill: (prefill) => {
    persistView('breakpoints');
    set({ pendingBreakpointPrefill: prefill, view: 'breakpoints' as ViewMode, selectedTrafficKey: null });
  },
  clearPendingBreakpointPrefill: () => set({ pendingBreakpointPrefill: null }),
  setVerificationDraft: (prefill) => {
    persistView('verification');
    set({ pendingVerificationDraft: prefill, view: 'verification' as ViewMode, selectedTrafficKey: null });
  },
  clearPendingVerificationDraft: () => set({ pendingVerificationDraft: null }),
  setChaosDraft: (prefill) => {
    persistView('chaos');
    set({ pendingChaosDraft: prefill, view: 'chaos' as ViewMode, selectedTrafficKey: null });
  },
  clearPendingChaosDraft: () => set({ pendingChaosDraft: null }),
  // A locally-set error (failed clear, parse failure, connection-lost notice) is
  // tagged 'client' so a subsequent data push cannot silently wipe it — it stays
  // until the user dismisses it or a later setError/push supersedes it.
  setError: (error) => set({ error, errorSource: error != null ? 'client' : null }),
  setNotification: (notification) => set({ notification }),
  openDebugMismatch: (result) =>
    set({ debugMismatchOpen: true, debugMismatchResult: result, debugMismatchLoading: false, debugMismatchError: null }),
  closeDebugMismatch: () =>
    set({ debugMismatchOpen: false, debugMismatchResult: null, debugMismatchLoading: false, debugMismatchError: null }),
  setDebugMismatchLoading: (loading) => set({ debugMismatchLoading: loading }),
  setDebugMismatchError: (error) =>
    set({ debugMismatchError: error, debugMismatchLoading: false }),

  openGenerateStub: (suggestions, confidence) =>
    set({ generateStubOpen: true, generateStubSuggestions: suggestions, generateStubConfidence: confidence, generateStubLoading: false, generateStubError: null }),
  closeGenerateStub: () =>
    set({ generateStubOpen: false, generateStubSuggestions: [], generateStubConfidence: 0, generateStubLoading: false, generateStubError: null }),
  setGenerateStubLoading: (loading) => set({ generateStubLoading: loading }),
  setGenerateStubError: (error) =>
    set({ generateStubError: error, generateStubLoading: false }),
}));

/*
 * Write each live change of view, searches or target through to the workspaces key once it is
 * in use, under this tab's active id, so the legacy keys and the blob always describe the same
 * workspace even when another browser tab wrote last.
 */
useDashboardStore.subscribe((state, prev) => {
  if (!workspacesPersisted || state.activeWorkspaceId !== prev.activeWorkspaceId) return;
  if (
    state.view === prev.view
    && state.logSearch === prev.logSearch
    && state.expectationSearch === prev.expectationSearch
    && state.receivedSearch === prev.receivedSearch
    && state.proxiedSearch === prev.proxiedSearch
    && state.trafficSearch === prev.trafficSearch
    && state.connection === prev.connection
  ) return;
  persistWorkspaces(
    state.workspaces.map((w) => (w.id === state.activeWorkspaceId ? { ...w, ...workspaceStateOf(state) } : w)),
    state.activeWorkspaceId,
  );
});
