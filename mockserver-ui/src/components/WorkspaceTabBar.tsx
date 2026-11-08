import { useCallback, useState } from 'react';
import Box from '@mui/material/Box';
import ButtonBase from '@mui/material/ButtonBase';
import IconButton from '@mui/material/IconButton';
import InputBase from '@mui/material/InputBase';
import Tooltip from '@mui/material/Tooltip';
import AddIcon from '@mui/icons-material/Add';
import CloseIcon from '@mui/icons-material/Close';
import DnsOutlinedIcon from '@mui/icons-material/DnsOutlined';
import PauseCircleIcon from '@mui/icons-material/PauseCircle';
import { useDashboardStore } from '../store';
import { connectionKey, describeTarget, effectiveConnection, urlConnectionParams } from '../lib/connectionTarget';
import { usePausedBreakpointCount } from '../hooks/usePausedBreakpointCount';
import WorkspaceTargetDialog from './WorkspaceTargetDialog';

/**
 * The workspace switcher.
 *
 * A workspace is an independent investigation context — its own view and its own
 * per-panel search terms — so one window can hold two separate lines of enquiry
 * without either clobbering the other's filters.
 *
 * PLACEMENT. This renders as its OWN full-width row beneath the app bar, never
 * inside it. An earlier "recent views" feature put quick-access tabs *into* the
 * app bar's navigation region and was reverted because the bar no longer fitted
 * on typical widths (commits d536433f1 / d55d7077d). Two rules keep that from
 * repeating here:
 *   1. the tabs compete for their own row's horizontal space, not the nav's, and
 *      overflow scrolls (`nowrap` + `overflowX: auto`) instead of wrapping the
 *      bar to a second line;
 *   2. the row does not exist at all until there is more than one workspace (or
 *      the only one has its own target), so the default dashboard is
 *      pixel-identical to before. The
 *      one control that has to live in the app bar is the single icon button
 *      that creates the second workspace.
 *
 * Once any workspace has its own MockServer target, every tab shows its target,
 * so the server each tab's actions go to is never a guess.
 *
 * A background workspace's breakpoint session stays open, so a background tab on another server
 * shows how many items are paused there; clicking that count opens its Breakpoints view.
 */
