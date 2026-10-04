import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

const read = (path: string) => readFileSync(fileURLToPath(new URL(path, import.meta.url)), 'utf8')

const tokens = read('./tokens.css')
const styles = read('../index.css')

const lightTheme = tokens.slice(0, tokens.indexOf('.dark {'))
const darkTheme = tokens.slice(tokens.indexOf('.dark {'))

describe('calendar HPE holiday tag theming', () => {
  it('has readable HPEH colours in light mode', () => {
    expect(lightTheme).toMatch(/--attendance-hpeh-bg:\s*#F0FDF4/)
    expect(lightTheme).toMatch(/--attendance-hpeh-text:\s*#166534/)
  })

  it('has muted, higher-contrast HPEH colours in dark mode', () => {
    expect(darkTheme).toMatch(/--attendance-hpeh-bg:\s*rgb\(\s*6 74 52\s*\)/)
    expect(darkTheme).toMatch(/--attendance-hpeh-text:\s*rgb\(\s*52 211 153\s*\)/)
    // the dark fill is a dark tone, never the bright light-mode fill
    expect(darkTheme).not.toMatch(/--attendance-hpeh-bg:\s*#F0FDF4/)
  })

  it('renders the tag from theme tokens instead of hard-coded colours', () => {
    const tag = styles.match(/\.cal-hpeh-tag\s*\{[^}]*\}/)?.[0] ?? ''
    expect(tag).not.toBe('')
    expect(tag).toContain('var(--attendance-hpeh-bg)')
    expect(tag).toContain('var(--attendance-hpeh-text)')
    expect(tag).not.toMatch(/#[0-9a-fA-F]{3,8}/)
  })

  it('keeps the tag small and non-dominant', () => {
    const tag = styles.match(/\.cal-hpeh-tag\s*\{[^}]*\}/)?.[0] ?? ''
    expect(tag).toContain('font-size: 9px')
  })
})

/* ------------------------------------------------------------------------- *
 * Attendance status palette — the regression that made every roster status
 * cell render as an empty dark rectangle.
 *
 * The bug: dark `--attendance-*` values were stored as bare "R G B" channel
 * triplets while every consumer applies them through a plain `var()` with no
 * rgb() wrapper. `background-color: 6 59 43` is not a valid colour, so the
 * declaration was dropped at computed-value time and the cell fell back to
 * transparent + inherited text. Light Mode stored hex and therefore worked,
 * which is why the two themes looked like different applications.
 *
 * These assertions pin the contract that actually matters: the value must be
 * usable in a bare var(), and the pair must be readable.
 * ------------------------------------------------------------------------- */
describe('attendance status tokens are valid bare-var() colours in BOTH themes', () => {
  const tokensOf = (block: string) => {
    const out: Record<string, string> = {}
    for (const m of block.matchAll(/(--attendance-[a-z0-9-]+)\s*:\s*([^;]+);/g)) {
      out[m[1]] = m[2].trim()
    }
    return out
  }
  const light = tokensOf(lightTheme)
  const dark = tokensOf(darkTheme)

  const channels = (v: string) => {
    const m = /^rgb\(\s*(\d+)\s+(\d+)\s+(\d+)\s*\)$/.exec(v)
    return m ? [Number(m[1]), Number(m[2]), Number(m[3])] : null
  }
  const hexToRgb = (v: string) => {
    const m = /^#([0-9a-f]{6})$/i.exec(v)
    if (!m) return null
    const n = parseInt(m[1], 16)
    return [(n >> 16) & 255, (n >> 8) & 255, n & 255]
  }
  const rgbOf = (v: string) => channels(v) ?? hexToRgb(v)

  const lum = ([r, g, b]: number[]) => {
    const f = (x: number) => (x / 255 <= 0.03928 ? x / 255 / 12.92 : (((x / 255) + 0.055) / 1.055) ** 2.4)
    return 0.2126 * f(r) + 0.7152 * f(g) + 0.0722 * f(b)
  }
  const contrast = (a: number[], b: number[]) => {
    const [hi, lo] = [lum(a), lum(b)].sort((x, y) => y - x)
    return (hi + 0.05) / (lo + 0.05)
  }

  const names = Object.keys(light).filter((k) => k.endsWith('-bg'))
  const suffixes = ['-bg', '-text']

  it('declares the same complete set of tokens in both themes', () => {
    expect(names.length).toBeGreaterThanOrEqual(18)
    for (const bg of names) {
      const code = bg.replace('--attendance-', '').replace('-bg', '')
      for (const suffix of suffixes) {
        const key = `--attendance-${code}${suffix}`
        expect(light[key], `${key} missing from :root`).toBeTruthy()
        expect(dark[key], `${key} missing from .dark`).toBeTruthy()
      }
    }
  })

  it.each(names)('%s is a complete colour in .dark, not bare channels', (bg) => {
    const code = bg.replace('--attendance-', '').replace('-bg', '')
    // A bare "6 59 43" expands to `background-color: 6 59 43` → invalid → dropped.
    for (const suffix of suffixes) {
      const value = dark[`--attendance-${code}${suffix}`]
      expect(value, `${code}${suffix} = "${value}"`).toMatch(/^(#[0-9a-fA-F]{3,8}|rgba?\([^)]*\))$/)
    }
  })

  it.each(names)('%s text meets WCAG AA against its own fill in .dark', (bg) => {
    const code = bg.replace('--attendance-', '').replace('-bg', '')
    const bgRgb = rgbOf(dark[bg])!
    const textRgb = rgbOf(dark[`--attendance-${code}-text`])!
    expect(
      contrast(textRgb, bgRgb),
      `${code}: text ${dark[`--attendance-${code}-text`]} on ${dark[bg]}`,
    ).toBeGreaterThanOrEqual(4.5)
  })

  it('gives each status a dark fill that is actually distinguishable from the dark row', () => {
    // The roster row surface in dark is around #121519; a fill at or below the
    // row luminance is invisible regardless of its text colour.
    const row = [0x12, 0x15, 0x19]
    for (const bg of names) {
      if (bg.includes('empty')) continue // empty cells are intentionally subtle
      const value = rgbOf(dark[bg])!
      expect(lum(value), `${dark[bg]} is not lighter than the dark row`).toBeGreaterThan(lum(row))
    }
  })

  it('never reuses the light-mode fill in dark mode', () => {
    for (const bg of names) {
      expect(dark[bg], `${bg} is identical in both themes`).not.toBe(light[bg])
    }
  })

  it('preserves the light palette unchanged (hex, and not the dark tints)', () => {
    // Light Mode is the approved design; these are its exact values.
    expect(light['--attendance-wfo-bg']).toBe('#86EFAC')
    expect(light['--attendance-wo-bg']).toBe('#E5E7EB')
    expect(light['--attendance-co-bg']).toBe('#DBEAFE')
    expect(light['--attendance-sl-bg']).toBe('#FEF3C7')
    expect(light['--attendance-empty-bg']).toBe('#F7F8FA')
    for (const bg of names) {
      expect(light[bg], `${bg} must stay a hex value in light`).toMatch(/^#[0-9a-fA-F]{6}$/)
    }
  })

  it('exposes a distinct fill per status family so cells stay distinguishable', () => {
    const fills = new Set(names.map((bg) => dark[bg]))
    // 17 statuses collapse into a handful of semantic families (e.g. SL/ITS
    // share rose, CO/TR share blue) — but no status may fall back to the
    // generic row or to another hue entirely.
    expect(fills.size).toBeGreaterThanOrEqual(11)
    expect(dark['--attendance-empty-bg']).not.toBe(dark['--attendance-wo-bg'])
  })
})
