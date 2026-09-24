import { useCallback, useEffect, useState } from 'react'
import {
  View,
  Text,
  StyleSheet,
  FlatList,
  TouchableOpacity,
  TextInput,
  ActivityIndicator,
  RefreshControl,
} from 'react-native'
import client, { paginate } from '../api/client'

export default function MarketplaceScreen() {
  const [query, setQuery] = useState('')
  const [rows, setRows] = useState([])
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(0)
  const [loading, setLoading] = useState(true)
  const [refreshing, setRefreshing] = useState(false)

  const load = useCallback(
    (scroll = false) => {
      client
        .get(`/products${paginate({ page: scroll ? page + 1 : page, size: 20, query })}`)
        .then(({ data }) => {
          if (Array.isArray(data)) {
            setTotal(data.length)
            setRows(data)
          } else {
            setTotal(data.totalElements ?? data.length ?? 0)
            setRows((r) => (scroll ? [...r, ...(data.content ?? data)] : data.content ?? data))
          }
        })
        .catch(() => {})
        .finally(() => {
          setLoading(false)
          setRefreshing(false)
        })
    },
    [page, query],
  )

  useEffect(() => {
    setPage(0)
    load(false)
  }, [query])

  useEffect(() => {
    if (page > 0) load(true)
  }, [page])

  function onRefresh() {
    setRefreshing(true)
    setPage(0)
    load(false)
  }

  return (
    <View style={styles.wrap}>
      <TextInput
        style={styles.search}
        placeholder="Search products…"
        value={query}
        onChangeText={setQuery}
      />
      {loading ? (
        <ActivityIndicator style={{ marginTop: 40 }} />
      ) : (
        <FlatList
          data={rows}
          keyExtractor={(p) => p.productId}
          contentContainerStyle={{ padding: 16, gap: 12 }}
          onEndReached={() => rows.length < total && setPage(page + 1)}
          onEndReachedThreshold={0.4}
          refreshControl={<RefreshControl refreshing={refreshing} onRefresh={onRefresh} />}
          ListHeaderComponent={<Text style={styles.count}>{total} products</Text>}
          renderItem={({ item: p }) => (
            <View style={styles.card}>
              <Text style={styles.name}>{p.name}</Text>
              <Text style={styles.muted}>{p.sellerName} · {p.sector}</Text>
              <View style={styles.row}>
                <Text style={styles.price}>
                  ${p.unitPrice}/{p.unit}
                </Text>
                <Text style={styles.muted}>avail. {p.availableQuantity}</Text>
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
  search: {
    margin: 16,
    padding: 12,
    borderRadius: 10,
    borderWidth: 1,
    borderColor: '#CFD8DE',
    backgroundColor: '#fff',
    fontSize: 15,
  },
  count: { color: '#5B6B78', fontSize: 13, marginBottom: 4 },
  card: { backgroundColor: '#fff', borderRadius: 12, borderWidth: 1, borderColor: '#E2E8EE', padding: 16 },
  name: { fontSize: 16, fontWeight: '600', color: '#10362A' },
  muted: { color: '#5B6B78', fontSize: 13, marginTop: 2 },
  row: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', marginTop: 8 },
  price: { fontSize: 16, fontWeight: '700', color: '#146A43' },
})