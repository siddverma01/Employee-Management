export interface ChartTheme {
  grid: string
  tick: string
  accent: string
  fallback: string
}

let cached: ChartTheme | null = null

/** Resolve HPE token values for use by SVG chart libraries (recharts). */
export const chartTheme = (): ChartTheme => {
  if (cached) return cached
  const root = getComputedStyle(document.documentElement)
  const pick = (token: string, fallback: string) => {
    const v = root.getPropertyValue(token).trim()
    const parts = v.split(/\s+/).map(Number)
    return parts.length === 3 && parts.every(Number.isFinite) ? `rgb(${parts.join(', ')})` : v || fallback
  }
  cached = {
    grid: pick('--hpe-surface-200', '#e2e4e7'),
    tick: pick('--hpe-surface-500', '#6a7178'),
    accent: pick('--hpe-brand-500', '#068667'),
    fallback: pick('--hpe-surface-400', '#989ea6'),
  }
  return cached
}