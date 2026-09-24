import { useState } from 'react'
import { api } from '../api'
import { usePage, Pagination, StatusBadge, Empty } from '../usePage'

const ROLE_OPTIONS = ['BUYER', 'SUPPLIER', 'FARMER', 'INSPECTOR', 'DRIVER', 'ADMIN']
const STATUS_OPTIONS = ['ACTIVE', 'PENDING_VERIFICATION', 'SUSPENDED', 'DEACTIVATED']

export default function Users() {
  const [role, setRole] = useState('')
  const [status, setStatus] = useState('')
  const { data, loading, error, page, setPage, refresh } = usePage(
    '/admin/users',
    { role, status },
    [role, status],
  )

  async function act(u, action) {
    try {
      if (action === 'verify') {
        await api.patch(`/users/${u.userId}/verification`, { verificationStatus: 'VERIFIED' })
      } else {
        await api.patch(`/users/${u.userId}/status`, { status: action })
      }
      refresh()
    } catch (e) {
      alert(e.message)
    }
  }

  return (
    <div className="page">
      <h1>Users</h1>
      <div className="filters">
        <label>
          Role
          <select value={role} onChange={(e) => { setRole(e.target.value); setPage(0) }}>
            <option value="">All</option>
            {ROLE_OPTIONS.map((r) => <option key={r} value={r}>{r}</option>)}
          </select>
        </label>
        <label>
          Status
          <select value={status} onChange={(e) => { setStatus(e.target.value); setPage(0) }}>
            <option value="">All</option>
            {STATUS_OPTIONS.map((s) => <option key={s} value={s}>{s}</option>)}
          </select>
        </label>
        <span className="muted">total {data.totalElements}</span>
      </div>
      <Empty loading={loading} error={error}>
        <table className="table">
          <thead>
            <tr><th>Name</th><th>Email</th><th>Role</th><th>Status</th><th>Verification</th><th>Actions</th></tr>
          </thead>
          <tbody>
            {data.content.map((u) => (
              <tr key={u.userId}>
                <td>{u.fullName}</td>
                <td>{u.email}</td>
                <td><StatusBadge value={u.role} /></td>
                <td><StatusBadge value={u.status} /></td>
                <td><StatusBadge value={u.verificationStatus} /></td>
                <td className="actions">
                  {u.verificationStatus !== 'VERIFIED' && (
                    <button className="btn sm" onClick={() => act(u, 'verify')}>Verify</button>
                  )}
                  {u.status === 'ACTIVE' && (
                    <button className="btn sm danger" onClick={() => act(u, 'SUSPENDED')}>Suspend</button>
                  )}
                  {u.status === 'SUSPENDED' && (
                    <button className="btn sm" onClick={() => act(u, 'ACTIVE')}>Activate</button>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        <Pagination page={page} setPage={setPage} totalPages={data.totalPages} />
      </Empty>
    </div>
  )
}