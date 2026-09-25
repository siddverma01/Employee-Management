import { forwardRef, type InputHTMLAttributes } from 'react'
import { cn } from '@/utils'

interface InputProps extends InputHTMLAttributes<HTMLInputElement> {
  label?: string
  error?: string
}

export const Input = forwardRef<HTMLInputElement, InputProps>(
  ({ className, label, error, id, ...props }, ref) => {
    const inputId = id ?? props.name
    return (
      <div className="w-full">
        {label && (
          <label htmlFor={inputId} className="mb-1 block text-sm font-medium text-surface-700">
            {label}
          </label>
        )}
        <input
          ref={ref}
          id={inputId}
          aria-invalid={error ? true : undefined}
          className={cn(
            'w-full rounded-md border border-surface-300 bg-field px-3 py-2 text-sm text-surface-800 placeholder:text-surface-400 transition-colors hover:border-surface-400 focus:border-brand-500 focus:ring-[3px] focus:ring-brand-500/20 disabled:bg-surface-100 disabled:text-surface-500',
            error && 'border-red-500',
            className,
          )}
          {...props}
        />
        {error && <p className="mt-1 text-xs text-red-600">{error}</p>}
      </div>
    )
  },
)
Input.displayName = 'Input'