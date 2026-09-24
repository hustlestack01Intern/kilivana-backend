import { useState } from 'react'
import { api } from '../api'
import { usePage, Pagination, StatusBadge, Empty } from '../usePage'

export default function Disputes() {
  const [status, setStatus] = useState('')
  const { data, loading, error, page, setPage, refresh } = usePage(
    '/admin/disputes',
    { status },
    [status],
  )

  async function resolve(d) {
    const note = window.prompt(`Resolution note for dispute ${d.disputeId?.slice?.(0, 8)}:`)
    if (note == null || !note.trim()) return
    try {
      await api.post(`/disputes/${d.disputeId}/resolve`, { resolutionNote: note })
      refresh()
    } catch (e) {
      alert(e.message)
    }
  }

  async function escalate(d) {
    try {
      await api.post(`/disputes/${d.disputeId}/escalate`)
      refresh()
    } catch (e) {
      alert(e.message)
    }
  }

  return (
    <div className="page">
      <h1>Disputes</h1>
      <div className="filters">
        <label>
          Status
          <select value={status} onChange={(e) => { setStatus(e.target.value); setPage(0) }}>
            <option value="">All</option>
            {['OPEN', 'ESCALATED', 'RESOLVED'].map((s) => (
              <option key={s} value={s}>{s}</option>
            ))}
          </select>
        </label>
        <span className="muted">total {data.totalElements}</span>
      </div>
      <Empty loading={loading} error={error}>
        <table className="table">
          <thead>
            <tr><th>ID</th><th>Order</th><th>Raised by</th><th>Subject</th><th>Status</th><th>Description</th><th>Actions</th></tr>
          </thead>
          <tbody>
            {data.content.map((d) => (
              <tr key={d.disputeId}>
                <td className="mono">{d.disputeId?.slice?.(0, 8)}</td>
                <td className="mono">{d.orderId?.slice?.(0, 8)}</td>
                <td className="mono">{d.raisedById?.slice?.(0, 8)}</td>
                <td>{d.subject}</td>
                <td><StatusBadge value={d.status} /></td>
                <td className="clip">{d.description}</td>
                <td className="actions">
                  {d.status !== 'RESOLVED' && (
                    <button className="btn sm" onClick={() => resolve(d)}>Resolve</button>
                  )}
                  {d.status === 'OPEN' && (
                    <button className="btn sm danger" onClick={() => escalate(d)}>Escalate</button>
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