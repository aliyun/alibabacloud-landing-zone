// Small SVGs and icons keep their original format and transparency.
const MAX_ICON_BYTES = 32 * 1024;

export async function compactFavicon(url: string, signal: AbortSignal): Promise<string> {
  const response = await fetch(url, { signal });
  if (!response.ok) return url;
  const blob = await response.blob();
  if (blob.size <= MAX_ICON_BYTES || signal.aborted) return url;

  const source = URL.createObjectURL(blob);
  try {
    const image = new Image();
    image.src = source;
    await image.decode();
    if (signal.aborted || !image.naturalWidth || !image.naturalHeight) return url;
    const canvas = document.createElement('canvas');
    canvas.width = canvas.height = 64;
    const context = canvas.getContext('2d');
    if (!context) return url;
    const scale = Math.min(64 / image.naturalWidth, 64 / image.naturalHeight, 1);
    const width = image.naturalWidth * scale;
    const height = image.naturalHeight * scale;
    context.drawImage(image, (64 - width) / 2, (64 - height) / 2, width, height);
    const compact = canvas.toDataURL('image/png');
    return compact.startsWith('data:image/png;base64,') && compact.length < blob.size ? compact : url;
  } finally {
    URL.revokeObjectURL(source);
  }
}
