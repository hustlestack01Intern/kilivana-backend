import { useCallback, useEffect, useState } from 'react'
import { View, Text, StyleSheet, FlatList, ActivityIndicator, RefreshControl, TouchableOpacity } from 'react-native'
import client from '../api/client'

export default function HistoryScreen({ navigation }) {
  const [rows, setRows] = useState([])
  const [loading, setLoading] = useState(true)
  const [refreshing, setRefreshing] = useState(false)

  const load = useCallback(() => {
    client
      .get('/logistics/jobs/mine')
      .then(({ data }) => setRows(data.filter((j) => ['DELIVERED', 'FAILED', 'CANCELLED'].includes(j.status))))
      .catch(() => {})
      .finally(() => {
        setLoading(false)
        setRefreshing(false)
      })
  }, [])

  useEffect(() => {
    load()
  }, [load])

  return (
    <View style={styles.wrap}>
      {loading ? (
        <ActivityIndicator style={{ marginTop: 40 }} />
      ) : (
        <FlatList
          data={rows}
          keyExtractor={(j) => j.jobId}
          contentContainerStyle={{ padding: 16, gap: 12 }}
          refreshControl={<RefreshControl refreshing={refreshing} onRefresh={() => { setRefreshing(true); load() }} />}
          ListEmptyComponent={<Text style={styles.empty}>No completed jobs yet.</Text>}
          renderItem={({ item: j }) => (
            <TouchableOpacity style={styles.card} onPress={() => navigation.navigate('JobDetail', { jobId: j.jobId })}>
              <View style={styles.row}>
                <Text style={styles.name}>Job #{j.jobId.slice(0, 8)}</Text>
                <Text style={styles.status}>{j.status}</Text>
              </View>
              <Text style={styles.muted}>Delivered to {j.deliveredTo || '—'} {j.podSubmittedAt ? `· ${j.podSubmittedAt.slice(0, 10)}` : ''}</Text>
            </TouchableOpacity>
          )}
        />
      )}
    </View>
  )
}

const styles = StyleSheet.create({
  wrap: { flex: 1, backgroundColor: '#F3F5F7' },
  card: { backgroundColor: '#fff', borderRadius: 12, borderWidth: 1, borderColor: '#E2E8EE', padding: 16, gap: 4 },
  row: { flexDirection: 'row', justifyContent: 'space-between' },
  name: { fontSize: 16, fontWeight: '600', color: '#10362A' },
  status: { color: '#5B6B78', fontSize: 13 },
  muted: { color: '#5B6B78', fontSize: 13 },
  empty: { color: '#5B6B78', textAlign: 'center', marginTop: 40 },
})