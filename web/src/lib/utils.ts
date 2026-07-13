import { clsx, type ClassValue } from "clsx";
import { twMerge } from "tailwind-merge";

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
}

export function fmtCost(usd: number | null | undefined): string {
  if (usd == null) return "-";
  if (usd === 0) return "$0.00";
  if (usd < 0.001) return "<$0.001";
  return `$${usd.toFixed(usd < 0.01 ? 4 : 3)}`;
}
