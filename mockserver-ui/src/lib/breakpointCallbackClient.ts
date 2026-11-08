/**
 * WebSocket callback client for interactive breakpoint resolution.
 *
 * Opens a connection to `/_mockserver_callback_websocket` (the same endpoint
 * used by language clients for `forwardObject`/`responseObject` callbacks) and
 * reads its server-assigned `clientId` from the first `WebSocketClientIdDTO`
 * message. The browser WebSocket API does not support custom request headers,
 * so the server generates the clientId server-side when the
 * `CLIENT_REGISTRATION_ID_HEADER` is absent (already implemented in
 * `CallbackWebSocketServerHandler.upgradeChannel`).
 *
 * Messages from the server use the `{type, value}` envelope where `value` is a
 * double-encoded JSON string (the same as language clients). Four server message
 * types are handled:
 *
 * 1. `org.mockserver.model.HttpRequest` (REQUEST phase breakpoint)
 * 2. `org.mockserver.model.HttpRequestAndHttpResponse` (RESPONSE phase breakpoint)
 * 3. `org.mockserver.serialization.model.PausedStreamFrameDTO` (FRAME phase breakpoint)
 * 4. `org.mockserver.serialization.model.BreakpointReleasedDTO` (the server resolved a
 *    paused item itself, e.g. after the breakpoint timeout, so it is no longer paused)
 *
 * The client dispatches each message to a registered handler by breakpoint id
 * (extracted from `X-MockServer-BreakpointId` header for request/response, or from
 * `breakpointId` field for frames). The handler returns a resolution which is
 * serialized back over the WS.
 */

import { connectionKey, type ConnectionParams } from './connectionTarget';

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

/** The `{type, value}` envelope used on the callback WebSocket. */
export interface WsEnvelope {
  type: string;
  value: string; // double-encoded JSON
}

/** Server-assigned clientId. */
export interface WebSocketClientIdDTO {
  clientId: string;
}

/** Paused request pushed for REQUEST-phase breakpoint. */
export interface PausedRequest {
  method?: string;
  path?: string;
  headers?: Record<string, string[]>;
  body?: unknown;
  [key: string]: unknown;
}

/** Paused request+response pushed for RESPONSE-phase breakpoint. */
export interface PausedRequestAndResponse {
  httpRequest?: PausedRequest;
  httpResponse?: {
    statusCode?: number;
    reasonPhrase?: string;
    headers?: Record<string, string[]>;
    body?: unknown;
    [key: string]: unknown;
  };
}

/** Paused stream frame pushed for RESPONSE_STREAM / INBOUND_STREAM breakpoint. */
export interface PausedStreamFrame {
  correlationId: string;
  streamId: string;
  sequenceNumber: number;
  direction: 'INBOUND' | 'OUTBOUND';
  phase: 'RESPONSE_STREAM' | 'INBOUND_STREAM';
  body: string; // Base64
  requestMethod?: string | null;
  requestPath?: string | null;
  breakpointId?: string | null;
  /** Epoch-millis when MockServer first received the originating request. */
  requestTimestamp?: number | null;
}

/** Resolution for a stream frame. */
export interface StreamFrameDecision {
  correlationId: string;
  action: 'CONTINUE' | 'MODIFY' | 'DROP' | 'INJECT' | 'CLOSE';
  body?: string | null; // Base64
}

export type BreakpointPhase = 'REQUEST' | 'RESPONSE' | 'RESPONSE_STREAM' | 'INBOUND_STREAM';

/** Server notice that it resolved a paused item itself, so the item is no longer paused. */
export interface BreakpointReleasedDTO {
  correlationId: string;
  /** `TIMEOUT` (continued after the breakpoint timeout) or `STREAM_ENDED` (frame dropped). */
  reason: string;
  message?: string | null;
}

/**
 * Discriminated union for items pushed to the UI from the callback WS.
 * The `phase` field lets consumers determine what kind of item they're resolving.
 */
export type PausedItem =
  | { phase: 'REQUEST'; breakpointId: string | null; correlationId: string; requestTimestamp: number | null; request: PausedRequest }
  | { phase: 'RESPONSE'; breakpointId: string | null; correlationId: string; requestTimestamp: number | null; request: PausedRequest; response: PausedRequestAndResponse['httpResponse'] }
  | { phase: 'RESPONSE_STREAM' | 'INBOUND_STREAM'; breakpointId: string | null; frame: PausedStreamFrame };

