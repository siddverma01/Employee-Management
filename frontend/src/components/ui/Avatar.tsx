import { initials } from '@/utils'

export function Avatar({ name, src, size = 'md' }: { name?: string | null; src?: string | null; size?: 'sm' | 'md' | 'lg' }) {
  const sizes = { sm: 'h-8 w-8 text-xs', md: 'h-10 w-10 text-sm', lg: 'h-16 w-16 text-lg' }
  const display = src
    ? <img src={src} alt={name ?? 'avatar'} className="h-full w-full rounded-full object-cover" />
    : <span className="font-semibold text-[#0B0D12]">{initials(name)}</span>
  return (
    <div className={`${sizes[size]} flex shrink-0 items-center justify-center overflow-hidden rounded-full bg-brand-500 ring-1 ring-brand-500/30`}>
      {display}
    </div>
  )
}