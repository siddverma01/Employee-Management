export interface RosterStatusMeta {
  code: string
  label: string
}

export const ROSTER_STATUS_CODES = [
  'WO', 'WFO', 'WFH', 'PL', 'SL', 'CO', 'FL', 'HPEH', 'SW OFF', 'SW WK', 'WK WRK', 'HD', 'WX', 'TR', 'ITS', 'WDT', 'ATR',
] as const

export const ROSTER_STATUSES: RosterStatusMeta[] = [
  { code: 'WO', label: 'Week Off' },
  { code: 'WFO', label: 'Work From Office' },
  { code: 'WFH', label: 'Work From Home' },
  { code: 'PL', label: 'Privilege Leave' },
  { code: 'SL', label: 'Sick Leave' },
  { code: 'CO', label: 'Compensatory Off' },
  { code: 'FL', label: 'Furlough' },
  { code: 'HPEH', label: 'HPE Holiday' },
  { code: 'SW OFF', label: 'Swap Off' },
  { code: 'SW WK', label: 'Swap Working' },
  { code: 'WK WRK', label: 'Weekend Working' },
  { code: 'HD', label: 'Half Day' },
  { code: 'WX', label: 'Wellness' },
  { code: 'TR', label: 'Training' },
  { code: 'ITS', label: 'IT Issues' },
  { code: 'WDT', label: 'Working for Different Team' },
  { code: 'ATR', label: 'Attrition / Left Team' },
]

export function statusLabel(code: string | null | undefined): string {
  if (!code) return ''
  const base = ROSTER_STATUSES.find((s) => code === s.code)
  if (base) return base.label
  if (code.startsWith('ATR')) return 'Attrition / Left Team'
  return code
}

export function isRosterStatus(code: string | null | undefined): boolean {
  if (!code) return false
  if (ROSTER_STATUS_CODES.includes(code as (typeof ROSTER_STATUS_CODES)[number])) return true
  return /^ATR\d*$/.test(code)
}

/** CSS class for a status cell; multi-word codes are slugified (SW OFF -> SW-OFF). */
export function rosterStatusClass(code: string | null | undefined): string {
  if (!code) return 'roster-cell-empty'
  const base = code.startsWith('ATR') ? 'ATR' : code
  return `roster-status-${base.replace(/[^A-Za-z0-9]+/g, '-')}`
}

// ---------------------------------------------------------------------------
// Centralized attendance-status color system (single source of truth).
// Used by the attendance-roster matrix cells AND its legend — never redefine
// these colors anywhere else. `getAttendanceCellStyle` is the only entry point.
// ---------------------------------------------------------------------------

export interface AttendanceCellStyle {
  backgroundColor: string
  color: string
  /** Optional subtle 1px border; omitted for flat roster fills. */
  borderColor?: string
}

/**
 * Theme-aware status fills. Values are CSS custom properties declared in
 * src/styles/tokens.css — the SAME var(--attendance-*) tokens are referenced
 * in both themes, and the browser swaps them the instant the `.dark` class
 * toggles on <html>. No render-time theme detection is needed, so the cells,
 * legend, counters and picker re-theme live without any React re-render.
 */
export const STATUS_STYLES: Record<string, AttendanceCellStyle> = Object.freeze({
  WO: { backgroundColor: 'var(--attendance-wo-bg)', color: 'var(--attendance-wo-text)' },
  WFO: { backgroundColor: 'var(--attendance-wfo-bg)', color: 'var(--attendance-wfo-text)' },
  WFH: { backgroundColor: 'var(--attendance-wfh-bg)', color: 'var(--attendance-wfh-text)' },
  PL: { backgroundColor: 'var(--attendance-pl-bg)', color: 'var(--attendance-pl-text)' },
  SL: { backgroundColor: 'var(--attendance-sl-bg)', color: 'var(--attendance-sl-text)' },
  CO: { backgroundColor: 'var(--attendance-co-bg)', color: 'var(--attendance-co-text)' },
  HPEH: { backgroundColor: 'var(--attendance-hpeh-bg)', color: 'var(--attendance-hpeh-text)' },
  FL: { backgroundColor: 'var(--attendance-fl-bg)', color: 'var(--attendance-fl-text)' },
  'SW OFF': { backgroundColor: 'var(--attendance-sw-off-bg)', color: 'var(--attendance-sw-off-text)' },
  'SW WK': { backgroundColor: 'var(--attendance-sw-wk-bg)', color: 'var(--attendance-sw-wk-text)' },
  'WK WRK': { backgroundColor: 'var(--attendance-wk-wrk-bg)', color: 'var(--attendance-wk-wrk-text)' },
  HD: { backgroundColor: 'var(--attendance-hd-bg)', color: 'var(--attendance-hd-text)' },
  WX: { backgroundColor: 'var(--attendance-wx-bg)', color: 'var(--attendance-wx-text)' },
  TR: { backgroundColor: 'var(--attendance-tr-bg)', color: 'var(--attendance-tr-text)' },
  ITS: { backgroundColor: 'var(--attendance-its-bg)', color: 'var(--attendance-its-text)' },
  WDT: { backgroundColor: 'var(--attendance-wdt-bg)', color: 'var(--attendance-wdt-text)' },
  ATR: { backgroundColor: 'var(--attendance-atr-bg)', color: 'var(--attendance-atr-text)' },
})

/** Empty / "." cell — no attendance entry. */
export const ATTENDANCE_EMPTY_STYLE: AttendanceCellStyle = Object.freeze({
  backgroundColor: 'var(--attendance-empty-bg)',
  color: 'var(--attendance-empty-text)',
})

/** Canonical, upper-cased status code ("" when empty/unknown). "ATR1" -> "ATR". */
export function normalizeStatus(code: string | null | undefined): string {
  if (!code) return ''
  const trimmed = String(code)
    .trim()
    .toUpperCase()
    .replace(/\s+/g, ' ')
  if (!trimmed || trimmed === '.') return ''
  if (/^ATR\d*$/.test(trimmed)) return 'ATR'
  return (ROSTER_STATUS_CODES as readonly string[]).includes(trimmed) ? trimmed : ''
}

/** Reusable: single source for every attendance status fill.
 *  Returns `{ backgroundColor, color }` for the matrix, legend and badges.
 *  The values are CSS variables, so they stay current in either theme. */
export function getAttendanceCellStyle(status: string | null | undefined): AttendanceCellStyle {
  const code = normalizeStatus(status)
  if (!code) return ATTENDANCE_EMPTY_STYLE
  return STATUS_STYLES[code]
}