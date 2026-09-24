import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react'
import client, { getTokens, setTokens, clearTokens } from '../api/client'

const AuthContext = createContext(null)

export function AuthProvider({ children }) {
  const [user, setUser] = useState(null)
  const [ready, setReady] = useState(false)

  useEffect(() => {
    let cancelled = false
    ;(async () => {
      const { access } = await getTokens()
      if (!access) {
        if (!cancelled) setReady(true)
        return
      }
      try {
        const { data } = await client.get('/auth/me')
        if (!cancelled) setUser(data)
      } catch {
        await clearTokens()
      } finally {
        if (!cancelled) setReady(true)
      }
    })()
    return () => {
      cancelled = true
    }
  }, [])

  const login = useCallback(async (email, password) => {
    const { data } = await client.post('/auth/login', { email, password })
    await setTokens(data)
    setUser(data)
    return data
  }, [])

  const signup = useCallback(async (payload) => {
    const { data } = await client.post('/auth/signup', payload)
    await setTokens(data)
    setUser(data)
    return data
  }, [])

  const logout = useCallback(async () => {
    const { refresh } = await getTokens()
    try {
      if (refresh) await client.post('/auth/logout', { refreshToken: refresh })
    } catch {
      /* ignore */
    }
    await clearTokens()
    setUser(null)
  }, [])

  const value = useMemo(() => ({ user, ready, login, signup, logout }), [user, ready, login, signup, logout])

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth() {
  return useContext(AuthContext)
}

export const ROLE_LABEL = {
  BUYER: 'Buyer',
  FARMER: 'Farmer',
  SUPPLIER: 'Supplier',
  INSPECTOR: 'Inspector',
  DRIVER: 'Driver',
  ADMIN: 'Admin',
}