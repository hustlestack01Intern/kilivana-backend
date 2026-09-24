import { useEffect, useState } from 'react'
import { api } from '../api'
import { Empty } from '../usePage'

const SECTIONS = (d) => [
  {
    title: 'Users',
    link: '/users',
    stat: (d) => `${d?.totalUsers ?? '–'}`,
    sub: `active ${d?.activeUsers ?? 0} · suspended ${d?.suspendedUsers ?? 0}`,
    count: d?.totalUsers,
  },
  {
    title: 'Products',
    link: '/products',
    stat: (d) => `${d?.totalProducts ?? '–'}`,
    sub: `active ${d?.activeProducts ?? 0}`,
    count: d?.totalProducts,
  },
  {
    title: 'Orders',
    link: '/orders',
    stat: (d) => `${d?.totalOrders ?? '–'}`,
    sub: `completed ${d?.completedOrders ?? 0} · active ${d?.activeOrders ?? 0}`,
    count: d?.totalOrders,
  },
  {
    title: 'Sales total',
    stat: (d) => `$${d?.orders?.salesTotal ?? '0.00'}`,
    sub: (d) => `completed ${d?.orders?.completedOrders ?? 0}`,
  },
  { title: 'Payments', link: '/payments', stat: (d) => `${d?.payments?.totalPayments ?? '–'}`, sub: (d) => `${d?.payments?.verifiedPayments ?? 0} verified`, count: d?.payments?.totalPayments },
  { title: 'Logistics', link: '/logistics', stat: (d) => `${d?.logistics?.totalJobs ?? '–'}`, sub: (d) => `${d?.logistics?.activeDeliveries ?? 0} active deliveries`, count: d?.logistics?.totalJobs },
]

export default function Dashboard() {
  const [data, setData] = useState(null)
  const [error, setError] = useState('')

  useEffect(() => {
    api
      .get('/admin/dashboard')
      .then(setData)
      .catch((e) => setError(e.message || 'Failed to load'))
  }, [])

  return (
    <Empty loading={!data && !error} error={error}>
      <h1>Dashboard</h1>
      <div className="grid">
        {SECTIONS().map((s) => {
          const sub = typeof s.sub === 'function' ? s.sub(data) : s.sub
          const stat = s.stat?.(data)
          return (
            <div className="card metric" key={s.title}>
              <strong>{s.title}</strong>
              <span className="metric-value">{stat}</span>
              <small className="muted">{typeof sub === 'string' ? (sub?.replace('0.00', '0.00') ?? '') : sub}</small>
            </div>
          )
        })}
      </div>
      <h2 className="muted">By status</h2>
      <div className="grid">
        <div className="card">
          <strong>Orders by status</strong>
          <StatusList m={data?.orders?.byStatus} />
        </div>
        <div className="card">
          <strong>Payments by status</strong>
          <StatusList m={data?.payments?.byStatus} />
        </div>
        <div className="card">
          <strong>Logistics by status</strong>
          <StatusList m={data?.logistics?.byStatus} />
        </div>
        <div className="card">
          <strong>Products by status</strong>
          <StatusList m={data?.products?.byStatus} />
        </div>
      </div>
    </Empty>
  )
}

function StatusList({ m }) {
  if (!m) return <span className="muted">–</span>
  return (
    <ul className="status-list">
      {Object.entries(m).map(([k, v]) => (
        <li key={k}>
          <span>{k}</span>
          <strong>{v}</strong>
        </li>
      ))}
    </ul>
  )
}