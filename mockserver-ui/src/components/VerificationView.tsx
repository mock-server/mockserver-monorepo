import { useState, useCallback, useMemo, useEffect, type ReactNode } from 'react';
import Box from '@mui/material/Box';
import Paper from '@mui/material/Paper';
import Typography from '@mui/material/Typography';
import TextField from '@mui/material/TextField';
import Select from '@mui/material/Select';
import MenuItem from '@mui/material/MenuItem';
import Button from '@mui/material/Button';
import IconButton from '@mui/material/IconButton';
import ButtonBase from '@mui/material/ButtonBase';
import Alert from '@mui/material/Alert';
import Collapse from '@mui/material/Collapse';
import ToggleButton from '@mui/material/ToggleButton';
import ToggleButtonGroup from '@mui/material/ToggleButtonGroup';
import AddIcon from '@mui/icons-material/Add';
import DeleteIcon from '@mui/icons-material/Delete';
import CheckCircleIcon from '@mui/icons-material/CheckCircle';
import ExpandMoreIcon from '@mui/icons-material/ExpandMore';
import ExpandLessIcon from '@mui/icons-material/ExpandLess';
import type { ConnectionParams } from '../hooks/useConnectionParams';
import { parseKeyValueLines, ignoredKeyValueLines } from '../lib/standardCodegen';
import { buildBaseUrl } from '../lib/mcpClient';
import {
  bodyMatcher,
  timesSpecProblem,
  verifyRequest,
  verifySequence,
  type VerificationTimesMode,
  type VerifyResult,
} from '../lib/verification';
import VerificationReview from './VerificationReview';
import OperatorSearchField from './OperatorSearchField';
import {
  parseRequestScope,
  isApplicableScope,
  withHostHeaderLine,
  statusRangeProblem,
  verificationStatusFor,
  VERIFICATION_SCOPE_FIELDS,
  type VerificationStatus,
} from '../lib/filterScope';
import HumanErrorAlert from './HumanErrorAlert';
import { useDashboardStore } from '../store';
import { humanizeError, type HumanError } from '../lib/errorMessage';
import { monospaceFontFamily } from '../theme';

const METHODS = ['', 'GET', 'POST', 'PUT', 'DELETE', 'PATCH', 'HEAD', 'OPTIONS'];
// the server counts mocked and forwarded responses (EXPECTATION_RESPONSE, FORWARDED_REQUEST), never its own no-match 404
const RESPONSE_MATCHER_CAPTION =
  'Match against the responses MockServer returned: mocked by an expectation or forwarded from upstream (not the 404 for an unmatched request).';

interface RequestForm {
  method: string;
  path: string;
  headers: string;
  queryString: string;
  body: string;
}

function emptyRequest(): RequestForm {
  return { method: '', path: '', headers: '', queryString: '', body: '' };
}

interface ResponseForm {
  statusCode: string;
  /** A class (`4XX`) or one comparison (`>= 400`); the server uses it in place of `statusCode`. */
  statusCodeRange: string;
  headers: string;
  body: string;
}

function emptyResponse(): ResponseForm {
  return { statusCode: '', statusCodeRange: '', headers: '', body: '' };
}

/** Build an httpRequest matcher object from a form row (omitting empty fields). */
function buildHttpRequest(form: RequestForm): Record<string, unknown> {
  const req: Record<string, unknown> = {};
  if (form.method.trim()) req['method'] = form.method.trim();
  if (form.path.trim()) req['path'] = form.path.trim();
  const headers = parseKeyValueLines(form.headers, ':');
  if (headers) req['headers'] = headers;
  const query = parseKeyValueLines(form.queryString, '=');
  if (query) req['queryStringParameters'] = query;
  const body = bodyMatcher(form.body);
  if (body) req['body'] = body;
  return req;
}

/** Build an httpResponse matcher object from a form row (omitting empty fields). */
function buildHttpResponse(form: ResponseForm): Record<string, unknown> {
  const resp: Record<string, unknown> = {};
  const code = form.statusCode.trim();
  if (code) {
    const parsed = Number(code);
    if (!Number.isNaN(parsed) && parsed > 0) {
      resp['statusCode'] = parsed;
    }
  }
  const range = form.statusCodeRange.trim();
  if (range) resp['statusCodeRange'] = range;
  const headers = parseKeyValueLines(form.headers, ':');
  if (headers) resp['headers'] = headers;
  const body = bodyMatcher(form.body);
  if (body) resp['body'] = body;
  return resp;
}

