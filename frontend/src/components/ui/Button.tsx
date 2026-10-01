import { forwardRef, type ButtonHTMLAttributes } from 'react'
import { Loader2 } from 'lucide-react'
import { cn } from '@/utils'

interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: 'primary' | 'secondary' | 'danger' | 'ghost'
  size?: 'sm' | 'md'
  loading?: boolean
}

export const Button = forwardRef<HTMLButtonElement, ButtonProps>(
  ({ className, variant = 'primary', size = 'md', loading = false, children, disabled, ...props }, ref) => {
    const base =
      'inline-flex items-center justify-center gap-2 rounded-md font-medium transition-colors disabled:opacity-50 disabled:cursor-not-allowed'
    const variants = {
      primary:
        'bg-brand-500 text-white shadow-hpe-teal hover:bg-brand-600 dark:bg-[#00e599] dark:text-[#0d0f12] dark:font-semibold dark:shadow-none dark:hover:bg-[#00c785]',
      secondary:
        'border border-surface-300 bg-surface-0 text-surface-700 hover:bg-surface-100 dark:border-[#333d4d] dark:bg-[#1e232b] dark:text-[#cbd5e1] dark:hover:bg-[#2a313d]',
      danger: 'bg-red-500 text-white hover:bg-red-600',
      ghost: 'text-surface-600 hover:bg-surface-100',
    }
    const sizes = { sm: 'px-3 py-1.5 text-xs', md: 'px-4 py-2 text-sm' }
    return (
      <button
        ref={ref}
        disabled={disabled || loading}
        className={cn(base, variants[variant], sizes[size], className)}
        {...props}
      >
        {loading && <Loader2 className="h-4 w-4 animate-spin" />}
        {children}
      </button>
    )
  },
)
Button.displayName = 'Button'