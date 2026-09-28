/**
 * questionbank 移动端（设计稿 quizHome / quizSession）：
 * 练习首页（统计 + 随机抽题 + 排行榜）与作答页（题卡选项 + 答题卡 + 交卷）。
 * 数据走 /api/plugins/questionbank/me/practice/sessions 与 /me/quiz/leaderboard；
 * 视觉经 sdk.theme 与 plugin-mobile-ui，不写死色值。
 */
import React, { useCallback, useEffect, useState } from 'react';
import { Alert, Pressable, Text, View } from 'react-native';
import type { MobilePluginModule, PluginMobileSdk } from '@yudream/plugin-sdk-mobile';
import {
  BackRow, Badge, Card, Empty, Loading, PrimaryButton, Screen,
  SectionTitle, StatTile, useResource,
} from '@yudream/plugin-mobile-ui';

let currentSdk: PluginMobileSdk | null = null;
function useSdk(): PluginMobileSdk {
  return currentSdk!;
}

const API = '/api/plugins/questionbank';
const BUILD_MARK = 'B7';

interface SessionRow {
  id: string;
  status: string;
  totalCount: number;
  correctCount: number;
  requestedCount: number;
  createdAt: number;
  submittedAt?: number | null;
}
interface SessionQuestion {
  questionId: string;
  type: string;
  typeLabel?: string;
  content: string;
  options?: string[];
}
interface SessionDetail extends SessionRow {
  questions?: SessionQuestion[];
}
interface LeaderRow {
  rank: number;
  name: string;
  score: number;
}

/* ---------------- 练习首页 ---------------- */

