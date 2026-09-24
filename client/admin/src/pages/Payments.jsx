import { useState } from 'react'
import { usePage, Pagination, StatusBadge, Empty } from '../usePage'

export default function Payments() {
  const [status, setStatus] = useState('')
  const { data, loading, error, page, setPage } = usePage(
    '/admin/payments',
    { status },
    [status],
  )

  return (
    <div className="page">
      <h1>Payments</h1>
      <div className="filters">
        <label>
          Status
          <select value={status} onChange={(e) => { setStatus(e.target.value); setPage(0) }}>
            <option value="">All</option>
            {['PENDING', 'VERIFIED', 'FAILED', 'REFUNDED'].map((s) => (
              <option key={s} value={s}>{s}</option>
            ))}
          </select>
        </label>
        <label>
          Provider
          <select onChange={(e) => setPage(0)} disabled>
            <option value="">All</option>
          </select>
        </label>
        <span className="muted">total {data.totalElements}</span>
      </div>
      <Empty loading={loading} error={error}>
        <table className="table">
          <thead>
            <tr><th>ID</th><th>Order</th><th>Provider</th><th>Amount</th><th>Idempotency key</th><th>Status</th><th>Gateway ref</th></tr>
          </thead>
          <tbody>
            {data.content.map((p) => (
              <tr key={p.paymentId}>
                <td className="mono">{p.paymentId?.slice(0, 8)}</td>
                <td className="mono">{p.orderId?.slice?.(0, 8)}</td>
                <td>{p.provider}</td>
                <td>${p.amount}</td>
                <td className="mono">{p.idempotencyKey}</td>
                <td><StatusBadge value={p.status} /></td>
                <td className="mono">{p.gatewayReference}</td>
              </tr>
            ))}
          </tbody>
        </table>
        <Pagination page={page} setPage={setPage} totalPages={data.totalPages} />
      </Empty>
    </div>
  )
}