import '@testing-library/jest-dom/vitest';
import { vi } from 'vitest';
import { createElement } from 'react';
import { ensureWebStorage } from './test-setup-storage';


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