/**
 * Inline error for a "Name: value" / "key=value" lines field, or undefined. A line the
 * parser cannot split would otherwise be dropped from the matcher, loosening the check
 * into a false pass, so it blocks verifying instead.
 */
function linesError(text: string, separator: ':' | '='): string | undefined {
  const bad = ignoredKeyValueLines(text, separator);
  if (bad.length === 0) return undefined;
  const format = separator === ':' ? 'Name: value' : 'key=value';
  const more = bad.length > 1 ? ` (and ${bad.length - 1} more)` : '';
  return `"${bad[0]}" is not ${format}${more}`;
}

interface RequestErrors { headers?: string; queryString?: string }
interface ResponseErrors { headers?: string; statusCodeRange?: string }

function requestErrors(form: RequestForm): RequestErrors {
  return { headers: linesError(form.headers, ':'), queryString: linesError(form.queryString, '=') };
}

function responseErrors(form: ResponseForm): ResponseErrors {
  // The server ignores the status code when a range is set, so both together would hide one.
  const both = form.statusCode.trim() !== '' && form.statusCodeRange.trim() !== '';
  return {
    headers: linesError(form.headers, ':'),
    statusCodeRange: both ? 'Fill the status code or the status range, not both.' : statusRangeProblem(form.statusCodeRange) ?? undefined,
  };
}

const hasError = (errors: RequestErrors | ResponseErrors): boolean => Object.values(errors).some(Boolean);

/**
 * Filter-DSL "quick scope" for a request/response matcher pair. Uses the same
 * operator syntax as the Traffic and dashboard search boxes, narrowed via
 * `VERIFICATION_SCOPE_FIELDS` to the operators the matchers can genuinely honour —
 * anything else (`operation:`) is flagged in the input instead of silently doing
 * nothing. Applying FILLS the fields below (a glob becomes the equivalent regex, a
 * `status:` the response status code or range), so the form stays the single source
 * of truth and every field remains editable.
 */
function ScopeField({ id, ariaLabel, form, onChange, onStatus, busy }: { id: string; ariaLabel: string; form: RequestForm; onChange: (f: RequestForm) => void; onStatus: (status: VerificationStatus) => void; busy?: boolean }) {
  const [term, setTerm] = useState('');
  const parsed = useMemo(() => parseRequestScope(term, VERIFICATION_SCOPE_FIELDS), [term]);
  const status = useMemo(() => verificationStatusFor(parsed.scope), [parsed]);
  const statusError = status && 'error' in status ? status.error : null;
  const methodUnknown = parsed.scope.method != null && !METHODS.includes(parsed.scope.method);
  const applicable = isApplicableScope(parsed) && !methodUnknown && statusError == null;
  // `unsupportedFields` already has its own helper text inside the input, so only
  // the other refusals get a message here — never two messages for one mistake.
  const message = methodUnknown
    ? `method:${parsed.scope.method} is not one of ${METHODS.filter(Boolean).join(', ')}.`
    : parsed.error ?? statusError;

  const apply = () => {
    const { scope } = parsed;
    const next = { ...form };
    if (scope.method) next.method = scope.method;
    if (scope.path) next.path = scope.path;
    if (scope.host) next.headers = withHostHeaderLine(next.headers, scope.host);
    onChange(next);
    if (status && !('error' in status)) onStatus(status);
    setTerm('');
  };

  return (
    <Box>
      <Box sx={{ display: 'flex', gap: 1, alignItems: 'flex-start', flexWrap: 'wrap' }}>
        <OperatorSearchField
          id={id}
          ariaLabel={ariaLabel}
          value={term}
          onChange={setTerm}
          fields={VERIFICATION_SCOPE_FIELDS}
          maxWidth={520}
          sx={{ ml: 0, flex: 1, minWidth: 240 }}
        />
        <Button size="small" variant="outlined" disabled={!applicable || busy} onClick={apply}>
          Apply scope
        </Button>
      </Box>
      {message && (
        <Typography variant="caption" color="error" sx={{ display: 'block', mt: 0.5 }}>
          {message}
        </Typography>
      )}
    </Box>
  );
}

