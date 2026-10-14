import { useState, useEffect, useCallback } from 'react';
import Dialog from '@mui/material/Dialog';
import DialogTitle from '@mui/material/DialogTitle';
import DialogContent from '@mui/material/DialogContent';
import DialogActions from '@mui/material/DialogActions';
import useMediaQuery from '@mui/material/useMediaQuery';
import { useTheme } from '@mui/material/styles';
import Box from '@mui/material/Box';
import Typography from '@mui/material/Typography';
import Chip from '@mui/material/Chip';
import Button from '@mui/material/Button';
import Alert from '@mui/material/Alert';
import Paper from '@mui/material/Paper';
import CircularProgress from '@mui/material/CircularProgress';
import type { ConnectionParams } from '../hooks/useConnectionParams';
import {
  explainRequest,
  ExplainRequestError,
  type ArrivalOutcome,
  type ExplainedExpectation,
  type ExplainReason,
  type RequestExplanation,
} from '../lib/explainRequest';
import { monospaceFontFamily } from '../theme';
import { humanizeError, type HumanError } from '../lib/errorMessage';
import HumanErrorAlert from './HumanErrorAlert';

const REASON_LABELS: Record<ExplainReason, string> = {
  NAMESPACE: 'Other Namespace',
  INACTIVE: 'Inactive',
  FIELDS_DIFFER: 'Fields Differ',
  NOT_OPERATOR: 'Excluded by not()',
  CONVERSATION: 'Conversation',
  SCENARIO_STATE: 'Scenario State',
  PERCENTAGE: 'Percentage',
  LOWER_PRIORITY: 'Lower Priority',
};

const ARRIVAL_LABELS: Record<ArrivalOutcome, string> = {
  MATCHED: 'matched',
  NO_MATCH: 'matched no expectation',
  FORWARDED_WITHOUT_EXPECTATION: 'was forwarded without an expectation',
  RESPONDED_WITHOUT_EXPECTATION: 'was answered without an expectation',
  UNKNOWN: 'has no recorded outcome',
};

function ExpectationRow({ exp }: { exp: ExplainedExpectation }) {
  return (
    <Box
      data-testid="explained-expectation"
      sx={{ pl: 1.5, py: 0.5, borderLeft: 2, borderColor: 'divider', mb: 0.5 }}
    >
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, flexWrap: 'wrap' }}>
        {exp.position !== undefined && (
          <Typography variant="caption" color="text.secondary">#{exp.position}</Typography>
        )}
        <Typography variant="caption" sx={{ fontFamily: monospaceFontFamily }}>
          {exp.expectationMethod} {exp.expectationPath || '(any path)'}
        </Typography>
        {exp.expectationId && (
          <Chip size="small" variant="outlined" label={exp.expectationId} sx={{ fontFamily: monospaceFontFamily }} />
        )}
        {exp.priority !== undefined && exp.priority !== 0 && (
          <Typography variant="caption" color="text.secondary">priority {exp.priority}</Typography>
        )}
        {exp.reason && (
          <Chip
            size="small"
            color={exp.reason === 'LOWER_PRIORITY' ? 'default' : 'warning'}
            label={REASON_LABELS[exp.reason] ?? exp.reason}
          />
        )}
      </Box>
      {exp.explanation && (
        <Typography variant="caption" component="div" color="text.secondary">
          {exp.explanation}
        </Typography>
      )}
      {exp.differences && Object.keys(exp.differences).length > 0 && (
        <Box sx={{ mt: 0.5 }}>
          {Object.entries(exp.differences).map(([field, diffs]) => (
            <Typography
              key={field}
              variant="caption"
              component="div"
              color="text.secondary"
              sx={{ fontFamily: monospaceFontFamily, fontSize: '0.7rem', whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}
            >
              <b>{field}</b>: {diffs.join('\n')}
              {exp.remediation?.[field] && <em> — {exp.remediation[field]}</em>}
            </Typography>
          ))}
        </Box>
      )}
    </Box>
  );
}

function Section({ title, items, empty }: { title: string; items: ExplainedExpectation[]; empty: string }) {
  return (
    <Box sx={{ mt: 1.5 }}>
      <Typography variant="subtitle2" component="h3">{title} ({items.length})</Typography>
      {items.length === 0
        ? <Typography variant="caption" color="text.secondary">{empty}</Typography>
        : items.map((exp, i) => <ExpectationRow key={`${exp.expectationId ?? ''}-${i}`} exp={exp} />)}
    </Box>
  );
}

