import { useState } from 'react'
import { api } from '../api'
import { usePage, Pagination, StatusBadge, Empty } from '../usePage'

export default function Logistics() {
  const [status, setStatus] = useState('')
  const { data, loading, error, page, setPage, refresh } = usePage(
    '/admin/logistics/jobs',
    { status },
    [status],
  )

  async function assign(job) {
    const driverId = window.prompt(`Assign driver (user ID) to job ${job.jobId?.slice?.(0, 8)}:`)
    if (!driverId) return
    try {
      await api.post(`/logistics/jobs/${job.jobId}/assign`, { driverId })
      refresh()
    } catch (e) {
      alert(e.message)
    }
  }

  return (
    <div className="page">
      <h1>Logistics jobs</h1>
      <div className="filters">
        <label>
          Status
          <select value={status} onChange={(e) => { setStatus(e.target.value); setPage(0) }}>
            <option value="">All</option>
            {['PENDING_ACCEPTANCE', 'ACCEPTED', 'AT_PICKUP', 'PICKED_UP', 'IN_TRANSIT', 'DELIVERED', 'FAILED', 'CANCELLED'].map((s) => (
              <option key={s} value={s}>{s}</option>
            ))}
          </select>
        </label>
        <span className="muted">total {data.totalElements}</span>
      </div>
      <Empty loading={loading} error={error}>
        <table className="table">
          <thead>
            <tr><th>Job</th><th>Order</th><th>Status</th><th>Driver</th><th>ETA</th><th>Delivered to / POD</th><th>Created</th><th></th></tr>
          </thead>
          <tbody>
            {data.content.map((j) => (
              <tr key={j.jobId}>
                <td className="mono">{j.jobId?.slice?.(0, 8)}</td>
                <td className="mono">{j.orderId?.slice?.(0, 8)}</td>
                <td><StatusBadge value={j.status} /></td>
                <td>{j.driverName || (j.driverId ? j.driverId.slice(0, 8) : '—')}</td>
                <td>{j.estimatedArrival?.slice?.(0, 16) || '—'}</td>
                <td>{j.deliveredTo || (j.podSubmittedAt ? j.podSubmittedAt.slice(0, 10) : '—')}</td>
                <td>{j.createdAt?.slice?.(0, 10)}</td>
                <td className="actions">
                  <button className="btn sm" onClick={() => assign(j)}>Assign driver</button>
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