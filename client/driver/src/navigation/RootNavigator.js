import { ActivityIndicator, View } from 'react-native'
import { createNativeStackNavigator } from '@react-navigation/native-stack'
import { createBottomTabNavigator } from '@react-navigation/bottom-tabs'
import LoginScreen from '../screens/Login'
import JobsScreen from '../screens/Jobs'
import JobDetailScreen from '../screens/JobDetail'
import HistoryScreen from '../screens/History'
import ProfileScreen from '../screens/Profile'

const Stack = createNativeStackNavigator()
const Tabs = createBottomTabNavigator()

function MainTabs() {
  return (
    <Tabs.Navigator screenOptions={{ headerShown: false }}>
      <Tabs.Screen name="Jobs" component={JobsScreen} />
      <Tabs.Screen name="History" component={HistoryScreen} />
      <Tabs.Screen name="Profile" component={ProfileScreen} />
    </Tabs.Navigator>
  )
}

export default function RootNavigator({ user, ready }) {
  if (!ready) {
    return (
      <View style={{ flex: 1, alignItems: 'center', justifyContent: 'center' }}>
        <ActivityIndicator />
      </View>
    )
  }

  return (
    <Stack.Navigator screenOptions={{ headerShown: false }}>
      {!user ? (
        <Stack.Screen name="Login" component={LoginScreen} />
      ) : (
        <>
          <Stack.Screen name="Main" component={MainTabs} />
          <Stack.Screen name="JobDetail" component={JobDetailScreen} />
        </>
      )}
    </Stack.Navigator>
  )
}