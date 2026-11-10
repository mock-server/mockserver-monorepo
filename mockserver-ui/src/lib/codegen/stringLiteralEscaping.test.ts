/**
 * Event text holding characters a client language's string literal cannot carry raw still
 * produces source that compiles: C# line separators, Ruby end-of-script control characters
 * and lone UTF-16 surrogates in Go.
 */
import { describe, it, expect } from 'vitest';
import { standardToCsharp, standardToGo, standardToRuby, standardToRust } from '../standardCodegen';
import { keptFieldsEdit } from './extractParityCases';

const LS = String.fromCharCode(0x2028);
const PS = String.fromCharCode(0x2029);
const NEL = String.fromCharCode(0x85);
const LONE_SURROGATE = String.fromCharCode(0xd800);

function sseWith(text: string) {
  return keptFieldsEdit('escaping', {
    type: 'sse',
    sse: { statusCode: 200, headers: '', events: [{ event: '', data: 'd', id: '', retry: '' }], closeConnection: true },
  }, 'httpSseResponse', (w) => ({ ...w, events: [{ data: text, comment: text }] }));
}

describe('string literals in generated code', () => {
  it('C# escapes NEL, LS, PS and control characters', () => {
    const c = sseWith(`a${NEL}b${LS}c${PS}d\u0001e`);
    const code = standardToCsharp(c.matcher, c.action, c.baseUrl);
    expect(code).not.toMatch(/(?!\n)[\p{Cc}\u2028\u2029]/u);
    expect(code).toContain('Data = "a\\u0085b\\u2028c\\u2029d\\u0001e"');
    expect(code).toContain('Comment = "a\\u0085b\\u2028c\\u2029d\\u0001e"');
  });

  it('Ruby escapes NUL, ^D and ^Z', () => {
    const c = sseWith('a\u0000b\u0004c\u001ad');
    const code = standardToRuby(c.matcher, c.action, c.baseUrl);
    expect(code).not.toMatch(/(?!\n)\p{Cc}/u);
    expect(code).toContain('data: "a\\x00b\\x04c\\x1ad"');
    expect(code).toContain('comment: "a\\x00b\\x04c\\x1ad"');
  });

  it('C# and Rust replace a lone surrogate with U+FFFD', () => {
    const c = sseWith(`a${LONE_SURROGATE}b`);
    expect(standardToCsharp(c.matcher, c.action, c.baseUrl)).toContain('Comment = "a\ufffdb"');
    expect(standardToRust(c.matcher, c.action, c.baseUrl)).toContain('.comment("a\\u{fffd}b")');
  });

  it('Go replaces a lone surrogate, which it cannot escape, with U+FFFD', () => {
    const c = sseWith(`a${LONE_SURROGATE}b\\ud800`);
    const code = standardToGo(c.matcher, c.action, c.baseUrl);
    expect(code).not.toMatch(/(?<!\\)(?:\\\\)*\\ud800/i);
    expect(code).toContain('"a\\ufffdb\\\\ud800"');
  });
});