function RequestFields({ scopeId, scopeLabel, form, onChange, onStatus, busy }: { scopeId: string; scopeLabel: string; form: RequestForm; onChange: (f: RequestForm) => void; onStatus: (status: VerificationStatus) => void; busy?: boolean }) {
  const errors = requestErrors(form);
  return (
    <Box sx={{ display: 'flex', flexDirection: 'column', gap: 1 }}>
      <ScopeField id={scopeId} ariaLabel={scopeLabel} form={form} onChange={onChange} onStatus={onStatus} busy={busy} />
      <Box sx={{ display: 'flex', gap: 1, flexWrap: 'wrap' }}>
        <Select
          size="small"
          displayEmpty
          value={form.method}
          onChange={(e) => onChange({ ...form, method: e.target.value })}
          inputProps={{ 'aria-label': 'Method' }}
          sx={{ width: { xs: '100%', sm: 195 } }}
          renderValue={(v) => (v ? String(v) : 'Any method')}
        >
          {METHODS.map((m) => <MenuItem key={m || 'any'} value={m}>{m || 'Any method'}</MenuItem>)}
        </Select>
        <TextField size="small" label="Path" placeholder="/api/orders" value={form.path}
          onChange={(e) => onChange({ ...form, path: e.target.value })} sx={{ flex: 1, minWidth: 160 }} />
      </Box>
      <Box sx={{ display: 'flex', gap: 1 }}>
        <TextField size="small" label="Headers (Name: value per line)" multiline minRows={1} maxRows={4}
          error={!!errors.headers} helperText={errors.headers}
          value={form.headers} onChange={(e) => onChange({ ...form, headers: e.target.value })} sx={{ flex: 1 }} />
        <TextField size="small" label="Query (key=value per line)" multiline minRows={1} maxRows={4}
          error={!!errors.queryString} helperText={errors.queryString}
          value={form.queryString} onChange={(e) => onChange({ ...form, queryString: e.target.value })} sx={{ flex: 1 }} />
      </Box>
      <TextField size="small" label="Body (substring/JSON match)" multiline minRows={1} maxRows={6}
        value={form.body} onChange={(e) => onChange({ ...form, body: e.target.value })}
        slotProps={{ input: { sx: { fontFamily: monospaceFontFamily, fontSize: (theme) => theme.typography.body2.fontSize } } }} />
    </Box>
  );
}

function ResponseFields({ form, onChange }: { form: ResponseForm; onChange: (f: ResponseForm) => void }) {
  const errors = responseErrors(form);
  return (
    <Box sx={{ display: 'flex', flexDirection: 'column', gap: 1 }}>
      <Box sx={{ display: 'flex', gap: 1, flexWrap: 'wrap', alignItems: 'flex-start' }}>
        <TextField size="small" label="Status code" placeholder="200" value={form.statusCode}
          onChange={(e) => onChange({ ...form, statusCode: e.target.value })} sx={{ width: { xs: '100%', sm: 150 } }} />
        <TextField size="small" label="Status range" placeholder="4XX or >= 400" value={form.statusCodeRange}
          error={!!errors.statusCodeRange} helperText={errors.statusCodeRange}
          onChange={(e) => onChange({ ...form, statusCodeRange: e.target.value })} sx={{ width: { xs: '100%', sm: 220 } }} />
      </Box>
      <TextField size="small" label="Response headers (Name: value per line)" multiline minRows={1} maxRows={4}
        error={!!errors.headers} helperText={errors.headers}
        value={form.headers} onChange={(e) => onChange({ ...form, headers: e.target.value })} sx={{ flex: 1 }} />
      <TextField size="small" label="Response body (substring/JSON match)" multiline minRows={1} maxRows={6}
        value={form.body} onChange={(e) => onChange({ ...form, body: e.target.value })}
        slotProps={{ input: { sx: { fontFamily: monospaceFontFamily, fontSize: (theme) => theme.typography.body2.fontSize } } }} />
    </Box>
  );
}

