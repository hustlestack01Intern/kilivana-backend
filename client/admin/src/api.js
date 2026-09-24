// Shared API client: attaches the access token, refreshes it on 401 once,
// and unwraps JSON error responses into { error, message, details }.

const TOKEN_KEY = 'kilivana.access'
const REFRESH_KEY = 'kilivana.refresh'

const apiBase = () => '/api/v1'

export const storage = {
  getAccess: () => localStorage.getItem(TOKEN_KEY),
  getRefresh: () => localStorage.getItem(REFRESH_KEY),
  setTokens: ({ accessToken, refreshToken }) => {
    localStorage.setItem(TOKEN_KEY, accessToken)
    if (refreshToken) localStorage.setItem(REFRESH_KEY, refreshToken)
  },
  clear: () => {
    localStorage.removeItem(TOKEN_KEY)
    localStorage.removeItem(REFRESH_KEY)
  },
}

async function raw(method, path, body, retried = false) {
  let res
  try {
    res = await fetch(`${apiBase()}${path}`, {
      method,
      headers: {
        'Content-Type': 'application/json',
        ...(storage.getAccess() ? { Authorization: `Bearer ${storage.getAccess()}` } : {}),
      },
      body: body == null ? undefined : JSON.stringify(body),
    })
  } catch {
    throw new ApiError(0, 'network_error', 'Cannot reach the API server')
  }

  if (res.status === 401 && !retried && storage.getRefresh()) {
    const refreshed = await tryRefresh()
    if (refreshed) return raw(method, path, body, true)
  }

  if (res.status === 204) return null

  const text = await res.text()
  let payload = null
  try {
    payload = text ? JSON.parse(text) : null
  } catch {
    payload = null
  }

  if (!res.ok) {
    throw new ApiError(
      res.status,
      payload?.code || payload?.error || 'request_failed',
      payload?.message || `Request failed with status ${res.status}`,
      payload?.details,
    )
  }
  return payload
}

async function tryRefresh() {
  const refreshToken = storage.getRefresh()
  if (!refreshToken) return false
  try {
    const res = await fetch(`${apiBase()}/auth/refresh`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ refreshToken }),
    })
    if (!res.ok) return false
    const data = await res.json()
    storage.setTokens(data)
    return true
  } catch {
    return false
  }
}

export class ApiError extends Error {
  constructor(status, code, message, details) {
    super(message)
    this.status = status
    this.code = code
    this.details = details
  }
}

export const api = {
  get: (path) => raw('GET', path),
  post: (path, body) => raw('POST', path, body),
  put: (path, body) => raw('PUT', path, body),
  patch: (path, body) => raw('PATCH', path, body),
  del: (path) => raw('DELETE', path),
}

export function pageQuery(params = {}) {
  const q = { page: 0, size: 20, ...params }
  const search = new URLSearchParams()
  for (const [k, v] of Object.entries(q)) {
    if (v != null && v !== '') search.set(k, String(v))
  }
  const s = search.toString()
  return s ? `?${s}` : ''
}