function Summary({ result }: { result: RequestExplanation }) {
  const arrival = result.atArrival;
  const arrivalText = arrival.outcome === 'MATCHED' && arrival.expectationId
    ? `matched expectation ${arrival.expectationId}${arrival.expectationStillStored === false ? ' (since removed)' : ''}`
    : ARRIVAL_LABELS[arrival.outcome] ?? arrival.outcome;
  return (
    <>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, flexWrap: 'wrap', mb: 1 }}>
        <Typography variant="subtitle1" sx={{ fontFamily: monospaceFontFamily }}>
          {result.request.method} {result.request.path}
        </Typography>
        {result.request.timestamp && (
          <Typography variant="caption" color="text.secondary">{result.request.timestamp}</Typography>
        )}
      </Box>
      <Typography variant="body2">When it arrived, the request {arrivalText}{arrival.forwardFailure ? ', and its forward failed' : ''}.</Typography>
      <Typography variant="body2" sx={{ mb: 1 }}>
        {result.matchedExpectation
          ? <>Now it would be served by expectation <b>{result.matchedExpectation.expectationId}</b>.</>
          : <>Now no expectation would serve it{result.closestExpectationId ? <>; the closest is <b>{result.closestExpectationId}</b></> : null}.</>}
        {result.sameOutcomeAsAtArrival === false && ' That is a different outcome from when it arrived.'}
      </Typography>
      {arrival.forwardFailure && (
        <Alert severity="error" sx={{ mb: 1 }}>
          MockServer answered {arrival.forwardFailure.statusCode ?? 502} because the forward failed: {arrival.forwardFailure.reason ?? 'unknown error'}
          {arrival.forwardFailure.causeNamed === false && (
            <Box component="span" sx={{ display: 'block', mt: 0.5 }}>
              MockServer could not name the cause, so this is the raw error. The log entries with this correlation ID show the request&apos;s whole lifecycle, including any exception logged for it.
            </Box>
          )}
        </Alert>
      )}
      {result.expectationsChangedSince && (
        <Alert severity="info" sx={{ mb: 1 }}>
          The expectations have changed since this request arrived, so this explanation may differ from what happened then.
        </Alert>
      )}
      {result.request.bodyTruncated && (
        <Alert severity="warning" sx={{ mb: 1 }}>
          {result.request.bodyTruncatedNote ?? 'The recorded body was truncated, so body matching used the truncated copy.'}
        </Alert>
      )}
      {result.truncated && (
        <Alert severity="warning" sx={{ mb: 1 }}>
          Only the first {result.maxExpectationsEvaluated ?? result.evaluatedExpectations} expectations were evaluated.
        </Alert>
      )}
    </>
  );
}

/**
 * Explains one recorded request (by correlation ID): the expectation that would serve it now, the
 * ones evaluated ahead of it that were skipped and why, and the lower-priority ones it shadows.
 */
export default function ExplainRequestDialog({
  open,
  onClose,
  correlationId,
  connectionParams,
}: {
  open: boolean;
  onClose: () => void;
  correlationId: string;
  connectionParams: ConnectionParams;
}) {
  const theme = useTheme();
  const fullScreen = useMediaQuery(theme.breakpoints.down('sm'));
  const [result, setResult] = useState<RequestExplanation | null>(null);
  const [error, setError] = useState<HumanError | null>(null);
  const [refreshTick, setRefreshTick] = useState(0);

  const refresh = useCallback(() => { setResult(null); setError(null); setRefreshTick((t) => t + 1); }, []);

  useEffect(() => {
    if (!open) return;
    const controller = new AbortController();
    explainRequest(connectionParams, correlationId, controller.signal)
      .then((next) => { setResult(next); setError(null); })
      .catch((e: unknown) => {
        if (controller.signal.aborted) return;
        setError(e instanceof ExplainRequestError ? { message: e.message } : humanizeError(e));
      });
    return () => controller.abort();
  }, [open, connectionParams, correlationId, refreshTick]);

  const loading = open && result === null && error === null;

  return (
    <Dialog open={open} onClose={onClose} maxWidth="md" fullWidth fullScreen={fullScreen} aria-labelledby="explain-request-dialog-title">
      <DialogTitle id="explain-request-dialog-title-row">
        <span id="explain-request-dialog-title">Explain Request</span>
        <Chip size="small" variant="outlined" label={correlationId} sx={{ ml: 1, fontFamily: monospaceFontFamily }} />
        <Button size="small" sx={{ ml: 2 }} disabled={loading} onClick={refresh}>Refresh</Button>
      </DialogTitle>
      <DialogContent>
        {error && <HumanErrorAlert error={error} sx={{ mb: 1.5 }} />}
        {loading && <Box sx={{ display: 'flex', justifyContent: 'center', p: 2 }}><CircularProgress size={24} /></Box>}
        {!loading && result && (
          <>
            <Summary result={result} />
            <Paper variant="outlined" sx={{ p: 1.5 }}>
              <Section
                title="Matched"
                items={result.matchedExpectation ? [result.matchedExpectation] : []}
                empty="No expectation matches this request now."
              />
              <Section
                title="Skipped"
                items={result.skippedExpectations}
                empty="No expectation was evaluated ahead of the match."
              />
              <Section
                title="Shadowed"
                items={result.shadowedExpectations}
                empty="No lower-priority expectation also matches."
              />
            </Paper>
            <Typography variant="caption" color="text.secondary" component="div" sx={{ mt: 1 }}>
              Evaluated {result.evaluatedExpectations} of {result.totalExpectations} expectations. {result.note}
            </Typography>
          </>
        )}
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Close</Button>
      </DialogActions>
    </Dialog>
  );
}
