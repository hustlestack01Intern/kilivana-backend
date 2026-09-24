import { createContext, useCallback, useContext, useEffect, useState } from 'react'
import { api, storage } from './api'

const AuthContext = createContext(null)

export function AuthProvider({ children }) {
  const [user, setUser] = useState(() => {
    const raw = localStorage.getItem('kilivana.user')
    return raw ? JSON.parse(raw) : null
  })
  const [ready, setReady] = useState(Boolean(storage.getAccess()))

  useEffect(() => {
    if (!storage.getAccess()) return
    api.get('/auth/me')
      .then(setUser)
      .catch(() => {
        storage.clear()
        setUser(null)
      })
      .finally(() => setReady(true))
  }, [])

  const login = useCallback(async (email, password) => {
    const res = await api.post('/auth/login', { email, password })
    if (res.role !== 'ADMIN') throw new Error('This portal is for administrators')
    storage.setTokens(res)
    localStorage.setItem('kilivana.user', JSON.stringify(res))
    setUser(res)
    return res
  }, [])

  const logout = useCallback(async () => {
    const refreshToken = storage.getRefresh()
    try {
      if (refreshToken) await api.post('/auth/logout', { refreshToken })
    } catch {
      /* ignore */
    }
    storage.clear()
    localStorage.removeItem('kilivana.user')
    setUser(null)
  }, [])

  return (
    <AuthContext.Provider value={{ user, ready, login, logout }}>
      {children}
    </AuthContext.Provider>
  )
}

export function useAuth() {
  return useContext(AuthContext)
}