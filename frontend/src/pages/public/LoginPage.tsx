import { useEffect, useState } from 'react'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { useLocation, useNavigate } from 'react-router-dom'
import toast from 'react-hot-toast'
import { loginSchema, type LoginForm } from '@/validations/schemas'
import { useAuth } from '@/hooks/useAuth'
import { extractMessage } from '@/api/client'
import { Input } from '@/components/ui/Input'
import { Button } from '@/components/ui/Button'
import hpeElement from '@/assets/hpe-element-color.svg'

export function LoginPage() {
  const { user, login, loading } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()
  const [submitting, setSubmitting] = useState(false)

  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<LoginForm>({ resolver: zodResolver(loginSchema), defaultValues: { rememberMe: true } })

  useEffect(() => {
    if (user && !loading) {
      const dest = (location.state as { from?: string } | null)?.from
      navigate(dest ?? (user.role === 'ADMIN' ? '/admin/dashboard' : '/dashboard'), { replace: true })
    }
  }, [user, loading, navigate, location.state])

  const onSubmit = handleSubmit(async (values) => {
    try {
      setSubmitting(true)
      await login(values.email, values.password, values.rememberMe)
      toast.success('Signed in successfully')
    } catch (err) {
      toast.error(extractMessage(err))
    } finally {
      setSubmitting(false)
    }
  })

  return (
    <div className="flex min-h-screen items-center justify-center bg-surface-50 p-4">
      <div className="w-full max-w-md">
        <div className="mb-8 text-center">
          <img src={hpeElement} alt="HPE" className="mx-auto mb-5 block h-6 w-auto" />
          <h1 className="text-2xl font-semibold text-surface-800">Employee management</h1>
          <p className="mt-1 text-sm text-surface-500">Sign in to access your workspace</p>
        </div>

        <form onSubmit={onSubmit} className="card p-6">
          <div className="space-y-4">
            <Input
              label="Email"
              type="email"
              placeholder="you@company.com"
              autoComplete="email"
              {...register('email')}
              error={errors.email?.message}
            />
            <Input
              label="Password"
              type="password"
              placeholder="••••••••"
              autoComplete="current-password"
              {...register('password')}
              error={errors.password?.message}
            />
            <label className="flex items-center gap-2 text-sm text-surface-600">
              <input type="checkbox" className="h-4 w-4 rounded border-surface-300 text-brand-500 focus:outline-none" {...register('rememberMe')} />
              Remember me
            </label>
            <Button type="submit" loading={submitting} className="w-full">
              Sign in
            </Button>
          </div>
        </form>

        <div className="mt-6 rounded-lg border border-surface-200 bg-surface-0 p-4 text-xs leading-relaxed text-surface-600">
          <p className="mb-1 font-semibold text-surface-700">Demo credentials (dev seed)</p>
          <p>Admin: admin@emplmgt.com / Admin@123</p>
          <p>Employee: masher.choudhary-ext@hpe.com / Welcome@123</p>
        </div>
      </div>
    </div>
  )
}