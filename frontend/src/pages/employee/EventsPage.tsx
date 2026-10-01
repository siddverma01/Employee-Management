import { useEffect, useMemo, useRef, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import toast from 'react-hot-toast'
import {
  Bell,
  BellOff,
  CalendarClock,
  CalendarPlus,
  Clock,
  ExternalLink,
  LayoutGrid,
  List,
  ListChecks,
  Plus,
  Search,
  Sun,
  UserRound,
  Users,
  Video,
  X,
} from 'lucide-react'
import { eventApi } from '@/api'
import { extractMessage } from '@/api/client'
import {
  UPCOMING_EVENT_CATEGORIES,
  upcomingEventCategoryMeta,
} from '@/constants/calendarEventKind'
import {
  getHolidayCellStyle,
  holidayLabel,
  resolveHolidayDisplayType,
} from '@/constants/holidayStatus'
import type {
  AssignableEngineer,
  CreateEventRequest,
  UpcomingEvent,
  UpcomingEventCategory,
  UpcomingEventsResponse,
} from '@/types'
import { cn, formatDate } from '@/utils'
import { PageHeader } from '@/components/ui/PageHeader'
import { LoadingState } from '@/components/ui/LoadingState'
import { EmptyState } from '@/components/ui/EmptyState'
import { Button } from '@/components/ui/Button'
import { Input } from '@/components/ui/Input'
import { Select } from '@/components/ui/Select'
import { Modal } from '@/components/ui/Modal'

const WINDOW_OPTIONS = [
  { value: '7', label: 'Next 7 days' },
  { value: '14', label: 'Next 14 days' },
  { value: '30', label: 'Next 30 days' },
  { value: '90', label: 'Next 90 days' },
]

const ALL_TAB = 'ALL' as const

/**
 * The four filter pills the reference design specifies.
 *
 * They deliberately group the five wire categories into three buckets:
 * `CUSTOMER_REMOTE_SESSION` + `SCHEDULED_MEETING` are both externally-facing
 * meetings, and `BIRTHDAY` is folded into Holidays because neither the
 * reference layout nor the top section has a place to render it separately.
 * Counts are always computed from the live feed, never hardcoded.
 */
const FILTER_PILLS = [
  {
    key: 'CUSTOMER',
    label: 'Customer Meetings',
    categories: ['CUSTOMER_REMOTE_SESSION', 'SCHEDULED_MEETING'],
  },
  { key: 'HOLIDAYS', label: 'Holidays', categories: ['HOLIDAY', 'BIRTHDAY'] },
  { key: 'OFFICE', label: 'Office Meetings', categories: ['OFFICE_MEETING'] },
] as const satisfies readonly {
  key: string
  label: string
  categories: readonly UpcomingEventCategory[]
}[]

type FilterKey = (typeof FILTER_PILLS)[number]['key']
type PillTab = FilterKey | typeof ALL_TAB

const CATEGORY_SET: Record<FilterKey, ReadonlySet<UpcomingEventCategory>> = {
  CUSTOMER: new Set<UpcomingEventCategory>([
    'CUSTOMER_REMOTE_SESSION',
    'SCHEDULED_MEETING',
  ]),
  HOLIDAYS: new Set<UpcomingEventCategory>(['HOLIDAY', 'BIRTHDAY']),
  OFFICE: new Set<UpcomingEventCategory>(['OFFICE_MEETING']),
}

// ---------------------------------------------------------------------------
// Dev-only sample data
//
// Both feed sections are driven by GET /api/events/upcoming, so on a freshly
// migrated database they render nothing and the filter pills read (0). These
// cards are the design's reference payload, so the dev server fills in whatever
// the database does not already provide.
//
// Dev only: `import.meta.env.DEV` is statically false in a production build, so
// Vite drops this branch from production bundles. Read through a function (rather
// than a module const) so tests can stub it per-case and cover both branches.
// Delete this block once real holidays, internal meetings and customer meetings
// exist in the development database.
// ---------------------------------------------------------------------------

function devSampleMode(): boolean {
  return import.meta.env.DEV
}

/** ISO date `days` from today, so the sample cards always sit in the feed window. */
function isoDaysFromNow(days: number): string {
  const d = new Date()
  d.setDate(d.getDate() + days)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

/** Naive wall-clock timestamp, matching the LocalDateTime the API sends. */
function wallClock(date: string, hour: number, minute: number): string {
  return `${date}T${String(hour).padStart(2, '0')}:${String(minute).padStart(2, '0')}:00`
}

/** Whole days from today to `iso`, so a countdown can never contradict its date. */
function daysUntil(iso: string): number {
  const target = new Date(`${iso}T00:00:00`)
  const today = new Date()
  today.setHours(0, 0, 0, 0)
  return Math.round((target.getTime() - today.getTime()) / 86_400_000)
}

type SampleEventFields = Omit<
  UpcomingEvent,
  'id' | 'source' | 'date' | 'daysUntil'
>

function sampleCustomerMeeting(
  id: number,
  days: number,
  fields: Omit<SampleEventFields, 'startTime' | 'endTime'>,
  /** [startHour, startMinute, endHour, endMinute] in 24-hour local time. */
  hours: [number, number, number, number] = [9, 0, 9, 30],
): UpcomingEvent {
  const date = isoDaysFromNow(days)
  return {
    ...fields,
    id,
    source: 'EVENT',
    date,
    startTime: wallClock(date, hours[0], hours[1]),
    endTime: wallClock(date, hours[2], hours[3]),
    daysUntil: days,
  }
}

const DEV_SAMPLE_EVENTS: UpcomingEvent[] = [
  sampleCustomerMeeting(
    9001,
    3,
    {
      category: 'CUSTOMER_REMOTE_SESSION',
      eventType: 'CUSTOMER_REMOTE_SESSION',
      subject: 'Q4 Architecture & Solution Review',
      description:
        'Walk the Vertex team through the Q4 solution design and agree the migration sequence.',
      organization: 'Vertex Retail Systems',
      location: 'Virtual Teams Room',
      meetingLink: 'https://teams.example.com/vertex',
      setReminder: true,
      todoItems: [
        'Confirm POS integration test coverage',
        'Share the Q4 capacity model',
        'Agree the phased cutover plan',
      ],
      employeeName: null,
      assignedEngineerId: 901,
      assignedEngineerName: 'Sarah Jenkins',
      assignedEngineerDepartment: 'Voice',
      assignedEngineerDesignation: 'Lead Architect',
    },
    [14, 0, 15, 30],
  ),
  sampleCustomerMeeting(
    9002,
    7,
    {
      category: 'CUSTOMER_REMOTE_SESSION',
      eventType: 'CUSTOMER_REMOTE_SESSION',
      subject: 'Global Logistics Cloud Migration Check-in',
      description:
        'Status check-in on the Maersk migration waves and open data residency risks.',
      organization: 'Maersk Global Tech',
      location: 'Virtual Conference Room A',
      meetingLink: 'https://teams.example.com/maersk',
      setReminder: true,
      todoItems: [
        'Migration wave status',
        'Open data residency risks',
        'Next checkpoint date',
      ],
      employeeName: null,
      // Deliberately unassigned: this is the card that exercises the
      // "Needs Engineer" treatment and the assign action.
      assignedEngineerId: null,
      assignedEngineerName: null,
      assignedEngineerDepartment: null,
      assignedEngineerDesignation: null,
    },
    [10, 0, 11, 0],
  ),
]

/**
 * Reference payload for "Upcoming Holidays & Internal Meetings".
 *
 * <p>Dates are pinned to the design's October 2026 reference rather than offset
 * from today, so the set reads as specified. `daysUntil` is still derived from
 * the date at load time, so a countdown can never contradict the date beside it.</p>
 */
function sampleHolidayMeeting(
  id: number,
  date: string,
  fields: Partial<
    Omit<SampleEventFields, 'id' | 'source' | 'date' | 'daysUntil'>
  >,
): UpcomingEvent {
  // Spelled out rather than defaulted with `??` so every field a holiday row can
  // carry is visible in one place; `fields` then overrides the interesting ones.
  const base: UpcomingEvent = {
    id,
    source: 'EVENT',
    date,
    daysUntil: daysUntil(date),
    category: 'HOLIDAY',
    eventType: 'HOLIDAY',
    holidayCountry: null,
    subject: '',
    description: null,
    organization: null,
    location: null,
    startTime: null,
    endTime: null,
    meetingLink: null,
    setReminder: false,
    todoItems: [],
    employeeName: null,
    assignedEngineerId: null,
    assignedEngineerName: null,
    assignedEngineerDepartment: null,
    assignedEngineerDesignation: null,
  }
  // id/source/date/daysUntil are re-asserted after the spread so a sample can
  // never drift from the date its own countdown is derived from.
  return {
    ...base,
    ...fields,
    id,
    source: 'EVENT',
    date,
    daysUntil: daysUntil(date),
  }
}

const DEV_SAMPLE_HOLIDAY_MEETINGS: UpcomingEvent[] = [
  sampleHolidayMeeting(9101, '2026-10-02', {
    category: 'HOLIDAY',
    subject: 'Gandhi Jayanti',
    description: 'National holiday. All India offices closed.',
    location: 'All India Offices',
    eventType: 'HPE_HOLIDAY',
    holidayCountry: 'IN',
  }),
  sampleHolidayMeeting(9102, '2026-10-04', {
    category: 'OFFICE_MEETING',
    subject: 'Voice Team Standup',
    description: 'Daily Voice team sync.',
    startTime: wallClock('2026-10-04', 10, 0),
    endTime: wallClock('2026-10-04', 10, 30),
    location: 'Conference 4B',
  }),
  sampleHolidayMeeting(9103, '2026-10-07', {
    category: 'OFFICE_MEETING',
    subject: 'Quarterly Town Hall',
    description: 'Company-wide quarterly update.',
    startTime: wallClock('2026-10-07', 14, 0),
    location: 'Auditorium & Virtual',
  }),
  sampleHolidayMeeting(9104, '2026-10-12', {
    category: 'HOLIDAY',
    subject: 'Columbus Day',
    description: 'US locations closed.',
    location: 'US Locations',
    eventType: 'PUBLIC',
    holidayCountry: 'US',
  }),
  sampleHolidayMeeting(9105, '2026-10-14', {
    category: 'HOLIDAY',
    subject: 'Company Foundation Day',
    description: 'Company-wide foundation day.',
    location: 'Global',
    eventType: 'PUBLIC',
    holidayCountry: 'US',
  }),
  sampleHolidayMeeting(9106, '2026-10-21', {
    category: 'OFFICE_MEETING',
    subject: 'Engineering All Hands',
    description: 'Monthly engineering all-hands.',
    startTime: wallClock('2026-10-21', 16, 0),
    endTime: wallClock('2026-10-21', 17, 0),
    location: 'Main Stage & Virtual',
  }),
]

/**
 * Real feed rows first, then any sample card the database does not already
 * provide. Keyed on subject so a real event and its sample twin never render
 * twice. A no-op outside dev, so production renders the feed untouched.
 */
function withSampleFallback(
  real: UpcomingEvent[],
  samples: UpcomingEvent[],
): UpcomingEvent[] {
  if (!devSampleMode()) return real
  const present = new Set(real.map((e) => e.subject.trim().toLowerCase()))
  return [
    ...real,
    ...samples.filter((s) => !present.has(s.subject.trim().toLowerCase())),
  ]
}

/** Keeps the assign picker usable when GET /api/events/assignable-engineers is down. */
const DEV_SAMPLE_ENGINEERS = [
  {
    id: 901,
    employeeCode: '25102288',
    fullName: 'Sarah Jenkins',
    department: 'Voice',
  },
  {
    id: 902,
    employeeCode: '25102404',
    fullName: 'Siddhesh Verma',
    department: 'Voice',
  },
  {
    id: 903,
    employeeCode: '25106149',
    fullName: 'Masher Choudary',
    department: 'Voice',
  },
]

/** "Today" / "Tomorrow" / "in 5 days", falling back to nothing. */
function relativeDay(days: number): string {
  if (days === 0) return 'Today'
  if (days === 1) return 'Tomorrow'
  if (days > 1 && days < 7) return `In ${days} days`
  return ''
}

/** Date plus optional wall-clock time, e.g. "5 Oct 2026 · 15:30". */
function formatWhen(event: UpcomingEvent): string {
  const date = formatDate(event.date)
  if (!event.startTime) return date
  return `${date} · ${event.startTime.slice(11, 16)}`
}

/**
 * When-line for the holidays/internal meetings cards.
 *
 * <p>Like {@link formatCardWhen} but without the timezone suffix: that suffix
 * earns its place on a customer card you might join, whereas these rows are
 * informational. Dates come from `formatDate`, so a holiday reads "Oct 2, 2026"
 * and a timed meeting reads "Oct 4 • 10:00 - 10:30 AM".</p>
 */
function sectionWhen(event: UpcomingEvent): string {
  const date = formatDate(event.date)
  if (!event.startTime) return date
  const start = clockTime(event.startTime)
  if (!event.endTime) return `${date} • ${start}`
  const end = clockTime(event.endTime)
  const startSuffix = start.match(/(AM|PM)$/)?.[1] ?? ''
  const endSuffix = end.match(/(AM|PM)$/)?.[1] ?? ''
  const startBody = start.replace(/\s?(AM|PM)$/, '')
  const endBody = end.replace(/\s?(AM|PM)$/, '')
  const suffix =
    startSuffix === endSuffix
      ? ` ${startSuffix}`
      : ` ${startSuffix} - ${endSuffix}`
  return `${date} • ${startBody} - ${endBody}${suffix}`
}

/** Character budget echoed under the subject field, matching the reference. */
const SUBJECT_MAX_LENGTH = 100

/**
 * The only categories this form may create.
 *
 * <p>Narrower than {@link UpcomingEventCategory} on purpose: `BIRTHDAY` is computed
 * from employee date of birth and is rejected by the backend. `HOLIDAY` is included
 * and handled as a distinct branch, because it is stored as a `holidays` row
 * rather than an `events` row.</p>
 */
type CreatableEventType = Exclude<UpcomingEventCategory, 'BIRTHDAY'>

/** Runtime mirror of {@link CreatableEventType}, used to re-check before submit. */
const CREATABLE_EVENT_TYPES: readonly CreatableEventType[] = [
  'CUSTOMER_REMOTE_SESSION',
  'OFFICE_MEETING',
  'HOLIDAY',
]

/**
 * Category cards for the create form.
 *
 * <p>All three are selectable. A holiday only needs a purpose and a date, so
 * selecting it swaps the meeting-only fields for those two rather than disabling
 * the card: the holiday calendar is the same source of truth, and creating one
 * here writes through to it.</p>
 */
const ADD_EVENT_CATEGORY_CARDS: {
  value: CreatableEventType
  label: string
  icon: typeof Users
}[] = [
  { value: 'CUSTOMER_REMOTE_SESSION', label: 'Customer Meeting', icon: Video },
  { value: 'OFFICE_MEETING', label: 'Office Meeting', icon: Users },
  { value: 'HOLIDAY', label: 'Holiday', icon: Sun },
]

function AddEventForm({ onDone }: { onDone: () => void }) {
  const queryClient = useQueryClient()
  const [subject, setSubject] = useState('')
  // The reference layout merges these into one "Meeting Link or Conference Room"
  // field, so the value is split on submit: an http(s) value is a link, anything
  // else is a room name. Both target fields already exist on the request.
  const [linkOrRoom, setLinkOrRoom] = useState('')
  const [date, setDate] = useState('')
  const [startClock, setStartClock] = useState('')
  const [allDay, setAllDay] = useState(false)
  const [planOfAction, setPlanOfAction] = useState('')
  const [setReminder, setSetReminder] = useState(true)
  // '' rather than null so clearing the chip returns the field to its empty state.
  const [assignedEngineerId, setAssignedEngineerId] = useState('')
  const [eventType, setEventType] = useState<CreatableEventType>(
    'CUSTOMER_REMOTE_SESSION',
  )
  // A holiday is a `holidays` row, not a meeting: it collects a purpose and a date
  // and nothing else, and the server stores it through the holiday calendar.
  const isHoliday = eventType === 'HOLIDAY'
  const [formError, setFormError] = useState<string | null>(null)

  const { data: engineersRaw, isLoading: loadingEngineers } = useQuery({
    queryKey: ['events', 'assignable-engineers'],
    queryFn: eventApi.assignableEngineers,
    staleTime: 5 * 60 * 1000,
    retry: 0, // Prevent spinning in dev when backend isn't reachable; fallback below
  })

  const engineers =
    devSampleMode() && (!engineersRaw || engineersRaw.length === 0)
      ? DEV_SAMPLE_ENGINEERS
      : engineersRaw

  const assignedEngineer = (engineers ?? []).find(
    (e) => String(e.id) === assignedEngineerId,
  )

  const mutation = useMutation({
    mutationFn: eventApi.create,
    onSuccess: () => {
      toast.success('Event created')
      queryClient.invalidateQueries({ queryKey: ['events'] })
      onDone()
    },
    onError: (err) => setFormError(extractMessage(err)),
  })

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    setFormError(null)
    // Title, Category, Date and Start Time, in that order. The category check is
    // belt-and-braces: the state is typed to the two creatable values and starts
    // on one, so this only fires if that invariant is ever broken upstream.
    if (!subject.trim()) {
      setFormError('Meeting subject is required')
      return
    }
    if (!CREATABLE_EVENT_TYPES.includes(eventType)) {
      setFormError('Choose an event category')
      return
    }
    if (!date) {
      setFormError(
        isHoliday ? 'Holiday date is required' : 'Meeting date is required',
      )
      return
    }
    // A holiday is all-day by nature and the form shows no time field for it.
    if (!isHoliday && !allDay && !startClock) {
      setFormError('Start time is required unless this is an all-day event')
      return
    }

    const trimmed = linkOrRoom.trim()
    const isLink = /^https?:\/\//i.test(trimmed)

    // One textarea, two targets: the whole text is the description, and each
    // non-empty line also becomes an agenda item so the existing agenda modal
    // still has something to show for an unassigned meeting.
    const planLines = planOfAction
      .split('\n')
      .map((line) => line.trim())
      .filter(Boolean)

    // Holidays are stored server-side as a `holidays` row, so the meeting-only
    // fields are sent empty rather than as values the backend would discard.
    const body: CreateEventRequest = isHoliday
      ? {
          subject: subject.trim(),
          description: planOfAction.trim() || null,
          date,
          startTime: null,
          endTime: null,
          organization: null,
          location: null,
          meetingLink: null,
          eventType,
          setReminder: false,
          todoItems: [],
          assignedEngineerId: null,
        }
      : {
          subject: subject.trim(),
          description: planOfAction.trim() || null,
          date,
          startTime: allDay || !startClock ? null : `${date}T${startClock}:00`,
          endTime: null,
          organization: null,
          location: isLink ? null : trimmed || null,
          meetingLink: isLink ? trimmed : null,
          eventType,
          setReminder,
          todoItems: planLines,
          assignedEngineerId: assignedEngineerId
            ? Number(assignedEngineerId)
            : null,
        }
    mutation.mutate(body)
  }

  return (
    <form onSubmit={handleSubmit} className="space-y-6">
      <fieldset>
        <legend className="mb-2 text-sm font-semibold text-surface-700">
          Event Category
        </legend>
        <div className="grid gap-3 sm:grid-cols-3">
          {ADD_EVENT_CATEGORY_CARDS.map((option) => {
            const selected = eventType === option.value
            const Icon = option.icon
            return (
              <button
                key={option.label}
                type="button"
                aria-pressed={selected}
                onClick={() => setEventType(option.value)}
                className={cn(
                  // h-full plus items-center: the grid stretches all three to one
                  // row height, and centering keeps the icon and label on the same
                  // optical line now that the cards carry one line of text each.
                  'relative flex h-full items-center gap-2.5 rounded-xl border p-3 text-left transition-colors',
                  selected &&
                    'border-2 border-success-500 bg-success-50/40 dark:border-[#0a5c43] dark:bg-[#063b2b]',
                  !selected && 'border-surface-200 hover:border-surface-300',
                )}
              >
                <Icon
                  className={cn(
                    'h-4 w-4 shrink-0',
                    selected
                      ? 'text-success-600 dark:text-[#00e599]'
                      : 'text-surface-400',
                  )}
                />
                <span
                  className={cn(
                    'min-w-0 text-sm font-semibold',
                    selected
                      ? 'text-success-700 dark:text-[#00e599]'
                      : 'text-surface-700',
                  )}
                >
                  {option.label}
                </span>
                {selected && (
                  <span className="absolute right-2.5 top-2.5 h-2 w-2 rounded-full bg-success-500 dark:bg-[#00e599]" />
                )}
              </button>
            )
          })}
        </div>
      </fieldset>

      {isHoliday ? (
        <>
          <Input
            label="Holiday Purpose"
            name="subject"
            value={subject}
            maxLength={SUBJECT_MAX_LENGTH}
            onChange={(e) => setSubject(e.target.value)}
            placeholder="e.g. Gandhi Jayanti"
          />
          <p className="-mt-4 text-xs text-surface-400">
            Max {SUBJECT_MAX_LENGTH} characters
          </p>

          <div>
            <label
              htmlFor="event-date"
              className="mb-1 block text-xs font-medium text-surface-600"
            >
              Date
            </label>
            <input
              id="event-date"
              name="date"
              type="date"
              value={date}
              onChange={(e) => setDate(e.target.value)}
              className="w-full rounded-md border border-surface-300 bg-surface-0 px-3 py-2 text-sm text-surface-800 focus:border-brand-500 focus:outline-none focus:ring-1 focus:ring-brand-500"
            />
            <p className="mt-1 text-xs text-surface-400">
              Applies to all locations and shows in the holiday calendar.
            </p>
          </div>
        </>
      ) : (
        <>
          <div>
            <Input
              label="Meeting Subject & Title"
              name="subject"
              value={subject}
              maxLength={SUBJECT_MAX_LENGTH}
              onChange={(e) => setSubject(e.target.value)}
              placeholder="e.g. Customer VRS walkthrough"
            />
            <p className="mt-1 text-xs text-surface-400">
              Max {SUBJECT_MAX_LENGTH} characters
            </p>
          </div>

          <div className="rounded-xl border border-surface-200 bg-surface-50 p-4">
            <div className="mb-3 flex items-center justify-between gap-3">
              <span className="flex items-center gap-2 text-sm font-semibold text-surface-700">
                <Clock className="h-4 w-4 text-surface-400" />
                Schedule Timing
              </span>
              <label className="flex cursor-pointer items-center gap-2 text-xs font-medium text-surface-600">
                <span>All day event</span>
                <button
                  type="button"
                  role="switch"
                  aria-checked={allDay}
                  aria-label="All day event"
                  onClick={() => setAllDay((prev) => !prev)}
                  className={cn(
                    'relative h-5 w-9 shrink-0 rounded-full transition-colors',
                    allDay ? 'bg-success-500' : 'bg-surface-300',
                  )}
                >
                  <span
                    className={cn(
                      'absolute top-0.5 h-4 w-4 rounded-full bg-white shadow-sm transition-all',
                      allDay ? 'left-[1.125rem]' : 'left-0.5',
                    )}
                  />
                </button>
              </label>
            </div>

            {/* Two equal columns: the Date picker and the Start Time row each take
            half the box, so the zone badge eats into Start Time rather than
            pushing the pair off-balance. min-w-0 lets the time input shrink. */}
            <div className="grid gap-3 sm:grid-cols-2">
              <div className="min-w-0">
                <label
                  htmlFor="event-date"
                  className="mb-1 block text-xs font-medium text-surface-600"
                >
                  Date
                </label>
                <input
                  id="event-date"
                  name="date"
                  type="date"
                  value={date}
                  onChange={(e) => setDate(e.target.value)}
                  className="w-full rounded-md border border-surface-300 bg-surface-0 px-3 py-2 text-sm text-surface-800 focus:border-brand-500 focus:outline-none focus:ring-1 focus:ring-brand-500"
                />
              </div>
              <div className="min-w-0">
                <label
                  htmlFor="event-start"
                  className="mb-1 block text-xs font-medium text-surface-600"
                >
                  Start Time
                </label>
                <div className="flex items-center gap-2">
                  <input
                    id="event-start"
                    name="startClock"
                    type="time"
                    value={startClock}
                    disabled={allDay}
                    onChange={(e) => setStartClock(e.target.value)}
                    className="min-w-0 flex-1 rounded-md border border-surface-300 bg-surface-0 px-3 py-2 text-sm text-surface-800 focus:border-brand-500 focus:outline-none focus:ring-1 focus:ring-brand-500 disabled:cursor-not-allowed disabled:bg-surface-100 disabled:text-surface-400"
                  />
                  {/* Reader's own clock, not a hardcoded zone: these are naive wall-clock
                  values, so the badge names the clock they are read on. shrink-0 keeps
                  it from being squeezed by the time input on a narrow viewport. */}
                  {timeZoneLabel() && (
                    <span className="h-fit shrink-0 self-center rounded-full bg-surface-200 px-2 py-0.5 text-[11px] font-semibold text-surface-600">
                      {timeZoneLabel()}
                    </span>
                  )}
                </div>
              </div>
            </div>
          </div>

          <div>
            <label
              htmlFor="event-link"
              className="mb-1 block text-sm font-medium text-surface-700"
            >
              Meeting Link or Conference Room
            </label>
            <div className="relative">
              <Video className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-surface-400" />
              <input
                id="event-link"
                name="linkOrRoom"
                value={linkOrRoom}
                onChange={(e) => setLinkOrRoom(e.target.value)}
                placeholder="https://meet.example.com/… or Conference 4B"
                className="w-full rounded-md border border-surface-300 bg-surface-0 py-2 pl-9 pr-3 text-sm text-surface-800 placeholder:text-surface-400 focus:border-brand-500 focus:outline-none focus:ring-1 focus:ring-brand-500"
              />
            </div>
            <p className="mt-1 text-xs text-surface-400">
              A link starting with http is saved as the meeting link, anything
              else as the room.
            </p>
          </div>

          <div>
            <span className="mb-1 block text-sm font-medium text-surface-700">
              Assign to
            </span>
            {/* Add Member leads so the dashed pill stays put on the left and a
            selected chip wraps onto the row after it. There is no empty-state
            label: the field speaks for itself when it holds only the pill. */}
            <div className="flex flex-wrap items-center gap-2 rounded-md border border-surface-300 bg-surface-0 p-2">
              <span className="relative inline-flex">
                <select
                  aria-label="Add Member"
                  value=""
                  disabled={loadingEngineers}
                  onChange={(e) => setAssignedEngineerId(e.target.value)}
                  className="absolute inset-0 h-full w-full cursor-pointer appearance-none rounded-full opacity-0"
                >
                  <option value="" disabled>
                    Add Member
                  </option>
                  {(engineers ?? []).map((e) => (
                    <option key={e.id} value={String(e.id)}>
                      {e.department
                        ? `${e.fullName} · ${e.department}`
                        : e.fullName}
                    </option>
                  ))}
                </select>
                <span className="pointer-events-none inline-flex items-center gap-1 rounded-full border border-dashed border-surface-300 px-2.5 py-1 text-xs font-medium text-surface-600">
                  <Plus className="h-3 w-3" />
                  Add Member
                </span>
              </span>

              {assignedEngineer && (
                <span className="inline-flex items-center gap-1.5 rounded-full bg-brand-50 py-0.5 pl-0.5 pr-1.5">
                  <span className="grid h-6 w-6 shrink-0 place-items-center rounded-full bg-brand-600 text-[10px] font-semibold text-white">
                    {initialsOf(assignedEngineer.fullName)}
                  </span>
                  <span className="text-xs font-medium text-brand-800">
                    {assignedEngineer.fullName}
                  </span>
                  <button
                    type="button"
                    aria-label={`Remove ${assignedEngineer.fullName}`}
                    onClick={() => setAssignedEngineerId('')}
                    className="rounded-full p-0.5 text-brand-400 transition-colors hover:bg-brand-100 hover:text-brand-700"
                  >
                    <X className="h-3 w-3" />
                  </button>
                </span>
              )}
            </div>
          </div>

          <div>
            <div className="mb-1 flex items-center gap-2">
              <label
                htmlFor="event-plan"
                className="text-sm font-medium text-surface-700"
              >
                Plan of Action
              </label>
              <span className="rounded-full bg-surface-100 px-2 py-0.5 text-[10px] font-semibold text-surface-500">
                Optional
              </span>
            </div>
            <textarea
              id="event-plan"
              name="planOfAction"
              rows={3}
              value={planOfAction}
              onChange={(e) => setPlanOfAction(e.target.value)}
              placeholder="Outline key objectives, talking points, deliverables, or next steps..."
              className="w-full resize-y rounded-md border border-surface-300 bg-surface-0 px-3 py-2 text-sm text-surface-800 placeholder:text-surface-400 focus:border-brand-500 focus:outline-none focus:ring-1 focus:ring-brand-500"
            />
          </div>

          <label className="flex cursor-pointer items-start gap-2.5 text-sm text-surface-600">
            <input
              type="checkbox"
              name="setReminder"
              checked={setReminder}
              onChange={(e) => setSetReminder(e.target.checked)}
              className="mt-0.5 h-4 w-4 shrink-0 rounded border-surface-300 accent-emerald-600 focus:ring-brand-500"
            />
            <span className="flex items-center gap-1.5">
              <Bell className="h-4 w-4 shrink-0 text-surface-400" />
              Trigger smart reminders
            </span>
          </label>
        </>
      )}

      {formError && <p className="text-sm text-error-600">{formError}</p>}

      <div className="flex justify-end gap-2">
        <Button type="button" variant="secondary" onClick={onDone}>
          Cancel
        </Button>
        <Button type="submit" loading={mutation.isPending}>
          {mutation.isPending ? 'Creating…' : 'Create Event'}
          {!mutation.isPending && <span aria-hidden="true"> →</span>}
        </Button>
      </div>
    </form>
  )
}

function EventCard({
  event,
  onOpen,
  layout = 'grid',
}: {
  event: UpcomingEvent
  onOpen: () => void
  layout?: 'grid' | 'list'
}) {
  const meta = upcomingEventCategoryMeta(event.category)
  const Icon = meta.icon
  const relative = relativeDay(event.daysUntil)

  return (
    <button
      type="button"
      onClick={onOpen}
      className={cn(
        'w-full rounded-lg border border-surface-200 bg-surface-0 text-left transition-colors hover:border-surface-300 hover:bg-surface-100',
        layout === 'grid' ? 'p-4' : 'flex items-center gap-4 px-4 py-3',
      )}
    >
      <div className="flex items-start gap-3">
        <span
          className={`grid h-9 w-9 shrink-0 place-items-center rounded-lg ${meta.chipClass}`}
        >
          <Icon className="h-4 w-4" />
        </span>

        <div className="min-w-0 flex-1">
          <p className="truncate text-sm font-medium text-surface-800">
            {event.subject}
          </p>
          <p className="mt-0.5 flex flex-wrap items-center gap-x-2 text-xs text-surface-500">
            <span>{formatWhen(event)}</span>
            {relative && (
              <span className="font-medium text-brand-600">{relative}</span>
            )}
          </p>
          {event.assignedEngineerName && (
            <span className="mt-1 inline-flex max-w-full items-center gap-1 truncate text-xs text-surface-600">
              <UserRound className="h-3 w-3 shrink-0 text-surface-400" />
              <span className="truncate">
                Assigned To: {event.assignedEngineerName}
              </span>
            </span>
          )}
          {event.meetingLink && (
            <span className="mt-1 inline-flex max-w-full items-center gap-1 truncate text-xs font-medium text-brand-600">
              <Video className="h-3 w-3 shrink-0" />
              <span className="truncate">{event.meetingLink}</span>
            </span>
          )}
        </div>

        <span
          className={`shrink-0 rounded-full px-2 py-0.5 text-[10px] font-semibold ${meta.chipClass}`}
        >
          {meta.compactLabel}
        </span>
      </div>
    </button>
  )
}

/**
 * One card in the "Upcoming Holidays & Internal Meetings" grid.
 *
 * <p>Distinct from {@link EventCard}: the horizontal feed row is dense because it
 * lists arbitrary events, whereas these reference cards stack icon + title + tag
 * over a muted when-line and a split footer of countdown and location.</p>
 */
/**
 * Whether a holiday is a US or an HPE holiday, so the feed can say which.
 *
 * <p>HPE holidays are identified by their persisted type. US federal holidays are
 * stored as `PUBLIC`, so `holidayCountry` is what separates them; without it they
 * would be indistinguishable from a generic public holiday. Returns `null` for
 * anything that is not a holiday, and for a holiday with no recognisable type so
 * the caller can simply omit the chip.</p>
 */
function holidayTypeBadge(event: UpcomingEvent) {
  if (event.category !== 'HOLIDAY') return null
  const displayType = resolveHolidayDisplayType({
    holidayType: event.eventType ?? undefined,
    country: event.holidayCountry ?? undefined,
  })
  const label = holidayLabel(displayType)
  if (!label) return null
  return { label, style: getHolidayCellStyle(displayType) }
}

function HolidayMeetingCard({
  event,
  onOpen,
}: {
  event: UpcomingEvent
  onOpen: () => void
}) {
  const meta = upcomingEventCategoryMeta(event.category)
  const Icon = meta.icon
  const relative = countdownLabel(event.daysUntil)
  const holidayType = holidayTypeBadge(event)

  return (
    <button
      type="button"
      onClick={onOpen}
      className="flex w-full flex-col rounded-lg border border-surface-200 bg-surface-0 p-4 text-left transition-colors hover:border-surface-300 hover:bg-surface-100 dark:rounded-xl dark:border-[#222731] dark:bg-[#161a20] dark:hover:border-[#2d3748] dark:hover:bg-[#1a202c]"
    >
      <div className="flex items-start gap-3">
        <span
          className={cn(
            'grid h-9 w-9 shrink-0 place-items-center rounded-lg',
            meta.chipClass,
          )}
        >
          <Icon className="h-4 w-4" />
        </span>
        <span className="min-w-0 flex-1 truncate text-sm font-semibold text-surface-800 dark:text-white">
          {event.subject}
        </span>
        {holidayType ? (
          <span
            className="shrink-0 rounded-full border px-2 py-0.5 text-[10px] font-semibold"
            style={{
              ...holidayType.style,
              borderColor: holidayType.style.borderColor,
            }}
            title={holidayType.label}
          >
            {holidayType.label}
          </span>
        ) : (
          <span
            className={cn(
              'shrink-0 rounded-full px-2 py-0.5 text-[10px] font-semibold',
              meta.chipClass,
            )}
          >
            {meta.compactLabel}
          </span>
        )}
      </div>

      <p className="mt-3 text-xs text-surface-500 dark:text-[#8a99ad]">{sectionWhen(event)}</p>

      <div className="mt-3 flex items-center justify-between gap-3 border-t border-surface-100 pt-2.5 dark:border-[#1e232b]">
        <span className="shrink-0 text-xs font-semibold text-success-700 dark:text-[#00e599]">
          {relative}
        </span>
        <span className="min-w-0 truncate text-xs text-surface-500 dark:text-[#8a99ad]">
          {event.location?.trim() || '—'}
        </span>
      </div>
    </button>
  )
}

function EventDetails({
  event,
  onClose,
  onSaved,
}: {
  event: UpcomingEvent
  onClose: () => void
  onSaved?: (updated: UpcomingEvent) => void
}) {
  const meta = upcomingEventCategoryMeta(event.category)
  const Icon = meta.icon
  const holidayType = holidayTypeBadge(event)
  const queryClient = useQueryClient()

  const { data: engineersRaw, isLoading: loadingEngineers } = useQuery({
    queryKey: ['events', 'assignable-engineers'],
    queryFn: () => eventApi.assignableEngineers(),
  })
  const engineers = useMemo(() => engineersRaw ?? [], [engineersRaw])

  /**
   * Only rows backed by the `events` table can be moved or reassigned. Holidays and
   * birthdays are synthesized from their own tables, so they have no event id to
   * PATCH and the editor stays read-only for them.
   */
  const canEdit = event.source === 'EVENT'

  const [editing, setEditing] = useState(false)
  const [date, setDate] = useState(event.date)
  const [startClock, setStartClock] = useState(isoClock(event.startTime))
  const [endClock, setEndClock] = useState(isoClock(event.endTime))
  const [assigneeId, setAssigneeId] = useState(
    event.assignedEngineerId == null ? '' : String(event.assignedEngineerId),
  )

  /**
   * Compared field by field rather than by rebuilding the ISO string, because
   * Jackson may or may not render the trailing seconds. Comparing `14:30` to
   * `14:30` keeps Save correctly disabled on a freshly opened dialog.
   */
  const scheduleDirty =
    date !== event.date ||
    startClock !== isoClock(event.startTime) ||
    endClock !== isoClock(event.endTime)
  const assigneeDirty =
    (assigneeId === '' ? null : Number(assigneeId)) !== event.assignedEngineerId
  const dirty = scheduleDirty || assigneeDirty

  /**
   * Reschedule runs before assign so that a dialog changing both persists the
   * new window first and the assign response carries it back.
   */
  const save = useMutation({
    mutationFn: async () => {
      let updated = event
      if (scheduleDirty) {
        updated = await eventApi.reschedule(event.id, {
          date,
          startTime: startClock ? `${date}T${startClock}:00` : null,
          endTime: endClock ? `${date}T${endClock}:00` : null,
        })
      }
      if (assigneeDirty) {
        updated = await eventApi.assign(
          event.id,
          assigneeId === '' ? null : Number(assigneeId),
        )
      }
      return updated
    },
    onSuccess: (updated) => {
      queryClient.invalidateQueries({ queryKey: ['events', 'upcoming'] })
      onSaved?.(updated)
      onClose()
    },
  })

  // The current assignee may be someone no longer offered by the dropdown (left
  // the company), so fall back to the name the feed already reported.
  const assigneeName =
    engineers.find((e) => String(e.id) === assigneeId)?.fullName ??
    (assigneeId === '' ? null : event.assignedEngineerName)

  const planOfAction =
    (event.description ?? '').trim() || (event.todoItems ?? []).join('\n')

  const inputClass =
    'rounded-md border border-surface-300 bg-surface-0 px-2.5 py-1.5 text-sm text-surface-800 focus:border-brand-500 focus:outline-none focus:ring-1 focus:ring-brand-500'

  return (
    <Modal
      open
      onClose={onClose}
      title={event.subject}
      size="detail"
      variant="feature"
    >
      <div className="space-y-5">
        <div className="flex items-center justify-between gap-3">
          <div className="flex min-w-0 items-center gap-2">
            {holidayType ? (
              <span
                className="inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-xs font-medium"
                style={holidayType.style}
              >
                <Icon className="h-3.5 w-3.5" />
                {holidayType.label}
              </span>
            ) : (
              <span
                className={`inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-xs font-medium ${meta.chipClass}`}
              >
                <Icon className="h-3.5 w-3.5" />
                {meta.label}
              </span>
            )}
          </div>
          {event.employeeName && (
            <p className="truncate text-sm text-surface-500">{event.employeeName}</p>
          )}
        </div>

        <dl className="divide-y divide-surface-100 text-sm">
          <div className="flex items-start justify-between gap-4 py-2.5">
            <dt className="shrink-0 text-surface-500">Subject</dt>
            <dd className="text-right font-medium text-surface-800">
              {event.subject}
            </dd>
          </div>

          <div className="flex items-start justify-between gap-4 py-2.5">
            <dt className="shrink-0 pt-1 text-surface-500">Scheduled Time</dt>
            <dd className="min-w-0 flex-1">
              {editing ? (
                <div className="space-y-2">
                  <div>
                    <label
                      htmlFor="event-detail-date"
                      className="mb-1 block text-xs font-medium text-surface-600"
                    >
                      Date
                    </label>
                    <input
                      id="event-detail-date"
                      type="date"
                      value={date}
                      onChange={(e) => setDate(e.target.value)}
                      className={`${inputClass} w-full`}
                    />
                  </div>
                  <div className="grid grid-cols-2 gap-2">
                    <div>
                      <label
                        htmlFor="event-detail-start"
                        className="mb-1 block text-xs font-medium text-surface-600"
                      >
                        Start
                      </label>
                      <input
                        id="event-detail-start"
                        type="time"
                        value={startClock}
                        onChange={(e) => setStartClock(e.target.value)}
                        className={`${inputClass} w-full`}
                      />
                    </div>
                    <div>
                      <label
                        htmlFor="event-detail-end"
                        className="mb-1 block text-xs font-medium text-surface-600"
                      >
                        End
                      </label>
                      <input
                        id="event-detail-end"
                        type="time"
                        value={endClock}
                        onChange={(e) => setEndClock(e.target.value)}
                        className={`${inputClass} w-full`}
                      />
                    </div>
                  </div>
                </div>
              ) : (
                <div className="flex items-center justify-end gap-2">
                  <span className="text-right font-medium text-surface-800">
                    {formatWhen(event)}
                  </span>
                  {canEdit && (
                    <button
                      type="button"
                      onClick={() => setEditing(true)}
                      className="shrink-0 rounded px-1.5 py-0.5 text-xs font-medium text-brand-600 transition-colors hover:bg-brand-50"
                    >
                      Edit
                    </button>
                  )}
                </div>
              )}
            </dd>
          </div>

          <div className="flex items-start justify-between gap-4 py-2.5">
            <dt className="shrink-0 pt-1 text-surface-500">Assigned To</dt>
            <dd className="flex min-w-0 flex-1 flex-wrap items-center justify-end gap-2">
              {assigneeName ? (
                <span className="inline-flex items-center gap-1.5 rounded-full bg-surface-100 py-0.5 pl-0.5 pr-2">
                  <span className="grid h-6 w-6 shrink-0 place-items-center rounded-full bg-brand-600 text-[10px] font-semibold text-white">
                    {initialsOf(assigneeName)}
                  </span>
                  <span className="truncate text-xs font-medium text-surface-700">
                    {assigneeName}
                  </span>
                </span>
              ) : (
                <span className="text-xs text-surface-400">Unassigned</span>
              )}
              {canEdit && (
                <select
                  aria-label="Assigned To"
                  value={assigneeId}
                  disabled={loadingEngineers}
                  onChange={(e) => setAssigneeId(e.target.value)}
                  className="min-w-0 max-w-full rounded-md border border-surface-300 bg-surface-0 px-2 py-1 text-xs text-surface-700 focus:border-brand-500 focus:outline-none focus:ring-1 focus:ring-brand-500"
                >
                  <option value="">Unassigned</option>
                  {engineers.map((e) => (
                    <option key={e.id} value={String(e.id)}>
                      {e.fullName}
                    </option>
                  ))}
                </select>
              )}
            </dd>
          </div>

          <div className="flex items-start justify-between gap-4 py-2.5">
            <dt className="shrink-0 text-surface-500">Meeting Link</dt>
            <dd className="text-right">
              {event.meetingLink ? (
                <a
                  href={event.meetingLink}
                  target="_blank"
                  rel="noreferrer"
                  className="inline-flex items-center gap-1 font-medium text-brand-600 hover:underline"
                >
                  Join meeting
                  <ExternalLink className="h-3 w-3" />
                </a>
              ) : (
                <span className="text-surface-400">None</span>
              )}
            </dd>
          </div>
          <div className="flex items-start justify-between gap-4 py-2.5">
            <dt className="shrink-0 text-surface-500">Reminder Status</dt>
            <dd className="flex items-center justify-end gap-1.5 font-medium text-surface-800">
              {event.setReminder ? (
                <>
                  <Bell className="h-3.5 w-3.5 text-surface-400" />
                  Set
                </>
              ) : (
                <>
                  <BellOff className="h-3.5 w-3.5 text-surface-300" />
                  Not set
                </>
              )}
            </dd>
          </div>
        </dl>

        <div>
          <p className="mb-1.5 text-sm text-surface-500">Plan of Action</p>
          <div className="rounded-xl border border-surface-200 bg-surface-50 p-4 text-sm text-surface-700">
            {planOfAction ? (
              <p className="whitespace-pre-line">{planOfAction}</p>
            ) : (
              <p className="text-surface-400">No plan of action recorded</p>
            )}
          </div>
        </div>

        <div className="flex justify-end gap-2 border-t border-surface-200 pt-5">
          <Button variant="secondary" onClick={onClose}>
            Close
          </Button>
          <Button
            variant="primary"
            disabled={!dirty || !canEdit}
            loading={save.isPending}
            onClick={() => save.mutate()}
          >
            Save Changes
          </Button>
        </div>
      </div>
    </Modal>
  )
}

/**
 * "14:30" for an `<input type="time">`, from an ISO timestamp.
 *
 * <p>Slices the wall clock rather than reformatting, so it never shifts the
 * stored naive time through a timezone the backend never applied.</p>
 */
function isoClock(iso: string | null): string {
  if (!iso || iso.length < 16) return ''
  return iso.slice(11, 16)
}

/** "02:00 PM" from an ISO timestamp. Hours are zero-padded to match the card mock. */
function clockTime(iso: string | null): string {
  if (!iso) return ''
  const hh = Number(iso.slice(11, 13))
  const mm = iso.slice(14, 16)
  const suffix = hh >= 12 ? 'PM' : 'AM'
  const h12 = hh % 12 === 0 ? 12 : hh % 12
  return `${String(h12).padStart(2, '0')}:${mm} ${suffix}`
}

/**
 * Short timezone label for the reader, e.g. "PST".
 *
 * <p>Meeting times are stored as naive wall-clock values, so this says which
 * clock the range is being read on rather than claiming a conversion happened.
 * Resolved once and cached, because it cannot change within a session.</p>
 */
let timeZoneLabelCache: string | null = null
function timeZoneLabel(): string {
  if (timeZoneLabelCache === null) {
    const part = new Intl.DateTimeFormat('en-US', { timeZoneName: 'short' })
      .formatToParts(new Date())
      .find((p) => p.type === 'timeZoneName')
    timeZoneLabelCache = part?.value ?? ''
  }
  return timeZoneLabelCache
}

/**
 * "Oct 5, 2026 • 02:00 - 03:30 PM PST" for a meeting, or the plain date when the
 * event has no wall-clock time. A one-hour meeting renders a single PM suffix.
 */
function formatCardWhen(event: UpcomingEvent): string {
  const date = formatDate(event.date)
  if (!event.startTime) return date
  const zone = timeZoneLabel()
  const zoned = zone ? ` ${zone}` : ''
  const start = clockTime(event.startTime)
  if (!event.endTime) return `${date} • ${start}${zoned}`
  const end = clockTime(event.endTime)
  const startSuffix = start.match(/(AM|PM)$/)?.[1] ?? ''
  const endSuffix = end.match(/(AM|PM)$/)?.[1] ?? ''
  const startBody = start.replace(/\s?(AM|PM)$/, '')
  const endBody = end.replace(/\s?(AM|PM)$/, '')
  // "02:00 - 03:30 PM" when both sides share a meridiem, "11:00 AM - 01:00 PM" when not.
  const suffix =
    startSuffix === endSuffix
      ? ` ${startSuffix}`
      : ` ${startSuffix} - ${endSuffix}`
  return `${date} • ${startBody} - ${endBody}${suffix}${zoned}`
}

/** "In 3 days" / "Today" / "In 12 days"; empty when the window is too wide to be useful. */
function countdownLabel(daysUntil: number): string {
  if (daysUntil < 0) return 'Past'
  if (daysUntil === 0) return 'Today'
  if (daysUntil === 1) return 'Tomorrow'
  return `In ${daysUntil} days`
}

/** Up to two initials, e.g. "Sarah Jenkins" -> "SJ". Tolerates a missing name. */
function initialsOf(name: string | null | undefined): string {
  if (!name) return ''
  return name
    .split(/\s+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((p) => p[0]?.toUpperCase() ?? '')
    .join('')
}

/**
 * Inline engineer autocomplete shared by the "Reassign" and "+ Assign Engineer"
 * actions.
 *
 * <p>Opens in place of the card action instead of in a dialog, so searching never
 * covers the meeting being edited. Reuses the existing `assignable-engineers`
 * query rather than adding a second source of truth, and writes through
 * `eventApi.assign` so the change is persisted rather than only held in local
 * state.</p>
 */
function AssignEngineerSearch({
  event,
  onClose,
}: {
  event: UpcomingEvent
  onClose: () => void
}) {
  const queryClient = useQueryClient()
  const { data: engineersRaw, isLoading } = useQuery({
    queryKey: ['events', 'assignable-engineers'],
    queryFn: eventApi.assignableEngineers,
    staleTime: 5 * 60 * 1000,
    retry: 0, // Prevent spinning in dev when backend isn't reachable; fallback below
  })

  const engineers =
    devSampleMode() && (!engineersRaw || engineersRaw.length === 0)
      ? DEV_SAMPLE_ENGINEERS
      : engineersRaw

  const [query, setQuery] = useState('')
  const [open, setOpen] = useState(true)
  const [activeIndex, setActiveIndex] = useState(0)
  const [error, setError] = useState<string | null>(null)
  const rootRef = useRef<HTMLDivElement>(null)
  const inputRef = useRef<HTMLInputElement>(null)

  // A single keystroke filters. Prefix hits outrank substring hits so typing
  // "s" surfaces Siddhesh before the alphabetically later Masher/Choudary.
  const matches = useMemo(() => {
    const all: AssignableEngineer[] = engineers ?? []
    const q = query.trim().toLowerCase()
    if (!q) return all
    const prefix: AssignableEngineer[] = []
    const loose: AssignableEngineer[] = []
    for (const e of all) {
      const name = e.fullName.toLowerCase()
      const role = (e.department ?? '').toLowerCase()
      if (name.startsWith(q) || role.startsWith(q)) prefix.push(e)
      else if (name.includes(q) || role.includes(q)) loose.push(e)
    }
    return [...prefix, ...loose]
  }, [engineers, query])

  // Shortening the list must not leave the highlight past the last option.
  useEffect(() => setActiveIndex(0), [matches])

  useEffect(() => {
    inputRef.current?.focus()
  }, [])

  // Anywhere outside the panel is a cancel, same as Escape.
  useEffect(() => {
    const onPointerDown = (e: MouseEvent) => {
      if (!rootRef.current?.contains(e.target as Node)) onClose()
    }
    document.addEventListener('mousedown', onPointerDown)
    return () => document.removeEventListener('mousedown', onPointerDown)
  }, [onClose])

  const mutation = useMutation({
    mutationFn: (engineerId: number | null) =>
      eventApi.assign(event.id, engineerId),
    onSuccess: (updated) => {
      toast.success(
        updated.assignedEngineerId
          ? `Assigned to ${updated.assignedEngineerName}`
          : 'Engineer unassigned',
      )
      // Rewrite the one row in place so every section and the open details
      // modal pick up the new assignee without a full refetch.
      queryClient.setQueryData<UpcomingEventsResponse>(
        ['events', 'upcoming', 30],
        (prev) =>
          prev && {
            ...prev,
            events: prev.events.map((e) =>
              e.source === 'EVENT' && e.id === updated.id
                ? { ...e, ...updated }
                : e,
            ),
          },
      )
      queryClient.invalidateQueries({ queryKey: ['events', 'upcoming'] })
      onClose()
    },
    onError: (err) => setError(extractMessage(err)),
  })

  const choose = (engineerId: number | null) => mutation.mutate(engineerId)

  const onKeyDown = (e: React.KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'Escape') {
      e.preventDefault()
      onClose()
      return
    }
    if (e.key === 'ArrowDown') {
      e.preventDefault()
      setOpen(true)
      setActiveIndex((i) => (matches.length ? Math.min(i + 1, matches.length - 1) : 0))
      return
    }
    if (e.key === 'ArrowUp') {
      e.preventDefault()
      setActiveIndex((i) => Math.max(i - 1, 0))
      return
    }
    if (e.key === 'Enter') {
      const picked = matches[activeIndex]
      if (!picked || mutation.isPending) return
      e.preventDefault()
      choose(picked.id)
    }
  }

  return (
    <div
      ref={rootRef}
      className="border-t border-surface-200 bg-surface-50 px-4 py-3 dark:border-[#222936] dark:bg-[#12161C]"
    >
      <h4 className="mb-1.5 text-[11px] font-semibold uppercase tracking-wide text-surface-500 dark:text-slate-400">
        {event.assignedEngineerId ? 'Reassign engineer' : 'Assign an engineer'}
      </h4>

      <div className="flex items-center gap-2">
        <div className="relative min-w-0 flex-1">
          <Search
            aria-hidden="true"
            className="pointer-events-none absolute left-2.5 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-surface-400 dark:text-slate-500"
          />
          <input
            ref={inputRef}
            value={query}
            role="combobox"
            aria-label="Search engineers"
            aria-expanded={open}
            aria-controls="assign-engineer-listbox"
            aria-autocomplete="list"
            autoComplete="off"
            placeholder="Search by name or role…"
            onChange={(e) => {
              setQuery(e.target.value)
              setOpen(true)
            }}
            onKeyDown={onKeyDown}
            className="w-full rounded-md border border-surface-300 bg-surface-0 py-1.5 pl-8 pr-2 text-sm text-surface-800 outline-none transition-colors placeholder:text-surface-400 focus:border-brand-500 focus:ring-[3px] focus:ring-brand-500/20 dark:border-[#222936] dark:bg-[#0D0F12] dark:text-white"
          />
        </div>
        <Button variant="secondary" size="sm" onClick={onClose}>
          Cancel
        </Button>
      </div>

      {open && (
        <ul
          id="assign-engineer-listbox"
          role="listbox"
          aria-label="Engineers"
          className="mt-2 max-h-56 overflow-y-auto rounded-md border border-surface-200 bg-surface-0 dark:border-[#222936] dark:bg-[#12161C]"
        >
          {isLoading && (
            <li className="px-3 py-2.5 text-sm text-surface-400">Loading engineers…</li>
          )}

          {!isLoading && matches.length === 0 && (
            <li className="px-3 py-2.5 text-sm text-surface-500 dark:text-slate-400">
              No engineer matches “{query.trim()}”
            </li>
          )}

          {!isLoading &&
            matches.map((e, i) => {
              const isCurrent = e.id === event.assignedEngineerId
              const isActive = i === activeIndex
              return (
                <li key={e.id} role="option" aria-selected={isActive}>
                  <button
                    type="button"
                    tabIndex={-1}
                    disabled={mutation.isPending}
                    onMouseEnter={() => setActiveIndex(i)}
                    onClick={() => choose(e.id)}
                    className={cn(
                      'flex w-full items-center gap-3 px-3 py-2 text-left transition-colors disabled:opacity-60',
                      isActive
                        ? 'bg-surface-100 dark:bg-[#1a1f27]'
                        : 'hover:bg-surface-100 dark:hover:bg-[#161a20]',
                    )}
                  >
                    <span className="grid h-7 w-7 shrink-0 place-items-center rounded-full bg-brand-100 text-[10px] font-semibold text-brand-700 dark:bg-[#00E599] dark:text-[#0D0F12] dark:font-bold">
                      {initialsOf(e.fullName)}
                    </span>
                    <span className="min-w-0 flex-1">
                      <span className="block truncate text-sm font-medium text-surface-800 dark:text-white">
                        {e.fullName}
                      </span>
                      <span className="block truncate text-xs text-surface-500 dark:text-slate-400">
                        {e.department ?? e.employeeCode}
                      </span>
                    </span>
                    {isCurrent && (
                      <span className="shrink-0 text-[11px] font-semibold text-brand-600 dark:text-[#00E599]">
                        Current
                      </span>
                    )}
                  </button>
                </li>
              )
            })}
        </ul>
      )}

      {event.assignedEngineerId && (
        <Button
          variant="secondary"
          size="sm"
          className="mt-2"
          disabled={mutation.isPending}
          onClick={() => choose(null)}
        >
          Remove assignment
        </Button>
      )}

      {error && <p className="mt-2 text-sm text-error-600">{error}</p>}
    </div>
  )
}

/** Agenda drawer, backed by the event's TO DO items and description. */
function AgendaModal({
  event,
  onClose,
}: {
  event: UpcomingEvent
  onClose: () => void
}) {
  const items = event.todoItems ?? []

  return (
    <Modal open onClose={onClose} title="Meeting agenda" size="md">
      <div className="space-y-4">
        <div>
          <p className="text-sm font-medium text-surface-800">
            {event.subject}
          </p>
          <p className="text-xs text-surface-500">{formatCardWhen(event)}</p>
        </div>

        {event.description && (
          <div>
            <p className="mb-1 text-sm text-surface-500">Notes</p>
            <p className="text-sm text-surface-700">{event.description}</p>
          </div>
        )}

        <div>
          <p className="mb-1.5 text-sm text-surface-500">Agenda</p>
          {items.length > 0 ? (
            <ul className="space-y-1">
              {items.map((item, i) => (
                <li
                  key={`${item}-${i}`}
                  className="flex items-start gap-2 text-sm text-surface-700"
                >
                  <ListChecks className="mt-0.5 h-3.5 w-3.5 shrink-0 text-surface-400" />
                  <span>{item}</span>
                </li>
              ))}
            </ul>
          ) : (
            <p className="text-sm text-surface-400">
              No agenda items have been added yet.
            </p>
          )}
        </div>

        {event.meetingLink && (
          <div>
            <p className="mb-1 text-sm text-surface-500">Meeting link</p>
            <a
              href={event.meetingLink}
              target="_blank"
              rel="noreferrer"
              className="inline-flex items-center gap-1 break-all text-sm font-medium text-brand-600 hover:underline"
            >
              {event.meetingLink}
              <ExternalLink className="h-3 w-3 shrink-0" />
            </a>
          </div>
        )}

        <div className="flex justify-end border-t border-surface-200 pt-3">
          <Button variant="secondary" onClick={onClose}>
            Close
          </Button>
        </div>
      </div>
    </Modal>
  )
}

/**
 * Large featured card for a customer-facing meeting.
 *
 * <p>The action pair depends on assignment state: an assigned meeting offers
 * Reassign + Join Call, an unassigned one offers View Agenda + Assign Engineer,
 * because there is no call to join until somebody is on the hook for it.</p>
 */
function CustomerMeetingCard({
  event,
  onOpen,
}: {
  event: UpcomingEvent
  onOpen: () => void
}) {
  const [assigning, setAssigning] = useState(false)
  const [agendaOpen, setAgendaOpen] = useState(false)
  const [joinError, setJoinError] = useState<string | null>(null)

  const isAssigned = Boolean(event.assignedEngineerId)
  const countdown = countdownLabel(event.daysUntil)
  // A title says what the assignee is there to do ("Lead Architect"), which is
  // what the chip promises; the department is the fallback when there is none.
  const assigneeRole =
    event.assignedEngineerDesignation ?? event.assignedEngineerDepartment

  // Both top pills sit above the title, but they cannot share the left edge or the
  // second one would read as a label for the title. An unassigned card leads with
  // "Needs Engineer" and keeps the countdown on the right; an assigned card has only
  // the countdown, which then leads on the left.
  const countdownOnLeft = isAssigned || !countdown

  const joinCall = () => {
    if (!event.meetingLink) {
      setJoinError('No meeting link was set for this event.')
      return
    }
    setJoinError(null)
    window.open(event.meetingLink, '_blank', 'noopener,noreferrer')
  }

  return (
<article className="flex flex-col overflow-hidden rounded-lg border border-surface-200 bg-surface-0 shadow-sm dark:rounded-xl dark:bg-[#161a20] dark:border-[#222731]">
        <div className="flex flex-wrap items-center justify-between gap-2 border-b border-surface-100 bg-surface-50 px-4 py-2.5 dark:border-[#1e232b] dark:bg-[#121519]">
          <div className="flex min-w-0 flex-wrap items-center gap-2">
            {!isAssigned && (
              <span className="inline-flex shrink-0 items-center gap-1 rounded-full border border-warning-200 bg-warning-50 px-2 py-0.5 text-[10px] font-semibold text-warning-700 dark:border-[#5c3a0a] dark:bg-[#3b2506] dark:text-[#fbbf24]">
              • Needs Engineer
            </span>
          )}
          {countdown && countdownOnLeft && (
            <span className="shrink-0 rounded-full bg-surface-200 px-2.5 py-0.5 text-[11px] font-semibold text-surface-700 dark:bg-[#1e232b] dark:text-[#718096]">
              {countdown}
            </span>
          )}
        </div>
        {countdown && !countdownOnLeft && (
          <span className="shrink-0 rounded-full bg-surface-200 px-2.5 py-0.5 text-[11px] font-semibold text-surface-700 dark:bg-[#1e232b] dark:text-[#718096]">
            {countdown}
          </span>
        )}
      </div>

      <div className="flex-1 space-y-3 px-4 py-4">
        <button
          type="button"
          onClick={onOpen}
          className="block text-left text-base font-semibold text-surface-800 hover:text-brand-700 hover:underline"
        >
          {event.subject}
        </button>

        <p className="flex items-center gap-1.5 text-sm text-surface-600">
          <CalendarClock className="h-4 w-4 shrink-0 text-surface-400" />
          {formatCardWhen(event)}
        </p>

        {isAssigned ? (
          <div className="flex items-center gap-2">
            <span className="text-xs text-surface-500">Assigned:</span>
            <span className="inline-flex min-w-0 items-center gap-1.5 rounded-full bg-brand-50 py-0.5 pl-0.5 pr-2.5 dark:border dark:border-[#222936] dark:bg-[#12161C]">
              <span className="grid h-6 w-6 shrink-0 place-items-center rounded-full bg-brand-600 text-[10px] font-semibold text-white dark:bg-[#00E599] dark:text-[#0D0F12] dark:font-bold">
                {initialsOf(event.assignedEngineerName)}
              </span>
              {/* Name and role stay inside one truncating element so the chip reads as a
                  single unit ("Sarah Jenkins (Lead Architect)") rather than two
                  fragments, while the role can still drop to muted slate on its own. */}
              <span className="truncate text-xs font-medium text-brand-800 dark:text-white dark:font-semibold">
                {event.assignedEngineerName}
                {assigneeRole && (
                  <span className="dark:text-slate-400 dark:font-normal"> ({assigneeRole})</span>
                )}
              </span>
            </span>
          </div>
        ) : (
          <span className="inline-flex items-center gap-1 rounded-full border border-warning-200 bg-warning-50 px-2 py-0.5 text-[11px] font-semibold text-warning-700 dark:border-[#4a2608] dark:bg-[#2e1805] dark:text-[#f97316]">
            • Unassigned
          </span>
        )}

        {joinError && <p className="text-xs text-error-600">{joinError}</p>}
      </div>

      <div className="flex flex-wrap items-center justify-between gap-2 border-t border-surface-100 bg-surface-50 px-4 py-2.5 dark:border-[#1e232b] dark:bg-[#121519]">
        <p className="flex min-w-0 items-center gap-1.5 text-xs text-surface-500 dark:text-[#8a99ad]">
          <Video className="h-3.5 w-3.5 shrink-0" />
          <span className="truncate">
            {event.location?.trim() || 'No platform set'}
          </span>
        </p>
        <div className="flex shrink-0 items-center gap-2">
          {isAssigned ? (
            <>
              <Button
                variant="secondary"
                size="sm"
                onClick={() => setAssigning(true)}
              >
                Reassign
              </Button>
              <Button size="sm" onClick={joinCall}>
                Join Call
              </Button>
            </>
          ) : (
            <>
              <Button
                variant="secondary"
                size="sm"
                onClick={() => setAgendaOpen(true)}
              >
                View Agenda
              </Button>
              <Button size="sm" onClick={() => setAssigning(true)}>
                <Plus className="h-4 w-4" />
                Assign Engineer
              </Button>
            </>
          )}
        </div>
      </div>

      {assigning && (
        <AssignEngineerSearch
          event={event}
          onClose={() => setAssigning(false)}
        />
      )}
      {agendaOpen && (
        <AgendaModal event={event} onClose={() => setAgendaOpen(false)} />
      )}
    </article>
  )
}

/** One highlighted band on the page, e.g. "Customer Meetings". */
function Section({
  title,
  hint,
  badge,
  children,
}: {
  title: string
  hint?: string
  badge?: string
  children: React.ReactNode
}) {
  return (
    <section className="mb-8 last:mb-0">
      <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
        <div className="flex items-center gap-2">
          <h2 className="text-sm font-semibold text-surface-800">{title}</h2>
          {hint && (
            <span className="rounded-full bg-surface-100 px-2 py-0.5 text-[11px] font-medium text-surface-600">
              {hint}
            </span>
          )}
        </div>
        {badge && (
          <span className="rounded-full border border-warning-200 bg-warning-50 px-2.5 py-0.5 text-[11px] font-semibold text-warning-700 dark:border-[#5c3a0a] dark:bg-[#3b2506] dark:text-[#fbbf24]">
            {badge}
          </span>
        )}
      </div>
      {children}
    </section>
  )
}

export function EventsPage() {
  const [days, setDays] = useState(30)
  const [tab, setTab] = useState<PillTab>(ALL_TAB)
  const [view, setView] = useState<'grid' | 'list'>('grid')
  const [showCreate, setShowCreate] = useState(false)
  const [selected, setSelected] = useState<UpcomingEvent | null>(null)

  const { data, isLoading, isError, error, refetch } = useQuery({
    queryKey: ['events', 'upcoming', days],
    queryFn: () => eventApi.upcoming(days),
  })

  const remoteEvents = data?.events ?? []

  /**
   * Real feed rows, topped up in dev with any reference card the database does
   * not already provide. `withSampleFallback` is a no-op outside dev, so a
   * production build renders the feed untouched.
   */
  const events = useMemo(
    () =>
      withSampleFallback(remoteEvents, [
        ...DEV_SAMPLE_EVENTS,
        ...DEV_SAMPLE_HOLIDAY_MEETINGS,
      ]),
    [remoteEvents],
  )
  const usingSampleData =
    devSampleMode() && events.length !== remoteEvents.length

  /** Per-pill counts, so every pill carries a live number from the feed. */
  const counts = useMemo(() => {
    const map = new Map<FilterKey, number>()
    for (const pill of FILTER_PILLS) {
      map.set(
        pill.key,
        events.filter((e) => CATEGORY_SET[pill.key].has(e.category)).length,
      )
    }
    return map
  }, [events])

  const visible = useMemo(
    () =>
      tab === ALL_TAB
        ? events
        : events.filter((e) => CATEGORY_SET[tab as FilterKey].has(e.category)),
    [events, tab],
  )

  /**
   * The two highlighted sections. A pill other than All narrows to just the
   * matching section, so the page never shows an empty band above real content.
   */
  const customerEvents = useMemo(
    () => visible.filter((e) => CATEGORY_SET.CUSTOMER.has(e.category)),
    [visible],
  )
  const otherEvents = useMemo(
    () => visible.filter((e) => !CATEGORY_SET.CUSTOMER.has(e.category)),
    [visible],
  )

  const windowLabel =
    WINDOW_OPTIONS.find((o) => o.value === String(days))?.label ??
    `Next ${days} days`

  const renderCards = (list: UpcomingEvent[]) => (
    <div
      className={
        view === 'grid'
          ? 'grid gap-3 md:grid-cols-2 lg:grid-cols-3'
          : 'flex flex-col gap-2'
      }
    >
      {list.map((event) => (
        <EventCard
          key={`${event.source}-${event.id}`}
          event={event}
          layout={view}
          onOpen={() => setSelected(event)}
        />
      ))}
    </div>
  )

  // Customer meetings get the large featured treatment regardless of the Grid/List
  // toggle, since the reference layout reserves that toggle for the internal list below.
  const renderCustomerCards = (list: UpcomingEvent[]) => (
    <div className="grid gap-4 lg:grid-cols-2">
      {list.map((event) => (
        <CustomerMeetingCard
          key={`${event.source}-${event.id}`}
          event={event}
          onOpen={() => setSelected(event)}
        />
      ))}
    </div>
  )

  if (isLoading) return <LoadingState label="Loading upcoming events…" />

  return (
    <div>
      {/* Reference layout: the range picker, view toggle and Add Event button sit
          together on the top right, above the title rather than beside it. */}
      <div className="mb-4 flex flex-wrap items-center justify-end gap-2">
        <Select
          name="window"
          value={String(days)}
          onChange={(e) => setDays(Number(e.target.value))}
          options={WINDOW_OPTIONS}
          className="w-auto"
        />
        <div className="flex items-center gap-1 rounded-md border border-surface-200 p-0.5">
          <button
            type="button"
            aria-label="Grid view"
            aria-pressed={view === 'grid'}
            onClick={() => setView('grid')}
            className={cn(
              'rounded p-1.5 transition-colors',
              view === 'grid'
                ? 'bg-brand-50 text-brand-700'
                : 'text-surface-400 hover:bg-surface-100',
            )}
          >
            <LayoutGrid className="h-4 w-4" />
          </button>
          <button
            type="button"
            aria-label="List view"
            aria-pressed={view === 'list'}
            onClick={() => setView('list')}
            className={cn(
              'rounded p-1.5 transition-colors',
              view === 'list'
                ? 'bg-brand-50 text-brand-700'
                : 'text-surface-400 hover:bg-surface-100',
            )}
          >
            <List className="h-4 w-4" />
          </button>
        </div>
        <Button onClick={() => setShowCreate(true)}>
          <Plus className="h-4 w-4" />
          Add Event
        </Button>
      </div>

      <PageHeader
        title={
          <span className="flex flex-wrap items-center gap-2">
            Events &amp; Schedule
            <span className="rounded-full border border-success-200 bg-success-50 px-2.5 py-0.5 text-[11px] font-semibold text-success-700">
              Q4 FY26 Active
            </span>
          </span>
        }
        subtitle="View upcoming organizational holidays, birthdays, team syncs, and remote office sessions."
      />

      {isError && !usingSampleData ? (
        <div className="card p-6 text-center">
          <p className="text-sm text-error-600">{extractMessage(error)}</p>
          <Button
            variant="secondary"
            size="sm"
            className="mt-3"
            onClick={() => refetch()}
          >
            Try again
          </Button>
        </div>
      ) : events.length === 0 ? (
        <div className="card">
          <EmptyState
            title="Nothing coming up"
            description={`No holidays, birthdays or events in the next ${days} days.`}
          />
        </div>
      ) : (
        <>
          {usingSampleData && (
            <div className="mb-4 rounded-md border border-warning-200 bg-warning-50 px-3 py-2 text-xs text-warning-800 dark:border-[#5c3a0a] dark:bg-[#3b2506] dark:text-[#fbbf24]">
              Showing reference sample events. The feed did not supply{' '}
              {events.length - remoteEvents.length} of them
              {isError ? ' (API unreachable)' : ''}.
            </div>
          )}

          <div
            className="mb-6 flex flex-wrap gap-2"
            role="tablist"
            aria-label="Event categories"
          >
            <button
              type="button"
              role="tab"
              aria-selected={tab === ALL_TAB}
              onClick={() => setTab(ALL_TAB)}
              className={cn(
                'rounded-full px-3.5 py-1.5 text-xs font-semibold transition-colors',
                tab === ALL_TAB
                  ? 'bg-brand-600 text-white dark:border dark:border-[#0a5c43] dark:bg-[#063b2b] dark:text-[#00e599]'
                  : 'border border-surface-200 text-surface-600 hover:bg-surface-100 dark:border-[#2d3748] dark:bg-[#1a202c] dark:text-[#94a3b8]',
              )}
            >
              All ({events.length})
            </button>
            {FILTER_PILLS.map((pill) => {
              const isActive = tab === pill.key
              return (
                <button
                  key={pill.key}
                  type="button"
                  role="tab"
                  aria-selected={isActive}
                  onClick={() => setTab(pill.key)}
                  className={cn(
                    'inline-flex items-center gap-2 rounded-full px-3.5 py-1.5 text-xs font-semibold transition-colors',
                    isActive
                      ? 'bg-brand-600 text-white dark:border dark:border-[#0a5c43] dark:bg-[#063b2b] dark:text-[#00e599]'
                      : 'border border-surface-200 text-surface-600 hover:bg-surface-100 dark:border-[#2d3748] dark:bg-[#1a202c] dark:text-[#94a3b8]',
                  )}
                >
                  {pill.key === 'CUSTOMER' && (
                    <span
                      className={cn(
                        'h-1.5 w-1.5 rounded-full',
                        isActive ? 'bg-[#00e599]' : 'bg-success-500 dark:bg-[#00e599]',
                      )}
                    />
                  )}
                  {pill.label} ({counts.get(pill.key) ?? 0})
                </button>
              )
            })}
          </div>

          {visible.length === 0 ? (
            <div className="card">
              <EmptyState title="Nothing in this category" />
            </div>
          ) : (
            <>
              {customerEvents.length > 0 && (
                <Section
                  title="Customer Meetings"
                  hint={`${customerEvents.length} scheduled this month`}
                  badge="High Priority"
                >
                  {renderCustomerCards(customerEvents)}
                </Section>
              )}

              {otherEvents.length > 0 && (
                <Section
                  title={`Upcoming Holidays & Internal Meetings • ${otherEvents.length} Events`}
                >
                  <div className="mb-3 flex items-center justify-end text-xs text-surface-500">
                    <span>{windowLabel}</span>
                  </div>
                  <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
                    {otherEvents.map((event) => (
                      <HolidayMeetingCard
                        key={`${event.source}-${event.id}`}
                        event={event}
                        onOpen={() => setSelected(event)}
                      />
                    ))}
                  </div>
                </Section>
              )}
            </>
          )}
        </>
      )}

      <Modal
        open={showCreate}
        onClose={() => setShowCreate(false)}
        title="Schedule New Event"
        subtitle="Create a meeting, holiday entry, or company session across teams."
        icon={
          <span className="grid h-10 w-10 place-items-center rounded-xl bg-success-50 text-success-700">
            <CalendarPlus className="h-5 w-5" />
          </span>
        }
        size="2xl"
        variant="feature"
      >
        {showCreate && <AddEventForm onDone={() => setShowCreate(false)} />}
      </Modal>

      {selected && (
        <EventDetails
        event={selected}
        onClose={() => setSelected(null)}
        onSaved={setSelected}
      />
      )}
    </div>
  )
}

/** Re-exported for the unit tests, which assert the datetime split directly. */
export const __testing = {
  relativeDay,
  formatWhen,
  formatCardWhen,
  timeZoneLabel,
}
