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
    // Dark HPEH is the muted charcoal/emerald pair: 20 35 30 = #14231E fill,
    // 0 179 136 = #00B388 text. Values are stored as space-separated channels.
    expect(darkTheme).toMatch(/--attendance-hpeh-bg:\s*20 35 30/)
    expect(darkTheme).toMatch(/--attendance-hpeh-text:\s*0 179 136/)
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
