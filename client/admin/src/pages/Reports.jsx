import { useEffect, useState } from 'react'
import { api } from '../api'
import { Empty } from '../usePage'

export default function Reports() {
  const [data, setData] = useState(null)
  const [error, setError] = useState('')

  useEffect(() => {
    api
      .get('/admin/reports')
      .then(setData)
      .catch((e) => setError(e.message || 'Failed to load'))
  }, [])

  const rows = data?.ordersLast30Days ? Object.entries(data.ordersLast30Days).sort() : []
  const max = Math.max(1, ...rows.map(([, v]) => v))

  return (
    <Empty loading={!data && !error} error={error}>
      <h1>Reports</h1>
      <div className="grid">
        <K label="Total sales" value={`$${data?.totalSales ?? '0.00'}`} />
        <K label="Orders (total)" value={data?.totalOrders} />
        <K label="Completed" value={data?.completedOrders} />
        <K label="Cancelled" value={data?.cancelledOrders} />
        <K label="Avg order value" value={`$${data?.averageOrderValue ?? '0.00'}`} />
        <K label="Active users" value={data?.activeUsers} />
        <K label="Active deliveries" value={data?.activeDeliveries} />
        <K label="Pending inspections" value={data?.pendingInspections} />
        <K label="Open disputes" value={data?.openDisputes} />
      </div>
      <h2 className="muted">Orders, last 30 days</h2>
      <div className="chart">
        {rows.map(([day, n]) => (
          <div className="bar-col" key={day} title={`${day}: ${n}`}>
            <div className="bar" style={{ height: `${Math.round((n / max) * 100)}%` }} />
            <small>{day.slice(5)}</small>
          </div>
        ))}
      </div>
    </Empty>
  )
}

function K({ label, value }) {
  return (
    <div className="card metric">
      <strong>{label}</strong>
      <span className="metric-value">{value}</span>
    </div>
  )
}