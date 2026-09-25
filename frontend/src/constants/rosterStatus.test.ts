import { describe, expect, it } from 'vitest'
import {
  ATTENDANCE_EMPTY_STYLE,
  STATUS_STYLES,
  getAttendanceCellStyle,
  normalizeStatus,
} from '@/constants/rosterStatus'

describe('normalizeStatus', () => {
  it('normalizes case and whitespace', () => {
    expect(normalizeStatus('wfo')).toBe('WFO')
    expect(normalizeStatus(' wfo ')).toBe('WFO')
    expect(normalizeStatus('SW OFF')).toBe('SW OFF')
    expect(normalizeStatus(' sw  off ')).toBe('SW OFF')
  })

  it('maps ATR variants to the canonical ATR code', () => {
    expect(normalizeStatus('ATR')).toBe('ATR')
    expect(normalizeStatus('ATR1')).toBe('ATR')
    expect(normalizeStatus('atr3')).toBe('ATR')
  })

  it('returns empty for empty or "." entries', () => {
    for (const value of [null, undefined, '', '  ', '.']) {
      expect(normalizeStatus(value)).toBe('')
    }
  })

  it('returns empty for unknown statuses', () => {
    expect(normalizeStatus('3pm-12am')).toBe('')
    expect(normalizeStatus('Random')).toBe('')
  })
})

describe('getAttendanceCellStyle', () => {
  it('covers every roster status code', () => {
    const codes = [
      'WO', 'WFO', 'WFH', 'PL', 'SL', 'CO', 'FL', 'HPEH',
      'SW OFF', 'SW WK', 'WK WRK', 'HD', 'WX', 'TR', 'ITS', 'WDT', 'ATR',
    ]
    for (const code of codes) {
      expect(getAttendanceCellStyle(code)).toBe(STATUS_STYLES[code])
    }
  })

  it('normalizes the lookup key', () => {
    expect(getAttendanceCellStyle(' wfo ')).toBe(STATUS_STYLES.WFO)
    expect(getAttendanceCellStyle('ATR2')).toBe(STATUS_STYLES.ATR)
  })

  it('uses the empty-cell style when no status is present', () => {
    expect(getAttendanceCellStyle(null)).toBe(ATTENDANCE_EMPTY_STYLE)
    expect(getAttendanceCellStyle('.')).toBe(ATTENDANCE_EMPTY_STYLE)
    expect(getAttendanceCellStyle('Unrecognised')).toBe(ATTENDANCE_EMPTY_STYLE)
  })
})