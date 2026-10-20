import ts from 'typescript';
import { readFileSync, readdirSync } from 'node:fs';
import { dirname, relative, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

export function checkResourcePair(left, right, namespace = 'resource') {
  const errors = [];
  function flatten(value, prefix = '', result = {}) {
    if (value && typeof value === 'object' && !Array.isArray(value)) {
      for (const [key, child] of Object.entries(value)) flatten(child, prefix ? `${prefix}.${key}` : key, result);
    } else result[prefix] = value;
    return result;
  }
  const a = flatten(left), b = flatten(right);
  const variables = value => [...new Set([...value.matchAll(/\{\{\s*([^{}]+?)\s*\}\}/g)].map(match => match[1].split(',')[0].trim()))].sort().join('|');
  // Compare application placeholders, not formatting or translated markup order.
  const basicFormatting = new Set(['br', 'strong', 'i', 'p']);
  const components = value => [...new Set([...value.matchAll(/<\/?([A-Za-z][\w.-]*|\d+)(?:\s[^<>]*?)?\/?>/g)]
    .map(match => match[1]).filter(name => !basicFormatting.has(name)))].sort().join('|');
  const pluralSuffix = /_(zero|one|two|few|many|other)$/;
  const keys = new Set([...Object.keys(a), ...Object.keys(b)]);
  for (const [locale, dictionary] of [['zh-CN', a], ['en-US', b]]) {
    for (const [key, value] of Object.entries(dictionary)) {
      if (typeof value !== 'string' || !value.trim()) errors.push(`${namespace}.${key} (${locale}): translation must be a nonempty string`);
      else if (locale === 'en-US' && /\p{Script=Han}/u.test(value)) errors.push(`${namespace}.${key} (${locale}): translation contains Han text`);
    }
  }
  // ponytail: sibling-key scans are quadratic; index plural bases if large namespaces slow this gate.
  for (const key of new Set([...keys].map(key => key.replace(pluralSuffix, '')))) {
    const variants = [...keys].filter(candidate => pluralSuffix.test(candidate) && candidate.replace(pluralSuffix, '') === key);
    if (key in a || key in b) {
      if (!(key in a) || !(key in b)) errors.push(`${namespace}.${key}: missing language pair`);
      else if (typeof a[key] === 'string' && typeof b[key] === 'string') {
        if (variables(a[key]) !== variables(b[key])) errors.push(`${namespace}.${key}: interpolation mismatch`);
        if (components(a[key]) !== components(b[key])) errors.push(`${namespace}.${key}: component placeholder mismatch`);
      }
    }
    if (!variants.length) continue;
    const interpolation = new Set();
    const componentNames = new Set();
    for (const [locale, dictionary] of [['zh-CN', a], ['en-US', b]]) {
      const required = new Intl.PluralRules(locale).resolvedOptions().pluralCategories;
      for (const category of required) {
        if (!(`${key}_${category}` in dictionary)) errors.push(`${namespace}.${key} (${locale}): missing plural category ${category}`);
      }
      for (const variant of variants) {
        if (!(variant in dictionary)) continue;
        const category = variant.match(pluralSuffix)[1];
        if (category !== 'zero' && !required.includes(category)) errors.push(`${namespace}.${variant} (${locale}): unreachable plural category`);
        if (typeof dictionary[variant] === 'string') {
          interpolation.add(variables(dictionary[variant]));
          componentNames.add(components(dictionary[variant]));
        }
      }
    }
    if (interpolation.size > 1) errors.push(`${namespace}.${key}: interpolation mismatch across plural variants`);
    if (componentNames.size > 1) errors.push(`${namespace}.${key}: component placeholder mismatch across plural variants`);
  }
  return errors;
}
const visibleProperties = new Set(['label', 'title', 'placeholder', 'description', 'content', 'text', 'tooltip', 'emptyText', 'okText', 'cancelText', 'confirmTitle', 'message', 'help', 'extra', 'suffix', 'prefix', 'addonBefore', 'addonAfter', 'notFoundContent', 'alt', 'aria-label', 'aria-description', 'aria-valuetext']);
const technicalProperties = new Set(['key', 'type', 'value', 'code', 'status', 'name', 'path', 'url', 'href', 'to', 'className', 'id', 'role', 'data-testid', 'style', 'color', 'background', 'fontFamily', 'contentType']);
function propertyName(node, source) { return node && (ts.isIdentifier(node) || ts.isStringLiteral(node)) ? node.text : node?.getText(source); }
export function findUiLiterals(text, file = 'source.tsx') {
  const source = ts.createSourceFile(file, text, ts.ScriptTarget.Latest, true, file.endsWith('.tsx') ? ts.ScriptKind.TSX : ts.ScriptKind.TS);
  const found = [];
  function site(node) {
    if ((ts.isPropertyAssignment(node.parent) || ts.isJsxAttribute(node.parent)) && node.parent.name === node) return false;
    for (let parent = node.parent; parent; parent = parent.parent) {
      if (ts.isImportDeclaration(parent) || ts.isExportDeclaration(parent) || ts.isLiteralTypeNode(parent)) return false;
      if (ts.isJsxAttribute(parent) || ts.isPropertyAssignment(parent)) {
        const name = propertyName(parent.name, source);
        if (technicalProperties.has(name) || name?.startsWith('data-')) return false;
        if (visibleProperties.has(name)) return true;
        if (ts.isJsxAttribute(parent)) return /[\u3400-\u9fff]/u.test(node.text ?? '');
      }
      if (ts.isJsxExpression(parent) && (ts.isJsxElement(parent.parent) || ts.isJsxFragment(parent.parent))) return true;
      if (ts.isCallExpression(parent)) {
        const callee = parent.expression.getText(source);
        if (/^(?:message|notification|Modal|modal)\.(?:success|error|warning|info|open|confirm)$/.test(callee)) return true;
        if (/^(?:t|i18n\.t)$/.test(callee)) return false;
      }
    }
    return /[\u3400-\u9fff]/u.test(node.text ?? '');
  }
  function visit(node) {
    if (ts.isJsxText(node) || ts.isStringLiteral(node) || ts.isNoSubstitutionTemplateLiteral(node) || ts.isTemplateExpression(node)) {
      const literal = ts.isJsxText(node) ? node.text.replace(/\s+/g, ' ').trim() : ts.isTemplateExpression(node) ? node.getText(source) : node.text;
      if (literal && (ts.isJsxText(node) || site(node))) {
        const line = source.getLineAndCharacterOfPosition(node.getStart(source)).line + 1;
        found.push({ file, kind: ts.isJsxText(node) ? 'jsx-text' : 'literal', text: literal, line });
      }
      if (ts.isTemplateExpression(node)) return;
    }
    ts.forEachChild(node, visit);
  }
  visit(source);
  return found;
}
const fingerprint = item => JSON.stringify([item.file, item.kind, item.text]);
export function checkBaseline(literals, baseline) {
  const remaining = new Map();
  for (const item of baseline) { const key = fingerprint(item); remaining.set(key, (remaining.get(key) ?? 0) + 1); }
  return literals.filter(item => { const key = fingerprint(item), count = remaining.get(key) ?? 0; if (!count) return true; remaining.set(key, count - 1); return false; });
}
function files(directory) {
  return readdirSync(directory, { withFileTypes: true }).flatMap(entry => entry.isDirectory() ? files(resolve(directory, entry.name)) : [resolve(directory, entry.name)]);
}
export function scanSource(root) {
  return files(resolve(root, 'src')).filter(file => /\.tsx?$/.test(file) && !/\.(?:test|d)\.tsx?$/.test(file) && !file.includes('/src/test/'))
    .flatMap(file => findUiLiterals(readFileSync(file, 'utf8'), relative(root, file)));
}
export function checkProject(root = resolve(dirname(fileURLToPath(import.meta.url)), '..')) {
  const errors = [];
  const locales = resolve(root, 'src/shared/i18n/locales');
  const left = readdirSync(resolve(locales, 'zh-CN')).filter(name => name.endsWith('.json'));
  const right = readdirSync(resolve(locales, 'en-US')).filter(name => name.endsWith('.json'));
  for (const name of new Set([...left, ...right])) {
    if (!left.includes(name) || !right.includes(name)) { errors.push(`${name}: missing namespace pair`); continue; }
    try { errors.push(...checkResourcePair(JSON.parse(readFileSync(resolve(locales, 'zh-CN', name), 'utf8')), JSON.parse(readFileSync(resolve(locales, 'en-US', name), 'utf8')), name)); }
    catch (error) { errors.push(`${name}: ${error.message}`); }
  }
  const baseline = JSON.parse(readFileSync(resolve(root, 'scripts/i18n-legacy-baseline.json'), 'utf8'));
  for (const item of checkBaseline(scanSource(root), baseline)) errors.push(`${item.file}:${item.line}: hardcoded UI text ${JSON.stringify(item.text)}`);
  return errors;
}
if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  const errors = checkProject();
  if (errors.length) { console.error(errors.join('\n')); process.exitCode = 1; }
  else console.log('i18n resource pairs and UI literals checked.');
}
