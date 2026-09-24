import { useState } from 'react'
import { api } from '../api'
import { usePage, Pagination, StatusBadge, Empty } from '../usePage'

export default function Products() {
  const [query, setQuery] = useState('')
  const [status, setStatus] = useState('')
  const { data, loading, error, page, setPage, refresh } = usePage(
    '/admin/products',
    { query, status },
    [query, status],
  )

  async function moderate(p, next) {
    try {
      await api.patch(`/products/${p.productId}/status`, { status: next })
      refresh()
    } catch (e) {
      alert(e.message)
    }
  }

  return (
    <div className="page">
      <h1>Products</h1>
      <div className="filters">
        <label>
          Search
          <input value={query} onChange={(e) => { setQuery(e.target.value); setPage(0) }} placeholder="name, seller…" />
        </label>
        <label>
          Status
          <select value={status} onChange={(e) => { setStatus(e.target.value); setPage(0) }}>
            <option value="">All</option>
            {['ACTIVE', 'INACTIVE', 'HIDDEN', 'PENDING_APPROVAL'].map((s) => (
              <option key={s} value={s}>{s}</option>
            ))}
          </select>
        </label>
        <span className="muted">total {data.totalElements}</span>
      </div>
      <Empty loading={loading} error={error}>
        <table className="table">
          <thead>
            <tr><th>Name</th><th>Seller</th><th>Sector</th><th>Price</th><th>Qty</th><th>Status</th><th>Actions</th></tr>
          </thead>
          <tbody>
            {data.content.map((p) => (
              <tr key={p.productId}>
                <td>{p.name}</td>
                <td>{p.sellerName}</td>
                <td>{p.sector}</td>
                <td>${p.unitPrice}/{p.unit}</td>
                <td>{p.availableQuantity}</td>
                <td><StatusBadge value={p.status} /></td>
                <td className="actions">
                  {p.status !== 'ACTIVE' && (
                    <button className="btn sm" onClick={() => moderate(p, 'ACTIVE')}>Approve</button>
                  )}
                  {p.status === 'ACTIVE' && (
                    <button className="btn sm danger" onClick={() => moderate(p, 'HIDDEN')}>Hide</button>
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