/** Listener for paused items pushed over the callback WS. */
export type PausedItemListener = (item: PausedItem) => void;

/**
 * A paused item as accumulated in the module-level store: the wire item plus a
 * stable monotonic `key` (for React list identity and tiebreak sorting) and the
 * client-side `receivedAt` arrival time (fallback sort key when the server does
 * not send a request timestamp).
 */
export type StoredPausedItem = PausedItem & { key: number; receivedAt: number };

/** Listener for changes to the accumulated paused-item store. */
export type PausedItemsListener = (items: StoredPausedItem[]) => void;

/**
 * What the dashboard learns from a {@link BreakpointReleasedDTO}: either a paused item it
 * still listed has gone (`item` set), or a decision the user already sent arrived after the
 * server had resolved the item itself and so was ignored (`actionIgnored`).
 */
export interface BreakpointReleaseNotice {
  correlationId: string;
  reason: string;
  message: string;
  item: StoredPausedItem | null;
  actionIgnored: boolean;
}

/** Listener for release notices. */
export type ReleaseNoticeListener = (notice: BreakpointReleaseNotice) => void;

/** The correlation id a paused item is resolved by. */
export function correlationIdOf(item: PausedItem): string {
  return item.phase === 'REQUEST' || item.phase === 'RESPONSE' ? item.correlationId : item.frame.correlationId;
}

/** Connection state. */
export type CallbackClientState = 'disconnected' | 'connecting' | 'connected';

/** State-change listener. */
export type StateListener = (state: CallbackClientState) => void;

// ---------------------------------------------------------------------------
// Type constants (Java class names used in the envelope `type` field)
// ---------------------------------------------------------------------------

const TYPE_HTTP_REQUEST = 'org.mockserver.model.HttpRequest';
const TYPE_HTTP_RESPONSE = 'org.mockserver.model.HttpResponse';
const TYPE_REQUEST_AND_RESPONSE = 'org.mockserver.model.HttpRequestAndHttpResponse';
const TYPE_CLIENT_ID = 'org.mockserver.serialization.model.WebSocketClientIdDTO';
const TYPE_PAUSED_FRAME = 'org.mockserver.serialization.model.PausedStreamFrameDTO';
const TYPE_FRAME_DECISION = 'org.mockserver.serialization.model.StreamFrameDecisionDTO';
const TYPE_BREAKPOINT_RELEASED = 'org.mockserver.serialization.model.BreakpointReleasedDTO';

const BREAKPOINT_ID_HEADER = 'X-MockServer-BreakpointId';
const CORRELATION_ID_HEADER = 'WebSocketCorrelationId';
const REQUEST_TIMESTAMP_HEADER = 'X-MockServer-RequestTimestamp';

// ---------------------------------------------------------------------------
// Helper
// ---------------------------------------------------------------------------

function extractHeader(headers: Record<string, string[]> | undefined, name: string): string | null {
  if (!headers) return null;
  // Headers may be stored case-insensitively
  for (const key of Object.keys(headers)) {
    if (key.toLowerCase() === name.toLowerCase()) {
      const vals = headers[key];
      return Array.isArray(vals) && vals.length > 0 ? vals[0]! : null;
    }
  }
  return null;
}

/**
 * Return a copy of the given headers with the correlation id set, WITHOUT
 * mutating the original object — the header object is still referenced by the
 * held paused item in React state, so mutating it in place would corrupt that
 * state. Handles both the object form (`{name: [values]}`) and the list form
 * (`[{name, values}]`) the wire format can use.
 */
function headersWithCorrelationId(
  headers: unknown,
  correlationId: string,
): Record<string, string[]> | Array<{ name: string; values: string[] }> {
  if (Array.isArray(headers)) {
    const withoutExisting = (headers as Array<{ name?: unknown; values?: unknown }>).filter(
      (h) => !(h && typeof h.name === 'string' && h.name.toLowerCase() === CORRELATION_ID_HEADER.toLowerCase()),
    );
    return [
      ...(withoutExisting as Array<{ name: string; values: string[] }>),
      { name: CORRELATION_ID_HEADER, values: [correlationId] },
    ];
  }
  return { ...((headers as Record<string, string[]>) ?? {}), [CORRELATION_ID_HEADER]: [correlationId] };
}

