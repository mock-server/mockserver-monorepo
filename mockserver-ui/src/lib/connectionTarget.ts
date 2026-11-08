/**
 * The MockServer instance the dashboard talks to: derived from the page URL, or
 * overridden per workspace. Kept free of store and React imports so the store,
 * the hooks and the callback-WebSocket client can all share it.
 */

export interface ConnectionParams {
  host: string;
  port: string;
  secure: boolean;
  /**
   * Context/base path the dashboard is served under (e.g. behind a reverse proxy), without a
   * trailing slash. Prefixed to every REST and WebSocket URL so actions reach the server even
   * when it is not at the host root. Empty string when served at the root. Optional so existing
   * { host, port, secure } literals (tests, component props) remain assignable.
   */
  basePath?: string;
}

/** A workspace's own target. Every field is explicit, so it never mixes with the URL's. */
export interface WorkspaceConnection {
  host: string;
  port: string;
  secure: boolean;
  basePath: string;
}

/** A `?host=`, `?port=` or `?basePath=` value the dashboard URL supplied but {@link resolveUrlConnection} refused. */
export type UrlTargetField = 'host' | 'port' | 'basePath';

export interface UrlConnection {
  params: ConnectionParams;
  /** Fields whose URL value was invalid and was replaced by the page origin's. Empty when none were. */
  rejected: UrlTargetField[];
  /** The server that served the page: what the dashboard targets when the link names nothing. */
  page: ConnectionParams;
  /** Whether a valid `?host=` names a different host from the page's, so the link sends the user's data elsewhere. */
  foreignHost: boolean;
}

/**
 * The target named by the dashboard URL (`?host=`, `?port=`, `?secure=`, `?basePath=`), else the
 * page origin. A link is untrusted input, so each value passes the checks a typed target does and
 * an invalid one falls back, field by field, to the page origin's value.
 */
export function resolveUrlConnection(): UrlConnection {
  const location = window.location;
  const params = new URLSearchParams(location.search ?? '');
  const rejected: UrlTargetField[] = [];

  const pageHost = (location.hostname || '127.0.0.1').toLowerCase();
  const hostParam = params.get('host')?.trim();
  let host = pageHost;
  if (hostParam) {
    if (hostProblem(hostParam) === null) host = hostParam.toLowerCase();
    else rejected.push('host');
  }

  const pagePort = location.port || defaultPort(location.protocol);
  const portParam = params.get('port')?.trim();
  let port = pagePort;
  if (portParam) {
    if (portProblem(portParam) === null) port = String(Number(portParam));
    else rejected.push('port');
  }

  const pageSecure = location.protocol === 'https:';
  const secureParam = params.get('secure');
  const secure = secureParam !== null ? secureParam === 'true' : pageSecure;

  // The page's own base path is the segment before a trailing /mockserver/dashboard; without that
  // suffix the dashboard is assumed to be at the root.
  const pathMatch = /^(.*)\/mockserver\/dashboard\/?$/.exec(location.pathname ?? '');
  const pageBasePath = normaliseBasePath(pathMatch ? (pathMatch[1] ?? '') : '');
  const basePathParam = params.get('basePath');
  const validPageBasePath = basePathProblem(pageBasePath) === null ? pageBasePath : '';
  let basePath = validPageBasePath;
  if (basePathParam !== null) {
    const candidate = normaliseBasePath(basePathParam);
    if (basePathProblem(candidate) === null) basePath = candidate;
    else rejected.push('basePath');
  } else if (validPageBasePath !== pageBasePath) {
    rejected.push('basePath');
  }

  return {
    params: { host, port, secure, basePath },
    rejected,
    page: { host: pageHost, port: pagePort, secure: pageSecure, basePath: validPageBasePath },
    foreignHost: host !== pageHost,
  };
}

/** The target named by the dashboard URL, with any invalid value replaced; see {@link resolveUrlConnection}. */
export function urlConnectionParams(): ConnectionParams {
  return resolveUrlConnection().params;
}

const URL_TARGET_PARAMS = ['host', 'port', 'secure', 'basePath'];

/** Whether the dashboard URL names a server itself, rather than leaving the dashboard on the page origin. */
export function urlNamesTarget(): boolean {
  const params = new URLSearchParams(window.location.search ?? '');
  return URL_TARGET_PARAMS.some((name) => params.has(name));
}

/** The dashboard's own address without the link's target parameters, so it targets the server that served it. */
export function pageOriginDashboardUrl(): string {
  const url = new URL(window.location.href);
  for (const name of URL_TARGET_PARAMS) url.searchParams.delete(name);
  return url.toString();
}

