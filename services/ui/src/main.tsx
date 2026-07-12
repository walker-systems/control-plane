import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { QueryClientProvider } from '@tanstack/react-query'
import { queryClient } from '@/lib/query-client'
import { hydrateSession } from '@/lib/auth'
import App from '@/App'
import './index.css'

// Synchronous: fill in the roles field from the access token's claims
// for any persisted session whose stored record was written before
// roles were tracked (or before a token refresh). Runs before render
// so the first paint already reflects the correct role gating.
hydrateSession()

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <App />
    </QueryClientProvider>
  </StrictMode>,
)
