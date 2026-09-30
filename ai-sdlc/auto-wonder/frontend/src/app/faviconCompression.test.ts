import { compactFavicon } from './favicon';

it('keeps small originals and shrinks large logos proportionally on a transparent canvas', async () => {
  const fetchMock = vi.fn();
  vi.stubGlobal('fetch', fetchMock);
  const drawImage = vi.fn();
  const context = vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue({ drawImage } as unknown as CanvasRenderingContext2D);
  const encoded = 'data:image/png;base64,c21hbGw=';
  const encode = vi.spyOn(HTMLCanvasElement.prototype, 'toDataURL').mockReturnValue(encoded);
  const previousDecode = HTMLImageElement.prototype.decode;
  const decode = vi.fn().mockResolvedValue(undefined);
  HTMLImageElement.prototype.decode = decode;
  vi.spyOn(HTMLImageElement.prototype, 'naturalWidth', 'get').mockReturnValue(800);
  vi.spyOn(HTMLImageElement.prototype, 'naturalHeight', 'get').mockReturnValue(400);
  const createObjectURL = vi.fn(() => 'blob:logo');
  const revokeObjectURL = vi.fn();
  const previousCreate = URL.createObjectURL;
  const previousRevoke = URL.revokeObjectURL;
  URL.createObjectURL = createObjectURL;
  URL.revokeObjectURL = revokeObjectURL;
  try {
    const controller = new AbortController();
    fetchMock.mockResolvedValue({ ok: true, blob: async () => ({ size: 32 * 1024 }) });
    expect(await compactFavicon('/logo.svg?v=1', controller.signal)).toBe('/logo.svg?v=1');
    expect(decode).not.toHaveBeenCalled();
    fetchMock.mockResolvedValue({ ok: true, blob: async () => ({ size: 32 * 1024 + 1 }) });
    expect(await compactFavicon('/logo?v=2', controller.signal)).toBe(encoded);
    expect(drawImage).toHaveBeenCalledWith(expect.any(HTMLImageElement), 0, 16, 64, 32);
    expect(encode).toHaveBeenCalledWith('image/png');
    const canvas = context.mock.instances[0] as unknown as HTMLCanvasElement;
    expect([canvas.width, canvas.height]).toEqual([64, 64]);
    expect(revokeObjectURL).toHaveBeenCalledWith('blob:logo');
    encode.mockReturnValue('data:image/png;base64,' + 'a'.repeat(40 * 1024));
    expect(await compactFavicon('/logo?v=3', controller.signal)).toBe('/logo?v=3');
  } finally {
    HTMLImageElement.prototype.decode = previousDecode;
    URL.createObjectURL = previousCreate;
    URL.revokeObjectURL = previousRevoke;
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  }
});
