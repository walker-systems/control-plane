import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom'
import { Login } from '@/routes/Login'
import { Dashboard } from '@/routes/Dashboard'
import { JobsList } from '@/routes/JobsList'
import { JobDetail } from '@/routes/JobDetail'
import { SchedulesList } from '@/routes/SchedulesList'
import { ScheduleDetail } from '@/routes/ScheduleDetail'
import { ScheduleCreate } from '@/routes/ScheduleCreate'
import { ProtectedRoute } from '@/routes/ProtectedRoute'
import { Layout } from '@/routes/Layout'

// Route tree:
//   /login           — public
//   /                — Layout (protected) → Dashboard
//     /jobs, /jobs/:id
//     /schedules, /schedules/new, /schedules/:id
//   *                — redirect to /
//
// /schedules/new is declared before /schedules/:id for readability
// only — React Router v6 ranks static segments above dynamic ones
// regardless of declaration order.
function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/login" element={<Login />} />
        <Route element={<ProtectedRoute />}>
          <Route element={<Layout />}>
            <Route path="/" element={<Dashboard />} />
            <Route path="/jobs" element={<JobsList />} />
            <Route path="/jobs/:id" element={<JobDetail />} />
            <Route path="/schedules" element={<SchedulesList />} />
            <Route path="/schedules/new" element={<ScheduleCreate />} />
            <Route path="/schedules/:id" element={<ScheduleDetail />} />
          </Route>
        </Route>
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </BrowserRouter>
  )
}

export default App
