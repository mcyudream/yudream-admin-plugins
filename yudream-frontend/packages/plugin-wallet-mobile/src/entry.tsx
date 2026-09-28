/**
 * wallet 移动端（设计稿 walletHome / walletRecharge）：
 * 钱包首页（总余额 + 积分 + 四宫格 + 最近流水）与充值页（档位 + 支付宝渠道 + 下单）。
 * 数据走 /api/plugins/yudream-wallet/me/**；金额为字符串；视觉经 sdk.theme。
 */
import React, { useState } from 'react';
import { Alert, Pressable, Text, View } from 'react-native';
import type { MobilePluginModule, PluginMobileSdk } from '@yudream/plugin-sdk-mobile';
import {
  Badge, Card, Empty, Loading, Screen, SectionTitle, UiProvider, useResource,
} from '@yudream/plugin-mobile-ui';

let currentSdk: PluginMobileSdk | null = null;
function useSdk(): PluginMobileSdk {
  return currentSdk!;
}

const API = '/api/plugins/yudream-wallet';

interface Balance {
  assetCode: string;
  balance: string;
  historicalTotalAmount?: string;
}
interface Tx {
  id: string;
  type: string;
  assetCode: string;
  amount: string;
  remark?: string;
  createdAt?: number | string;
}
interface RechargeOptions {
  enabled?: boolean;
  defaultProductType?: string;
  channels?: { code: string; name?: string; enabled?: boolean }[];
  rules?: { assetCode: string; enabled?: boolean; ratio?: string; minPayAmount?: string }[];
}

