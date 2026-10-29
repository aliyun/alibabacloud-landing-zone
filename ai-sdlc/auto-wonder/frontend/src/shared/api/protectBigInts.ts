// Convert integer literals exceeding Number.MAX_SAFE_INTEGER to strings before parsing,
// so their precision survives JSON.parse. Must be string-aware: a bare regex over the raw
// text also matches digits inside string values (e.g. escaped JSON in a contentMd field),
// which corrupts the payload and makes the whole response unparseable.
export function protectBigInts(text: string): string {
  // Preserve untouched text as slices. Small replacement segments are coalesced
  // in bounded batches so dense numeric payloads do not create one array entry
  // (or a long rope) per source character.
  const chunks: string[] = [];
  let parts: string[] = [];
  let partLength = 0;
  let copiedThrough = 0;
  const append = (part: string) => {
    if (!part) return;
    parts.push(part);
    partLength += part.length;
    if (parts.length >= 256 || partLength >= 64 * 1024) {
      chunks.push(parts.join(''));
      parts = [];
      partLength = 0;
    }
  };
  let inString = false;
  for (let i = 0; i < text.length; i++) {
    const ch = text[i];
    if (inString) {
      if (ch === '\\') {
        i++;
      } else if (ch === '"') {
        inString = false;
      }
      continue;
    }
    if (ch === '"') {
      inString = true;
      continue;
    }
    // Outside a string, digits can only be part of a number value (JSON keys are quoted).
    if (ch === '-' || (ch >= '0' && ch <= '9')) {
      let j = i;
      if (text[j] === '-') j++;
      const intStart = j;
      while (j < text.length && text[j] >= '0' && text[j] <= '9') j++;
      const intDigits = j - intStart;
      let isInteger = true;
      if (text[j] === '.') {
        isInteger = false;
        j++;
        while (j < text.length && text[j] >= '0' && text[j] <= '9') j++;
      }
      if (text[j] === 'e' || text[j] === 'E') {
        isInteger = false;
        j++;
        if (text[j] === '+' || text[j] === '-') j++;
        while (j < text.length && text[j] >= '0' && text[j] <= '9') j++;
      }
      const token = text.slice(i, j);
      if (isInteger && intDigits >= 16 && Number(token) > Number.MAX_SAFE_INTEGER) {
        append(text.slice(copiedThrough, i));
        append(`"${token}"`);
        copiedThrough = j;
      }
      i = j - 1;
      continue;
    }
  }
  if (copiedThrough === 0) return text;
  append(text.slice(copiedThrough));
  if (parts.length) chunks.push(parts.join(''));
  return chunks.join('');
}
