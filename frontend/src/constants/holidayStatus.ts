import type { AttendanceCellStyle } from './rosterStatus'

export interface HolidayStatusMeta {
  type: string
  label: string
  compactLabel: string
}

export const HOLIDAY_STATUSES: HolidayStatusMeta[] = [
  { type: 'HPE_HOLIDAY', label: 'HPE Holiday', compactLabel: 'HPEH' },
  { type: 'US', label: 'US Holiday', compactLabel: 'US' },
  { type: 'PUBLIC', label: 'Public Holiday', compactLabel: 'PH' },
  { type: 'OPTIONAL', label: 'Optional Holiday', compactLabel: 'OH' },
  { type: 'OBSERVED', label: 'Observed Holiday', compactLabel: 'OB' },
]

export function holidayLabel(type: string | null | undefined): string {
  if (!type) return ''
  const base = HOLIDAY_STATUSES.find((s) => type === s.type)
  if (base) return base.label
  return type
}

export function holidayCompactLabel(type: string | null | undefined): string {
  if (!type) return ''
  const base = HOLIDAY_STATUSES.find((s) => type === s.type)
  if (base) return base.compactLabel
  return type.length > 2 ? type.slice(0, 2).toUpperCase() : type.toUpperCase()
}

export function getHolidayCellStyle(type: string | null | undefined): AttendanceCellStyle {
  const normalized = normalizeHolidayType(type)
  return HOLIDAY_STYLES[normalized] ?? HOLIDAY_EMPTY_STYLE
}

export function normalizeHolidayType(type: string | null | undefined): string {
  if (!type) return ''
  const trimmed = String(type).trim().toUpperCase()
  if (!trimmed) return ''
  const validTypes = HOLIDAY_STATUSES.map((s) => s.type)
  return validTypes.includes(trimmed) ? trimmed : ''
}

export const HOLIDAY_EMPTY_STYLE: AttendanceCellStyle = Object.freeze({
  backgroundColor: 'var(--attendance-empty-bg)',
  color: 'var(--attendance-empty-text)',
})

export const HOLIDAY_STYLES: Record<string, AttendanceCellStyle> = Object.freeze({
  HPE_HOLIDAY: {
    backgroundColor: 'var(--holiday-hpe-bg)',
    color: 'var(--holiday-hpe-text)',
    borderColor: 'var(--holiday-hpe-border)',
  },
  US: {
    backgroundColor: 'var(--holiday-us-bg)',
    color: 'var(--holiday-us-text)',
    borderColor: 'var(--holiday-us-border)',
  },
  PUBLIC: {
    backgroundColor: 'var(--holiday-public-bg)',
    color: 'var(--holiday-public-text)',
    borderColor: 'var(--holiday-public-border)',
  },
  OPTIONAL: {
    backgroundColor: 'var(--holiday-optional-bg)',
    color: 'var(--holiday-optional-text)',
    borderColor: 'var(--holiday-optional-border)',
  },
  OBSERVED: {
    backgroundColor: 'var(--holiday-observed-bg)',
    color: 'var(--holiday-observed-text)',
    borderColor: 'var(--holiday-observed-border)',
  },
})

/**
 * Determine the effective holiday type for display based on the holiday's
 * master definition. US holidays are identified by country=US and type=PUBLIC.
 * HPE holidays are identified by holidayType=HPE_HOLIDAY.
 */
export function resolveHolidayDisplayType(holiday: { holidayType?: string; country?: string; applicableLocations?: string }): string {
  if (holiday.holidayType === 'HPE_HOLIDAY') return 'HPE_HOLIDAY'
  if (holiday.country === 'US' && holiday.holidayType === 'PUBLIC') return 'US'
  if (holiday.applicableLocations === 'US') return 'US'
  return holiday.holidayType ?? 'PUBLIC'
}

export interface HolidayDisplayInfo {
  label: string
  compactLabel: string
  style: AttendanceCellStyle
}

export function getHolidayDisplayInfo(event: { kind: string; extra?: Record<string, unknown> | null }): HolidayDisplayInfo {
  if (event.kind === 'HOLIDAY') {
    const displayType = resolveHolidayDisplayType(event.extra as any)
    return {
      label: holidayLabel(displayType),
      compactLabel: holidayCompactLabel(displayType),
      style: getHolidayCellStyle(displayType),
    }
  }
  return { label: '', compactLabel: '', style: HOLIDAY_EMPTY_STYLE }
}