/**
 * Equal-level collapsible section used for BOTH the request and response matchers,
 * so the two read as symmetric, optional inputs (same header + chevron styling).
 */
function MatcherSection({
  title,
  caption,
  expanded,
  invalid,
  onToggle,
  children,
}: {
  title: string;
  caption?: string;
  expanded: boolean;
  /** A section holding an invalid field cannot be collapsed, so its error is never hidden. */
  invalid?: boolean;
  onToggle: () => void;
  children: ReactNode;
}) {
  return (
    <Box sx={{ mt: 1.5 }}>
      <ButtonBase
        onClick={onToggle}
        disabled={!!invalid}
        focusRipple
        sx={{ display: 'flex', alignItems: 'center', gap: 0.5, p: 0.5, borderRadius: 1, userSelect: 'none' }}
        aria-expanded={expanded}
        aria-label={expanded ? `Collapse ${title}` : `Expand ${title}`}
      >
        {expanded ? <ExpandLessIcon fontSize="small" /> : <ExpandMoreIcon fontSize="small" />}
        <Typography variant="body2" color="text.secondary" sx={{ fontWeight: 500 }}>
          {title}
        </Typography>
      </ButtonBase>
      <Collapse in={expanded}>
        <Box sx={{ mt: 1, pl: 1 }}>
          {caption && (
            <Typography variant="caption" color="text.secondary" sx={{ mb: 1, display: 'block' }}>
              {caption}
            </Typography>
          )}
          {children}
        </Box>
      </Collapse>
    </Box>
  );
}

/** Says why the Verify button is disabled; the offending fields carry the detail. */
function InvalidFormHint() {
  return (
    <Typography variant="caption" color="error" sx={{ ml: 'auto' }}>
      Fix the fields marked in red to verify.
    </Typography>
  );
}

function ResultAlert({ result }: { result: VerifyResult | null }) {
  if (!result) return null;
  if (result.verified) {
    return (
      <Alert severity="success" icon={<CheckCircleIcon fontSize="inherit" />} sx={{ mt: 1.5 }}>
        Verified — MockServer saw matching traffic the expected number of times.
      </Alert>
    );
  }
  return (
    <Alert severity="error" sx={{ mt: 1.5 }}>
      <Typography variant="subtitle2" sx={{ fontWeight: 700 }}>Verification failed</Typography>
      <Box component="pre" sx={{ whiteSpace: 'pre-wrap', fontFamily: monospaceFontFamily, fontSize: (theme) => theme.typography.subtitle2.fontSize, m: 0, mt: 0.5 }}>
        {result.failureMessage}
      </Box>
    </Alert>
  );
}

