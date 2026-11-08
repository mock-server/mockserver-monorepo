import { useCallback, useSyncExternalStore } from 'react';
import { findBreakpointCallbackClient, subscribeBreakpointCallbackClients } from '../lib/breakpointCallbackClient';

/**
 * How many items are paused at a breakpoint on the server with this `connectionKey`, as held by
 * that server's callback client. Zero when no client exists, so reading it never opens a session.
 */
export function usePausedBreakpointCount(targetKey: string): number {
  const subscribe = useCallback((onChange: () => void) => {
    let unsubscribeItems: (() => void) | undefined;
    // A client can be created (Breakpoints view opened) or released after this subscribes.
    const attach = () => {
      unsubscribeItems?.();
      unsubscribeItems = findBreakpointCallbackClient(targetKey)?.subscribePausedItems(onChange);
      onChange();
    };
    attach();
    const unsubscribeRegistry = subscribeBreakpointCallbackClients(attach);
    return () => {
      unsubscribeRegistry();
      unsubscribeItems?.();
    };
  }, [targetKey]);
  const count = useCallback(() => findBreakpointCallbackClient(targetKey)?.getPausedItems().length ?? 0, [targetKey]);
  return useSyncExternalStore(subscribe, count);
}
