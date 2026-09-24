import { useCallback, useEffect, useState } from 'react'
import {
  View,
  Text,
  StyleSheet,
  FlatList,
  ActivityIndicator,
  RefreshControl,
} from 'react-native'
import client, { paginate } from '../api/client'

const BADGE_COLOR = {
  PLACED: '#8A6116',
  CONFIRMED: '#8A6116',
  READY_FOR_PICKUP: '#8A6116',
  IN_PROGRESS: '#8A6116',
  DELIVERED: '#177438',
  COMPLETED: '#177438',
  CANCELLED: '#A12B2B',
  REJECTED: '#A12B2B',
}

export default function OrdersScreen() {
  const [rows, setRows] = useState([])
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(0)
  const [loading, setLoading] = useState(true)
  const [refreshing, setRefreshing] = useState(false)

  const load = useCallback(
    (scroll = false) => {
      client
        .get(`/orders/mine${paginate({ page: scroll ? page + 1 : page, size: 20 })}`)
        .then(({ data }) => {
          setTotal(data.totalElements)
          setRows((r) => (scroll ? [...r, ...data.content] : data.content))
        })
        .catch(() => {})
        .finally(() => {
          setLoading(false)
          setRefreshing(false)
        })
    },
    [page],
  )

  useEffect(() => {
    load(false)
  }, [])

  useEffect(() => {
    if (page > 0) load(true)
  }, [page])

  return (
    <View style={styles.wrap}>
      {loading ? (
        <ActivityIndicator style={{ marginTop: 40 }} />
      ) : (
        <FlatList
          data={rows}
          keyExtractor={(o) => o.orderId}
          contentContainerStyle={{ padding: 16, gap: 12 }}
          onEndReached={() => rows.length < total && setPage(page + 1)}
          onEndReachedThreshold={0.4}
          refreshControl={<RefreshControl refreshing={refreshing} onRefresh={() => { setRefreshing(true); load(false) }} />}
          ListHeaderComponent={<Text style={styles.count}>{total} orders</Text>}
          renderItem={({ item: o }) => (
            <View style={styles.card}>
              <View style={styles.row}>
                <View style={styles.badge}>
                  <Text style={[styles.badgeText, { color: BADGE_COLOR[o.status] }]}>{o.status}</Text>
                </View>
                <Text style={styles.date}>{o.createdAt?.slice?.(0, 10)}</Text>
              </View>
              <Text style={styles.muted}>{o.items?.length ?? 0} item(s)</Text>
              <View style={styles.row}>
                <Text style={styles.total}>${o.totalAmount}</Text>
                <Text style={styles.muted}>delivery ${o.deliveryFee}</Text>
              </View>
            </View>
          )}
        />
      )}
    </View>
  )
}

const styles = StyleSheet.create({
  wrap: { flex: 1, backgroundColor: '#F3F5F7' },
  count: { color: '#5B6B78', fontSize: 13, marginBottom: 4 },
  card: { backgroundColor: '#fff', borderRadius: 12, borderWidth: 1, borderColor: '#E2E8EE', padding: 16, gap: 6 },
  row: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' },
  badge: { alignSelf: 'flex-start', paddingHorizontal: 8, paddingVertical: 3, borderRadius: 999, backgroundColor: '#FFF3D6' },
  badgeText: { fontSize: 12, fontWeight: '600' },
  date: { color: '#5B6B78', fontSize: 12 },
  muted: { color: '#5B6B78', fontSize: 13 },
  total: { fontSize: 17, fontWeight: '700', color: '#10362A' },
})