export default function VerificationView({ connectionParams }: { connectionParams: ConnectionParams }) {
  const [mode, setMode] = useState<'single' | 'sequence'>('single');

  // Single-request verification
  const [single, setSingle] = useState<RequestForm>(emptyRequest);
  const [timesMode, setTimesMode] = useState<VerificationTimesMode>('atLeast');
  const [count, setCount] = useState(1);
  const [atMost, setAtMost] = useState(1);

  const [singleRequestExpanded, setSingleRequestExpanded] = useState(true);

  // Single-request response matcher
  const [singleResponse, setSingleResponse] = useState<ResponseForm>(emptyResponse);
  const [singleResponseExpanded, setSingleResponseExpanded] = useState(false);

  // Sequence verification
  const [sequence, setSequence] = useState<RequestForm[]>([emptyRequest(), emptyRequest()]);
  const [seqRequestExpanded, setSeqRequestExpanded] = useState<boolean[]>([true, true]);

  // Sequence response matchers
  const [seqResponses, setSeqResponses] = useState<ResponseForm[]>([emptyResponse(), emptyResponse()]);
  const [seqResponseExpanded, setSeqResponseExpanded] = useState<boolean[]>([false, false]);

  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState<VerifyResult | null>(null);
  const [error, setError] = useState<HumanError | null>(null);

  // Consume a pending verification draft handed off from a flow's "Verify This
  // Request" launchpad action: prefill the single-request matcher's method/path
  // (times mode/count keep their form defaults — atLeast 1), switch to single
  // mode with the request matcher expanded, then clear the draft so it is
  // applied exactly once. Reading via the store hook (not getState) makes the
  // effect re-run when the draft arrives while the view is already mounted.
  const pendingVerificationDraft = useDashboardStore((s) => s.pendingVerificationDraft);
  const clearPendingVerificationDraft = useDashboardStore((s) => s.clearPendingVerificationDraft);
  useEffect(() => {
    // Always clear the one-shot hand-off on teardown so a draft can never
    // survive the view being unmounted mid-apply and re-apply on re-mount.
    if (!pendingVerificationDraft) return clearPendingVerificationDraft;
    const { method, path } = pendingVerificationDraft;
    // Consuming a one-shot hand-off from the Zustand store IS the legitimate
    // "sync React state from an external system" case the rule exempts; the
    // effect clears the signal at the end so it runs exactly once per hand-off
    // (the same pattern the Composer/Breakpoints panels use).
    /* eslint-disable react-hooks/set-state-in-effect */
    setMode('single');
    setSingle((prev) => ({
      ...prev,
      method: method ?? prev.method,
      path: path ?? prev.path,
    }));
    setSingleRequestExpanded(true);
    setResult(null);
    /* eslint-enable react-hooks/set-state-in-effect */
    clearPendingVerificationDraft();
    return clearPendingVerificationDraft;
  }, [pendingVerificationDraft, clearPendingVerificationDraft]);

  const run = useCallback(async (fn: () => Promise<VerifyResult>) => {
    setBusy(true);
    setError(null);
    setResult(null);
    try {
      setResult(await fn());
    } catch (e) {
      setError(humanizeError(e));
    } finally {
      setBusy(false);
    }
  }, []);

  const timesProblem = timesSpecProblem({ mode: timesMode, count, atMost });
  const singleInvalid = hasError(requestErrors(single)) || hasError(responseErrors(singleResponse)) || timesProblem != null;
  const sequenceInvalid = sequence.some((r) => hasError(requestErrors(r))) || seqResponses.some((r) => hasError(responseErrors(r)));

  const verifySingle = () => run(() => {
    const httpResponse = buildHttpResponse(singleResponse);
    return verifyRequest(
      connectionParams,
      buildHttpRequest(single),
      { mode: timesMode, count, atMost },
      Object.keys(httpResponse).length > 0 ? httpResponse : undefined,
    );
  });

  const verifySeq = () => run(() => {
    const builtResponses = seqResponses.map(buildHttpResponse);
    const hasAnyResponse = builtResponses.some((r) => Object.keys(r).length > 0);
    return verifySequence(
      connectionParams,
      sequence.map(buildHttpRequest),
      hasAnyResponse ? builtResponses.map((r) => Object.keys(r).length > 0 ? r : undefined) : undefined,
    );
  });

  const addSequenceStep = () => {
    setSequence([...sequence, emptyRequest()]);
    setSeqRequestExpanded([...seqRequestExpanded, true]);
    setSeqResponses([...seqResponses, emptyResponse()]);
    setSeqResponseExpanded([...seqResponseExpanded, false]);
  };

  const removeSequenceStep = (index: number) => {
    setSequence(sequence.filter((_, j) => j !== index));
    setSeqRequestExpanded(seqRequestExpanded.filter((_, j) => j !== index));
    setSeqResponses(seqResponses.filter((_, j) => j !== index));
    setSeqResponseExpanded(seqResponseExpanded.filter((_, j) => j !== index));
  };

  return (
    <Box sx={{ flex: 1, overflow: 'auto', p: 1.5 }}>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 1 }}>
        <Typography variant="subtitle1" sx={{ fontWeight: 700 }}>Verification</Typography>
        <ToggleButtonGroup
          size="small" exclusive value={mode}
          onChange={(_, v: 'single' | 'sequence' | null) => { if (v) { setMode(v); setResult(null); } }}
          sx={{ ml: 1 }}
        >
          <ToggleButton value="single">Single request</ToggleButton>
          <ToggleButton value="sequence">Ordered sequence</ToggleButton>
        </ToggleButtonGroup>
      </Box>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 1.5 }}>
        Assert against what MockServer has already seen. Both matchers are optional — fill the
        request, the response, or both: a request matcher alone checks received requests; a
        response matcher alone checks the responses MockServer returned, mocked or forwarded (any
        request); when both are given they must match on the same exchange. Pass means it happened
        the expected number of times; fail shows the closest matches and the actual count.
      </Typography>

      {mode === 'single' ? (
        <Paper variant="outlined" sx={{ p: 2 }}>
          <MatcherSection
            title="Request matcher (optional)"
            expanded={singleRequestExpanded}
            invalid={hasError(requestErrors(single))}
            onToggle={() => setSingleRequestExpanded(!singleRequestExpanded)}
          >
            <RequestFields
              scopeId="verify-scope-single"
              scopeLabel="Quick scope"
              form={single}
              onChange={setSingle}
              onStatus={(status) => {
                setSingleResponse((r) => ({ ...r, ...status }));
                setSingleResponseExpanded(true);
              }}
              busy={busy}
            />
          </MatcherSection>
          <MatcherSection
            title="Response matcher (optional)"
            caption={RESPONSE_MATCHER_CAPTION}
            expanded={singleResponseExpanded}
            invalid={hasError(responseErrors(singleResponse))}
            onToggle={() => setSingleResponseExpanded(!singleResponseExpanded)}
          >
            <ResponseFields form={singleResponse} onChange={setSingleResponse} />
          </MatcherSection>
          <Box sx={{ display: 'flex', gap: 1, mt: 1.5, alignItems: 'center', flexWrap: 'wrap' }}>
            <Typography variant="body2" color="text.secondary">Received</Typography>
            <Select size="small" value={timesMode} onChange={(e) => setTimesMode(e.target.value as VerificationTimesMode)}
              inputProps={{ 'aria-label': 'Times mode' }} sx={{ width: { xs: '100%', sm: 150 } }}>
              <MenuItem value="atLeast">at least</MenuItem>
              <MenuItem value="atMost">at most</MenuItem>
              <MenuItem value="exactly">exactly</MenuItem>
              <MenuItem value="between">between</MenuItem>
            </Select>
            <TextField size="small" type="number" label={timesMode === 'between' ? 'min' : 'times'} value={count}
              error={timesProblem?.field === 'count'} helperText={timesProblem?.field === 'count' ? timesProblem.message : undefined}
              onChange={(e) => setCount(Math.max(0, Number(e.target.value) || 0))} sx={{ width: timesProblem?.field === 'count' ? 150 : 90 }} />
            {timesMode === 'between' && (
              <>
                <Typography variant="body2" color="text.secondary">and</Typography>
                <TextField size="small" type="number" label="max" value={atMost}
                  error={timesProblem?.field === 'atMost'} helperText={timesProblem?.field === 'atMost' ? timesProblem.message : undefined}
                  onChange={(e) => setAtMost(Math.max(0, Number(e.target.value) || 0))} sx={{ width: timesProblem?.field === 'atMost' ? 150 : 90 }} />
              </>
            )}
            <Typography variant="body2" color="text.secondary">time(s)</Typography>
            {singleInvalid && <InvalidFormHint />}
            <Button variant="contained" size="small" disabled={busy || singleInvalid} onClick={verifySingle} sx={{ ml: singleInvalid ? 0 : 'auto' }}>
              Verify
            </Button>
          </Box>
        </Paper>
      ) : (
        <Paper variant="outlined" sx={{ p: 2 }}>
          <Typography variant="body2" color="text.secondary" sx={{ mb: 1 }}>
            The requests must have been received in this order (other requests may occur in between).
          </Typography>
          {sequence.map((row, i) => (
            <Box key={i} sx={{ display: 'flex', gap: 1, alignItems: 'flex-start', mb: 1.5 }}>
              <Typography variant="caption" sx={{ mt: 1, width: 20 }}>{i + 1}.</Typography>
              <Box sx={{ flex: 1 }}>
                <MatcherSection
                  title="Request matcher (optional)"
                  expanded={seqRequestExpanded[i] ?? true}
                  invalid={hasError(requestErrors(row))}
                  onToggle={() => setSeqRequestExpanded(seqRequestExpanded.map((v, j) => j === i ? !v : v))}
                >
                  <RequestFields
                    scopeId={`verify-scope-step-${i}`}
                    scopeLabel={`Scope for step ${i + 1}`}
                    form={row}
                    onChange={(f) => setSequence(sequence.map((r, j) => j === i ? f : r))}
                    onStatus={(status) => {
                      setSeqResponses((all) => all.map((r, j) => (j === i ? { ...r, ...status } : r)));
                      setSeqResponseExpanded((all) => all.map((v, j) => (j === i ? true : v)));
                    }}
                    busy={busy}
                  />
                </MatcherSection>
                <MatcherSection
                  title="Response matcher (optional)"
                  caption={RESPONSE_MATCHER_CAPTION}
                  expanded={seqResponseExpanded[i] ?? false}
                  invalid={hasError(responseErrors(seqResponses[i] ?? emptyResponse()))}
                  onToggle={() => setSeqResponseExpanded(seqResponseExpanded.map((v, j) => j === i ? !v : v))}
                >
                  <ResponseFields form={seqResponses[i] ?? emptyResponse()} onChange={(f) => setSeqResponses(seqResponses.map((r, j) => j === i ? f : r))} />
                </MatcherSection>
              </Box>
              <IconButton size="small" aria-label="Remove step" disabled={sequence.length <= 1}
                onClick={() => removeSequenceStep(i)}>
                <DeleteIcon fontSize="small" />
              </IconButton>
            </Box>
          ))}
          <Box sx={{ display: 'flex', gap: 1, mt: 1 }}>
            <Button size="small" startIcon={<AddIcon />} onClick={addSequenceStep}>
              Add step
            </Button>
            {sequenceInvalid && <InvalidFormHint />}
            <Button variant="contained" size="small" disabled={busy || sequenceInvalid} onClick={verifySeq} sx={{ ml: sequenceInvalid ? 0 : 'auto' }}>
              Verify sequence
            </Button>
          </Box>
        </Paper>
      )}

      {error && <HumanErrorAlert error={error} sx={{ mt: 1.5 }} onClose={() => setError(null)} />}
      <ResultAlert result={result} />

      {/* Code generation panel — always shown; an empty form previews a "verify any request" call. */}
      <VerificationReviewMemo
        mode={mode}
        single={single}
        singleResponse={singleResponse}
        timesMode={timesMode}
        count={count}
        atMost={atMost}
        sequence={sequence}
        seqResponses={seqResponses}
        connectionParams={connectionParams}
      />
    </Box>
  );
}

