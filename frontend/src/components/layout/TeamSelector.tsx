import { Building2, ChevronDown } from 'lucide-react'
import { useTeam } from '@/hooks/useTeam'
import { cn } from '@/utils'

export function TeamSelector({ className }: { className?: string }) {
  const { teams, teamId, teamLabel, isAdmin, setTeamId } = useTeam()

  return (
    /* The select stays the interactive element (unchanged behaviour and native
       option list); the icon and chevron are absolutely positioned over a
       padded control so the UA arrow can be suppressed without replacing the
       control. 40px tall, 8px radius. Light: white, hairline border, one-step
       shadow. Dark: the header surface itself with a dark-grey hairline and no
       shadow, so it reads flat against the rail. */
    <div className={cn('relative flex min-w-0 items-center', className)}>
      <Building2 className="pointer-events-none absolute left-2.5 h-4 w-4 shrink-0 text-brand-600" strokeWidth={1.5} />
      <select
        aria-label="Select team"
        value={teamId ?? ''}
        onChange={(e) => setTeamId(e.target.value === '' ? null : Number(e.target.value))}
        className={cn(
          'h-10 w-full min-w-0 appearance-none truncate rounded-lg border border-slate-200 bg-white pl-9 pr-8 text-sm font-medium leading-none text-surface-800 shadow-hpe-sm transition-colors hover:border-surface-400',
          'focus:outline-none focus:ring-2 focus:ring-brand-500',
          'dark:border-[#2E323B] dark:bg-[#141518] dark:text-slate-100 dark:shadow-none',
        )}
      >
        {isAdmin ? (
          <option value="">All Teams &amp; Guilds</option>
        ) : (
          <option value="">My Team · {teamLabel}</option>
        )}
        {teams.map((t) => (
          <option key={t.id} value={String(t.id)}>
            {t.name}
          </option>
        ))}
      </select>
      <ChevronDown
        aria-hidden="true"
        strokeWidth={1.5}
        className="pointer-events-none absolute right-2.5 h-4 w-4 shrink-0 text-surface-500 dark:text-slate-400"
      />
    </div>
  )
}