/**
 * UTF-8-safe Base64 encode. `btoa` operates on Latin-1 code units and throws
 * `InvalidCharacterError` for any char > U+00FF (e.g. emoji, typographic
 * quotes), so encode to UTF-8 bytes first. Used for stream-frame bodies, which
 * are arbitrary UTF-8 text (LLM streams, etc.).
 */
export function utf8ToBase64(input: string): string {
  const bytes = new TextEncoder().encode(input);
  let binary = '';
  for (let i = 0; i < bytes.length; i++) {
    binary += String.fromCharCode(bytes[i]!);
  }
  return btoa(binary);
}

/**
 * UTF-8-safe Base64 decode. `atob` yields Latin-1 code units, which mojibakes
 * multi-byte UTF-8 sequences, so decode the raw bytes back through a UTF-8
 * decoder. Throws if the input is not valid Base64.
 *
 * Text-only: the default `TextDecoder` replaces invalid UTF-8 byte sequences
 * with U+FFFD, so a genuinely binary frame will NOT round-trip byte-for-byte
 * through decode -> (unchanged) encode. This is intentional — the frame editor
 * targets text streams (SSE / LLM output); binary frames are out of scope.
 */
export function base64ToUtf8(input: string): string {
  const binary = atob(input);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) {
    bytes[i] = binary.charCodeAt(i);
  }
  return new TextDecoder().decode(bytes);
}

// ---------------------------------------------------------------------------
// Client
// ---------------------------------------------------------------------------

const RECONNECT_DELAY_MS = 3000;

// Upper bound on accumulated paused items. A breakpoint matcher with a broad
// pattern (e.g. path `.*`) pauses every exchange, and each held item retains a
// full request/response/frame. Without a cap a busy server would grow the store
// until the tab runs out of memory, so we drop the oldest items beyond this
// bound (each dropped exchange remains paused server-side and auto-resolves via
// the server's breakpoint timeout).
const MAX_PAUSED_ITEMS = 500;

// How many of the user's own decisions to remember, so a release notice that crosses one
// on the wire can report the decision as not applied.
const MAX_RECENTLY_RESOLVED = 200;

export class BreakpointCallbackClient {
  private ws: WebSocket | null = null;
  private _clientId: string | null = null;
  private _state: CallbackClientState = 'disconnected';
  private reconnectAttempts = 0;
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  private connectionParams: ConnectionParams | null = null;

  private pausedItemListener: PausedItemListener | null = null;
  private stateListener: StateListener | null = null;

  // Module-level (client-owned) store of paused items. Accumulates regardless of
  // whether the UI panel is mounted, so items pushed while the panel is unmounted
  // (e.g. the user navigated to another tab) are retained and resolvable when the
  // panel remounts and re-subscribes — the callback WS outlives the
  // panel, and the panel's React state does not.
  private pausedItems: StoredPausedItem[] = [];
  private pausedItemsListeners = new Set<PausedItemsListener>();
  private nextItemKey = 0;
  private releaseNoticeListeners = new Set<ReleaseNoticeListener>();
  private recentlyResolved = new Set<string>();

  /** The server-assigned clientId; null until connected. */
  get clientId(): string | null { return this._clientId; }

  /** Current connection state. */
  get state(): CallbackClientState { return this._state; }

  /** Register a listener that is called for every paused item. */
  onPausedItem(listener: PausedItemListener): void {
    this.pausedItemListener = listener;
  }

  /** Register a listener for connection state changes. */
  onStateChange(listener: StateListener): void {
    this.stateListener = listener;
  }

  /**
   * Subscribe to the accumulated paused-item store. The listener is invoked
   * immediately with the current items (so a freshly-mounted panel reflects
   * anything buffered while it was unmounted) and again on every change. Returns
   * an unsubscribe function.
   */
  subscribePausedItems(listener: PausedItemsListener): () => void {
    this.pausedItemsListeners.add(listener);
    listener(this.pausedItems);
    return () => {
      this.pausedItemsListeners.delete(listener);
    };
  }

  /** Current snapshot of accumulated paused items. */
  getPausedItems(): StoredPausedItem[] {
    return this.pausedItems;
  }

  /**
   * Subscribe to release notices: the server resolved a paused item itself (for example
   * after the breakpoint timeout). Returns an unsubscribe function.
   */
  subscribeReleaseNotices(listener: ReleaseNoticeListener): () => void {
    this.releaseNoticeListeners.add(listener);
    return () => {
      this.releaseNoticeListeners.delete(listener);
    };
  }

