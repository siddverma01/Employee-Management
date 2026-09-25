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
    expect(lightTheme).toMatch(/--attendance-hpeh-bg:\s*#FDE68A/)
    expect(lightTheme).toMatch(/--attendance-hpeh-text:\s*#78350F/)
  })

  it('has muted, higher-contrast HPEH colours in dark mode', () => {
    expect(darkTheme).toMatch(/--attendance-hpeh-bg:\s*#463817/)
    expect(darkTheme).toMatch(/--attendance-hpeh-text:\s*#E7CE70/)
    // the dark fill is a dark tone, never the bright light-mode amber
    expect(darkTheme).not.toMatch(/--attendance-hpeh-bg:\s*#FDE68A/)
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
