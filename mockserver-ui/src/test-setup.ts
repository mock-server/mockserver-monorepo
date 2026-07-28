import '@testing-library/jest-dom/vitest';
import { vi } from 'vitest';
import { createElement } from 'react';
import { ensureWebStorage } from './test-setup-storage';

// jsdom 30.0.0 added "convert length values into pixels" to getComputedStyle().
// Its length resolver reduces calc() with cssValues.resolveCalc and then does
//
//     const [, value] = FONT_SIZE_REGEXP.exec(resolvedSize);
//
// with no null check. A calc() mixing a percentage with a length - MUI's Dialog
// uses max-width/max-height: calc(100% - 64px) - cannot be reduced to a single
// length without layout, which jsdom has no notion of, so resolveCalc returns it
// unchanged, the regex does not match, and destructuring null throws
// "object null is not iterable". @testing-library calls getComputedStyle() on
// every accessibility/role query, so this takes out any test that renders a
// Dialog rather than anything font-related.
//
// jsdom's own caller already treats a non-numeric result as "leave the value
// alone", so returning NaN restores the pre-30 behaviour exactly. The wrapper
// only changes anything when the original throws, so it neutralises itself once
// jsdom ships a fix - delete it then, along with this comment.
type LengthResolver = ((...args: unknown[]) => unknown) & { patched?: boolean };

async function guardJsdomCalcLengthResolution(): Promise<void> {
  try {
    // Imported by path rather than from node:module so this file stays free of
    // node typings (the UI tsconfig covers src with DOM libs only).
    const specifier = 'jsdom/lib/jsdom/living/css/helpers/font-sizes.js';
    const imported = (await import(/* @vite-ignore */ specifier)) as {
      default?: { resolveLengthInPixels?: LengthResolver };
      resolveLengthInPixels?: LengthResolver;
    };
    // A CommonJS module reached through ESM exposes module.exports as .default;
    // that is the same object jsdom itself calls through, so patching it takes
    // effect for the live document.
    const fontSizes = imported.default ?? imported;
    const original = fontSizes.resolveLengthInPixels;
    if (typeof original !== 'function' || original.patched) {
      return;
    }
    const guarded: LengthResolver = function (this: unknown, ...args: unknown[]): unknown {
      try {
        return original.apply(this, args);
      } catch (error) {
        // Only the unguarded destructure above - anything else is a real fault.
        if (error instanceof TypeError && error.message.includes('is not iterable')) {
          return Number.NaN;
        }
        throw error;
      }
    };
    guarded.patched = true;
    fontSizes.resolveLengthInPixels = guarded;
  } catch {
    // jsdom's internals are not a public API; if the path moves there is
    // nothing to guard and the suite should still run.
  }
}
await guardJsdomCalcLengthResolution();

// jsdom does not always expose localStorage/sessionStorage (origin- and
// build-dependent). Guarantee a working Storage so suites that clear/read it in
// beforeEach are deterministic regardless of node_modules/jsdom install state.
ensureWebStorage();

// Monaco editor cannot run in jsdom (it needs real layout + clipboard/worker
// APIs). Globally replace the @monaco-editor/react wrapper (and the bundled
// monaco module + its ?worker imports) with lightweight stand-ins so any
// component that embeds a code editor (e.g. the Composer body matcher) renders
// in tests. Individual test files can still override this with their own
// vi.mock to assert editor-specific behaviour.
vi.mock('@monaco-editor/react', () => ({
  loader: { config: vi.fn() },
  default: ({
    value,
    language,
    onChange,
    onMount,
  }: {
    value?: string;
    language?: string;
    onChange?: (value: string | undefined) => void;
    onMount?: (editor: unknown, monaco: unknown) => void;
  }) => {
    onMount?.({}, { languages: { json: { jsonDefaults: { diagnosticsOptions: { schemas: [] }, setDiagnosticsOptions: vi.fn() } } } });
    return createElement('textarea', {
      'data-testid': 'monaco-textarea',
      'data-language': language,
      value: value ?? '',
      onChange: (e: { target: { value: string } }) => onChange?.(e.target.value),
    });
  },
  // The DiffEditor cannot run in jsdom either; render both panes as read-only
  // text areas so the JsonDiffViewer (capture/composer preview-diff) is testable.
  DiffEditor: ({
    original,
    modified,
    language,
  }: {
    original?: string;
    modified?: string;
    language?: string;
  }) =>
    createElement('div', { 'data-testid': 'monaco-diff', 'data-language': language }, [
      createElement('textarea', {
        key: 'original',
        'data-testid': 'monaco-diff-original',
        readOnly: true,
        value: original ?? '',
      }),
      createElement('textarea', {
        key: 'modified',
        'data-testid': 'monaco-diff-modified',
        readOnly: true,
        value: modified ?? '',
      }),
    ]),
}));

vi.mock('monaco-editor', () => ({
  MarkerSeverity: { Hint: 1, Info: 2, Warning: 4, Error: 8 },
  languages: { json: { jsonDefaults: { diagnosticsOptions: { schemas: [] }, setDiagnosticsOptions: vi.fn() } } },
}));

vi.mock('monaco-editor/editor/editor.worker?worker', () => ({ default: class {} }));
vi.mock('monaco-editor/language/json/json.worker?worker', () => ({ default: class {} }));

// jsdom does not implement ResizeObserver, which @mui/x-charts (and other
// responsive components) rely on. Provide a no-op so charts can render in tests.
if (typeof globalThis.ResizeObserver === 'undefined') {
  globalThis.ResizeObserver = class ResizeObserverStub {
    observe(): void {}
    unobserve(): void {}
    disconnect(): void {}
  } as unknown as typeof ResizeObserver;
}
