// Placeholder — Phase C.2 will replace with real status tiles and
// recent activity. Landing here after login proves the whole auth
// loop (login → token stored → protected route → guarded page) works.
export function Dashboard() {
  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-semibold text-slate-900">Dashboard</h1>
      <div className="rounded-lg border border-slate-200 bg-white p-6">
        <p className="text-slate-600">
          Signed in successfully. Job status tiles and recent activity land in
          the next PR.
        </p>
      </div>
    </div>
  )
}
