/**
 * 统一主题弹窗：替代 RN 原生 Alert.alert 的全 app 视觉弹窗。
 *
 * 用法与 Alert.alert 完全同构（便于机械替换）：
 *   uiAlert('标题');
 *   uiAlert('标题', '说明');
 *   uiAlert('标题', '说明', [
 *     { text: '取消', style: 'cancel' },
 *     { text: '移出', style: 'destructive', onPress: () => doRemove() },
 *   ]);
 *
 * DialogHost 由 UiProvider 渲染（每页一个），imperative api 通过模块级
 * emitter 投递；主题图标圆徽（信息=主色 / 破坏=警示红）+ 居中排版 +
 * 胶囊按钮（取消=浅灰底 / 破坏=浅红底红字 / 默认=主色实底），大圆角 24。
 */
import React, { useEffect, useRef, useState } from 'react';
import { Animated, Easing, Modal, Pressable, Text, View } from 'react-native';
import Ionicons from 'react-native-vector-icons/Ionicons';
import type { PluginThemeTokens } from '@yudream/plugin-sdk-mobile';

export interface UiDialogButton {
  text: string;
  onPress?: () => void;
  style?: 'default' | 'cancel' | 'destructive';
}

interface UiDialogRequest {
  title: string;
  message: string;
  buttons: UiDialogButton[];
}

let emitDialog: ((req: UiDialogRequest) => void) | null = null;

/** 主题弹窗（替代 Alert.alert）：按钮缺省时自动补「确定」。 */
export function uiAlert(title: string, message?: string, buttons?: UiDialogButton[]): void {
  const req: UiDialogRequest = {
    title,
    message: message ?? '',
    buttons: buttons && buttons.length > 0 ? buttons : [{ text: '确定' }],
  };
  if (emitDialog) {
    emitDialog(req);
  } else {
    const [primary] = req.buttons.slice(-1);
    primary?.onPress?.();
  }
}

/** 透明色附加（#rrggbb → #rrggbbaa）；非 hex 色值原样返回。 */
function withAlpha(color: string, alpha: string): string {
  return /^#[0-9a-fA-F]{6}$/.test(color) ? `${color}${alpha}` : color;
}

/** 供 UiProvider 渲染的全局弹窗宿主。 */
export function DialogHost({ theme }: { theme: PluginThemeTokens }) {
  const [req, setReq] = useState<UiDialogRequest | null>(null);
  const anim = useRef(new Animated.Value(0)).current;
  const [visible, setVisible] = useState(false);

  useEffect(() => {
    emitDialog = (r: UiDialogRequest) => {
      setReq(r);
      setVisible(true);
      anim.setValue(0);
      Animated.parallel([
        Animated.timing(anim, { toValue: 1, duration: 170, easing: Easing.out(Easing.quad), useNativeDriver: true }),
        Animated.spring(anim, { toValue: 1, friction: 7, tension: 170, useNativeDriver: true }),
      ]).start();
    };
    return () => {
      emitDialog = null;
    };
  }, [anim]);

  const dismiss = (button?: UiDialogButton) => {
    setVisible(false);
    setReq(null);
    button?.onPress?.();
  };

  const c = theme.colors;
  const destructive = !!req?.buttons.some((b) => b.style === 'destructive');
  const tint = destructive ? (c.danger ?? '#dc2626') : c.accent;
  const glyph = destructive ? 'alert-circle' : 'information-circle';
  const single = req && req.buttons.length === 1;

  return (
    <Modal transparent visible={visible} statusBarTranslucent onRequestClose={() => dismiss()}>
      {req ? (
        <Animated.View
          style={{
            flex: 1,
            backgroundColor: 'rgba(0,0,0,0.5)',
            opacity: anim,
            alignItems: 'center',
            justifyContent: 'center',
            paddingHorizontal: 30,
          }}
        >
          <Pressable
            onPress={() => dismiss()}
            style={{ position: 'absolute', left: 0, right: 0, top: 0, bottom: 0 }}
          />
          <Animated.View
            style={{
              width: '100%',
              maxWidth: 330,
              borderRadius: 24,
              backgroundColor: c.bgSurface,
              paddingTop: 24,
              paddingBottom: 20,
              paddingHorizontal: 20,
              alignItems: 'center',
              opacity: anim,
              transform: [
                { translateY: anim.interpolate({ inputRange: [0, 1], outputRange: [26, 0] }) },
                { scale: anim.interpolate({ inputRange: [0, 1], outputRange: [0.92, 1] }) },
              ],
            }}
          >
            <View
              style={{
                width: 52,
                height: 52,
                borderRadius: 26,
                backgroundColor: withAlpha(tint, '1F'),
                alignItems: 'center',
                justifyContent: 'center',
                marginBottom: 14,
              }}
            >
              <Ionicons name={glyph} size={28} color={tint} />
            </View>
            <Text
              numberOfLines={2}
              style={{ color: c.textPrimary, fontSize: theme.typography.sizeMd + 2, fontWeight: '700', lineHeight: 25, textAlign: 'center' }}
            >
              {req.title}
            </Text>
            {req.message ? (
              <Text
                style={{
                  color: c.textSecondary,
                  fontSize: theme.typography.sizeSm,
                  lineHeight: 20,
                  marginTop: 8,
                  textAlign: 'center',
                }}
              >
                {req.message}
              </Text>
            ) : null}
            <View style={{ flexDirection: 'row', marginTop: 18, gap: 10, width: '100%' }}>
              {req.buttons.map((b, i) => {
                const isDestructive = b.style === 'destructive';
                const isCancel = b.style === 'cancel';
                const isPrimary = !single && i === req.buttons.length - 1 && !isDestructive && !isCancel;
                const bg = isDestructive
                  ? withAlpha(c.danger ?? '#dc2626', '1A')
                  : isPrimary || (single && !isCancel)
                    ? c.accent
                    : c.fillHover;
                const fg = isDestructive
                  ? (c.danger ?? '#dc2626')
                  : isPrimary || (single && !isCancel)
                    ? c.onAccent
                    : c.textSecondary;
                return (
                  <Pressable
                    key={`${b.text}-${i}`}
                    onPress={() => dismiss(b)}
                    android_ripple={{ color: c.fillHover, radius: 70 }}
                    style={({ pressed }) => ({
                      flex: 1,
                      height: 46,
                      borderRadius: 16,
                      backgroundColor: pressed ? c.fillHover : bg,
                      alignItems: 'center',
                      justifyContent: 'center',
                      opacity: pressed ? 0.8 : 1,
                    })}
                  >
                    <Text style={{ color: fg, fontSize: theme.typography.sizeSm + 1, fontWeight: '600' }}>
                      {b.text}
                    </Text>
                  </Pressable>
                );
              })}
            </View>
          </Animated.View>
        </Animated.View>
      ) : null}
    </Modal>
  );
}
