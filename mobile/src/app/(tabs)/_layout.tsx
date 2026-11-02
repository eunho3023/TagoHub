import MaterialIcons from '@expo/vector-icons/MaterialIcons';
import Tabs from 'expo-router/tabs';
import { ComponentProps } from 'react';
import { ColorValue } from 'react-native';

type IconName = ComponentProps<typeof MaterialIcons>['name'];
const icon = (name: IconName) => ({ color, size }: { color: ColorValue; size: number }) => <MaterialIcons name={name} color={color as string} size={size} />;

export default function TabLayout() {
  return (
    <Tabs>
      <Tabs.Screen name="index" options={{ title: '지도', headerShown: false, tabBarIcon: icon('map') }} />
      <Tabs.Screen name="bus" options={{ title: '버스', tabBarIcon: icon('directions-bus') }} />
      <Tabs.Screen name="subway" options={{ title: '지하철', tabBarIcon: icon('subway') }} />
      <Tabs.Screen name="settings" options={{ title: '설정', tabBarIcon: icon('settings') }} />
    </Tabs>
  );
}
