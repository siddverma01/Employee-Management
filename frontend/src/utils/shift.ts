const SHIFT_PATTERN = /^\s*(\d{1,2}):(\d{2})\s*-\s*(\d{1,2}):(\d{2})\s*$/

/** Minutes since midnight for a "HH:mm-HH:mm" shift start, or null when unknown/invalid. */
export function parseShiftStartTime(shift: string | null | undefined): number | null {
  if (!shift) return null
  const m = SHIFT_PATTERN.exec(shift)
  if (!m) return null
  const [, sh, sm, eh, em] = m
  const startHour = Number(sh)
  const startMin = Number(sm)
  const endHour = Number(eh)
  const endMin = Number(em)
  if (startHour > 23 || startMin > 59 || endHour > 23 || endMin > 59) return null
  return startHour * 60 + startMin
}

/** Renders "HH:mm-HH:mm" as 12-hour "HH:mm AM - HH:mm PM", or null when unknown/invalid. */
export function formatShiftTime(shift: string | null | undefined): string | null {
  if (!shift) return null
  const m = SHIFT_PATTERN.exec(shift)
  if (!m) return null
  const [, sh, sm, eh, em] = m
  const startHour = Number(sh)
  const startMin = Number(sm)
  const endHour = Number(eh)
  const endMin = Number(em)
  if (startHour > 23 || startMin > 59 || endHour > 23 || endMin > 59) return null
  return `${toTwelveHour(startHour, startMin)} - ${toTwelveHour(endHour, endMin)}`
}

/** Renders a shift for display: AM/PM when parseable, else the raw value ("—" when absent). */
export function formatShiftDisplay(shift: string | null | undefined): string {
  if (!shift) return '—'
  return formatShiftTime(shift) ?? shift
}

/** AM/PM "07:00 PM - 04:00 AM" back to the stored 24-hour "19:00-04:00"; null when not parseable. */
export function to24hShift(shift: string | null | undefined): string | null {
  if (!shift) return null
  const m = String(shift)
    .trim()
    .match(/^(\d{1,2}):(\d{2})\s+(AM|PM)\s*-\s*(\d{1,2}):(\d{2})\s+(AM|PM)$/i)
  if (!m) return null
  const startHour = to24hHour(Number(m[1]), m[3])
  const startMin = Number(m[2])
  const endHour = to24hHour(Number(m[4]), m[6])
  const endMin = Number(m[5])
  if (startHour === null || endHour === null || startMin > 59 || endMin > 59) return null
  return `${pad2(startHour)}:${pad2(startMin)}-${pad2(endHour)}:${pad2(endMin)}`
}

function to24hHour(hour: number, period: string): number | null {
  if (hour < 1 || hour > 12) return null
  const h = hour % 12
  return period.toUpperCase() === 'PM' ? h + 12 : h
}

function pad2(value: number): string {
  return String(value).padStart(2, '0')
}

function toTwelveHour(hour: number, minute: number): string {
  const h12 = hour % 12 === 0 ? 12 : hour % 12
  const ampm = hour < 12 ? 'AM' : 'PM'
  return `${String(h12).padStart(2, '0')}:${String(minute).padStart(2, '0')} ${ampm}`
}