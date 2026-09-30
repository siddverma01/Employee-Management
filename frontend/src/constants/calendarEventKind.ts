import type { LucideIcon } from 'lucide-react'
import { CalendarClock, Cake, PartyPopper, Sun, Users } from 'lucide-react'
import type { AttendanceCellStyle } from './rosterStatus'
import type { UpcomingEventCategory } from '@/types'
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
    label: 'HPE Holiday',
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

/**
 * Display metadata for the employee "Upcoming Events" feed.
 *
 * This is a separate axis from `CALENDAR_CATEGORIES`, which colours the month
 * grid: the grid splits holidays by applicability (HPE / US) and folds all
 * meetings into one "Company Event" bar, whereas the feed talks about a single
 * row in the future — so a holiday is one entry and each *kind* of meeting is
 * its own entry. Keeping them in one file stops the two views from drifting
 * apart when the backend `EventType` gains a value.
 */
export interface UpcomingEventCategoryMeta {
  /** Full name shown on the feed row and in the create form. */
  label: string
  /** Short badge text for the row's right-hand tag. */
  compactLabel: string
  /** Tailwind classes for the row's leading icon chip. */
  chipClass: string
  /** Row icon component, so each category reads at a glance. */
  icon: LucideIcon
}

/** Every `UpcomingEventCategory`, in the order the feed renders them. */
export const UPCOMING_EVENT_CATEGORIES: Record<UpcomingEventCategory, UpcomingEventCategoryMeta> = {
  HOLIDAY: {
    label: 'Upcoming Holiday',
    compactLabel: 'Holiday',
    chipClass: 'bg-sky-100 text-sky-700 dark:bg-sky-500/15 dark:text-sky-300',
    icon: Sun,
  },
  BIRTHDAY: {
    label: 'Birthday',
    compactLabel: 'Birthday',
    chipClass: 'bg-pink-100 text-pink-700 dark:bg-pink-500/15 dark:text-pink-300',
    icon: Cake,
  },
  OFFICE_MEETING: {
    label: 'Upcoming Office Meeting',
    compactLabel: 'Office',
    chipClass: 'bg-brand-100 text-brand-700 dark:bg-brand-500/15 dark:text-brand-300',
    icon: Users,
  },
  SCHEDULED_MEETING: {
    label: 'Upcoming Scheduled Meeting',
    compactLabel: 'Meeting',
    chipClass: 'bg-indigo-100 text-indigo-700 dark:bg-indigo-500/15 dark:text-indigo-300',
    icon: CalendarClock,
  },
  CUSTOMER_REMOTE_SESSION: {
    label: 'Upcoming Customer Virtual Remote session',
    compactLabel: 'Remote',
    chipClass: 'bg-purple-100 text-purple-700 dark:bg-purple-500/15 dark:text-purple-300',
    icon: PartyPopper,
  },
}

/** The categories an employee may create; holidays and birthdays are derived. */
export const CREATABLE_EVENT_CATEGORIES = [
  'OFFICE_MEETING',
  'SCHEDULED_MEETING',
  'CUSTOMER_REMOTE_SESSION',
] as const satisfies readonly UpcomingEventCategory[]

export type CreatableEventCategory = (typeof CREATABLE_EVENT_CATEGORIES)[number]

/**
 * Category the Add Event form submits when the user is not asked to pick one.
 *
 * The "Event Type" select was removed from the modal, so every event created
 * from the page lands in the feed under one fixed category. Kept here rather
 * than inline in the page so restoring the picker is a one-line change.
 */
export const DEFAULT_CREATED_EVENT_TYPE: CreatableEventCategory = 'OFFICE_MEETING'

/** Display metadata for a feed category; unknown values fall back to the key. */
export function upcomingEventCategoryMeta(category: string | null | undefined): UpcomingEventCategoryMeta {
  return (
    UPCOMING_EVENT_CATEGORIES[category as UpcomingEventCategory] ?? {
      label: category ?? 'Event',
      compactLabel: category ?? 'Event',
      chipClass: 'bg-surface-100 text-surface-600 dark:bg-white/5 dark:text-surface-300',
      icon: PartyPopper,
    }
  )
}

/** Full name for a feed category, e.g. "Upcoming Office Meeting". */
export function upcomingEventCategoryLabel(category: string | null | undefined): string {
  return upcomingEventCategoryMeta(category).label
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
