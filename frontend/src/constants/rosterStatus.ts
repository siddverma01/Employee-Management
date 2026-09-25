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