/**
 * Parses every ```mermaid block under docs/ with Mermaid itself.
 *
 * A diagram that does not parse renders as an error box on GitHub, and nothing else in
 * the build looks at them -- one shipped with a `;` inside a Note, which Mermaid reads
 * as a statement separator. Markdown does not care, so only the renderer ever finds out.
 *
 * Usage: node scripts/check-diagrams.mjs [files...]   (default: docs/diagrams/*.md)
 */
import fs from 'node:fs';
import path from 'node:path';
import { JSDOM } from 'jsdom';

const files = process.argv.length > 2
  ? process.argv.slice(2)
  : fs.readdirSync('docs/diagrams')
      .filter((f) => f.endsWith('.md'))
      .map((f) => path.join('docs/diagrams', f));

function extract(file) {
  const lines = fs.readFileSync(file, 'utf8').split('\n');
  const blocks = [];
  for (let i = 0; i < lines.length; i++) {
    if (!lines[i].trim().startsWith('```mermaid')) continue;
    const start = i + 1;
    let j = start;
    while (j < lines.length && !lines[j].trim().startsWith('```')) j++;
    blocks.push({ file, line: start + 1, code: lines.slice(start, j).join('\n') });
    i = j;
  }
  return blocks;
}

// Mermaid needs a DOM even to parse.
const dom = new JSDOM('<!DOCTYPE html><body></body>', { pretendToBeVisual: true });
global.window = dom.window;
global.document = dom.window.document;
// navigator is getter-only on Node 22, so it cannot be assigned directly.
Object.defineProperty(global, 'navigator', { value: dom.window.navigator, configurable: true });
global.HTMLElement = dom.window.HTMLElement;
global.SVGElement = dom.window.SVGElement;

const mermaid = (await import('mermaid')).default;
mermaid.initialize({ startOnLoad: false, securityLevel: 'loose' });

const blocks = files.flatMap(extract);
let failed = 0;

for (const b of blocks) {
  try {
    await mermaid.parse(b.code);
    console.log(`OK    ${b.file}:${b.line}`);
  } catch (e) {
    failed++;
    const detail = String(e.message).split('\n').slice(0, 4).join('\n      ');
    console.log(`FAIL  ${b.file}:${b.line}\n      ${detail}`);
  }
}

if (blocks.length === 0) {
  console.error('No mermaid blocks found — check the paths.');
  process.exit(1);
}

console.log(failed ? `\n${failed} of ${blocks.length} diagrams failed` : `\nAll ${blocks.length} diagrams parse`);
process.exit(failed ? 1 : 0);
