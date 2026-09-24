import Constants from 'expo-constants'
import AsyncStorage from '@react-native-async-storage/async-storage'
import axios from 'axios'

const BASE_URL = Constants.expoConfig?.extra?.apiUrl || 'http://localhost:8080'

const TOKEN_KEY = 'kilivana.access'
const REFRESH_KEY = 'kilivana.refresh'

export async function getTokens() {
  try {
    const [access, refresh] = await Promise.all([
      AsyncStorage.getItem(TOKEN_KEY),
      AsyncStorage.getItem(REFRESH_KEY),
    ])
    return { access, refresh }
  } catch {
    return { access: null, refresh: null }
  }
}

export async function setTokens({ accessToken, refreshToken }) {
  if (accessToken) await AsyncStorage.setItem(TOKEN_KEY, accessToken)
  if (refreshToken) await AsyncStorage.setItem(REFRESH_KEY, refreshToken)
}

export async function clearTokens() {
  await AsyncStorage.multiRemove([TOKEN_KEY, REFRESH_KEY])
}

const client = axios.create({ baseURL: `${BASE_URL}/api/v1` })

client.interceptors.request.use(async (config) => {
  const { access } = await getTokens()
  if (access) config.headers.Authorization = `Bearer ${access}`
  return config
})

let refreshing = null
async function refreshTokens() {
  if (!refreshing) {
    refreshing = (async () => {
      const { access, refresh } = await getTokens()
      if (!refresh) return null
      const { data } = await axios.post(`${BASE_URL}/api/v1/auth/refresh`, { refreshToken: refresh })
      await setTokens(data)
      return data
    })().finally(() => {
      refreshing = null
    })
  }
  return refreshing
}

client.interceptors.response.use(
  (res) => res,
  async (error) => {
    const original = error.config
    if (error.response?.status === 401 && !original._retried) {
      original._retried = true
      try {
        await refreshTokens()
        return client(original)
      } catch {
        await clearTokens()
      }
    }
    const message =
      error.response?.data?.message ||
      error.response?.data?.error ||
      error.message ||
      'Something went wrong'
    return Promise.reject(new Error(message))
  },
)

export function paginate(params = {}) {
  const merged = { page: 0, size: 20, ...params }
  const searchParams = new URLSearchParams()
  for (const [k, v] of Object.entries(merged)) {
    if (v != null && v !== '') searchParams.set(k, String(v))
  }
  const qs = searchParams.toString()
  return qs ? `?${qs}` : ''
}

export default client