  /** Whether the item with this correlation id is still held (listed) by the dashboard. */
  isPaused(correlationId: string): boolean {
    return this.pausedItems.some((item) => correlationIdOf(item) === correlationId);
  }

  /** Remove a paused item from the store by its stable key (e.g. after resolving it). */
  removePausedItem(key: number): void {
    const next = this.pausedItems.filter((item) => item.key !== key);
    if (next.length !== this.pausedItems.length) {
      this.pausedItems = next;
      this.notifyPausedItems();
    }
  }

  /** Open the callback WS connection. Reconnects automatically on close. */
  connect(params: ConnectionParams): void {
    // Idempotent across UI re-mounts: if we are already connected (or connecting)
    // to the same server, keep the existing connection — and, crucially, its
    // server-assigned clientId and the breakpoint matchers registered under it —
    // alive. Tearing the socket down and reconnecting would assign a new clientId
    // and orphan those matchers (e.g. when navigating away from and back to the
    // Breakpoints tab).
    const sameServer =
      this.connectionParams !== null &&
      connectionKey(this.connectionParams) === connectionKey(params);
    if (sameServer && (this._state === 'connected' || this._state === 'connecting')) {
      return;
    }
    if (this.ws) {
      this.disconnect();
    }
    this.connectionParams = params;
    this.reconnectAttempts = 0;
    this._connect();
  }

  /** Close the WS connection and stop reconnecting. */
  disconnect(): void {
    if (this.reconnectTimer !== null) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
    if (this.ws) {
      // Detach handlers before closing to avoid triggering reconnect
      const ws = this.ws;
      this.ws = null;
      ws.onmessage = null;
      ws.onerror = null;
      ws.onclose = null;
      if (ws.readyState === WebSocket.CONNECTING) {
        ws.onopen = () => ws.close();
      } else {
        ws.onopen = null;
        ws.close();
      }
    }
    this._clientId = null;
    this.setState('disconnected');
  }

  /**
   * Send a REQUEST-phase resolution over the WS.
   *
   * @param correlationId The WebSocketCorrelationId from the paused request.
   * @param result Either an HttpRequest (continue/modify) or an HttpResponse (abort).
   */
  resolveRequest(correlationId: string, result: Record<string, unknown>): void {
    const isResponse = result.statusCode !== undefined;
    const type = isResponse ? TYPE_HTTP_RESPONSE : TYPE_HTTP_REQUEST;
    // Copy (never mutate) the held item's headers when echoing the correlation id.
    const payload = { ...result, headers: headersWithCorrelationId(result.headers, correlationId) };
    this.rememberResolved(correlationId);
    this.send({ type, value: JSON.stringify(payload) });
  }

  /**
   * Send a RESPONSE-phase resolution over the WS.
   *
   * @param correlationId The WebSocketCorrelationId from the paused request+response.
   * @param httpResponse The response to write to the downstream client.
   */
  resolveResponse(correlationId: string, httpResponse: Record<string, unknown>): void {
    // Copy (never mutate) the held item's headers when echoing the correlation id.
    const payload = { ...httpResponse, headers: headersWithCorrelationId(httpResponse.headers, correlationId) };
    this.rememberResolved(correlationId);
    this.send({ type: TYPE_HTTP_RESPONSE, value: JSON.stringify(payload) });
  }

  /**
   * Send a FRAME-phase resolution over the WS.
   */
  resolveFrame(decision: StreamFrameDecision): void {
    this.rememberResolved(decision.correlationId);
    this.send({ type: TYPE_FRAME_DECISION, value: JSON.stringify(decision) });
  }

  // -------------------------------------------------------------------------
  // Private
  // -------------------------------------------------------------------------

  private _connect(): void {
    if (!this.connectionParams) return;
    const params = this.connectionParams;
    const protocol = params.secure ? 'wss' : 'ws';
    // The capability asks the server for BreakpointReleasedDTO notices, which it sends only to clients that ask.
    const url = `${protocol}://${params.host}:${params.port}${params.basePath ?? ''}/_mockserver_callback_websocket?capabilities=breakpointReleased`;

    this.setState('connecting');
    const ws = new WebSocket(url);
    this.ws = ws;

    ws.onopen = () => {
      this.reconnectAttempts = 0;
      // We wait for the WebSocketClientIdDTO before declaring 'connected'
    };

    ws.onmessage = (event: MessageEvent) => {
      try {
        const envelope = JSON.parse(event.data as string) as WsEnvelope;
        this.handleMessage(envelope);
      } catch {
        // Ignore malformed messages
      }
    };

    ws.onclose = () => {
      this.ws = null;
      this._clientId = null;
      this.setState('disconnected');
      this.scheduleReconnect();
    };

    ws.onerror = () => {
      // onclose will fire after onerror
    };
  }

