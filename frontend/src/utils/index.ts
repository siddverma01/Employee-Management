import { clsx, type ClassValue } from 'clsx'
import { twMerge } from 'tailwind-merge'
import type { CalendarEvent } from '@/types'

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs))
}

export function formatDate(iso?: string | null): string {
  if (!iso) return '—'
  const d = new Date(iso + (iso.length === 10 ? 'T00:00:00' : ''))
  return isNaN(d.getTime()) ? '—' : new Intl.DateTimeFormat('en-US', { dateStyle: 'medium' }).format(d)
}

export function formatDateTime(iso?: string | null): string {
  if (!iso) return '—'
  const d = new Date(iso)
  return isNaN(d.getTime()) ? '—' : new Intl.DateTimeFormat('en-US', { dateStyle: 'medium', timeStyle: 'short' }).format(d)
}

export function formatDayShort(iso: string): string {
  const d = new Date(iso + 'T00:00:00')
  return new Intl.DateTimeFormat('en-US', { weekday: 'short', month: 'short', day: 'numeric' }).format(d)
}

export function formatMonthYear(iso: string): string {
  const d = new Date(iso + 'T00:00:00')
  return new Intl.DateTimeFormat('en-US', { month: 'long', year: 'numeric' }).format(d)
}

export function toISODate(date: Date): string {
  const y = date.getFullYear()
  const m = String(date.getMonth() + 1).padStart(2, '0')
  const d = String(date.getDate()).padStart(2, '0')
  return `${y}-${m}-${d}`
}

export function daysBetween(start: string, end: string): number {
  const s = new Date(start + 'T00:00:00')
  const e = new Date(end + 'T00:00:00')
  return Math.round((e.getTime() - s.getTime()) / 86_400_000) + 1
}

export function initials(name?: string | null): string {
  if (!name) return '?'
  return name
    .split(' ')
    .filter(Boolean)
    .slice(0, 2)
    .map((p) => p[0]?.toUpperCase())
    .join('')
}

export const LEAVE_STATUS_STYLES: Record<string, string> = {
  PENDING: 'bg-amber-100 text-amber-800 border-amber-300',
  APPROVED: 'bg-emerald-100 text-emerald-800 border-emerald-300',
  REJECTED: 'bg-red-100 text-red-800 border-red-300',
  CANCELLED: 'bg-surface-200 text-surface-600 border-surface-300',
}

export const ATTENDANCE_TYPE_LABELS: Record<string, string> = {
  WORK_FROM_OFFICE: 'Work From Office',
  WORK_FROM_HOME: 'Work From Home',
  LEAVE: 'On Leave',
  PRIVILEGE_LEAVE: 'Privilege Leave',
  SICK_LEAVE: 'Sick Leave',
  COMP_OFF: 'Comp Off',
  FURLOUGH: 'Furlough',
  HOLIDAY: 'Holiday',
  WEEK_OFF: 'Week Off',
  ATTRITION: 'Attrition',
}

export function attendanceShort(type: string): string {
  if (type === 'WORK_FROM_OFFICE') return 'WFO'
  if (type === 'WORK_FROM_HOME') return 'WFH'
  if (type === 'COMP_OFF') return 'CO'
  if (type === 'PRIVILEGE_LEAVE') return 'PL'
  if (type === 'SICK_LEAVE') return 'SL'
  if (type === 'FURLOUGH') return 'FL'
  if (type === 'WEEK_OFF') return 'WO'
  if (type === 'HOLIDAY') return 'HPEH'
  if (type === 'ATTRITION') return 'ATR'
  if (type === 'WORK_FROM_OFFICE') return 'WFO'
  return type.replace(/_/g, ' ').toLowerCase().replace(/\b\w/g, (c) => c.toUpperCase())
}

/** Short status label shown on the calendar chip for an HPE holiday. */
export const HPE_HOLIDAY_BADGE = 'HPEH'

/** Display name shown in the calendar details modal for an HPE holiday. */
export const HPE_HOLIDAY_LABEL = 'HPE Holiday'

/**
 * A calendar holiday whose master definition is an HPE holiday (backend
 * `extra.holidayType === 'HPE_HOLIDAY'`). Personal earned compensatory
 * entitlements are never calendar events, so they can never match here.
 */
export function isHpeHoliday(ev: Pick<CalendarEvent, 'kind' | 'extra'>): boolean {
  return ev.kind === 'HOLIDAY' && ev.extra?.holidayType === 'HPE_HOLIDAY'
}