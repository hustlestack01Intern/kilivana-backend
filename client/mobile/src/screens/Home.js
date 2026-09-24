import { useEffect, useState } from 'react'
import { View, Text, StyleSheet, ScrollView, TouchableOpacity } from 'react-native'
import client from '../api/client'
import { useAuth, ROLE_LABEL } from '../auth/AuthContext'

const QUICK = {
  BUYER: ['Marketplace', 'Orders'],
  FARMER: ['Products', 'Orders'],
  SUPPLIER: ['Products', 'Orders'],
  INSPECTOR: ['Inspections', 'Products'],
  DRIVER: ['Deliveries', 'Profile'],
}

export default function HomeScreen({ navigation }) {
  const { user, logout } = useAuth()
  const [stats, setStats] = useState({})

  useEffect(() => {
    client
      .get('/orders/mine?page=0&size=1')
      .then(({ data }) => setStats((s) => ({ ...s, orderCount: data.totalElements })))
      .catch(() => {})
  }, [])

  const quick = QUICK[user?.role] || []

  return (
    <ScrollView style={styles.wrap} contentContainerStyle={styles.content}>
      <Text style={styles.hello}>Karibu, {user?.fullName?.split(' ')[0]}</Text>
      <Text style={styles.muted}>
        {ROLE_LABEL[user?.role] || 'Member'} · {user?.email}
      </Text>

      <View style={styles.card}>
        <Text style={styles.muted}>Your orders</Text>
        <Text style={styles.big}>{stats.orderCount ?? '–'}</Text>
      </View>

      <View style={styles.grid}>
        {quick.map((label) => {
          const route = { Deliveries: 'Jobs', Products: 'Sell', Inspections: 'Inspections' }[label] || label
          return (
            <TouchableOpacity key={label} style={styles.action} onPress={() => navigation.navigate(route)}>
              <Text style={styles.actionText}>{label}</Text>
            </TouchableOpacity>
          )
        })}
      </View>

      <TouchableOpacity onPress={logout}>
        <Text style={styles.logout}>Sign out</Text>
      </TouchableOpacity>
    </ScrollView>
  )
}

const styles = StyleSheet.create({
  wrap: { flex: 1, backgroundColor: '#F3F5F7' },
  content: { padding: 20 },
  hello: { fontSize: 24, fontWeight: '700', color: '#10362A' },
  muted: { color: '#5B6B78', fontSize: 13, marginTop: 2 },
  card: { backgroundColor: '#fff', borderRadius: 12, borderWidth: 1, borderColor: '#E2E8EE', padding: 16, marginTop: 18 },
  big: { fontSize: 28, fontWeight: '700', color: '#10362A', marginTop: 4 },
  grid: { flexDirection: 'row', flexWrap: 'wrap', gap: 10, marginTop: 16 },
  action: { backgroundColor: '#146A43', borderRadius: 10, paddingVertical: 18, paddingHorizontal: 22, minWidth: '45%' },
  actionText: { color: '#fff', fontSize: 15, fontWeight: '600' },
  logout: { color: '#B3402E', textAlign: 'center', marginTop: 28, fontSize: 14 },
})