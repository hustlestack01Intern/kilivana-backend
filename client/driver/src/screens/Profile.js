import { View, Text, StyleSheet, ScrollView, TouchableOpacity } from 'react-native'
import { useAuth, ROLE_LABEL } from '../auth/AuthContext'

export default function ProfileScreen() {
  const { user } = useAuth()

  const fields = [
    ['Role', ROLE_LABEL[user?.role] || user?.role],
    ['Email', user?.email],
    ['Phone', user?.phoneNumber],
    user?.businessName && ['Business', user.businessName],
    user?.farmName && ['Farm', user.farmName],
    user?.farmLocation && ['Farm location', user.farmLocation],
    user?.licenseNumber && ['License', user.licenseNumber],
    user?.serviceArea && ['Service area', user.serviceArea],
  ].filter(Boolean)

  return (
    <ScrollView style={styles.wrap} contentContainerStyle={styles.content}>
      <Text style={styles.title}>{user?.fullName}</Text>
      <Text style={styles.muted}>Member since registration</Text>
      <View style={styles.card}>
        {fields.map(([k, v]) => (
          <View style={styles.row} key={k}>
            <Text style={styles.label}>{k}</Text>
            <Text style={styles.value}>{v}</Text>
          </View>
        ))}
      </View>
    </ScrollView>
  )
}

const styles = StyleSheet.create({
  wrap: { flex: 1, backgroundColor: '#F3F5F7' },
  content: { padding: 20 },
  title: { fontSize: 24, fontWeight: '700', color: '#10362A' },
  muted: { color: '#5B6B78', fontSize: 13, marginTop: 2 },
  card: { marginTop: 18, backgroundColor: '#fff', borderRadius: 12, borderWidth: 1, borderColor: '#E2E8EE', padding: 16 },
  row: { flexDirection: 'row', justifyContent: 'space-between', paddingVertical: 8, borderBottomWidth: 1, borderBottomColor: '#EEF2F5' },
  label: { color: '#5B6B78', fontSize: 14 },
  value: { color: '#10362A', fontSize: 14, fontWeight: '600', maxWidth: '60%', textAlign: 'right' },
})