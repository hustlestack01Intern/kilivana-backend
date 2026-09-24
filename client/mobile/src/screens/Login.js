import { useEffect, useState } from 'react'
import {
  View,
  Text,
  TextInput,
  TouchableOpacity,
  StyleSheet,
  KeyboardAvoidingView,
  Platform,
} from 'react-native'
import { Link } from '@react-navigation/native'
import { useAuth } from '../auth/AuthContext'

export default function LoginScreen({ navigation }) {
  const { login } = useAuth()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)

  async function submit() {
    if (!email || !password) return setError('Email and password are required')
    setBusy(true)
    setError('')
    try {
      await login(email, password)
    } catch (e) {
      setError(e.message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <KeyboardAvoidingView
      style={styles.wrap}
      behavior={Platform.OS === 'ios' ? 'padding' : undefined}
    >
      <View style={styles.card}>
        <Text style={styles.title}>Kilivana</Text>
        <Text style={styles.subtitle}>Farm to market</Text>

        <Text style={styles.label}>Email</Text>
        <TextInput
          style={styles.input}
          value={email}
          onChangeText={setEmail}
          autoCapitalize="none"
          keyboardType="email-address"
          placeholder="you@example.com"
        />

        <Text style={styles.label}>Password</Text>
        <TextInput
          style={styles.input}
          value={password}
          onChangeText={setPassword}
          secureTextEntry
          placeholder="••••••••"
        />

        {error ? <Text style={styles.error}>{error}</Text> : null}

        <TouchableOpacity style={styles.btn} onPress={submit} disabled={busy}>
          <Text style={styles.btnText}>{busy ? 'Signing in…' : 'Sign in'}</Text>
        </TouchableOpacity>

        <TouchableOpacity onPress={() => navigation.navigate('Register')}>
          <Text style={styles.link}>No account? Create one</Text>
        </TouchableOpacity>
      </View>
    </KeyboardAvoidingView>
  )
}

const styles = StyleSheet.create({
  wrap: { flex: 1, backgroundColor: '#0F2E22', justifyContent: 'center', padding: 24 },
  card: { backgroundColor: '#fff', borderRadius: 14, padding: 24 },
  title: { fontSize: 28, fontWeight: '700', color: '#10362A' },
  subtitle: { color: '#5B6B78', marginBottom: 18 },
  label: { fontSize: 13, color: '#5B6B78', marginTop: 10 },
  input: {
    borderWidth: 1,
    borderColor: '#CFD8DE',
    borderRadius: 8,
    padding: 12,
    fontSize: 15,
    marginTop: 4,
  },
  btn: { backgroundColor: '#146A43', borderRadius: 8, padding: 14, alignItems: 'center', marginTop: 18 },
  btnText: { color: '#fff', fontSize: 16, fontWeight: '600' },
  link: { color: '#146A43', textAlign: 'center', marginTop: 16, fontSize: 14 },
  error: { color: '#B3402E', marginTop: 10, fontSize: 13 },
})