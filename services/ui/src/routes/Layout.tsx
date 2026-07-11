import { NavLink, Outlet, useNavigate } from 'react-router-dom'
import { useAuthStore } from '@/lib/auth-store'
import { Button } from '@/components/ui/Button'

export function Layout() {
  const user = useAuthStore((s) => s.user)
  const clear = useAuthStore((s) => s.clear)
  const navigate = useNavigate()

  return (
    <div className="min-h-full flex flex-col">
      <header className="border-b border-slate-200 bg-white">
        <div className="mx-auto max-w-6xl flex h-14 items-center gap-6 px-4">
          <NavLink to="/" className="font-semibold text-slate-900">
            Control Plane
          </NavLink>
          <nav className="flex items-center gap-1 text-sm">
            <NavItem to="/">Dashboard</NavItem>
            <NavItem to="/jobs">Jobs</NavItem>
            <NavItem to="/schedules">Schedules</NavItem>
          </nav>
          <div className="ml-auto flex items-center gap-3 text-sm">
            {user && <span className="text-slate-500">{user.email}</span>}
            <Button
              variant="ghost"
              size="sm"
              onClick={() => {
                clear()
                navigate('/login', { replace: true })
              }}
            >
              Sign out
            </Button>
          </div>
        </div>
      </header>
      <main className="flex-1 mx-auto w-full max-w-6xl px-4 py-6">
        <Outlet />
      </main>
    </div>
  )
}

function NavItem({ to, children }: { to: string; children: React.ReactNode }) {
  return (
    <NavLink
      to={to}
      end={to === '/'}
      className={({ isActive }) =>
        'px-2 py-1 rounded-md ' +
        (isActive ? 'bg-slate-100 text-slate-900' : 'text-slate-600 hover:text-slate-900')
      }
    >
      {children}
    </NavLink>
  )
}
