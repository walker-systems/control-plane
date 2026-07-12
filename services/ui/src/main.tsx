import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { QueryClientProvider } from '@tanstack/react-query'
import { queryClient } from '@/lib/query-client'
import { hydrateSession } from '@/lib/auth'
import App from '@/App'
import './index.css'

// Fire-and-forget: refresh the persisted session against /me so any
// stored user record without roles (or with stale roles) gets
// hydrated before the router-level auth checks matter. Not awaited —
// we want the first paint to happen immediately.
void hydrateSession()

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <App />
    </QueryClientProvider>
  </StrictMode>,
)
