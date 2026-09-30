import { useEffect, useRef, type ReactNode } from 'react'
import { X } from 'lucide-react'
import { cn } from '@/utils'

interface ModalProps {
  open: boolean
  onClose: () => void
  title: string
  children: ReactNode
  size?: 'sm' | 'md' | 'lg' | '2xl'
  footer?: ReactNode
  /**
   * Secondary line under the title. Only rendered when provided, so the 12
   * existing callers keep their current single-line header.
   */
  subtitle?: string
  /** Leading element in the header, e.g. a coloured icon box. */
  icon?: ReactNode
  /**
   * `'feature'` switches to the focused treatment used by the Schedule New Event
   * dialog: a wider, roomier, more strongly elevated panel over a blurred scrim.
   * Defaults to `'default'`, which is unchanged for every other caller.
   */
  variant?: 'default' | 'feature'
}

export function Modal({
  open,
  onClose,
  title,
  children,
  size = 'md',
  footer,
  subtitle,
  icon,
  variant = 'default',
}: ModalProps) {
  const closeRef = useRef<HTMLButtonElement>(null)

  useEffect(() => {
    if (!open) return
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onClose()
    document.addEventListener('keydown', onKey)
    document.body.style.overflow = 'hidden'
    closeRef.current?.focus()
    return () => {
      document.removeEventListener('keydown', onKey)
      document.body.style.overflow = ''
    }
  }, [open, onClose])

  if (!open) return null

  const sizes = { sm: 'max-w-md', md: 'max-w-xl', lg: 'max-w-3xl', '2xl': 'max-w-2xl' }
  const feature = variant === 'feature'

  return (
    <div
      className={cn(
        'fixed inset-0 z-50 flex items-start justify-center overflow-y-auto p-4 sm:items-center',
        // The scrim is deliberately not a theme token: `--hpe-surface-900` inverts
        // in dark mode, so a token scrim would wash the app out instead of dimming
        // it. A fixed black matches the default variant's rgba(0,0,0,...) scrim.
        feature ? 'backdrop-blur-sm bg-black/40' : 'bg-[rgba(0,0,0,0.12)]',
      )}
      onClick={onClose}
    >
      <div
        role="dialog"
        aria-modal="true"
        aria-label={title}
        className={cn(
          'w-full my-8',
          feature
            ? cn('rounded-2xl border border-surface-200 bg-surface-0 p-8 shadow-2xl', sizes[size])
            : cn('card shadow-hpe-lg', sizes[size]),
        )}
        onClick={(e) => e.stopPropagation()}
      >
        <div
          className={cn(
            'flex items-start justify-between',
            feature ? 'mb-6' : 'border-b border-surface-200 px-5 py-4',
          )}
        >
          <div className="flex min-w-0 items-center gap-3">
            {icon && <span className="shrink-0">{icon}</span>}
            <div className="min-w-0">
              <h2 className={cn('font-semibold text-surface-800', feature ? 'text-lg' : 'text-base')}>
                {title}
              </h2>
              {subtitle && <p className="mt-0.5 text-sm text-surface-500">{subtitle}</p>}
            </div>
          </div>
          <button
            ref={closeRef}
            className="rounded p-1 text-surface-400 transition-colors hover:bg-surface-100 hover:text-surface-600"
            onClick={onClose}
            aria-label="Close"
          >
            <X className="h-5 w-5" />
          </button>
        </div>
        {/* The feature variant pads the panel once, so its sections supply no padding. */}
        <div className={feature ? undefined : 'px-5 py-4'}>{children}</div>
        {footer && (
          <div
            className={cn(
              'flex justify-end gap-2',
              feature ? 'mt-8' : 'border-t border-surface-200 px-5 py-3',
            )}
          >
            {footer}
          </div>
        )}
      </div>
    </div>
  )
}