function QuizHome({ onOpenSession }: { onOpenSession: (id: string) => void }) {
  const sdk = useSdk();
  const t = sdk.theme;
  const c = t.colors;
  const sessions = useResource<SessionRow[]>(
    () => sdk.api
      .request<SessionRow[] | { records?: SessionRow[] }>(`${API}/me/practice/sessions`)
      .then((r) => (Array.isArray(r) ? r : (r?.records ?? []))),
    [sdk],
  );
  const board = useResource<LeaderRow[]>(
    () => sdk.api
      .request<LeaderRow[] | { records?: LeaderRow[] }>(`${API}/me/quiz/leaderboard`)
      .then((r) => (Array.isArray(r) ? r : (r?.records ?? []))),
    [sdk],
  );
  const [creating, setCreating] = useState(false);

  const list = sessions.data ?? [];
  const done = list.filter((s) => s.status !== 'open');
  const answered = done.reduce((sum, s) => sum + (s.totalCount ?? 0), 0);
  const correct = done.reduce((sum, s) => sum + (s.correctCount ?? 0), 0);
  const accuracy = answered > 0 ? `${Math.round((correct / answered) * 100)}%` : '—';

  const draw = () => {
    setCreating(true);
    void sdk.api
      .request<SessionDetail>(`${API}/me/practice/sessions`, { method: 'POST', body: { count: 10 } })
      .then((s) => {
        setCreating(false);
        sessions.reload();
        const id = (s as { id?: string }).id ?? '';
        if (id) onOpenSession(id);
      })
      .catch((e) => {
        setCreating(false);
        Alert.alert('抽题失败', e instanceof Error ? e.message : String(e));
      });
  };

  return (
    <Screen>
      <View style={{ flexDirection: 'row', gap: 10 }}>
        <StatTile value={`${list.length}`} label={`练习次数 · ${BUILD_MARK}`} />
        <StatTile value={accuracy} label="正确率" sub={`${correct}/${answered} 题`} />
      </View>

      <Card onPress={() => { if (!creating) void draw(); }} style={creating ? { opacity: 0.6 } : undefined}>
        <View style={{ flexDirection: 'row', alignItems: 'center', gap: 12 }}>
          <View style={{ width: 40, height: 40, borderRadius: 12, backgroundColor: c.accent, alignItems: 'center', justifyContent: 'center' }}>
            <Text style={{ color: c.onAccent, fontSize: 18, fontWeight: '700' }}>⚡</Text>
          </View>
          <View style={{ flex: 1, gap: 1 }}>
            <Text style={{ color: c.textPrimary, fontSize: t.typography.sizeLg - 1, fontWeight: '700' }}>随机抽题</Text>
            <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeXs + 1 }}>10 题 · 全部分类 · 自动判分</Text>
          </View>
          <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeLg }}>{'›'}</Text>
        </View>
      </Card>

      <SectionTitle title="最近练习" actionText={list.length ? `${list.length} 次` : undefined} />
      {sessions.loading ? <Loading /> : null}
      {!sessions.loading && list.length === 0 ? <Empty text="还没有练习记录" /> : null}
      {list.slice(0, 5).map((s, i, arr) => (
        <Card key={s.id} onPress={() => s.status === 'ONGOING' ? onOpenSession(s.id) : undefined}>
          <View style={{ flexDirection: 'row', alignItems: 'center', gap: 8 }}>
            <Text style={{ color: c.textPrimary, fontSize: t.typography.sizeSm, fontWeight: '500', flex: 1 }}>
              练习 · {s.totalCount ?? s.requestedCount ?? 10} 题
            </Text>
            {(s.status === 'ONGOING' || s.status === 'open') ? <Badge text="进行中" tone="warning" /> : <Badge text={`对 ${s.correctCount ?? 0}/${s.totalCount ?? 0}`} tone="success" />}
            {i === arr.length - 1 ? null : null}
          </View>
        </Card>
      ))}

      <SectionTitle title="排行榜" actionText="完整榜单" />
      {board.loading ? <Loading /> : null}
      {!board.loading && (board.data ?? []).length === 0 ? <Empty text="暂无排行数据" /> : null}
      <Card>
        {(board.data ?? []).slice(0, 5).map((r, i) => (
          <View key={`${r.rank}-${r.name}`}>
            {i > 0 ? <View style={{ height: 1, backgroundColor: c.borderSubtle }} /> : null}
            <View style={{ flexDirection: 'row', alignItems: 'center', gap: 10, paddingVertical: 10 }}>
              <View
                style={{
                  width: 24, height: 24, borderRadius: 12,
                  backgroundColor: r.rank <= 3 ? c.accent : c.fillHover,
                  alignItems: 'center', justifyContent: 'center',
                }}
              >
                <Text style={{ color: r.rank <= 3 ? c.onAccent : c.textSecondary, fontSize: t.typography.sizeXs, fontWeight: '700' }}>
                  {r.rank}
                </Text>
              </View>
              <Text numberOfLines={1} style={{ color: c.textPrimary, fontSize: t.typography.sizeSm, fontWeight: r.rank <= 3 ? '700' : '400', flex: 1 }}>
                {r.name}
              </Text>
              <Text style={{ color: c.textSecondary, fontSize: t.typography.sizeSm, fontWeight: '500' }}>{r.score} 分</Text>
            </View>
          </View>
        ))}
      </Card>
    </Screen>
  );
}

/* ---------------- 作答页 ---------------- */

