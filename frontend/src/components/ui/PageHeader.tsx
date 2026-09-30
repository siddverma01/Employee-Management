export function PageHeader({
  title,
  subtitle,
  actions,
}: {
  /** Plain text, or a node when the heading needs an inline badge. */
  title: React.ReactNode
  subtitle?: string
  actions?: React.ReactNode
}) {
  return (
    <div className="mb-6 flex flex-wrap items-start justify-between gap-3">
      <div>
        <h1 className="text-xl font-semibold text-surface-800">{title}</h1>
        {subtitle && <p className="mt-1 text-sm text-surface-500">{subtitle}</p>}
      </div>
      {actions && <div className="flex flex-wrap items-center gap-2">{actions}</div>}
    </div>
  )
}