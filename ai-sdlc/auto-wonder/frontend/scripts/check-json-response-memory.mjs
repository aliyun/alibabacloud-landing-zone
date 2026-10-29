// Execute the real production converter in a memory-constrained child. A regression
// to per-character ropes exhausts this budget even though the input is only 8 MiB.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import ts from 'typescript';

const source = readFileSync(new URL('../src/shared/api/protectBigInts.ts', import.meta.url), 'utf8');
const parsed = ts.createSourceFile('converter.ts', source, ts.ScriptTarget.Latest, true);
const declaration = parsed.statements.find(node => ts.isFunctionDeclaration(node) && node.name?.text === 'protectBigInts');
assert.ok(declaration, 'production converter must exist');
const compiled = ts.transpileModule(declaration.getText(parsed), { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS } }).outputText;
const fixtures = `
const assert = require('node:assert/strict');
const body = '正文\\\\\\"abcd'.repeat(1024 * 1024);
const raw = '{"id":17900000000000001,"content":' + JSON.stringify(body) + '}';
const result = JSON.parse(protectBigInts(raw));
assert.equal(result.id, '17900000000000001');
assert.equal(result.content, body);
const dense = '[' + '17900000000000001,'.repeat(100000) + '42]';
const numbers = JSON.parse(protectBigInts(dense));
assert.equal(numbers.length, 100001);
assert.equal(numbers[0], '17900000000000001');
assert.equal(numbers[99999], '17900000000000001');
assert.equal(numbers[100000], 42);
console.log(JSON.stringify({success: true, inputCharacters: raw.length, heapUsed: process.memoryUsage().heapUsed}));
`;
const child = spawnSync(process.execPath, ['--max-old-space-size=192', '-e', compiled + fixtures], { encoding: 'utf8', timeout: 60_000, maxBuffer: 1024 * 1024 });
assert.equal(child.error, undefined);
assert.equal(child.status, 0, `converter exceeded resource budget or corrupted data (${child.signal}):\n${child.stderr}`);
process.stdout.write(child.stdout);