function SessionPage({ sessionId, onBack }: { sessionId: string; onBack: () => void }) {
  const sdk = useSdk();
  const t = sdk.theme;
  const c = t.colors;
  const session = useResource<SessionDetail>(
    () => sdk.api.request(`${API}/me/practice/sessions/${encodeURIComponent(sessionId)}`),
    [sessionId],
  );
  const [answers, setAnswers] = useState<Record<string, string>>({});
  const [submitting, setSubmitting] = useState(false);
  const questions = session.data?.questions ?? [];
  const [index, setIndex] = useState(0);

  const q = questions[index];
  const answeredCount = Object.keys(answers).length;

  const pick = (option: string) => {
    if (!q) return;
    setAnswers((prev) => ({ ...prev, [q.questionId]: option }));
  };

  const submit = () => {
    Alert.alert('交卷', `已答 ${answeredCount}/${questions.length} 题，确定交卷？`, [
      { text: '继续作答', style: 'cancel' },
      {
        text: '交卷',
        onPress: () => {
          setSubmitting(true);
          const payload = questions.map((qq) => ({ questionId: qq.questionId, choice: answers[qq.questionId] ?? '' }));
          void sdk.api
            .request(`${API}/me/practice/sessions/${encodeURIComponent(sessionId)}/submit`, {
              method: 'POST',
              body: { answers: payload },
            })
            .then(() => {
              setSubmitting(false);
              session.reload();
              Alert.alert('已交卷', '判分结果以练习记录为准', [{ text: '好的', onPress: onBack }]);
            })
            .catch((e) => {
              setSubmitting(false);
              Alert.alert('交卷失败', e instanceof Error ? e.message : String(e));
            });
        },
      },
    ]);
  };

  if (session.loading) return <Screen><Loading text="正在载入题目…" /></Screen>;
  if (session.error) {
    return (
      <Screen>
        <Card>
          <Text style={{ color: c.danger ?? '#dc2626', fontSize: t.typography.sizeSm }}>
            {session.error}
            {'\n'}
            sessionId: {sessionId}
          </Text>
        </Card>
      </Screen>
    );
  }
  if (!q) return <Screen><Empty text="该练习没有题目" /></Screen>;

  return (
    <Screen>
      {/* 进度条 */}
      <View style={{ height: 5, borderRadius: 3, backgroundColor: c.fillHover, overflow: 'hidden' }}>
        <View
          style={{
            width: `${Math.round(((index + 1) / Math.max(questions.length, 1)) * 100)}%`,
            height: 5, backgroundColor: c.accent, borderRadius: 3,
          }}
        />
      </View>

      {/* 题卡 */}
      <Card>
        <View style={{ flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' }}>
          <Badge text={q.typeLabel || '题目'} />
          <Text style={{ color: c.success ?? '#16a34a', fontSize: t.typography.sizeXs }}>{`答对 ${session.data?.correctCount ?? 0}/${questions.length}`}</Text>
        </View>
        <Text style={{ color: c.textPrimary, fontSize: t.typography.sizeMd + 1, fontWeight: '700', lineHeight: 23 }}>
          {(q.content || '').split(/
(?=[A-D][.、])/)[0].replace(/
?
?$$/, '')}
        </Text>
        <View style={{ gap: 8, marginTop: 2 }}>
          {(q.options ?? []).map((opt, i) => {
            const label = String.fromCharCode(65 + i);
            const on = answers[q.questionId] === label;
            return (
              <Pressable
                key={`${q.questionId}-${i}`}
                onPress={() => pick(label)}
                android_ripple={{ color: c.fillHover }}
                style={({ pressed }) => ({
                  flexDirection: 'row', alignItems: 'center', gap: 8,
                  paddingHorizontal: 12, paddingVertical: 11, borderRadius: t.radii.sm,
                  backgroundColor: pressed ? c.fillHover : on ? c.fillHover : c.bgPage,
                  borderWidth: 1, borderColor: on ? c.accent : c.borderSubtle,
                })}
              >
                <View
                  style={{
                    width: 22, height: 22, borderRadius: 11,
                    backgroundColor: on ? c.accent : c.bgSurface, borderWidth: 1, borderColor: on ? c.accent : c.borderSubtle,
                    alignItems: 'center', justifyContent: 'center',
                  }}
                >
                  <Text style={{ color: on ? c.onAccent : c.textTertiary, fontSize: t.typography.sizeXs, fontWeight: '700' }}>{label}</Text>
                </View>
                <Text style={{ color: on ? c.textPrimary : c.textSecondary, fontSize: t.typography.sizeSm, flexShrink: 1 }}>
                  {opt.replace(/^[A-D][.、]\s*/, '')}
                </Text>
              </Pressable>
            );
          })}
        </View>
      </Card>

      {/* 答题卡 */}
      <SectionTitle title="答题卡" actionText={`已答 ${answeredCount}`} />
      <Card>
        <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 6 }}>
          {questions.map((qq, i) => {
            const done = Boolean(answers[qq.questionId]);
            const cur = i === index;
            return (
              <Pressable
                key={qq.questionId}
                onPress={() => setIndex(i)}
                style={{
                  width: 34, height: 26, borderRadius: t.radii.sm,
                  backgroundColor: cur ? c.accent : done ? c.fillHover : c.bgPage,
                  borderWidth: 1, borderColor: cur ? c.accent : c.borderSubtle,
                  alignItems: 'center', justifyContent: 'center',
                }}
              >
                <Text style={{ color: cur ? c.onAccent : c.textTertiary, fontSize: t.typography.sizeXs, fontWeight: cur ? '700' : '400' }}>
                  {i + 1}
                </Text>
              </Pressable>
            );
          })}
        </View>
      </Card>

      {/* 上一题 / 下一题 / 交卷 */}
      <View style={{ flexDirection: 'row', gap: 10 }}>
        <Pressable
          onPress={() => setIndex((i) => Math.max(0, i - 1))}
          style={{ flex: 1, height: 46, borderRadius: t.radii.md, borderWidth: 1, borderColor: c.borderSubtle, backgroundColor: c.bgSurface, alignItems: 'center', justifyContent: 'center' }}
        >
          <Text style={{ color: c.textSecondary, fontSize: t.typography.sizeMd, fontWeight: '500' }}>上一题</Text>
        </Pressable>
        {index < questions.length - 1 ? (
          <Pressable
            onPress={() => setIndex((i) => Math.min(questions.length - 1, i + 1))}
            style={{ flex: 1, height: 46, borderRadius: t.radii.md, backgroundColor: c.accent, alignItems: 'center', justifyContent: 'center' }}
          >
            <Text style={{ color: c.onAccent, fontSize: t.typography.sizeMd, fontWeight: '500' }}>下一题</Text>
          </Pressable>
        ) : (
          <PrimaryButton title="交卷" onPress={submit} busy={submitting} />
        )}
      </View>
      {index === questions.length - 1 ? null : (
        <PrimaryButton title={`交卷（已答 ${answeredCount}/${questions.length}）`} onPress={submit} busy={submitting} />
      )}
    </Screen>
  );
}

