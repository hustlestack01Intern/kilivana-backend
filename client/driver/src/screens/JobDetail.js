import { useCallback, useEffect, useState } from 'react'
import {
  View,
  Text,
  StyleSheet,
  ScrollView,
  TouchableOpacity,
  TextInput,
  ActivityIndicator,
} from 'react-native'
import * as Location from 'expo-location'
import client from '../api/client'

const NEXT = {
  PENDING_ACCEPTANCE: ['ACCEPTED'],
  ACCEPTED: ['AT_PICKUP', 'CANCELLED'],
  AT_PICKUP: ['PICKED_UP', 'FAILED'],
  PICKED_UP: ['IN_TRANSIT', 'FAILED'],
  IN_TRANSIT: ['DELIVERED', 'FAILED'],
}

export default function JobDetailScreen({ route }) {
  const { jobId } = route.params
  const [job, setJob] = useState(null)
  const [note, setNote] = useState('')
  const [pod, setPod] = useState(null)
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)

  const load = useCallback(() => {
    client
      .get('/logistics/jobs/mine')
      .then(({ data }) => setJob(data.find((j) => j.jobId === jobId)))
      .catch(() => {})
  }, [jobId])

  useEffect(() => {
    load()
  }, [load])

  async function act(status) {
    setBusy(true)
    setError('')
    try {
      await client.patch(`/logistics/jobs/${jobId}/status`, { status })
      load()
    } catch (e) {
      setError(e.message)
    } finally {
      setBusy(false)
    }
  }

  async function decline() {
    setBusy(true)
    setError('')
    try {
      await client.post(`/logistics/jobs/${jobId}/decline`)
      load()
    } catch (e) {
      setError(e.message)
    } finally {
      setBusy(false)
    }
  }

  async function track(status) {
    setBusy(true)
    setError('')
    try {
      let coords = {}
      const perm = await Location.requestForegroundPermissionsAsync()
      if (perm.granted) {
        const loc = await Location.getCurrentPositionAsync({})
        coords = { latitude: loc.coords.latitude, longitude: loc.coords.longitude }
      }
      await client.post(`/logistics/jobs/${jobId}/tracking`, {
        status,
        ...coords,
        locationName: coords.latitude ? 'GPS position' : null,
        note: note || null,
      })
      setNote('')
      load()
    } catch (e) {
      setError(e.message)
    } finally {
      setBusy(false)
    }
  }

  async function submitPod() {
    if (!pod?.file || !pod.deliveredTo) return setError('POD requires a photo reference and recipient name')
    setBusy(true)
    setError('')
    try {
      await client.post(`/logistics/jobs/${jobId}/proof-of-delivery`, {
        fileName: pod.file,
        contentType: 'image/jpeg',
        deliveredTo: pod.deliveredTo,
      })
      setPod(null)
      load()
    } catch (e) {
      setError(e.message)
    } finally {
      setBusy(false)
    }
  }

  if (!job) return <ActivityIndicator style={{ marginTop: 60 }} />

  const terminal = ['DELIVERED', 'FAILED', 'CANCELLED'].includes(job.status)
  const nextStates = NEXT[job.status] || []
  const isMyAvailableJob = job.status === 'PENDING_ACCEPTANCE' && job.driverId == null

  return (
    <ScrollView style={styles.wrap} contentContainerStyle={styles.content}>
      <Text style={styles.name}>Job #{job.jobId.slice(0, 8)}</Text>
      <Text style={styles.status}>{job.status}</Text>
      {job.driverName ? <Text style={styles.muted}>Driver: {job.driverName}</Text> : null}
      <Text style={styles.muted}>ETA {job.estimatedArrival?.slice?.(0, 16) || '—'}</Text>
      <Text style={styles.muted}>Created {job.createdAt?.slice?.(0, 10)}</Text>
      {job.podSubmittedAt ? <Text style={styles.muted}>POD submitted {job.podSubmittedAt.slice(0, 16)}</Text> : null}

      {error ? <Text style={styles.error}>{error}</Text> : null}

      {!terminal && (
        <View style={styles.actions}>
          {nextStates.map((s) => (
            <TouchableOpacity
              key={s}
              style={[styles.btn, s === 'CANCELLED' && styles.btnDanger, s === 'FAILED' && styles.btnDanger]}
              onPress={() => (s === 'ACCEPTED' ? act(s) : track(s))}
              disabled={busy}
            >
              <Text style={styles.btnText}>{s.replace(/_/g, ' ')}</Text>
            </TouchableOpacity>
          ))}
          {isMyAvailableJob && (
            <TouchableOpacity style={[styles.btn, styles.btnDanger]} onPress={decline} disabled={busy}>
              <Text style={styles.btnText}>Decline</Text>
            </TouchableOpacity>
          )}
        </View>
      )}

      <Text style={styles.label}>Note (optional, sent with tracking update)</Text>
      <TextInput style={styles.input} value={note} onChangeText={setNote} placeholder="e.g. heavy traffic" />

      {job.status === 'IN_TRANSIT' && !job.podSubmittedAt && (
        <View style={styles.card}>
          <Text style={styles.label}>Proof of delivery</Text>
          <TextInput
            style={styles.input}
            value={pod?.deliveredTo}
            onChangeText={(v) => setPod((p) => ({ ...p, deliveredTo: v }))}
            placeholder="Recipient name"
          />
          <TextInput
            style={styles.input}
            value={pod?.file}
            onChangeText={(v) => setPod((p) => ({ ...p, file: v }))}
            placeholder="Photo file reference from upload"
          />
          <TouchableOpacity style={styles.btn} onPress={submitPod} disabled={busy}>
            <Text style={styles.btnText}>Submit proof of delivery</Text>
          </TouchableOpacity>
        </View>
      )}
    </ScrollView>
  )
}

const styles = StyleSheet.create({
  wrap: { flex: 1, backgroundColor: '#F3F5F7' },
  content: { padding: 20 },
  name: { fontSize: 22, fontWeight: '700', color: '#10362A' },
  status: {
    alignSelf: 'flex-start',
    backgroundColor: '#FFF3D6',
    color: '#8A6116',
    fontWeight: '600',
    fontSize: 13,
    paddingHorizontal: 10,
    paddingVertical: 4,
    borderRadius: 999,
    marginVertical: 8,
    overflow: 'hidden',
  },
  muted: { color: '#5B6B78', fontSize: 13, marginVertical: 2 },
  label: { fontSize: 13, color: '#5B6B78', marginTop: 16 },
  input: {
    borderWidth: 1,
    borderColor: '#CFD8DE',
    borderRadius: 8,
    padding: 12,
    fontSize: 15,
    marginTop: 6,
    backgroundColor: '#fff',
  },
  actions: { flexDirection: 'row', flexWrap: 'wrap', gap: 10, marginTop: 16 },
  btn: { backgroundColor: '#146A43', borderRadius: 8, paddingVertical: 12, paddingHorizontal: 18 },
  btnDanger: { backgroundColor: '#B3402E' },
  btnText: { color: '#fff', fontWeight: '600', fontSize: 14 },
  error: { color: '#B3402E', marginTop: 12, fontSize: 13 },
  card: { marginTop: 10, backgroundColor: '#fff', borderRadius: 12, padding: 16, borderWidth: 1, borderColor: '#E2E8EE' },
})