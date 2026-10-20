import { test } from 'node:test';
import assert from 'node:assert/strict';
import { checkResourcePair, findUiLiterals, checkBaseline } from './check-i18n.mjs';
test('resources reject missing keys, interpolation mismatch, and empty or nonstring values', () => {
  assert.equal(checkResourcePair({ save: '保存 {{name}}' }, { save: 'Save {{name}}' }).length, 0);
  for (const other of [{}, { save: 'Save {{other}}' }, { save: '' }, { save: 2 }]) {
    assert.ok(checkResourcePair({ save: '保存 {{name}}' }, other).length);
  }
});
test('parser finds English and Chinese JSX text, attributes, labels and messages', () => {
  const source = `const options = [{label: 'Save'}]; message.error('失败'); const view = <button title="Help" aria-label="帮助">Hello {'世界'}</button>;`;
  assert.deepEqual(findUiLiterals(source, 'Page.tsx').map(x => x.text).sort(), ['Save', '失败', 'Help', '帮助', 'Hello', '世界'].sort());
});
test('comments, imports, routes and technical constants do not count as UI', () => {
  assert.deepEqual(findUiLiterals(`// 中文注释\nimport x from 'thing'; const URL = '/api/users'; const state = 'LOADING'; const css = {color: 'red'};`, 'Page.tsx'), []);
});
test('baseline permits exact occurrences only and catches additions in old files', () => {
  const old = findUiLiterals(`const view = <button>Save</button>;`, 'Page.tsx');
  assert.deepEqual(checkBaseline(old, old), []);
  const added = findUiLiterals(`const view = <><button>Save</button><button>Save</button></>;`, 'Page.tsx');
  assert.equal(checkBaseline(added, old).length, 1);
  assert.equal(checkBaseline(findUiLiterals(`const view = <button>Delete</button>;`, 'Page.tsx'), old).length, 1);
});
test('quoted object property names and technical JSX styles are excluded', () => {
  assert.deepEqual(findUiLiterals(`const menu = {"label": "Save"}; const view = <div style={{fontFamily: 'Arial'}} data-testid="technical" />;`, 'Page.tsx').map(x => x.text), ['Save']);
});
test('templates in messages and Chinese prose fallback constants are checked', () => {
  const found = findUiLiterals('message.success(`Saved ${name}`); const fallback = "操作失败";', 'Page.tsx');
  assert.equal(found.length, 2);
});
test('nested JSX technical attributes are not treated as visible child text', () => {
  assert.deepEqual(findUiLiterals(`const view = <div>{ready && <Dropdown theme="light" placement="bottomLeft" trigger={['click']} overlayClassName="menu" />}</div>;`, 'Page.tsx'), []);
});
test('plural resources follow each locale categories and share interpolation variables', () => {
  assert.deepEqual(checkResourcePair({ items_other: '{{count}} 项' }, { items_one: '{{count}} item', items_other: '{{count}} items' }), []);
  assert.ok(checkResourcePair({ items_other: '{{count}} 项' }, { items_other: '{{count}} items' }).length);
  assert.ok(checkResourcePair({ items_one: '{{count}} 项' }, { items_one: '{{count}} item', items_other: '{{count}} items' }).length);
  assert.ok(checkResourcePair({ items_other: '{{count}} 项' }, { items_one: '{{other}} item', items_other: '{{count}} items' }).length);
});
test('ordinary keys need language pairs even when plural siblings exist', () => {
  const zh = { items: '所有项目', items_other: '{{count}} 项' };
  const en = { items_one: '{{count}} item', items_other: '{{count}} items' };
  assert.deepEqual(checkResourcePair(zh, en), ['resource.items: missing language pair']);
  assert.deepEqual(checkResourcePair({ items_other: '{{count}} 项' }, { ...en, items: 'All items' }), ['resource.items: missing language pair']);
});
test('ordinary interpolation must match independently of plural siblings', () => {
  assert.deepEqual(checkResourcePair(
    { items: '{{owner}} 的项目', items_other: '{{count}} 项' },
    { items: '{{name}} items', items_one: '{{count}} item', items_other: '{{count}} items' },
  ), ['resource.items: interpolation mismatch']);
});
test('natural repetition and ordering do not change interpolation identities', () => {
  assert.deepEqual(checkResourcePair(
    { greeting: '{{name}}，你好 {{name}}，来自 {{place}}' },
    { greeting: 'Hello {{place}}, {{name}}' },
  ), []);
  assert.deepEqual(checkResourcePair(
    { items_other: '{{count}} 项，{{count}} 项' },
    { items_one: '{{count}} item', items_other: '{{count}} items' },
  ), []);
});
test('named and numeric rich component identities must match', () => {
  for (const en of ['Changed <target/>', 'Changed <other/> <target/>', 'Changed <actor/>']) {
    assert.ok(checkResourcePair({ changed: '<actor/> 修改了 <target/>' }, { changed: en }).some(error => error.includes('component')));
  }
  assert.ok(checkResourcePair({ changed: '<0>作者</0> 修改了 <1>目标</1>' }, { changed: '<0>Actor</0> changed <2>target</2>' }).some(error => error.includes('component')));
  assert.deepEqual(checkResourcePair({ changed: '<actor/> 修改了 <target/>，<actor/>' }, { changed: '<target/> changed by <actor/>' }), []);
  assert.deepEqual(checkResourcePair({ help: '<strong>帮助</strong><br/>内容' }, { help: '<p>Help <i>content</i></p>' }), []);
});
test('rich component identity includes plural variants and independent ordinary siblings', () => {
  assert.ok(checkResourcePair(
    { items_other: '<actor/> 有 {{count}} 项' },
    { items_one: '<actor/> has {{count}} item', items_other: '{{count}} items' },
  ).some(error => error.includes('component')));
  assert.ok(checkResourcePair(
    { items: '<actor/> 的项目', items_other: '{{count}} 项' },
    { items: 'All items', items_one: '{{count}} item', items_other: '{{count}} items' },
  ).some(error => error.includes('component')));
});
test('English resource leaves reject Han including supplementary characters', () => {
  for (const text of ['Save 保存', '𠀀', '<strong>帮助</strong>']) {
    assert.ok(checkResourcePair({ help: '帮助' }, { help: text }, 'shared').some(error => error.includes('shared.help (en-US)') && error.includes('Han')));
  }
  assert.deepEqual(checkResourcePair({ help: '帮助' }, { help: 'AutoWonder help' }), []);
});