/* ---------------- 根组件 ---------------- */

type View_ = { name: 'home' } | { name: 'session'; id: string };

function QuizApp({ initialRoute }: { initialRoute?: string }) {
  const [view, setView] = useState<View_>({ name: 'home' });
  useEffect(() => {
    currentSdk?.navigation?.setTitle(view.name === 'home' ? '题库练习' : '随机练习');
  }, [view, initialRoute]);
  return view.name === 'home' ? (
    <QuizHome onOpenSession={(id) => setView({ name: 'session', id })} />
  ) : (
    <View style={{ flex: 1 }}>
      <View style={{ paddingTop: 12, paddingHorizontal: 20 }}>
        <BackRow onBack={() => setView({ name: 'home' })} />
      </View>
      <SessionPage sessionId={view.id} onBack={() => setView({ name: 'home' })} />
    </View>
  );
}

const QuizModule: MobilePluginModule = {
  default: (props: Record<string, unknown>) => {
    const sdk = (props as { sdk?: PluginMobileSdk }).sdk;
    if (!sdk) {
      throw new Error('questionbank: 宿主未注入 sdk');
    }
    currentSdk = sdk;
    const route = (props as { route?: string }).route;
    return (
      <UiProvider sdk={sdk}>
        <QuizApp initialRoute={route} />
      </UiProvider>
    );
  },
};

function UiProvider({ sdk, children }: { sdk: PluginMobileSdk; children: React.ReactNode }) {
  const { UiProvider: Provider } = require('@yudream/plugin-mobile-ui');
  return <Provider sdk={sdk}>{children}</Provider>;
}

export default QuizModule;
