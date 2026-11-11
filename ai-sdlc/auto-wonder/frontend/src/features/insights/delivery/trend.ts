/** Local linear LOESS; missing samples and partial intervals are never fabricated. */
export function smoothTrend(values: (number | null)[], partial: boolean[] = []): (number | null)[] {
  const points = values.flatMap((y, x) => y == null || partial[x] ? [] : [{ x, y }]);
  if (points.length < 3) return values.map(() => null);
  const neighbors = Math.min(points.length, Math.max(4, Math.ceil(points.length * 0.65)));
  return values.map((value, x) => {
    if (value == null || partial[x]) return null;
    const distances = points.map(p => Math.abs(p.x - x)).sort((a, b) => a - b);
    const radius = distances[neighbors - 1] * 1.00001 || 1;
    let sw = 0, sx = 0, sy = 0, sxx = 0, sxy = 0;
    for (const p of points) {
      const dx = p.x - x, u = Math.min(1, Math.abs(dx) / radius);
      const weight = (1 - u ** 3) ** 3;
      sw += weight; sx += weight * dx; sy += weight * p.y;
      sxx += weight * dx * dx; sxy += weight * dx * p.y;
    }
    const det = sw * sxx - sx * sx;
    return Math.max(0, Math.abs(det) < 1e-9 ? sy / sw : (sy * sxx - sx * sxy) / det);
  });
}
