import React from 'react'
import ReactDOM from 'react-dom/client'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { Toaster } from 'react-hot-toast'
import App from './App'
import { ErrorBoundary } from '@/components/ui/ErrorBoundary'
import './styles/tokens.css'
import './index.css'
// Page-scoped dark theme layers. Imported last so their `.dark .attendance-*`
// rules are the final word in the cascade over the shared utilities in index.css.
import './styles/attendance-analytics.css'
import './styles/attendance-roster.css'

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: 1,
      refetchOnWindowFocus: false,
      staleTime: 30_000,
    },
  },
})

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <QueryClientProvider client={queryClient}>
      <ErrorBoundary title="The application hit an unexpected error.">
        <App />
      </ErrorBoundary>
      <Toaster position="top-right" toastOptions={{ duration: 3500 }} />
    </QueryClientProvider>
  </React.StrictMode>,
)