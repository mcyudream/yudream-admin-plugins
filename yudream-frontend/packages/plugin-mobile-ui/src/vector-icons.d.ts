declare module 'react-native-vector-icons/Ionicons' {
  import type { ComponentType } from 'react';
  export interface IconProps {
    name?: string;
    size?: number;
    color?: string;
    [key: string]: unknown;
  }
  const Ionicons: ComponentType<IconProps>;
  export default Ionicons;
}
