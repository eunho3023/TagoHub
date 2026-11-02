import { DarkTheme, DefaultTheme, ThemeProvider } from 'expo-router';
import Stack from 'expo-router/stack';
import { StatusBar } from 'expo-status-bar';
import { useColorScheme } from 'react-native';

export default function RootLayout() {
  const dark = useColorScheme() === 'dark';
  return (
    <ThemeProvider value={dark ? DarkTheme : DefaultTheme}>
      <StatusBar style={dark ? 'light' : 'dark'} />
      <Stack screenOptions={{ headerBackTitle: '뒤로' }}>
        <Stack.Screen name="(tabs)" options={{ headerShown: false }} />
        <Stack.Screen name="stop" options={{ title: '정류장' }} />
        <Stack.Screen name="route" options={{ title: '노선' }} />
        <Stack.Screen name="station" options={{ title: '지하철역' }} />
      </Stack>
    </ThemeProvider>
  );
}
