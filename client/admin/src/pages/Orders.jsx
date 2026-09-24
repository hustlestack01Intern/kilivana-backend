import { useState } from 'react'
import { usePage, Pagination, StatusBadge, Empty } from '../usePage'

export default function Orders() {
  const [status, setStatus] = useState('')
  const { data, loading, error, page, setPage } = usePage(
    '/admin/orders',
    { status },
    [status],
  )

  return (
    <div className="page">
      <h1>Orders</h1>
      <div className="filters">
        <label>
          Status
          <select value={status} onChange={(e) => { setStatus(e.target.value); setPage(0) }}>
            <option value="">All</option>
            {['PLACED', 'CONFIRMED', 'READY_FOR_PICKUP', 'IN_PROGRESS', 'DELIVERED', 'COMPLETED', 'CANCELLED', 'REJECTED'].map((s) => (
              <option key={s} value={s}>{s}</option>
            ))}
          </select>
        </label>
        <span className="muted">total {data.totalElements}</span>
      </div>
      <Empty loading={loading} error={error}>
        <table className="table">
          <thead>
            <tr><th>ID</th><th>Buyer</th><th>Seller</th><th>Items</th><th>Subtotal</th><th>Delivery</th><th>Total</th><th>Status</th><th>Created</th></tr>
          </thead>
          <tbody>
            {data.content.map((o) => (
              <tr key={o.orderId}>
                <td className="mono">{o.orderId?.slice?.(0, 8)}</td>
                <td className="mono">{o.buyerId?.slice?.(0, 8)}</td>
                <td className="mono">{o.sellerId?.slice?.(0, 8)}</td>
                <td>{o.items?.length ?? 0}</td>
                <td>${o.subtotal}</td>
                <td>${o.deliveryFee}</td>
                <td>${o.totalAmount}</td>
                <td><StatusBadge value={o.status} /></td>
                <td>{o.createdAt?.slice?.(0, 10)}</td>
              </tr>
            ))}
          </tbody>
        </table>
        <Pagination page={page} setPage={setPage} totalPages={data.totalPages} />
      </Empty>
    </div>
  )
}