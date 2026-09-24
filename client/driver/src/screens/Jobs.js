import { useCallback, useEffect, useState } from 'react'
import {
  View,
  Text,
  StyleSheet,
  FlatList,
  TouchableOpacity,
  ActivityIndicator,
  RefreshControl,
} from 'react-native'
import client from '../api/client'

export default function JobsScreen({ navigation }) {
  const [tab, setTab] = useState('available')
  const [rows, setRows] = useState([])
  const [loading, setLoading] = useState(true)
  const [refreshing, setRefreshing] = useState(false)

  const load = useCallback(() => {
    const path = tab === 'available' ? '/logistics/jobs/available' : '/logistics/jobs/mine'
    client
      .get(path)
      .then(({ data }) => setRows(data))
      .catch(() => {})
      .finally(() => {
        setLoading(false)
        setRefreshing(false)
      })
  }, [tab])

  useEffect(() => {
    setLoading(true)
    load()
  }, [tab])

  return (
    <View style={styles.wrap}>
      <View style={styles.tabs}>
        {[
          ['available', 'Available'],
          ['my', 'My jobs'],
        ].map(([key, label]) => (
          <TouchableOpacity
            key={key}
            style={[styles.tab, tab === key && styles.tabActive]}
            onPress={() => setTab(key)}
          >
            <Text style={[styles.tabText, tab === key && styles.tabTextActive]}>{label}</Text>
          </TouchableOpacity>
        ))}
      </View>
      {loading ? (
        <ActivityIndicator style={{ marginTop: 40 }} />
      ) : (
        <FlatList
          data={rows}
          keyExtractor={(j) => j.jobId}
          contentContainerStyle={{ padding: 16, gap: 12 }}
          refreshControl={<RefreshControl refreshing={refreshing} onRefresh={() => { setRefreshing(true); load() }} />}
          ListEmptyComponent={<Text style={styles.empty}>No jobs here.</Text>}
          renderItem={({ item: j }) => (
            <TouchableOpacity style={styles.card} onPress={() => navigation.navigate('JobDetail', { jobId: j.jobId })}>
              <View style={styles.row}>
                <Text style={styles.name}>Job #{j.jobId.slice(0, 8)}</Text>
                <Text style={styles.muted}>{j.status}</Text>
              </View>
              <Text style={styles.muted}>ETA {j.estimatedArrival?.slice?.(0, 16) || '—'}</Text>
              <Text style={styles.muted}>
                Pickup {j.pickupAddressId?.slice?.(0, 8)} → {j.deliveryAddressId?.slice?.(0, 8)}
              </Text>
              <Text style={styles.muted}>Driver: {j.driverName || 'unassigned'}</Text>
            </TouchableOpacity>
          )}
        />
      )}
    </View>
  )
}

const styles = StyleSheet.create({
  wrap: { flex: 1, backgroundColor: '#F3F5F7' },
  tabs: { flexDirection: 'row', padding: 16, gap: 8 },
  tab: { paddingHorizontal: 16, paddingVertical: 8, borderRadius: 999, backgroundColor: '#fff', borderWidth: 1, borderColor: '#CFD8DE' },
  tabActive: { backgroundColor: '#146A43', borderColor: '#146A43' },
  tabText: { color: '#40505C', fontSize: 14 },
  tabTextActive: { color: '#fff', fontWeight: '600' },
  card: { backgroundColor: '#fff', borderRadius: 12, borderWidth: 1, borderColor: '#E2E8EE', padding: 16, gap: 4 },
  row: { flexDirection: 'row', justifyContent: 'space-between' },
  name: { fontSize: 16, fontWeight: '600', color: '#10362A' },
  muted: { color: '#5B6B78', fontSize: 13 },
  empty: { color: '#5B6B78', textAlign: 'center', marginTop: 40 },
})