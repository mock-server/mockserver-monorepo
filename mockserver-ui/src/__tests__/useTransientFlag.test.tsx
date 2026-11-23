import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, act, cleanup } from '@testing-library/react';
import { useTransientFlag } from '../hooks/useTransientFlag';
import CopyButton from '../components/CopyButton';

afterEach(() => {
  cleanup();
  vi.useRealTimers();
  vi.restoreAllMocks();
});

function Flasher() {
  const [value, flash] = useTransientFlag<'idle' | 'copied'>('idle');
  return (
    <button type="button" onClick={() => flash('copied', 'idle', 2000)}>
      {value}
    </button>
  );
}

describe('useTransientFlag', () => {
  it('flips to the transient value and resets itself after the delay', () => {
    vi.useFakeTimers();
    render(<Flasher />);
    expect(screen.getByRole('button')).toHaveTextContent('idle');

    act(() => {
      screen.getByRole('button').click();
    });
    expect(screen.getByRole('button')).toHaveTextContent('copied');

    act(() => {
      vi.advanceTimersByTime(2000);
    });
    expect(screen.getByRole('button')).toHaveTextContent('idle');
  });

  // The regression this hook exists for. A bare
  //   setCopied(true); setTimeout(() => setCopied(false), 2000);
  // leaves a timer that outlives the component. In the browser that is a warning;
  // under vitest the test file's jsdom environment is gone by the time it fires, so
  // React dereferences a window that no longer exists and throws
  // `ReferenceError: window is not defined`. Vitest reports that as an unhandled
  // error and exits NON-ZERO even though every test passed — which is how it
  // reached CI: "196 passed / 3084 passed", then "exit status 1".
  it('cancels a pending reset when the component unmounts, so no timer outlives it', () => {
    vi.useFakeTimers();
    const { unmount } = render(<Flasher />);
    act(() => {
      screen.getByRole('button').click();
    });
    expect(screen.getByRole('button')).toHaveTextContent('copied');

    unmount();
    // Nothing may remain that could fire into a torn-down environment.
    expect(vi.getTimerCount()).toBe(0);

    // And advancing past the delay must not throw or warn.
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {});
    act(() => {
      vi.advanceTimersByTime(10_000);
    });
    expect(consoleError).not.toHaveBeenCalled();
  });

  it('re-triggering restarts the delay rather than stacking timers', () => {
    vi.useFakeTimers();
    render(<Flasher />);
    act(() => {
      screen.getByRole('button').click();
    });
    act(() => {
      vi.advanceTimersByTime(1500);
    });
    act(() => {
      screen.getByRole('button').click();
    });
    expect(vi.getTimerCount()).toBe(1);

    // The first timer's original deadline passes with no reset — the second
    // trigger replaced it rather than adding to it.
    act(() => {
      vi.advanceTimersByTime(1000);
    });
    expect(screen.getByRole('button')).toHaveTextContent('copied');

    act(() => {
      vi.advanceTimersByTime(1000);
    });
    expect(screen.getByRole('button')).toHaveTextContent('idle');
  });
});

describe('CopyButton', () => {
  it('leaves no timer behind when unmounted straight after a copy', async () => {
    // jsdom 31+ queues each <style> load event via setImmediate, and MUI/Emotion
    // inserts <style>s on render: leave it real so only component timers count.
    vi.useFakeTimers({ toNotFake: ['setImmediate', 'clearImmediate'] });
    // jsdom does not define navigator.clipboard at all, so there is no getter to
    // spy on — it has to be defined. Define it CONFIGURABLE and restore the
    // original descriptor in the finally below: a bare
    // `Object.assign(navigator, ...)` is not undone by `vi.restoreAllMocks()` and
    // would leak a fake clipboard into every later test sharing this environment.
    const original = Object.getOwnPropertyDescriptor(navigator, 'clipboard');
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: { writeText: vi.fn().mockResolvedValue(undefined) },
    });

    try {

      const { unmount } = render(<CopyButton text="hello" />);
      await act(async () => {
        screen.getByRole('button').click();
        await Promise.resolve();
      });

      unmount();
      expect(vi.getTimerCount()).toBe(0);
    } finally {
      if (original) Object.defineProperty(navigator, 'clipboard', original);
      else delete (navigator as { clipboard?: unknown }).clipboard;
    }
  });
});
