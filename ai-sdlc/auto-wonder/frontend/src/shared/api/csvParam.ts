// axios serializes arrays as `squadIds[]=1`, which Spring's @RequestParam List<Long> cannot bind.
// A comma-joined value binds natively through Spring's StringToCollectionConverter.
export function csvParam(values?: readonly (number | string)[] | null): string | undefined {
  if (!values || values.length === 0) {
    return undefined;
  }
  return values.join(',');
}
