import { useCallback, useEffect, useState } from 'react'
import { api, ApiError, pageQuery } from './api'

// Small hook for Spring Data Page responses: { content, totalElements, number, totalPages }.
export function usePage(path, params, deps = []) {
  const [data, setData] = useState({ content: [], totalElements: 0, totalPages: 0, number: 0 })
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [page, setPage] = useState(0)
  const [size, setSize] = useState(20)
  const [refreshKey, setRefreshKey] = useState(0)

  const refresh = useCallback(() => setRefreshKey((k) => k + 1), [])

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    setError('')
    api
      .get(`${path}${pageQuery({ page, size, ...params })}`)
      .then((res) => {
        if (!cancelled) setData(res)
      })
      .catch((err) => {
        if (!cancelled) setError(err instanceof ApiError ? err.message : 'Failed to load')
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [path, page, size, refreshKey, ...deps])

  return { data, loading, error, page, setPage, size, setSize, refresh }
}

export function Pagination({ page, setPage, totalPages }) {
  return (
    <div className="pagination">
      <button className="btn sm" disabled={page <= 0} onClick={() => setPage(page - 1)}>
        ‹ Prev
      </button>
      <span>
        Page {page + 1} of {Math.max(totalPages, 1)}
      </span>
      <button className="btn sm" disabled={page + 1 >= totalPages} onClick={() => setPage(page + 1)}>
        Next ›
      </button>
    </div>
  )
}

export function StatusBadge({ value }) {
  return <span className={`badge ${String(value).toLowerCase().replace(/_/g, '-')}`}>{value}</span>
}

export function Empty({ loading, error, children }) {
  if (loading) return <div className="page muted">Loading…</div>
  if (error) return <div className="error">{error}</div>
  if (children) return children
  return <div className="page muted">Nothing here yet.</div>
}