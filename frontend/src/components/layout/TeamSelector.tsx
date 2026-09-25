import { Building2 } from 'lucide-react'
import { useTeam } from '@/hooks/useTeam'
import { cn } from '@/utils'

export function TeamSelector({ className }: { className?: string }) {
  const { teams, teamId, teamLabel, isAdmin, setTeamId } = useTeam()

  return (
    <div className={cn('flex min-w-0 items-center gap-2', className)}>
      <Building2 className="h-4 w-4 shrink-0 text-brand-600" />
      <select
        aria-label="Select team"
        value={teamId ?? ''}
        onChange={(e) => setTeamId(e.target.value === '' ? null : Number(e.target.value))}
        className={cn(
          'w-full min-w-0 truncate rounded-lg border border-surface-300 bg-field px-2 py-1.5 text-sm font-medium text-surface-800 transition-colors hover:border-surface-400',
          'focus:outline-none focus:ring-2 focus:ring-brand-500',
        )}
      >
        {isAdmin ? (
          <option value="">All Teams</option>
        ) : (
          <option value="">My Team · {teamLabel}</option>
        )}
        {teams.map((t) => (
          <option key={t.id} value={String(t.id)}>
            {t.name}
          </option>
        ))}
      </select>
    </div>
  )
}