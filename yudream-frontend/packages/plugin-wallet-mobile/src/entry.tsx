/**
 * wallet 移动端（设计稿 walletHome / walletRecharge）：
 * 钱包首页（总余额 + 资产 + 四宫格全部可用：充值/转账/流水/明细 + 最近流水点入明细）
 * 与充值页、转账页、流水页（收支筛选 + 资产筛选 + 加载更多）、流水明细页、资产明细页。
 * 数据走 /api/plugins/yudream-wallet/me/**；金额为字符串；视觉经 sdk.theme。
 */
import React, { useState } from 'react';
import { Pressable, Text, TextInput, View } from 'react-native';
import type { MobilePluginModule, PluginMobileSdk } from '@yudream/plugin-sdk-mobile';
import {
  Badge, Card, Empty, Icon, InfoRows, Loading, PrimaryButton, Screen, SectionTitle,
  UiProvider, useResource,  uiAlert,
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
interface TxUser { id?: string; username?: string; nickname?: string; avatar?: string | null }
interface Tx {
  id: string;
  businessNo?: string;
  type: string;
  source?: string;
  assetCode: string;
  fromUser?: TxUser | null;
  toUser?: TxUser | null;
  direction?: string; // IN | OUT | TRANSFER
  amount: string;
  fromBalanceAfter?: string;
  toBalanceAfter?: string;
  remark?: string;
  createdAt?: number | string;
}
interface RechargeOptions {
  enabled?: boolean;
  defaultProductType?: string;
  channels?: { code: string; name?: string; enabled?: boolean }[];
  rules?: { assetCode: string; enabled?: boolean; ratio?: string; minPayAmount?: string }[];
}

const errText = (e: unknown) => (e instanceof Error ? e.message : String(e));
function fmtMoney(v: string | number | null | undefined): string {
  const n = Number(v);
  return Number.isFinite(n) ? n.toFixed(2) : String(v ?? '0.00');
}
function fmtTime(ts?: number | string | null): string {
  const n = Number(ts);
  if (!ts || !Number.isFinite(n) || n <= 0) return '';
  const d = new Date(n);
  return `${d.getFullYear()}.${d.getMonth() + 1}.${d.getDate()} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
}
const userName = (u?: TxUser | null) => u?.nickname || u?.username || '';

function Field({
  value, onChangeText, placeholder, multiline, height, keyboard,
}: {
  value: string;
  onChangeText: (v: string) => void;
  placeholder: string;
  multiline?: boolean;
  height?: number;
  keyboard?: 'default' | 'numeric';
}) {
  const t = useSdk().theme;
  const c = t.colors;
  return (
    <TextInput
      value={value}
      onChangeText={onChangeText}
      placeholder={placeholder}
      placeholderTextColor={c.textTertiary}
      multiline={multiline}
      textAlignVertical={multiline ? 'top' : 'center'}
      keyboardType={keyboard === 'numeric' ? 'numeric' : 'default'}
      autoCapitalize="none"
      autoCorrect={false}
      style={{
        height: height ?? (multiline ? 72 : 42),
        borderRadius: t.radii.md,
        borderWidth: 1,
        borderColor: c.borderSubtle,
        backgroundColor: c.bgPage,
        color: c.textPrimary,
        fontSize: t.typography.sizeSm,
        paddingHorizontal: 10,
        paddingVertical: multiline ? 8 : 0,
      }}
    />
  );
}

/** 流水行：方向角标 + 说明 + 时间 + 金额；可点进明细。 */
function TxRow({ tx, onPress }: { tx: Tx; onPress?: () => void }) {
  const sdk = useSdk();
  const t = sdk.theme;
  const c = t.colors;
  const dir = tx.direction ?? (tx.type === 'CREDIT' ? 'IN' : tx.type === 'DEBIT' ? 'OUT' : 'TRANSFER');
  const credit = dir === 'IN';
  const counterpart = credit ? userName(tx.fromUser) : userName(tx.toUser);
  const title = tx.remark || (dir === 'TRANSFER' ? '转账' : credit ? '入账' : '支出');
  return (
    <Pressable onPress={onPress} android_ripple={{ color: c.fillHover }} disabled={!onPress}>
      <View style={{ flexDirection: 'row', alignItems: 'center', gap: 10, paddingVertical: 10 }}>
        <View style={{ width: 32, height: 32, borderRadius: 16, backgroundColor: c.fillHover, alignItems: 'center', justifyContent: 'center' }}>
          <Icon name={credit ? 'likeFilled' : 'close'} size={13} color={credit ? (c.success ?? '#16a34a') : c.textSecondary} />
        </View>
        <View style={{ flex: 1, gap: 1 }}>
          <Text numberOfLines={1} style={{ color: c.textPrimary, fontSize: t.typography.sizeSm, fontWeight: '500' }}>
            {title}
          </Text>
          <Text numberOfLines={1} style={{ color: c.textTertiary, fontSize: t.typography.sizeXs }}>
            {[counterpart || (credit ? '入账' : '支出'), fmtTime(tx.createdAt), tx.assetCode].filter(Boolean).join(' · ')}
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
    </Pressable>
  );
}

/* ---------------- 钱包首页 ---------------- */

function WalletHome({ onOpen, balances, txs }: {
  onOpen: (v: 'recharge' | 'transfer' | 'transactions' | 'assets') => void;
  balances: { data: Balance[] | null; loading: boolean; reload: () => void };
  txs: { data: { records?: Tx[] } | null; loading: boolean; reload: () => void };
}) {
  const sdk = useSdk();
  const t = sdk.theme;
  const c = t.colors;
  const list = balances.data ?? [];
  const cny = list.find((b) => b.assetCode === 'CNY');
  const point = list.find((b) => b.assetCode === 'POINT');
  const txList = (txs.data?.records ?? []).slice(0, 6);
  const quick: { label: string; icon: string; view: 'recharge' | 'transfer' | 'transactions' | 'assets' }[] = [
    { label: '充值', icon: 'add', view: 'recharge' },
    { label: '转账', icon: 'send', view: 'transfer' },
    { label: '流水', icon: 'time', view: 'transactions' },
    { label: '明细', icon: 'folder', view: 'assets' },
  ];

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

      {/* 四宫格快捷入口：全部可用 */}
      <View style={{ flexDirection: 'row', gap: 10 }}>
        {quick.map((item) => (
          <Pressable
            key={item.label}
            onPress={() => onOpen(item.view)}
            android_ripple={{ color: c.fillHover }}
            style={{
              flex: 1, alignItems: 'center', gap: 6, paddingVertical: 12,
              borderRadius: t.radii.lg, borderWidth: 1, borderColor: c.borderSubtle, backgroundColor: c.bgSurface,
            }}
          >
            <Icon name={item.icon} size={16} color={c.accent} />
            <Text style={{ color: c.textPrimary, fontSize: t.typography.sizeXs + 1 }}>{item.label}</Text>
          </Pressable>
        ))}
      </View>

      <SectionTitle title="最近流水" actionText="全部" onAction={() => onOpen('transactions')} />
      {txs.loading ? <Loading /> : null}
      {!txs.loading && txList.length === 0 ? <Empty text="暂无流水" /> : null}
      {txList.length > 0 ? (
        <Card>
          {txList.map((tx, i) => (
            <View key={tx.id}>
              {i > 0 ? <View style={{ height: 1, backgroundColor: c.borderSubtle }} /> : null}
              <TxRow tx={tx} onPress={() => onOpen('transactions')} />
            </View>
          ))}
        </Card>
      ) : null}
    </Screen>
  );
}

/* ---------------- 转账 ---------------- */

function TransferPage({ balances, onDone }: { balances: Balance[] | null; onDone: () => void }) {
  const sdk = useSdk();
  const t = sdk.theme;
  const c = t.colors;
  const [asset, setAsset] = useState('');
  const [account, setAccount] = useState('');
  const [amount, setAmount] = useState('');
  const [remark, setRemark] = useState('');
  const [busy, setBusy] = useState(false);
  const active = asset || (balances ?? [])[0]?.assetCode || 'POINT';
  const balanceOf = (balances ?? []).find((b) => b.assetCode === active);

  const submit = () => {
    const n = Number(amount);
    if (!account.trim()) {
      uiAlert('请填写收款人', '支持对方用户名 / 邮箱 / 用户 ID');
      return;
    }
    if (!Number.isFinite(n) || n <= 0) {
      uiAlert('请填写金额', '转账金额需为正数');
      return;
    }
    if (balanceOf && n > Number(balanceOf.balance)) {
      uiAlert('余额不足', `当前 ${active} 余额 ${fmtMoney(balanceOf.balance)}`);
      return;
    }
    uiAlert('确认转账', `向「${account.trim()}」转账 ${fmtMoney(n)} ${active}？`, [
      { text: '取消', style: 'cancel' },
      {
        text: '转账',
        onPress: () => {
          setBusy(true);
          void sdk.api
            .request(`${API}/me/transfers`, {
              method: 'POST',
              body: { toAccount: account.trim(), assetCode: active, amount: n, remark: remark.trim() || '移动端转账' },
            })
            .then(() => {
              setBusy(false);
              setAccount('');
              setAmount('');
              setRemark('');
              uiAlert('转账成功', '已到账对方钱包', [{ text: '好的', onPress: onDone }]);
            })
            .catch((e) => { setBusy(false); uiAlert('转账失败', errText(e)); });
        },
      },
    ]);
  };

  return (
    <Screen>
      <Card style={{ gap: 10 }}>
        <View style={{ flexDirection: 'row', alignItems: 'center', gap: 8 }}>
          <Icon name="send" size={16} color={c.accent} />
          <Text style={{ color: c.textPrimary, fontSize: t.typography.sizeSm + 1, fontWeight: '700', flex: 1 }}>转账</Text>
        </View>
        {(balances ?? []).length > 1 ? (
          <View style={{ flexDirection: 'row', gap: 6, flexWrap: 'wrap' }}>
            {(balances ?? []).map((b) => (
              <Pressable
                key={b.assetCode}
                onPress={() => setAsset(b.assetCode)}
                style={{
                  paddingHorizontal: 10, paddingVertical: 6, borderRadius: 999,
                  backgroundColor: active === b.assetCode ? c.accent : c.bgSurface,
                  borderWidth: 1, borderColor: active === b.assetCode ? c.accent : c.borderSubtle,
                }}
              >
                <Text style={{ color: active === b.assetCode ? c.onAccent : c.textSecondary, fontSize: t.typography.sizeXs }}>
                  {b.assetCode} · {fmtMoney(b.balance)}
                </Text>
              </Pressable>
            ))}
          </View>
        ) : null}
        <Field value={account} onChangeText={setAccount} placeholder="收款人（用户名 / 邮箱 / 用户 ID）" />
        <Field value={amount} onChangeText={setAmount} placeholder="转账金额" keyboard="numeric" />
        <Field value={remark} onChangeText={setRemark} placeholder="备注（可选）" />
        <PrimaryButton title="确认转账" onPress={submit} busy={busy} />
      </Card>
      <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeXs }}>
        转账即时到账、不可撤销，请核对收款人后操作
      </Text>
    </Screen>
  );
}

/* ---------------- 流水（筛选 + 加载更多） ---------------- */

function TransactionsPage({ onOpenDetail }: { onOpenDetail: (tx: Tx) => void }) {
  const sdk = useSdk();
  const t = sdk.theme;
  const c = t.colors;
  const [typeFilter, setTypeFilter] = useState<'' | 'CREDIT' | 'DEBIT'>('');
  const [items, setItems] = useState<Tx[]>([]);
  const [page, setPage] = useState(1);
  const [loading, setLoading] = useState(true);
  const [more, setMore] = useState(false);
  const [hasMore, setHasMore] = useState(false);
  const req = React.useRef(0);

  const load = React.useCallback(
    async (target: number, replace: boolean) => {
      const my = ++req.current;
      if (replace) setLoading(true); else setMore(true);
      try {
        const qs = [`page=${target}`, 'size=20'];
        if (typeFilter) qs.push(`type=${typeFilter}`);
        const res = await sdk.api.request<{ records?: Tx[]; total?: number }>(`${API}/me/transactions?${qs.join('&')}`);
        if (my !== req.current) return;
        const fresh = res.records ?? [];
        setItems((prev) => (replace ? fresh : [...prev, ...fresh.filter((x) => !prev.some((p) => p.id === x.id))]));
        setPage(target);
        setHasMore(target * 20 < Number(res.total ?? 0));
      } catch {
        if (my === req.current) setItems([]);
      } finally {
        if (my === req.current) { setLoading(false); setMore(false); }
      }
    },
    [sdk, typeFilter],
  );

  React.useEffect(() => { void load(1, true); }, [load]);

  return (
    <Screen>
      <View style={{ flexDirection: 'row', gap: 8 }}>
        {[
          { key: '', label: '全部' },
          { key: 'CREDIT', label: '收入' },
          { key: 'DEBIT', label: '支出' },
        ].map((f) => (
          <Pressable
            key={f.key}
            onPress={() => setTypeFilter(f.key as '' | 'CREDIT' | 'DEBIT')}
            style={{
              flex: 1, height: 38, borderRadius: t.radii.md, alignItems: 'center', justifyContent: 'center',
              backgroundColor: typeFilter === f.key ? c.accent : c.bgSurface,
              borderWidth: 1, borderColor: typeFilter === f.key ? c.accent : c.borderSubtle,
            }}
          >
            <Text style={{ color: typeFilter === f.key ? c.onAccent : c.textSecondary, fontSize: t.typography.sizeSm, fontWeight: '500' }}>
              {f.label}
            </Text>
          </Pressable>
        ))}
      </View>

      {loading ? <Loading /> : null}
      {!loading && items.length === 0 ? <Empty text="暂无流水记录" /> : null}
      {items.length > 0 ? (
        <Card>
          {items.map((tx, i) => (
            <View key={tx.id}>
              {i > 0 ? <View style={{ height: 1, backgroundColor: c.borderSubtle }} /> : null}
              <TxRow tx={tx} onPress={() => onOpenDetail(tx)} />
            </View>
          ))}
        </Card>
      ) : null}
      {hasMore ? (
        <Pressable onPress={() => void load(page + 1, false)} style={{ alignItems: 'center', paddingVertical: 12 }}>
          {more ? <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeSm }}>加载中…</Text> : <Text style={{ color: c.textSecondary, fontSize: t.typography.sizeSm }}>加载更多</Text>}
        </Pressable>
      ) : null}
    </Screen>
  );
}

/* ---------------- 流水明细 ---------------- */

function TxDetailPage({ tx }: { tx: Tx }) {
  const sdk = useSdk();
  const t = sdk.theme;
  const c = t.colors;
  const dir = tx.direction ?? (tx.type === 'CREDIT' ? 'IN' : tx.type === 'DEBIT' ? 'OUT' : 'TRANSFER');
  const credit = dir === 'IN';
  const typeText = dir === 'TRANSFER' ? '转账' : credit ? '入账' : '支出';
  const counterpart = credit ? userName(tx.fromUser) : userName(tx.toUser);
  const balanceAfter = credit ? tx.toBalanceAfter : tx.fromBalanceAfter;
  return (
    <Screen>
      <Card style={{ alignItems: 'center', gap: 6, paddingVertical: 22 }}>
        <Text style={{ color: credit ? (c.success ?? '#16a34a') : (c.danger ?? '#dc2626'), fontSize: 30, fontWeight: '700' }}>
          {credit ? '+' : '-'}
          {fmtMoney(tx.amount)}
        </Text>
        <Badge text={typeText} tone={credit ? 'success' : 'warning'} solid />
        {tx.remark ? <Text style={{ color: c.textSecondary, fontSize: t.typography.sizeSm }}>{tx.remark}</Text> : null}
      </Card>
      <InfoRows
        rows={[
          ['资产', tx.assetCode],
          ['方向', credit ? '收入' : dir === 'TRANSFER' ? '转出' : '支出'],
          ['对方', counterpart || '—'],
          ...(balanceAfter != null ? [['变动后余额', fmtMoney(balanceAfter)] as [string, string]] : []),
          ...(tx.source ? [['来源', tx.source] as [string, string]] : []),
          ['时间', fmtTime(tx.createdAt)],
          ...(tx.businessNo ? [['业务单号', tx.businessNo] as [string, string]] : []),
        ]}
      />
    </Screen>
  );
}

/* ---------------- 资产明细 ---------------- */

function AssetsPage({ balances }: { balances: Balance[] | null }) {
  const t = useSdk().theme;
  const c = t.colors;
  const list = balances ?? [];
  return (
    <Screen>
      {list.length === 0 ? <Empty text="暂无资产" /> : null}
      {list.map((b) => (
        <Card key={b.assetCode}>
          <View style={{ flexDirection: 'row', alignItems: 'center', gap: 10 }}>
            <View style={{ width: 40, height: 40, borderRadius: 12, backgroundColor: c.fillHover, alignItems: 'center', justifyContent: 'center' }}>
              <Text style={{ color: c.textPrimary, fontSize: 15, fontWeight: '700' }}>{b.assetCode.slice(0, 2)}</Text>
            </View>
            <View style={{ flex: 1, gap: 2 }}>
              <Text style={{ color: c.textPrimary, fontSize: t.typography.sizeSm + 1, fontWeight: '700' }}>{b.assetCode}</Text>
              <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeXs }}>
                {b.historicalTotalAmount ? `累计 ${fmtMoney(b.historicalTotalAmount)}` : '累计 —'}
              </Text>
            </View>
            <Text style={{ color: c.textPrimary, fontSize: t.typography.sizeMd, fontWeight: '700' }}>{fmtMoney(b.balance)}</Text>
          </View>
        </Card>
      ))}
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

function RechargePage() {
  const sdk = useSdk();
  const t = sdk.theme;
  const c = t.colors;
  const options = useResource<RechargeOptions>(
    () => sdk.api.request(`${API}/me/recharge/options`),
    [sdk],
  );
  const orders = useResource<{ records?: RechargeOrder[] }>(
    () => sdk.api.request<{ records?: RechargeOrder[] }>('/api/plugins/yudream-alipay/me/orders?page=1&size=5').catch(() => ({ records: [] } as { records?: RechargeOrder[] })),
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
      uiAlert('请选择金额', '先选择充值金额');
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
        uiAlert('订单已创建', '已唤起支付流程，支付完成后到账');
        orders.reload();
      })
      .catch((e) => {
        setBusy(false);
        uiAlert('下单失败', errText(e));
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

type View_ =
  | { name: 'home' }
  | { name: 'recharge' }
  | { name: 'transfer' }
  | { name: 'transactions' }
  | { name: 'txDetail'; tx: Tx }
  | { name: 'assets' };

const TITLES: Record<View_['name'], string> = {
  home: '钱包', recharge: '充值', transfer: '转账', transactions: '流水',
  txDetail: '流水明细', assets: '资产明细',
};

function WalletApp({ initialRoute }: { initialRoute?: string }) {
  const [stack, setStack] = useState<View_[]>([{ name: 'home' }]);
  const view = stack[stack.length - 1];
  const balances = useResource<Balance[]>(
    () => currentSdk!.api
      .request<Balance[] | { records?: Balance[] }>(`${API}/me/balances`)
      .then((r) => (Array.isArray(r) ? r : (r?.records ?? []))),
    [stack.length === 1],
  );
  const txs = useResource<{ records?: Tx[] }>(
    () => currentSdk!.api.request(`${API}/me/transactions?page=1&size=6`),
    [stack.length === 1],
  );

  React.useEffect(() => {
    currentSdk?.navigation?.setTitle(TITLES[view.name]);
    currentSdk?.navigation?.setBackAction?.(view.name === 'home' ? null : () => setStack((prev) => (prev.length > 1 ? prev.slice(0, -1) : prev)));
  }, [view, initialRoute]);

  const push = (next: View_) => setStack((prev) => [...prev, next]);

  return (
    <UiProvider sdk={currentSdk!}>
      {view.name === 'home' ? (
        <WalletHome
          onOpen={(v) => push({ name: v } as View_)}
          balances={balances}
          txs={txs}
        />
      ) : view.name === 'recharge' ? (
        <RechargePage />
      ) : view.name === 'transfer' ? (
        <TransferPage balances={balances.data} onDone={() => setStack((prev) => prev.slice(0, -1))} />
      ) : view.name === 'transactions' ? (
        <TransactionsPage onOpenDetail={(tx) => push({ name: 'txDetail', tx })} />
      ) : view.name === 'txDetail' ? (
        <TxDetailPage tx={view.tx} />
      ) : (
        <AssetsPage balances={balances.data} />
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