function fmtMoney(v: string | number): string {
  const n = Number(v);
  return Number.isFinite(n) ? n.toFixed(2) : String(v ?? '0.00');
}
function fmtTime(ts?: number | string): string {
  const n = Number(ts);
  if (!ts || !Number.isFinite(n) || n <= 0) return '';
  const d = new Date(n);
  return `${d.getMonth() + 1}.${d.getDate()} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
}

/* ---------------- 钱包首页 ---------------- */

function WalletHome({ onOpenRecharge }: { onOpenRecharge: () => void }) {
  const sdk = useSdk();
  const t = sdk.theme;
  const c = t.colors;
  const balances = useResource<Balance[]>(
    () => sdk.api
      .request<Balance[] | { records?: Balance[] }>(`${API}/me/balances`)
      .then((r) => (Array.isArray(r) ? r : (r?.records ?? []))),
    [sdk],
  );
  const txs = useResource<{ records?: Tx[]; total?: number }>(
    () => sdk.api.request(`${API}/me/transactions?page=1&size=5`),
    [sdk],
  );
  const list = balances.data ?? [];
  const cny = list.find((b) => b.assetCode === 'CNY');
  const point = list.find((b) => b.assetCode === 'POINT');
  const txList = txs.data?.records ?? [];
  const quick = ['充值', '转账', '流水', '明细'];

  return (
    <Screen>
      {/* 余额卡：主色底 + 总余额 + 积分/累计徽章 */}
      <View style={{ borderRadius: t.radii.lg, backgroundColor: c.accent, padding: 18, gap: 10 }}>
        <Text style={{ color: `${c.onAccent}B3`, fontSize: t.typography.sizeXs + 1 }}>总余额</Text>
        <Text style={{ color: c.onAccent, fontSize: 30, fontWeight: '700' }}>
          ¥ {fmtMoney(cny?.balance ?? 0)}
        </Text>
        <View style={{ flexDirection: 'row', gap: 8 }}>
          <View style={{ paddingHorizontal: 8, paddingVertical: 3, borderRadius: 999, backgroundColor: `${c.onAccent}26` }}>
            <Text style={{ color: c.onAccent, fontSize: t.typography.sizeXs }}>
              {point ? `积分 ${fmtMoney(point.balance)}` : '积分 —'}
            </Text>
          </View>
          {cny?.historicalTotalAmount ? (
            <View style={{ paddingHorizontal: 8, paddingVertical: 3, borderRadius: 999, backgroundColor: `${c.onAccent}26` }}>
              <Text style={{ color: c.onAccent, fontSize: t.typography.sizeXs }}>
                累计 ¥{fmtMoney(cny.historicalTotalAmount)}
              </Text>
            </View>
          ) : null}
        </View>
      </View>

      {/* 四宫格快捷入口：充值接充值页，其余为占位能力位 */}
      <View style={{ flexDirection: 'row', gap: 10 }}>
        {quick.map((label, i) => (
          <Pressable
            key={label}
            onPress={i === 0 ? onOpenRecharge : undefined}
            style={{
              flex: 1, alignItems: 'center', gap: 6, paddingVertical: 12,
              borderRadius: t.radii.lg, borderWidth: 1, borderColor: c.borderSubtle, backgroundColor: c.bgSurface,
            }}
          >
            <Text style={{ color: c.textPrimary, fontSize: 16 }}>{['＋', '⇄', '☰', '≡'][i]}</Text>
            <Text style={{ color: c.textPrimary, fontSize: t.typography.sizeXs + 1 }}>{label}</Text>
          </Pressable>
        ))}
      </View>

      <SectionTitle title="最近流水" actionText="全部" />
      {txs.loading ? <Loading /> : null}
      {!txs.loading && txList.length === 0 ? <Empty text="暂无流水" /> : null}
      <Card>
        {txList.map((tx, i) => {
          const credit = tx.type === 'CREDIT';
          return (
            <View key={tx.id}>
              {i > 0 ? <View style={{ height: 1, backgroundColor: c.borderSubtle }} /> : null}
              <View style={{ flexDirection: 'row', alignItems: 'center', gap: 8, paddingVertical: 10 }}>
                <View style={{ flex: 1, gap: 1 }}>
                  <Text numberOfLines={1} style={{ color: c.textPrimary, fontSize: t.typography.sizeSm, fontWeight: '500' }}>
                    {tx.remark || (credit ? '入账' : '支出')}
                  </Text>
                  <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeXs }}>
                    {fmtTime(tx.createdAt)} · {tx.assetCode}
                  </Text>
                </View>
                <Text
                  style={{
                    color: credit ? (c.success ?? '#16a34a') : (c.danger ?? '#dc2626'),
                    fontSize: t.typography.sizeSm, fontWeight: '700',
                  }}
                >
                  {credit ? '+' : '-'}
                  {fmtMoney(tx.amount)}
                </Text>
              </View>
            </View>
          );
        })}
      </Card>
    </Screen>
  );
}

/* ---------------- 充值页 ---------------- */

const PRESETS = [6, 30, 68, 128, 328, 648];

interface RechargeOrder {
  outTradeNo: string;
  amount?: string | number;
  status?: string;
  createdAt?: number | string;
  paidAt?: number | string;
}
const ORDER_STATUS_TEXT: Record<string, string> = {
  PAID: '成功', CREATED: '待支付', CLOSED: '已关闭',
};

function RechargePage({ onBack }: { onBack: () => void }) {
  const sdk = useSdk();
  const t = sdk.theme;
  const c = t.colors;
  const options = useResource<RechargeOptions>(
    () => sdk.api.request(`${API}/me/recharge/options`),
    [sdk],
  );
  const orders = useResource<{ records?: RechargeOrder[] }>(
    () => sdk.api.request('/api/plugins/yudream-alipay/me/orders?page=1&size=5').catch(() => ({ records: [] })),
    [sdk],
  );
  const channels = (options.data?.channels ?? []).filter((ch) => ch.enabled !== false);
  const rule = (options.data?.rules ?? []).find((r) => r.enabled !== false);
  const ratio = Number(rule?.ratio ?? 0);
  const channel = channels[0];
  const orderList = [...(orders.data?.records ?? [])].sort(
    (a, b) => Number(b.paidAt ?? b.createdAt ?? 0) - Number(a.paidAt ?? a.createdAt ?? 0),
  );
  const [amount, setAmount] = useState('');
  const [busy, setBusy] = useState(false);

  const pay = () => {
    const payAmount = Number(amount);
    if (!payAmount || payAmount <= 0) {
      Alert.alert('请选择金额', '先选择充值金额');
      return;
    }
    setBusy(true);
    void sdk.api
      .request(`${API}/me/recharges`, {
        method: 'POST',
        body: {
          assetCode: rule?.assetCode ?? 'CNY',
          channelCode: channel?.code ?? '',
          payAmount,
          productType: options.data?.defaultProductType ?? 'PAGE',
        },
      })
      .then(() => {
        setBusy(false);
        Alert.alert('订单已创建', '已唤起支付流程，支付完成后到账');
        orders.reload();
      })
      .catch((e) => {
        setBusy(false);
        Alert.alert('下单失败', e instanceof Error ? e.message : String(e));
      });
  };

  return (
    <Screen>
      <SectionTitle title="选择充值金额" />
      <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 8 }}>
        {PRESETS.map((v) => {
          const on = Number(amount) === v;
          return (
            <Pressable
              key={v}
              onPress={() => setAmount(String(v))}
              style={{
                flexBasis: '30%', flexGrow: 1, alignItems: 'center', paddingVertical: 12, gap: 2,
                borderRadius: t.radii.md,
                backgroundColor: on ? c.accent : c.bgSurface,
                borderWidth: 1, borderColor: on ? c.accent : c.borderSubtle,
              }}
            >
              <Text style={{ color: on ? c.onAccent : c.textPrimary, fontSize: t.typography.sizeMd, fontWeight: '700' }}>
                ¥{v}
              </Text>
              {ratio > 0 ? (
                <Text style={{ color: on ? c.onAccent : c.textTertiary, fontSize: t.typography.sizeXs }}>
                  送 {v * ratio} 积分
                </Text>
              ) : null}
            </Pressable>
          );
        })}
      </View>

      <SectionTitle title="支付方式" />
      {channel ? (
        <Card>
          <View style={{ flexDirection: 'row', alignItems: 'center', gap: 10 }}>
            <View style={{ width: 34, height: 34, borderRadius: 8, backgroundColor: c.fillHover, alignItems: 'center', justifyContent: 'center' }}>
              <Text style={{ color: c.textPrimary, fontSize: 14 }}>支</Text>
            </View>
            <View style={{ flex: 1, gap: 1 }}>
              <Text style={{ color: c.textPrimary, fontSize: t.typography.sizeSm, fontWeight: '500' }}>{channel.name || channel.code}</Text>
              <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeXs }}>跳转支付宝完成付款</Text>
            </View>
            <View style={{ width: 20, height: 20, borderRadius: 10, backgroundColor: c.accent, alignItems: 'center', justifyContent: 'center' }}>
              <Text style={{ color: c.onAccent, fontSize: t.typography.sizeXs }}>✓</Text>
            </View>
          </View>
        </Card>
      ) : (
        <Empty text="充值渠道未开通" />
      )}

      <SectionTitle title="充值记录" />
      {orderList.length === 0 ? <Empty text="暂无充值记录" /> : null}
      {orderList.length > 0 ? (
        <Card>
          {orderList.map((o, i) => {
            const st = ORDER_STATUS_TEXT[o.status ?? ''] ?? o.status ?? '';
            const when = Number(o.paidAt ?? o.createdAt ?? 0);
            return (
              <View key={o.outTradeNo} style={{ gap: 0 }}>
                {i > 0 ? <View style={{ height: 1, backgroundColor: c.borderSubtle }} /> : null}
                <View style={{ flexDirection: 'row', alignItems: 'center', gap: 8, paddingVertical: 10 }}>
                  <View style={{ flex: 1, gap: 1 }}>
                    <Text style={{ color: c.textPrimary, fontSize: t.typography.sizeSm, fontWeight: '600' }}>
                      ¥{fmtMoney(String(o.amount ?? '0'))}
                    </Text>
                    <Text style={{ color: o.status === 'CLOSED' ? (c.danger ?? '#dc2626') : c.textTertiary, fontSize: t.typography.sizeXs }}>
                      {[st, when ? fmtTime(when) : ''].filter(Boolean).join(' · ')}
                    </Text>
                  </View>
                  {o.status === 'PAID' ? <Badge text="到账" tone="success" /> : null}
                </View>
              </View>
            );
          })}
        </Card>
      ) : null}

      <Pressable
        onPress={pay}
        disabled={busy || !channel}
        style={{ height: 48, borderRadius: t.radii.md, backgroundColor: c.accent, alignItems: 'center', justifyContent: 'center', marginTop: 4 }}
      >
        <Text style={{ color: c.onAccent, fontSize: t.typography.sizeMd, fontWeight: '500' }}>
          {busy ? '处理中…' : `立即充值${amount ? ` ¥${amount}` : ''}`}
        </Text>
      </Pressable>
    </Screen>
  );
}

/* ---------------- 根组件 ---------------- */

type View_ = { name: 'home' } | { name: 'recharge' };

function WalletApp({ initialRoute }: { initialRoute?: string }) {
  const [view, setView] = useState<View_>({ name: 'home' });
  React.useEffect(() => {
    currentSdk?.navigation?.setTitle(view.name === 'home' ? '钱包' : '充值');
    // 充值页接管宿主返回键为应用内返回，首页恢复默认退出
    currentSdk?.navigation?.setBackAction?.(view.name === 'home' ? null : () => setView({ name: 'home' }));
  }, [view, initialRoute]);
  return (
    <UiProvider sdk={currentSdk!}>
      {view.name === 'home' ? (
        <WalletHome onOpenRecharge={() => setView({ name: 'recharge' })} />
      ) : (
        <RechargePage onBack={() => setView({ name: 'home' })} />
      )}
    </UiProvider>
  );
}

const WalletModule: MobilePluginModule = {
  default: (props: Record<string, unknown>) => {
    const sdk = (props as { sdk?: PluginMobileSdk }).sdk;
    if (!sdk) {
      throw new Error('wallet: 宿主未注入 sdk');
    }
    currentSdk = sdk;
    const route = (props as { route?: string }).route;
    return (
      <UiProvider sdk={sdk}>
        <WalletApp initialRoute={route} />
      </UiProvider>
    );
  },
};

export default WalletModule;
