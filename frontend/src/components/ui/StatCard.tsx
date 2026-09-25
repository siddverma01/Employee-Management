export function StatCard({
  label,
  value,
  icon,
  accent = 'brand',
}: {
  label: string
  value: string | number
  icon?: React.ReactNode
  accent?: 'brand' | 'emerald' | 'amber' | 'red' | 'violet' | 'info'
}) {
  const accents = {
    brand: 'bg-brand-50 text-brand-700 dark:bg-surface-100 dark:text-brand-400',
    emerald: 'bg-success-50 text-success-700 dark:bg-surface-100 dark:text-emerald-400',
    amber: 'bg-warning-50 text-warning-700 dark:bg-surface-100 dark:text-amber-400',
    red: 'bg-error-50 text-error-700 dark:bg-surface-100 dark:text-red-400',
    violet: 'bg-violet-50 text-violet-700 dark:bg-surface-100 dark:text-violet-400',
    info: 'bg-info-50 text-info-700 dark:bg-surface-100 dark:text-sky-400',
  }
  return (
    <div className="card flex items-center gap-4 p-4">
      {icon && (
        <div className={`flex h-11 w-11 shrink-0 items-center justify-center rounded-lg ${accents[accent]}`}>{icon}</div>
      )}
      <div className="min-w-0">
        <p className="truncate text-xs font-medium text-surface-500">{label}</p>
        <p className="mt-0.5 text-2xl font-semibold text-surface-800">{value}</p>
      </div>
    </div>
  )
}