/** Memoised wrapper that builds the matcher objects and passes them to VerificationReview. */
function VerificationReviewMemo({
  mode,
  single,
  singleResponse,
  timesMode,
  count,
  atMost,
  sequence,
  seqResponses,
  connectionParams,
}: {
  mode: 'single' | 'sequence';
  single: RequestForm;
  singleResponse: ResponseForm;
  timesMode: VerificationTimesMode;
  count: number;
  atMost: number;
  sequence: RequestForm[];
  seqResponses: ResponseForm[];
  connectionParams: ConnectionParams;
}) {
  const httpRequest = useMemo(() => buildHttpRequest(single), [single]);
  const httpResponse = useMemo(() => buildHttpResponse(singleResponse), [singleResponse]);
  const times = useMemo(() => ({ mode: timesMode, count, atMost }), [timesMode, count, atMost]);
  const httpRequests = useMemo(() => sequence.map(buildHttpRequest), [sequence]);
  const httpResponses = useMemo(
    () => seqResponses.map((r) => {
      const built = buildHttpResponse(r);
      return Object.keys(built).length > 0 ? built : undefined;
    }),
    [seqResponses],
  );
  const baseUrl = useMemo(() => buildBaseUrl(connectionParams), [connectionParams]);

  return (
    <VerificationReview
      mode={mode}
      httpRequest={httpRequest}
      httpResponse={httpResponse}
      times={times}
      httpRequests={httpRequests}
      httpResponses={httpResponses}
      baseUrl={baseUrl}
    />
  );
}
