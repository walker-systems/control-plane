import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { QueryClientProvider } from '@tanstack/react-query'
import { queryClient } from '@/lib/query-client'
import { hydrateSession, pingSession } from '@/lib/auth'
import App from '@/App'
import './index.css'

// Two-tier stale-session eviction:
//  1. hydrateSession — synchronous. Fills in roles from the token's
//     claims and evicts sessions whose JWT `exp` has already passed.
//     Runs before render so the first paint reflects correct gating.
//  2. pingSession    — asynchronous, fire-and-forget. Hits an
//     authenticated endpoint so the server gets to reject tokens the
//     client can't invalidate locally (secret rotation, revocation).
//     The api() 401 handler evicts the session if it fails.
hydrateSession()
void pingSession()

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <App />
    </QueryClientProvider>
  </StrictMode>,
)
