import type { AttendanceCellStyle } from './rosterStatus'
import { getHolidayCellStyle, getHolidayDisplayInfo } from './holidayStatus'

/**
 * Single source of truth for how a calendar entry is identified and coloured.
 *
 * The legend and the event bars both read from `CALENDAR_CATEGORIES`, so a
 * category can never be one colour in the legend and another in the grid.
 *
 * `AttendanceCellStyle.borderColor` carries a subtle 1px border so event bars
 * stay legible on soft fills without a harsh outline.
 */
export interface CalendarCategory {
  /** Stable key used for lookups and React keys. */
  key: string
  /** Full user-facing name, shown in the legend and the details modal. */
  label: string
  /** Short badge text shown inside a compact event bar ('' when not needed). */
  compactLabel: string
  /** Backend `CalendarEvent.kind`, for the non-holiday categories. */
  eventKind?: string
  /** Resolved holiday display type, for the holiday categories. */
  holidayType?: string
  style: AttendanceCellStyle
  /** Tailwind classes for the small colour square shown in the legend. */
  swatchClass: string
}

const CATEGORY_STYLES = {
  LEAVE: {
    backgroundColor: 'var(--cal-leave-bg)',
    color: 'var(--cal-leave-text)',
    borderColor: 'var(--cal-leave-border)',
  },
  COMP_OFF: {
    backgroundColor: 'var(--cal-co-bg)',
    color: 'var(--cal-co-text)',
    borderColor: 'var(--cal-co-border)',
  },
  BIRTHDAY: {
    backgroundColor: 'var(--cal-bday-bg)',
    color: 'var(--cal-bday-text)',
    borderColor: 'var(--cal-bday-border)',
  },
  EVENT: {
    backgroundColor: 'var(--cal-event-bg)',
    color: 'var(--cal-event-text)',
    borderColor: 'var(--cal-event-border)',
  },
} as const satisfies Record<string, AttendanceCellStyle>

export const EMPTY_CALENDAR_STYLE: AttendanceCellStyle = Object.freeze({
  backgroundColor: '',
  color: '',
  borderColor: '',
})

/**
 * The six categories the calendar legend advertises, in display order.
 *
 * Holidays are split into two explicit entries (HPE Holiday / US Holiday)
 * rather than one generic "Holiday" swatch, so the legend always explains
 * which holiday a given bar is. The underlying stored type may still be
 * `PUBLIC` — the holiday categories are resolved through
 * `resolveHolidayDisplayType`, never by a UI-side guess.
 */
export const CALENDAR_CATEGORIES: readonly CalendarCategory[] = Object.freeze([
  {
    key: 'LEAVE',
    label: 'Leave',
    compactLabel: '',
    eventKind: 'LEAVE',
    style: CATEGORY_STYLES.LEAVE,
    swatchClass: 'bg-amber-400 border border-amber-300/40',
  },
  {
    key: 'COMP_OFF',
    label: 'Comp Off',
    compactLabel: '',
    eventKind: 'COMP_OFF',
    style: CATEGORY_STYLES.COMP_OFF,
    swatchClass: 'bg-slate-400 border border-slate-300/40',
  },
  {
    key: 'HPE_HOLIDAY',
    label: 'Company Holiday',
    compactLabel: 'HPEH',
    holidayType: 'HPE_HOLIDAY',
    style: getHolidayCellStyle('HPE_HOLIDAY'),
    swatchClass: 'bg-emerald-500 border border-emerald-400/40',
  },
  {
    key: 'US',
    label: 'US Holiday',
    compactLabel: 'US',
    holidayType: 'US',
    style: getHolidayCellStyle('US'),
    swatchClass: 'bg-sky-500 border border-sky-400/40',
  },
  {
    key: 'BIRTHDAY',
    label: 'Birthday',
    compactLabel: '',
    eventKind: 'BIRTHDAY',
    style: CATEGORY_STYLES.BIRTHDAY,
    swatchClass: 'bg-pink-400 border border-pink-300/40',
  },
  {
    key: 'EVENT',
    label: 'Company Event',
    compactLabel: '',
    eventKind: 'EVENT',
    style: CATEGORY_STYLES.EVENT,
    swatchClass: 'bg-purple-500 border border-purple-400/40',
  },
] as const)

/** Style for a non-holiday `CalendarEvent.kind`. Unknown kinds render unstyled. */
export function getCalendarEventKindStyle(kind: string | null | undefined): AttendanceCellStyle {
  if (!kind) return EMPTY_CALENDAR_STYLE
  return CALENDAR_CATEGORIES.find((c) => c.eventKind === kind)?.style ?? EMPTY_CALENDAR_STYLE
}

/** Full user-facing category name for a non-holiday `CalendarEvent.kind`. */
export function calendarEventKindLabel(kind: string | null | undefined): string {
  if (!kind) return ''
  return CALENDAR_CATEGORIES.find((c) => c.eventKind === kind)?.label ?? kind
}

/** The legend entry for a resolved holiday display type (e.g. 'HPE_HOLIDAY'). */
export function holidayCategory(holidayType: string): CalendarCategory | undefined {
  return CALENDAR_CATEGORIES.find((c) => c.holidayType === holidayType)
}

export interface CalendarEntryDisplay {
  /** Full category name, e.g. "HPE Holiday" / "US Holiday" / "Leave". */
  label: string
  /** Short badge text, e.g. "HPEH" / "US"; empty when the bar needs no badge. */
  compactLabel: string
  style: AttendanceCellStyle
  isHoliday: boolean
}

const EMPTY_DISPLAY: CalendarEntryDisplay = Object.freeze({
  label: '',
  compactLabel: '',
  style: EMPTY_CALENDAR_STYLE,
  isHoliday: false,
})

/**
 * Resolve how one calendar entry should be labelled and coloured.
 *
 * Holidays go through `resolveHolidayDisplayType`, so a stored `PUBLIC` row with
 * `country = "US"` is reported as "US Holiday" and a stored `HPE_HOLIDAY` row as
 * "HPE Holiday" — the same mapping the legend advertises.
 */
export function getCalendarEntryDisplay(event: {
  kind: string
  extra?: Record<string, unknown> | null
}): CalendarEntryDisplay {
  if (event.kind === 'HOLIDAY') {
    const info = getHolidayDisplayInfo(event)
    return {
      label: info.label,
      compactLabel: info.compactLabel,
      style: info.style,
      isHoliday: true,
    }
  }
  const category = CALENDAR_CATEGORIES.find((c) => c.eventKind === event.kind)
  if (!category) return { ...EMPTY_DISPLAY, label: event.kind }
  return {
    label: category.label,
    compactLabel: category.compactLabel,
    style: category.style,
    isHoliday: false,
  }
}
