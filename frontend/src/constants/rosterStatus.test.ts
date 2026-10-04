import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import {
  ROSTER_STATUS_CODES,
  STATUS_STYLES,
  ATTENDANCE_EMPTY_STYLE,
  getAttendanceCellStyle,
  normalizeStatus,
  statusLabel,
} from './rosterStatus'

const read = (path: string) =>
  readFileSync(fileURLToPath(new URL(path, import.meta.url)), 'utf8')

const tokens = read('../styles/tokens.css')
const lightTheme = tokens.slice(0, tokens.indexOf('.dark {'))
const darkTheme = tokens.slice(tokens.indexOf('.dark {'))

/**
 * Regression guard for the Dark Mode bug where every roster status cell rendered
 * as an empty dark rectangle.
 *
 * The status fills are applied INLINE through a bare `var()`:
 *   style={{ backgroundColor: 'var(--attendance-wfo-bg)' }}
 * so each referenced custom property must resolve to a COMPLETE colour in BOTH
 * themes. A dark value stored as bare "R G B" channels expands to
 * `background-color: 6 59 43`, which is invalid, so the declaration is dropped
 * at computed-value time and the cell falls back to transparent + inherited
 * text. Light Mode stored hex, which is why only Dark Mode broke.
 *
 * These tests pin the cross-file contract between the status map and the token
 * file so a missing or malformed token can never silently blank a cell again.
 */

const varOf = (value: string) => /^var\((--[a-z0-9-]+)\)$/.exec(value)?.[1]

const allStyles: Array<[string, { backgroundColor: string; color: string }]> = [
  ...Object.entries(STATUS_STYLES),
  ['(empty)', ATTENDANCE_EMPTY_STYLE],
]

describe('roster status styles reference theme tokens, never literal colours', () => {
  it('covers every known status code plus the empty cell', () => {
    for (const code of ROSTER_STATUS_CODES) {
      expect(STATUS_STYLES[code], `no style for status "${code}"`).toBeTruthy()
    }
    // ATR is reachable both bare and numbered (ATR1 -> ATR).
    expect(STATUS_STYLES.ATR).toBeTruthy()
    expect(allStyles.length).toBe(ROSTER_STATUS_CODES.length + 1)
  })

  it.each(allStyles)('%s resolves fill and text through var()', (_code, style) => {
    expect(varOf(style.backgroundColor), `${_code} background is not a var()`).toBeTruthy()
    expect(varOf(style.color), `${_code} text is not a var()`).toBeTruthy()
    expect(style.backgroundColor).not.toMatch(/#[0-9a-fA-F]{3,8}/)
    expect(style.color).not.toMatch(/#[0-9a-fA-F]{3,8}/)
  })

  it.each(allStyles)('%s tokens exist in BOTH themes', (_code, style) => {
    for (const token of [varOf(style.backgroundColor)!, varOf(style.color)!]) {
      expect(lightTheme, `${token} missing from :root`).toMatch(
        new RegExp(`${token}\\s*:`),
      )
      expect(darkTheme, `${token} missing from .dark`).toMatch(new RegExp(`${token}\\s*:`))
    }
  })

  it.each(allStyles)('%s dark tokens are complete colours, not bare channels', (_code, style) => {
    for (const token of [varOf(style.backgroundColor)!, varOf(style.color)!]) {
      const m = new RegExp(`${token}\\s*:\\s*([^;]+);`).exec(darkTheme)
      expect(m, `${token} not found in .dark`).toBeTruthy()
      const value = m![1].trim()
      // A bare "6 59 43" is the exact defect this guards against.
      expect(value, `${token} = "${value}" is not a usable bare-var() colour`).toMatch(
        /^(#[0-9a-fA-F]{3,8}|rgba?\([^)]*\))$/,
      )
    }
  })

  it.each(allStyles)('%s light tokens keep their original hex values', (_code, style) => {
    for (const token of [varOf(style.backgroundColor)!, varOf(style.color)!]) {
      const m = new RegExp(`${token}\\s*:\\s*([^;]+);`).exec(lightTheme)
      expect(m![1].trim(), `${token} must remain hex in light mode`).toMatch(/^#[0-9a-fA-F]{6}$/i)
    }
  })

  it('gives each status a distinct dark fill for the statuses the UI shows', () => {
    const shown = ['WO', 'WFO', 'WFH', 'PL', 'SL', 'CO', 'FL', 'HPEH', 'SW OFF', 'SW WK', 'WK WRK', 'HD', 'WX', 'TR', 'ITS', 'WDT', 'ATR']
    const fills = shown.map((c) => varOf(STATUS_STYLES[c].backgroundColor)!)
    expect(new Set(fills).size).toBeGreaterThanOrEqual(11)
    // The empty cell must never be confused with a real status.
    expect(varOf(ATTENDANCE_EMPTY_STYLE.backgroundColor)).not.toBe(fills[0])
  })
})

describe('status normalisation and labelling are unchanged', () => {
  it('normalises case, spacing and numbered attrition codes', () => {
    expect(normalizeStatus('wfo')).toBe('WFO')
    expect(normalizeStatus('  pl ')).toBe('PL')
    expect(normalizeStatus('.')).toBe('')
    expect(normalizeStatus(null)).toBe('')
    expect(normalizeStatus('ATR1')).toBe('ATR')
    expect(normalizeStatus('NOPE')).toBe('')
  })

  it('returns the empty style for blank input', () => {
    expect(getAttendanceCellStyle('')).toBe(ATTENDANCE_EMPTY_STYLE)
    expect(getAttendanceCellStyle('.')).toBe(ATTENDANCE_EMPTY_STYLE)
  })

  it('returns the mapped style for a known code and preserves the object identity', () => {
    expect(getAttendanceCellStyle('WFO')).toBe(STATUS_STYLES.WFO)
    expect(getAttendanceCellStyle('ATR7')).toBe(STATUS_STYLES.ATR)
  })

  it('labels every status code', () => {
    for (const code of ROSTER_STATUS_CODES) {
      expect(statusLabel(code), `no label for ${code}`).not.toBe('')
    }
    expect(statusLabel('ATR2')).toBe('Attrition / Left Team')
  })
})