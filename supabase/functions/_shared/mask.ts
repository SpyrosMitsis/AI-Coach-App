// Masked credential hint for the settings UIs: enough of the key to recognise
// it ("sk-an••••3kQx"), never enough to use it.
export function maskKey(key: string): string {
  const k = key.trim();
  if (k.length <= 8) return `${k.slice(0, 2)}••••`;
  return `${k.slice(0, 5)}••••${k.slice(-4)}`;
}
