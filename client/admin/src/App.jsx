import React from 'react'
import { Routes, Route, Navigate, NavLink, useNavigate } from 'react-router-dom'
import { useAuth } from './auth'
import NAV from './nav'
import Login from './pages/Login'
import Dashboard from './pages/Dashboard'
import Users from './pages/Users'
import Products from './pages/Products'
import Orders from './pages/Orders'
import Logistics from './pages/Logistics'
import Payments from './pages/Payments'
import Disputes from './pages/Disputes'
import Reports from './pages/Reports'

function RequireAuth({ children }) {
  const { user, ready } = useAuth()
  if (!ready) return <div className="page">Loading…</div>
  if (!user) return <Navigate to="/login" replace />
  return children
}

export default function App() {
  const { user, logout } = useAuth()
  const navigate = useNavigate()

  return (
    <Routes>
      <Route path="/login" element={<Login />} />
      <Route
        path="/*"
        element={
          <RequireAuth>
            <div className="shell">
              <aside className="sidebar">
                <h1 className="brand">Kilivana Admin</h1>
                <nav>
                  {NAV.map(([label, path, icon]) => (
                    <NavLink
                      key={path}
                      to={path}
                      end={path === '/'}
                      className={({ isActive }) => (isActive ? 'nav-link active' : 'nav-link')}
                    >
                      <span>{icon}</span> {label}
                    </NavLink>
                  ))}
                </nav>
                <div className="sidebar-footer">
                  <div className="user-chip">{user?.fullName}</div>
                  <button
                    className="btn ghost"
                    onClick={() => logout().then(() => navigate('/login'))}
                  >
                    Sign out
                  </button>
                </div>
              </aside>
              <main className="content">
                <Routes>
                  <Route path="/" element={<Dashboard />} />
                  <Route path="/users" element={<Users />} />
                  <Route path="/products" element={<Products />} />
                  <Route path="/orders" element={<Orders />} />
                  <Route path="/logistics" element={<Logistics />} />
                  <Route path="/payments" element={<Payments />} />
                  <Route path="/disputes" element={<Disputes />} />
                  <Route path="/reports" element={<Reports />} />
                  <Route path="*" element={<Navigate to="/" replace />} />
                </Routes>
              </main>
            </div>
          </RequireAuth>
        }
      />
    </Routes>
  )
}