export default function WorkspaceTabBar() {
  const workspaces = useDashboardStore((s) => s.workspaces);
  const activeWorkspaceId = useDashboardStore((s) => s.activeWorkspaceId);
  const switchWorkspace = useDashboardStore((s) => s.switchWorkspace);
  const closeWorkspace = useDashboardStore((s) => s.closeWorkspace);
  const renameWorkspace = useDashboardStore((s) => s.renameWorkspace);
  const addWorkspace = useDashboardStore((s) => s.addWorkspace);
  const liveConnection = useDashboardStore((s) => s.connection);
  const setWorkspaceConnection = useDashboardStore((s) => s.setWorkspaceConnection);
  const setView = useDashboardStore((s) => s.setView);
  const [urlTarget] = useState(urlConnectionParams);
  const [targetEditId, setTargetEditId] = useState<string | null>(null);

  // Inline rename: the id being edited and the draft text. Enter or blur commits,
  // Escape abandons. A blank draft is rejected by the store, leaving the old name.
  const [editingId, setEditingId] = useState<string | null>(null);
  const [draftName, setDraftName] = useState('');

  const commitRename = useCallback(() => {
    if (editingId !== null) renameWorkspace(editingId, draftName);
    setEditingId(null);
  }, [editingId, draftName, renameWorkspace]);

  const connectionOf = (id: string) =>
    (id === activeWorkspaceId ? liveConnection : workspaces.find((w) => w.id === id)?.connection ?? null);
  const showTargets = workspaces.some((w) => connectionOf(w.id) !== null);
  const activeKey = connectionKey(effectiveConnection(liveConnection, urlTarget));

  // A single workspace needs no switcher — see the placement note above — unless it has its
  // own target: that must stay visible and editable, or every action would go to a hidden server.
  if (workspaces.length <= 1 && !showTargets) return null;
  const editing = targetEditId !== null ? workspaces.find((w) => w.id === targetEditId) : undefined;

  return (
    <Box
      role="group"
      aria-label="Workspaces"
      data-testid="workspace-tab-bar"
      sx={{
        flexShrink: 0,
        display: 'flex',
        alignItems: 'center',
        gap: 0.5,
        px: 1,
        py: 0.25,
        borderBottom: 1,
        borderColor: 'divider',
        bgcolor: 'action.hover',
        // Never wrap: extra workspaces scroll within this row rather than growing
        // the chrome and pushing the view area down.
        flexWrap: 'nowrap',
        overflowX: 'auto',
        overflowY: 'hidden',
      }}
    >
      {workspaces.map((workspace) => {
        const active = workspace.id === activeWorkspaceId;
        const effective = effectiveConnection(connectionOf(workspace.id), urlTarget);
        const target = describeTarget(effective);
        const targetKey = connectionKey(effective);
        return (
          <Box
            key={workspace.id}
            sx={{
              display: 'flex',
              alignItems: 'center',
              flexShrink: 0,
              maxWidth: showTargets ? 320 : 220,
              borderRadius: 1,
              bgcolor: active ? 'action.selected' : 'transparent',
            }}
          >
            {editingId === workspace.id ? (
              <InputBase
                autoFocus
                value={draftName}
                inputProps={{ 'aria-label': `Rename workspace ${workspace.name}` }}
                onChange={(e) => setDraftName(e.target.value)}
                onBlur={commitRename}
                onKeyDown={(e) => {
                  if (e.key === 'Enter') {
                    e.preventDefault();
                    commitRename();
                  } else if (e.key === 'Escape') {
                    e.preventDefault();
                    setEditingId(null);
                  }
                }}
                sx={{ px: 1, fontSize: '0.75rem', width: 140 }}
              />
            ) : (
              <Tooltip title={active ? `${workspace.name} (double-click to rename)` : `Switch to ${workspace.name}`}>
                <ButtonBase
                  aria-label={`Workspace ${workspace.name}`}
                  aria-current={active ? 'true' : undefined}
                  onClick={() => switchWorkspace(workspace.id)}
                  onDoubleClick={() => {
                    setDraftName(workspace.name);
                    setEditingId(workspace.id);
                  }}
                  sx={{
                    px: 1,
                    py: 0.25,
                    borderRadius: 1,
                    fontSize: '0.75rem',
                    fontWeight: active ? 600 : 400,
                    whiteSpace: 'nowrap',
                    overflow: 'hidden',
                    textOverflow: 'ellipsis',
                    display: 'block',
                  }}
                >
                  {workspace.name}
                  {showTargets && (
                    <Box
                      component="span"
                      data-testid={`workspace-target-${workspace.id}`}
                      sx={{ ml: 0.75, color: 'text.secondary', fontFamily: 'monospace', fontSize: '0.7rem' }}
                    >
                      {target}
                    </Box>
                  )}
                </ButtonBase>
              </Tooltip>
            )}
            {/* items on the active workspace's own server are already one view away */}
            {!active && targetKey !== activeKey && (
              <PausedBreakpointsButton
                workspaceId={workspace.id}
                workspaceName={workspace.name}
                targetKey={targetKey}
                target={target}
                onOpen={() => {
                  switchWorkspace(workspace.id);
                  setView('breakpoints');
                }}
              />
            )}
            <Tooltip title={`MockServer for ${workspace.name}: ${target}`}>
              <IconButton
                size="small"
                aria-label={`Set MockServer for workspace ${workspace.name}`}
                onClick={() => setTargetEditId(workspace.id)}
              >
                <DnsOutlinedIcon sx={{ fontSize: '0.8rem' }} />
              </IconButton>
            </Tooltip>
            {workspaces.length > 1 && (
              <IconButton
                size="small"
                aria-label={`Close workspace ${workspace.name}`}
                onClick={() => closeWorkspace(workspace.id)}
                sx={{ mr: 0.25 }}
              >
                <CloseIcon sx={{ fontSize: '0.75rem' }} />
              </IconButton>
            )}
          </Box>
        );
      })}
      <Tooltip title="New workspace">
        <IconButton size="small" aria-label="New workspace" onClick={addWorkspace} sx={{ flexShrink: 0 }}>
          <AddIcon sx={{ fontSize: '0.875rem' }} />
        </IconButton>
      </Tooltip>
      {editing && (
        <WorkspaceTargetDialog
          workspaceName={editing.name}
          connection={connectionOf(editing.id)}
          onSave={(connection) => {
            setWorkspaceConnection(editing.id, connection);
            setTargetEditId(null);
          }}
          onClose={() => setTargetEditId(null)}
        />
      )}
    </Box>
  );
}

/** The count of items paused on a background workspace's server; renders nothing when none are. */
function PausedBreakpointsButton({ workspaceId, workspaceName, targetKey, target, onOpen }: {
  workspaceId: string;
  workspaceName: string;
  targetKey: string;
  target: string;
  onOpen: () => void;
}) {
  const count = usePausedBreakpointCount(targetKey);
  if (count === 0) return null;
  const paused = `${count} ${count === 1 ? 'item' : 'items'} paused at a breakpoint on ${target}`;
  return (
    <Tooltip title={`${paused}. Open Breakpoints in ${workspaceName} to act on them before the breakpoint timeout continues them.`}>
      <ButtonBase
        aria-label={`${paused}: open Breakpoints in workspace ${workspaceName}`}
        data-testid={`workspace-paused-${workspaceId}`}
        onClick={onOpen}
        sx={{
          ml: 0.25,
          px: 0.5,
          gap: 0.25,
          borderRadius: 1,
          fontSize: '0.7rem',
          fontWeight: 600,
          bgcolor: 'warning.main',
          color: 'warning.contrastText',
        }}
      >
        <PauseCircleIcon sx={{ fontSize: '0.8rem' }} />
        {count}
      </ButtonBase>
    </Tooltip>
  );
}
