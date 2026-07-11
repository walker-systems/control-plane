import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom'
import { Login } from '@/routes/Login'
import { Dashboard } from '@/routes/Dashboard'
import { ProtectedRoute } from '@/routes/ProtectedRoute'
import { Layout } from '@/routes/Layout'

// Route tree:
//   /login           — public
//   /                — Layout (protected) → Dashboard
//     /jobs          — placeholder for Phase C.2
//     /schedules     — placeholder for Phase C.3
//   *                — redirect to /
function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/login" element={<Login />} />
        <Route element={<ProtectedRoute />}>
          <Route element={<Layout />}>
            <Route path="/" element={<Dashboard />} />
            <Route path="/jobs" element={<PlaceholderPage title="Jobs" />} />
            <Route path="/schedules" element={<PlaceholderPage title="Schedules" />} />
          </Route>
        </Route>
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </BrowserRouter>
  )
}

function PlaceholderPage({ title }: { title: string }) {
  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-semibold text-slate-900">{title}</h1>
      <div className="rounded-lg border border-slate-200 bg-white p-6 text-slate-600">
        Lands in the next PR.
      </div>
    </div>
  )
}

export default App
