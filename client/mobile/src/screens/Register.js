import { useState } from 'react'
import {
  View,
  Text,
  TextInput,
  TouchableOpacity,
  StyleSheet,
  ScrollView,
  KeyboardAvoidingView,
  Platform,
} from 'react-native'
import { useAuth, ROLE_LABEL } from '../auth/AuthContext'

const ROLES = ['BUYER', 'FARMER', 'SUPPLIER', 'INSPECTOR', 'DRIVER']

const EXTRA_FIELDS = {
  SUPPLIER: [['businessName', 'Business name']],
  FARMER: [
    ['farmName', 'Farm name'],
    ['farmLocation', 'Farm location'],
  ],
  INSPECTOR: [['employeeCode', 'Employee code']],
  DRIVER: [
    ['licenseNumber', 'License number'],
    ['vehicleType', 'Vehicle type'],
    ['vehiclePlate', 'Vehicle plate'],
    ['serviceArea', 'Service area'],
  ],
}

export default function RegisterScreen({ navigation }) {
  const { signup } = useAuth()
  const [form, setForm] = useState({
    role: 'BUYER',
    email: '',
    password: '',
    fullName: '',
    phoneNumber: '',
  })
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)

  const set = (k) => (v) => setForm((f) => ({ ...f, [k]: v }))

  async function submit() {
    if (!form.email || !form.password || !form.fullName || !form.phoneNumber) {
      return setError('Email, password, name and phone are required')
    }
    if (form.password.length < 8) return setError('Password must be at least 8 characters')
    setBusy(true)
    setError('')
    try {
      await signup(form)
    } catch (e) {
      setError(e.message)
    } finally {
      setBusy(false)
    }
  }

  const extras = EXTRA_FIELDS[form.role] || []

  return (
    <KeyboardAvoidingView
      style={styles.wrap}
      behavior={Platform.OS === 'ios' ? 'padding' : undefined}
    >
      <ScrollView contentContainerStyle={styles.card}>
        <Text style={styles.title}>Create account</Text>

        <Text style={styles.label}>I am a…</Text>
        <View style={styles.roles}>
          {ROLES.map((r) => (
            <TouchableOpacity
              key={r}
              onPress={() => set('role')(r)}
              style={[styles.role, form.role === r && styles.roleActive]}
            >
              <Text style={[styles.roleText, form.role === r && styles.roleTextActive]}>
                {ROLE_LABEL[r]}
              </Text>
            </TouchableOpacity>
          ))}
        </View>

        {[
          ['fullName', 'Full name'],
          ['email', 'Email'],
          ['phoneNumber', 'Phone number (+254…)'],
          ['password', 'Password (8+ chars)'],
          ...extras,
        ].map(([key, label], i) => (
          <View key={key}>
            <Text style={styles.label}>{label}</Text>
            <TextInput
              style={styles.input}
              value={form[key]}
              onChangeText={set(key)}
              autoCapitalize={key === 'email' ? 'none' : 'words'}
              keyboardType={key.includes('email') || key.includes('phone') ? (key.includes('email') ? 'email-address' : 'phone-pad') : 'default'}
              secureTextEntry={key === 'password'}
            />
          </View>
        ))}

        {error ? <Text style={styles.error}>{error}</Text> : null}

        <TouchableOpacity style={styles.btn} onPress={submit} disabled={busy}>
          <Text style={styles.btnText}>{busy ? 'Creating…' : 'Create account'}</Text>
        </TouchableOpacity>

        <TouchableOpacity onPress={() => navigation.goBack()}>
          <Text style={styles.link}>Already registered? Sign in</Text>
        </TouchableOpacity>
      </ScrollView>
    </KeyboardAvoidingView>
  )
}

const styles = StyleSheet.create({
  wrap: { flex: 1, backgroundColor: '#F3F5F7' },
  card: { padding: 24 },
  title: { fontSize: 24, fontWeight: '700', color: '#10362A', marginBottom: 8 },
  label: { fontSize: 13, color: '#5B6B78', marginTop: 12 },
  input: {
    borderWidth: 1,
    borderColor: '#CFD8DE',
    borderRadius: 8,
    padding: 12,
    fontSize: 15,
    marginTop: 4,
    backgroundColor: '#fff',
  },
  roles: { flexDirection: 'row', flexWrap: 'wrap', gap: 8, marginTop: 6 },
  role: {
    borderWidth: 1,
    borderColor: '#CFD8DE',
    borderRadius: 999,
    paddingHorizontal: 14,
    paddingVertical: 7,
    backgroundColor: '#fff',
  },
  roleActive: { backgroundColor: '#146A43', borderColor: '#146A43' },
  roleText: { color: '#40505C', fontSize: 13 },
  roleTextActive: { color: '#fff', fontWeight: '600' },
  btn: { backgroundColor: '#146A43', borderRadius: 8, padding: 14, alignItems: 'center', marginTop: 20 },
  btnText: { color: '#fff', fontSize: 16, fontWeight: '600' },
  link: { color: '#146A43', textAlign: 'center', marginTop: 16, fontSize: 14 },
  error: { color: '#B3402E', marginTop: 10, fontSize: 13 },
})