/** The connection a workspace uses: its own target when it has one, else the URL's. */
export function effectiveConnection(
  override: WorkspaceConnection | null | undefined,
  base: ConnectionParams,
): ConnectionParams {
  return override ? { ...override } : base;
}

/** A stable identity for a target: two params with the same key reach the same server. */
export function connectionKey(params: ConnectionParams): string {
  return `${params.secure ? 'https' : 'http'}://${params.host.toLowerCase()}:${params.port}${params.basePath ?? ''}`;
}

/** `host:port` plus any base path, as shown to the user. */
export function describeTarget(params: ConnectionParams): string {
  return `${params.host}:${params.port}${params.basePath ?? ''}`;
}

function defaultPort(protocol: string): string {
  return protocol === 'https:' ? '443' : '80';
}

/** The origin a target's control-plane `fetch` calls go to. */
export function targetOrigin(params: ConnectionParams): string {
  const protocol = params.secure ? 'https:' : 'http:';
  const port = params.port === defaultPort(protocol) ? '' : `:${params.port}`;
  return `${protocol}//${params.host.toLowerCase()}${port}`;
}

/** Whether calls to this target leave the page's origin, so the browser applies CORS to them. */
export function isCrossOrigin(params: ConnectionParams): boolean {
  if (typeof window === 'undefined' || !window.location) return false;
  const { protocol, hostname, port } = window.location;
  if (!protocol || !hostname) return false;
  const pagePort = port || defaultPort(protocol);
  const pageOrigin = `${protocol}//${hostname.toLowerCase()}${pagePort === defaultPort(protocol) ? '' : `:${pagePort}`}`;
  return targetOrigin(params) !== pageOrigin;
}

/** An `https:` dashboard cannot call an `http:` target: the browser blocks it as mixed content. */
export function isMixedContent(params: ConnectionParams): boolean {
  return typeof window !== 'undefined' && window.location?.protocol === 'https:' && !params.secure;
}

// `_` is allowed: browsers resolve it, and Docker/Compose service names often contain one.
const HOST_PATTERN = /^(\[[0-9a-fA-F:.]+\]|[A-Za-z0-9_]([A-Za-z0-9_.-]*[A-Za-z0-9_])?)$/;

export interface ConnectionDraft {
  host: string;
  port: string;
  secure: boolean;
  basePath: string;
}

export type ConnectionValidation =
  | { ok: true; value: WorkspaceConnection }
  | { ok: false; field: 'host' | 'port' | 'basePath'; message: string };

const HOST_MESSAGE_URL = 'Enter only the host name, without http:// or a path; set the base path below.';
const HOST_MESSAGE_INVALID = 'Not a valid host name; an IPv6 address goes in brackets, e.g. [::1].';
const PORT_MESSAGE = 'A port is a whole number from 1 to 65535.';
const BASE_PATH_MESSAGE = 'A base path starts with / and has no spaces, ?, #, %, \\, // or . and .. segments, e.g. /mock.';