  private handleMessage(envelope: WsEnvelope): void {
    const { type, value } = envelope;
    if (!type || !value) return;

    if (type === TYPE_CLIENT_ID) {
      const dto = JSON.parse(value) as WebSocketClientIdDTO;
      this._clientId = dto.clientId;
      this.setState('connected');
      return;
    }

    if (type === TYPE_HTTP_REQUEST) {
      const request = JSON.parse(value) as PausedRequest;
      const breakpointId = extractHeader(request.headers, BREAKPOINT_ID_HEADER);
      const correlationId = extractHeader(request.headers, CORRELATION_ID_HEADER);
      const tsRaw = extractHeader(request.headers, REQUEST_TIMESTAMP_HEADER);
      const requestTimestamp = tsRaw != null ? Number(tsRaw) : null;
      if (correlationId) {
        this.dispatchPausedItem({
          phase: 'REQUEST',
          breakpointId,
          correlationId,
          requestTimestamp: requestTimestamp != null && !isNaN(requestTimestamp) ? requestTimestamp : null,
          request,
        });
      }
      return;
    }

    if (type === TYPE_REQUEST_AND_RESPONSE) {
      const pair = JSON.parse(value) as PausedRequestAndResponse;
      const breakpointId = extractHeader(pair.httpRequest?.headers, BREAKPOINT_ID_HEADER);
      const correlationId = extractHeader(pair.httpRequest?.headers, CORRELATION_ID_HEADER);
      const tsRaw = extractHeader(pair.httpRequest?.headers, REQUEST_TIMESTAMP_HEADER);
      const requestTimestamp = tsRaw != null ? Number(tsRaw) : null;
      if (correlationId) {
        this.dispatchPausedItem({
          phase: 'RESPONSE',
          breakpointId,
          correlationId,
          requestTimestamp: requestTimestamp != null && !isNaN(requestTimestamp) ? requestTimestamp : null,
          request: pair.httpRequest ?? {},
          response: pair.httpResponse,
        });
      }
      return;
    }

    if (type === TYPE_PAUSED_FRAME) {
      const frame = JSON.parse(value) as PausedStreamFrame;
      this.dispatchPausedItem({
        phase: frame.phase === 'INBOUND_STREAM' ? 'INBOUND_STREAM' : 'RESPONSE_STREAM',
        breakpointId: frame.breakpointId ?? null,
        frame,
      });
      return;
    }

    if (type === TYPE_BREAKPOINT_RELEASED) {
      this.handleReleased(JSON.parse(value) as BreakpointReleasedDTO);
      return;
    }

    // Unknown type — ignore
  }

  /**
   * The server resolved a paused item itself. Drop it from the store so it can no longer be
   * acted on, and tell listeners — including when the user's own decision for it crossed
   * the notice on the wire and was therefore ignored by the server.
   */
  private handleReleased(dto: BreakpointReleasedDTO): void {
    if (!dto.correlationId) return;
    const message = dto.message || 'MockServer resolved this paused item itself.';
    const item = this.pausedItems.find((candidate) => correlationIdOf(candidate) === dto.correlationId) ?? null;
    if (item) {
      this.pausedItems = this.pausedItems.filter((candidate) => candidate !== item);
      this.notifyPausedItems();
    } else if (!this.recentlyResolved.has(dto.correlationId)) {
      return;
    }
    const notice: BreakpointReleaseNotice = {
      correlationId: dto.correlationId,
      reason: dto.reason,
      message,
      item,
      actionIgnored: item === null,
    };
    for (const listener of this.releaseNoticeListeners) {
      listener(notice);
    }
  }

  private rememberResolved(correlationId: string): void {
    this.recentlyResolved.add(correlationId);
    if (this.recentlyResolved.size > MAX_RECENTLY_RESOLVED) {
      const oldest = this.recentlyResolved.values().next().value;
      if (oldest !== undefined) this.recentlyResolved.delete(oldest);
    }
  }

