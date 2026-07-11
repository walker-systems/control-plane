import { Navigate, Outlet, useLocation } from 'react-router-dom'
import { useAuthStore } from '@/lib/auth-store'

// Route guard: if we have an access token, render the child routes.
// Otherwise send the user to /login and remember where they were
// heading so we can send them back after login.
export function ProtectedRoute() {
  const token = useAuthStore((s) => s.accessToken)
  const location = useLocation()
  if (!token) {
    return (
      <Navigate
        to="/login"
        replace
        state={{ from: location.pathname + location.search }}
      />
    )
  }
  return <Outlet />
}
