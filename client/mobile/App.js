import { NavigationContainer, DefaultTheme } from '@react-navigation/native'
import { AuthProvider, useAuth } from './src/auth/AuthContext'
import RootNavigator from './src/navigation/RootNavigator'
import { navigationRef } from './src/navigation/ref'

const theme = {
  ...DefaultTheme,
  colors: {
    ...DefaultTheme.colors,
    primary: '#146A43',
    background: '#F3F5F7',
  },
}

function Gate() {
  const { user, ready } = useAuth()
  return <RootNavigator user={user} ready={ready} />
}

export default function App() {
  return (
    <AuthProvider>
      <NavigationContainer ref={navigationRef} theme={theme}>
        <Gate />
      </NavigationContainer>
    </AuthProvider>
  )
}