  /**
   * Fan a newly-arrived paused item out to the raw per-item listener (kept for
   * back-compat) and accumulate it in the durable store (keyed + capped) so it
   * survives the panel being unmounted.
   */
  private dispatchPausedItem(item: PausedItem): void {
    this.pausedItemListener?.(item);
    // receivedAt is captured once, at arrival, and never changes — it gives the
    // lists a stable, monotonic fallback sort key.
    const keyed: StoredPausedItem = { ...item, key: this.nextItemKey++, receivedAt: Date.now() };
    const next = [...this.pausedItems, keyed];
    this.pausedItems = next.length > MAX_PAUSED_ITEMS ? next.slice(next.length - MAX_PAUSED_ITEMS) : next;
    this.notifyPausedItems();
  }

  private notifyPausedItems(): void {
    for (const listener of this.pausedItemsListeners) {
      listener(this.pausedItems);
    }
  }

  private clearPausedItems(): void {
    if (this.pausedItems.length > 0) {
      this.pausedItems = [];
      this.notifyPausedItems();
    }
  }

  private send(envelope: WsEnvelope): void {
    if (this.ws && this.ws.readyState === WebSocket.OPEN) {
      this.ws.send(JSON.stringify(envelope));
    }
  }

  private scheduleReconnect(): void {
    // Retry indefinitely with a capped backoff rather than permanently giving up
    // after a fixed number of attempts. The callback WS outlives the panel, so a
    // server outage longer than a few attempts (a deploy/restart) must still
    // reconnect automatically once it returns — otherwise breakpoints stay dead
    // until the user re-enters the Breakpoints view. onopen resets the counter.
    this.reconnectAttempts++;
    const delay = RECONNECT_DELAY_MS * Math.min(this.reconnectAttempts, 5); // capped at 15s
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null;
      this._connect();
    }, delay);
  }

  private setState(state: CallbackClientState): void {
    if (this._state !== state) {
      this._state = state;
      // Held items reference the previous clientId's correlationIds. On
      // disconnect the server issues a fresh clientId on reconnect, so these can
      // never be resolved again — drop them rather than leak stale items.
      if (state === 'disconnected') {
        this.clearPausedItems();
      }
      this.stateListener?.(state);
    }
  }
}

// ---------------------------------------------------------------------------
// One client per MockServer target
// ---------------------------------------------------------------------------

// Matchers are owned on the server by the clientId of the socket that registered them, and
// the server removes them (continuing anything they paused) when that socket closes. So each
// target keeps its own client: switching workspace leaves another server's breakpoints armed,
// and only releasing the last workspace on a target closes its session.
const _instances = new Map<string, BreakpointCallbackClient>();
const DEFAULT_KEY = '';
const registryListeners = new Set<() => void>();

function notifyRegistry(): void {
  for (const listener of registryListeners) listener();
}

/** The callback client for `params`' server, created on first use. */
export function getBreakpointCallbackClient(params?: ConnectionParams): BreakpointCallbackClient {
  const key = params ? connectionKey(params) : DEFAULT_KEY;
  let client = _instances.get(key);
  if (!client) {
    client = new BreakpointCallbackClient();
    _instances.set(key, client);
    notifyRegistry();
  }
  return client;
}

/** The callback client for the server with this {@link connectionKey}, if one exists; never creates one. */
export function findBreakpointCallbackClient(key: string): BreakpointCallbackClient | undefined {
  return _instances.get(key);
}

/** Be told whenever a client is created or released. Returns an unsubscribe function. */
export function subscribeBreakpointCallbackClients(listener: () => void): () => void {
  registryListeners.add(listener);
  return () => {
    registryListeners.delete(listener);
  };
}

/** Disconnect and forget every client whose target is not in `keys`. */
export function releaseBreakpointCallbackClientsExcept(keys: ReadonlySet<string>): void {
  let released = false;
  for (const [key, client] of _instances) {
    if (key !== DEFAULT_KEY && !keys.has(key)) {
      client.disconnect();
      _instances.delete(key);
      released = true;
    }
  }
  if (released) notifyRegistry();
}

/** Reset every client (for tests). */
export function _resetBreakpointCallbackClient(): void {
  for (const client of _instances.values()) client.disconnect();
  _instances.clear();
  notifyRegistry();
}