// RFC 3986 path characters except `%`: no `?` or `#` (either would cut off the endpoint path the
// dashboard appends), no encoded character, no `\` (a browser reads it as `/`), no empty segment.
const BASE_PATH_PATTERN = /^(\/[A-Za-z0-9._~!$&'()*+,;=:@-]+)+$/;

function hostProblem(host: string): string | null {
  if (host === '') return 'Enter the host MockServer is listening on.';
  if (/^[a-z][a-z0-9+.-]*:\/\//i.test(host) || host.includes('/')) return HOST_MESSAGE_URL;
  if (host.length > 253 || !HOST_PATTERN.test(host)) return HOST_MESSAGE_INVALID;
  return !host.startsWith('[') || isUrlHost(host) ? null : HOST_MESSAGE_INVALID;
}

/** Whether a bracketed host is an IPv6 address a URL accepts; `[1.2.3.4]` and `[:]` match the pattern but are not. */
function isUrlHost(host: string): boolean {
  try {
    return new URL(`http://${host}/`).hostname !== '';
  } catch {
    return false;
  }
}

function portProblem(port: string): string | null {
  return /^\d{1,5}$/.test(port) && Number(port) >= 1 && Number(port) <= 65535 ? null : PORT_MESSAGE;
}

function normaliseBasePath(basePath: string): string {
  return basePath.trim().replace(/\/+$/, '');
}

function basePathProblem(basePath: string): string | null {
  if (basePath === '') return null;
  const dotSegment = basePath.split('/').some((segment) => segment === '.' || segment === '..');
  return BASE_PATH_PATTERN.test(basePath) && !dotSegment ? null : BASE_PATH_MESSAGE;
}

/**
 * Validate a target typed by the user. A host is a name, an IPv4 address or a bracketed IPv6
 * address: never a URL, so a pasted scheme or path is refused rather than glued into every URL.
 */
export function validateConnection(draft: ConnectionDraft): ConnectionValidation {
  const host = draft.host.trim();
  const hostMessage = hostProblem(host);
  if (hostMessage !== null) return { ok: false, field: 'host', message: hostMessage };
  const port = draft.port.trim();
  const portMessage = portProblem(port);
  if (portMessage !== null) return { ok: false, field: 'port', message: portMessage };
  const basePath = normaliseBasePath(draft.basePath);
  const basePathMessage = basePathProblem(basePath);
  if (basePathMessage !== null) return { ok: false, field: 'basePath', message: basePathMessage };
  // Host names are case-insensitive; one spelling keeps one target from looking like two.
  return { ok: true, value: { host: host.toLowerCase(), port: String(Number(port)), secure: draft.secure, basePath } };
}

/** Coerce an untrusted stored value to a target, or null when it is not a valid one. */
export function coerceConnection(value: unknown): WorkspaceConnection | null {
  if (typeof value !== 'object' || value === null) return null;
  const v = value as Partial<WorkspaceConnection>;
  if (typeof v.host !== 'string' || typeof v.port !== 'string' || typeof v.secure !== 'boolean') return null;
  const result = validateConnection({
    host: v.host,
    port: v.port,
    secure: v.secure,
    basePath: typeof v.basePath === 'string' ? v.basePath : '',
  });
  return result.ok ? result.value : null;
}

/** Whether two optional targets are the same (both absent, or equal in every field). */
export function sameConnection(a: WorkspaceConnection | null | undefined, b: WorkspaceConnection | null | undefined): boolean {
  if (!a || !b) return !a && !b;
  return a.host === b.host && a.port === b.port && a.secure === b.secure && a.basePath === b.basePath;
}

/** The outcome of checking that a target answers the dashboard's control-plane calls. */
export type TargetProbe = { ok: true } | { ok: false; message: string };

const PROBE_TIMEOUT_MS = 5000;

/**
 * Call `PUT /mockserver/status` on the target, the same kind of request (cross-origin, with a
 * preflight) every control-plane action makes, and explain a failure in terms the user can act on.
 */
export async function probeTarget(params: ConnectionParams, fetchImpl: typeof fetch = fetch): Promise<TargetProbe> {
  const where = describeTarget(params);
  if (isMixedContent(params)) {
    return {
      ok: false,
      message: `This dashboard is served over https, so the browser blocks calls to an http target. Tick "Use https" if ${where} serves TLS.`,
    };
  }
  const url = `${params.secure ? 'https' : 'http'}://${params.host}:${params.port}${params.basePath ?? ''}/mockserver/status`;
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), PROBE_TIMEOUT_MS);
  try {
    const response = await fetchImpl(url, { method: 'PUT', signal: controller.signal });
    if (response.status === 401 || response.status === 403) {
      return {
        ok: false,
        message: `${where} requires control-plane authentication (HTTP ${response.status}), which the dashboard cannot supply from another origin.`,
      };
    }
    if (!response.ok) {
      return {
        ok: false,
        message: `${where} answered HTTP ${response.status} to PUT /mockserver/status, so it is not a MockServer at that base path.`,
      };
    }
    return { ok: true };
  } catch (e) {
    if (e instanceof Error && e.name === 'AbortError') {
      return { ok: false, message: `${where} did not answer within ${PROBE_TIMEOUT_MS / 1000}s.` };
    }
    // fetch reports a CORS refusal and an unreachable server identically.
    const cors = isCrossOrigin(params)
      ? ` Calls to it are cross-origin, so the browser also refuses them when it does not answer CORS for this dashboard's origin` +
        ` (by default MockServer does for a dashboard on localhost; otherwise list this dashboard's origin in its controlPlaneCorsAllowOrigin, and check no proxy in front of it drops the CORS headers).`
      : '';
    const tls = params.secure ? ' With https, the browser must also trust its certificate: open the target in a tab once to check.' : '';
    return { ok: false, message: `Could not reach MockServer at ${where}.${cors}${tls}` };
  } finally {
    clearTimeout(timer);
  }
}
