import { clsx, type ClassValue } from 'clsx'
import { twMerge } from 'tailwind-merge'

/** Tailwind-aware class merge (shadcn convention; Aceternity components use it too). */
export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs))
}
