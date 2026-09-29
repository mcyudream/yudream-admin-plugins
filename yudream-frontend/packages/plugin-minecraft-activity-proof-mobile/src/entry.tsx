/**
 * minecraft-activity-proof 移动端（设计稿 activitySquare / activityDetail）：
 * 活动广场（封面卡：站点封面或内置 MC 占位图 + 报名）与活动详情（按活动绑定渲染任务：
 * 在线答题走题库会话、时长/自定义核验、参与证明核验 + 盖章 PDF 下载落盘）。
 * 数据走 /api/plugins/minecraft-activity-proof/me/** 与题库 /api/plugins/questionbank/
 * me/practice/sessions**；视觉经 sdk.theme 与 plugin-mobile-ui，不写死色值。
 */
import React, { useCallback, useEffect, useState } from 'react';
import { Alert, Image, Pressable, Text, View } from 'react-native';
import RNFS from 'react-native-fs';
import type { MobilePluginModule, PluginMobileSdk } from '@yudream/plugin-sdk-mobile';
import {
  Badge, Card, Empty, Icon, Loading, PrimaryButton, ProgressBar, Screen, SectionTitle,
  UiProvider, useResource,
} from '@yudream/plugin-mobile-ui';

let currentSdk: PluginMobileSdk | null = null;
function useSdk(): PluginMobileSdk {
  return currentSdk!;
}

const API = '/api/plugins/minecraft-activity-proof';
const QB = '/api/plugins/questionbank';
/** 内置 MC 占位图（随 JAR 的 frontend-mobile 资产下发，匿名可访问）。 */
const PLACEHOLDER_PATH = '/api/platform/plugins/minecraft-activity-proof/assets/mobile/activity-placeholder.jpg';

interface RequirementRow { type: string; text: string; formCode?: string; formName?: string }
interface ActivityRow {
  id: string;
  title: string;
  summary?: string;
  description?: string;
  coverUrl?: string | null;
  signupStart?: number | string | null;
  signupEnd?: number | string | null;
  activityStart?: number | string | null;
  activityEnd?: number | string | null;
  status?: string;
  deptRestricted?: boolean;
  allowedDeptNames?: string[];
  requirements?: string[];
  requirementDetails?: RequirementRow[];
  participantCount?: number;
  eligible?: boolean;
  joinDisabledReason?: string;
  participationStatus?: string;
  joinedAt?: number;
  verifyStatus?: string;
  verifyNote?: string;
}
interface QuizView {
  enabled: boolean;
  available: boolean;
  joined: boolean;
  count: number;
  passCorrect: number;
  subjectiveMode?: string | null;
  attempts: number;
  passed: boolean;
  sessionId?: string | null;
  sessionStatus?: string | null;
  correctCount?: number | null;
  totalCount?: number | null;
  pendingReview?: boolean;
}
interface SessionQuestion { questionId: string; type: string; typeLabel?: string; content: string; options?: string[] }
interface SessionDetail { id: string; status?: string; questions?: SessionQuestion[]; correctCount?: number; totalCount?: number }
interface ExportRow {
  id: string;
  activityId?: string;
  activityName?: string;
  stampedPdfReady?: boolean;
  stampedPdfFilename?: string;
  stampedPdfSize?: number;
  generatedAt?: number;
}

const errText = (e: unknown) => (e instanceof Error ? e.message : String(e));
function num(v: unknown): number {
  const n = Number(v);
  return Number.isFinite(n) ? n : 0;
}
function fmtRange(a?: number | string | null, b?: number | string | null): string {
  const fmt = (v: unknown) => {
    const n = num(v);
    if (!n) return '';
    const d = new Date(n);
    return `${d.getMonth() + 1}.${d.getDate()}`;
  };
  const s = fmt(a);
  const e = fmt(b);
  return s && e ? `${s} – ${e}` : s || e || '时间待定';
}
const requirementLabel = (type: string) =>
  type === 'QUIZ' ? '在线答题' : type === 'PLAYTIME' ? '时长核验' : type === 'FORM' ? '表单任务' : '进阶核验';

/* ---------------- 封面（站点封面 / 内置占位图，坏图回退） ---------------- */

function CoverImage({ coverUrl, style }: { coverUrl?: string | null; style: { width: number | `${number}%`; height: number; borderRadius: number } }) {
  const sdk = useSdk();
  const t = sdk.theme;
  const [broken, setBroken] = useState(false);
  const uri = !broken && coverUrl ? `${sdk.baseUrl}${coverUrl}` : `${sdk.baseUrl}${PLACEHOLDER_PATH}`;
  return (
    <Image
      source={{ uri }}
      onError={() => setBroken(true)}
      style={[style, { backgroundColor: t.colors.fillHover }]}
      resizeMode="cover"
    />
  );
}

/* ---------------- 活动广场 ---------------- */

function ActivitySquare({ onOpen }: { onOpen: (id: string, title: string) => void }) {
  const sdk = useSdk();
  const t = sdk.theme;
  const c = t.colors;
  const acts = useResource<{ records?: ActivityRow[] }>(
    () => sdk.api.request(`${API}/me/activities?page=1&size=20`),
    [sdk],
  );
  const list = acts.data?.records ?? [];

  const signup = (a: ActivityRow) => {
    void sdk.api
      .request(`${API}/me/activities/${encodeURIComponent(a.id)}/join`, { method: 'POST' })
      .then(() => acts.reload())
      .catch((e) => Alert.alert('报名失败', errText(e)));
  };
  const cancelSignup = (a: ActivityRow) => {
    Alert.alert('取消报名', `取消「${a.title}」的报名？`, [
      { text: '取消', style: 'cancel' },
      {
        text: '确认取消', style: 'destructive',
        onPress: () => {
          void sdk.api
            .request(`${API}/me/activities/${encodeURIComponent(a.id)}/cancel`, { method: 'POST' })
            .then(() => acts.reload())
            .catch((e) => Alert.alert('取消失败', errText(e)));
        },
      },
    ]);
  };

  return (
    <Screen>
      {acts.loading ? <Loading /> : null}
      {!acts.loading && list.length === 0 ? <Empty text="暂无活动" /> : null}
      {list.map((a) => {
        const joined = a.participationStatus === 'JOINED' || a.participationStatus === 'ATTENDED';
        const signupOpen = a.status === 'SIGNUP' || a.status === 'ONGOING';
        const hasQuiz = (a.requirementDetails ?? []).some((r) => r.type === 'QUIZ');
        return (
          <Card key={a.id} onPress={() => onOpen(a.id, a.title)}>
            <View style={{ flexDirection: 'row', gap: 10 }}>
              <CoverImage coverUrl={a.coverUrl} style={{ width: 74, height: 64, borderRadius: 10 }} />
              <View style={{ flex: 1, gap: 4 }}>
                <View style={{ flexDirection: 'row', alignItems: 'center', gap: 6 }}>
                  <Text numberOfLines={1} style={{ color: c.textPrimary, fontSize: t.typography.sizeSm + 1, fontWeight: '700', flex: 1 }}>
                    {a.title}
                  </Text>
                  <Badge text={signupOpen ? '报名中' : '已结束'} tone={signupOpen ? 'success' : 'default'} />
                </View>
                <View style={{ flexDirection: 'row', alignItems: 'center', gap: 4 }}>
                  <Icon name="calendar" size={12} color={c.textTertiary} />
                  <Text numberOfLines={1} style={{ color: c.textTertiary, fontSize: t.typography.sizeXs, flex: 1 }}>
                    {fmtRange(a.activityStart, a.activityEnd)}
                  </Text>
                </View>
                <View style={{ flexDirection: 'row', alignItems: 'center', gap: 6 }}>
                  <ProgressBar fraction={Math.min(1, (a.participantCount ?? 0) / 50)} tone={signupOpen ? 'success' : 'default'} height={4} />
                  <Text style={{ color: c.textSecondary, fontSize: t.typography.sizeXs, fontWeight: '500' }}>
                    {a.participantCount ?? 0} 人
                  </Text>
                </View>
              </View>
            </View>
            <View style={{ flexDirection: 'row', alignItems: 'center', gap: 8 }}>
              {hasQuiz ? <Badge text="在线答题" /> : null}
              <Badge text="盖章证明" />
              <View style={{ flex: 1 }} />
              {joined ? (
                <Pressable
                  onPress={() => cancelSignup(a)}
                  style={{ paddingHorizontal: 12, paddingVertical: 6, borderRadius: 9, backgroundColor: c.fillHover }}
                >
                  <Text style={{ color: c.textSecondary, fontSize: t.typography.sizeXs, fontWeight: '500' }}>已报名</Text>
                </Pressable>
              ) : signupOpen ? (
                <Pressable
                  onPress={() => signup(a)}
                  style={{ paddingHorizontal: 12, paddingVertical: 6, borderRadius: 9, backgroundColor: c.accent }}
                >
                  <Text style={{ color: c.onAccent, fontSize: t.typography.sizeXs, fontWeight: '500' }}>立即报名</Text>
                </Pressable>
              ) : (
                <Badge text="已结束" />
              )}
            </View>
          </Card>
        );
      })}
    </Screen>
  );
}

/* ---------------- 在线答题（题库会话直答） ---------------- */

function QuizSession({ sessionId, onDone }: { sessionId: string; onDone: () => void }) {
  const sdk = useSdk();
  const t = sdk.theme;
  const c = t.colors;
  const session = useResource<SessionDetail>(
    () => sdk.api.request(`${QB}/me/practice/sessions/${encodeURIComponent(sessionId)}`),
    [sessionId],
  );
  const [answers, setAnswers] = useState<Record<string, string>>({});
  const [submitting, setSubmitting] = useState(false);
  const [index, setIndex] = useState(0);
  const questions = session.data?.questions ?? [];
  const q = questions[index];
  const answeredCount = Object.keys(answers).length;

  const submit = () => {
    Alert.alert('交卷', `已答 ${answeredCount}/${questions.length} 题，确定交卷？`, [
      { text: '继续作答', style: 'cancel' },
      {
        text: '交卷',
        onPress: () => {
          setSubmitting(true);
          const payload = questions.map((qq) => ({ questionId: qq.questionId, choice: answers[qq.questionId] ?? '' }));
          void sdk.api
            .request(`${QB}/me/practice/sessions/${encodeURIComponent(sessionId)}/submit`, {
              method: 'POST',
              body: { answers: payload },
            })
            .then(() => {
              setSubmitting(false);
              Alert.alert('已交卷', '判分结果以活动任务状态为准', [{ text: '好的', onPress: onDone }]);
            })
            .catch((e) => {
              setSubmitting(false);
              Alert.alert('交卷失败', errText(e));
            });
        },
      },
    ]);
  };

  if (session.loading) return <Screen><Loading text="正在载入题目…" /></Screen>;
  if (session.error) return <Screen><Empty text={session.error} /></Screen>;
  if (!q) return <Screen><Empty text="本次作答没有题目" /></Screen>;

  return (
    <Screen>
      <View style={{ height: 5, borderRadius: 3, backgroundColor: c.fillHover, overflow: 'hidden' }}>
        <View style={{ width: `${Math.round(((index + 1) / Math.max(questions.length, 1)) * 100)}%`, height: 5, backgroundColor: c.accent, borderRadius: 3 }} />
      </View>
      <Card>
        <View style={{ flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' }}>
          <Badge text={q.typeLabel || '题目'} />
          <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeXs }}>{`${index + 1} / ${questions.length}`}</Text>
        </View>
        <Text style={{ color: c.textPrimary, fontSize: t.typography.sizeMd + 1, fontWeight: '700', lineHeight: 23 }}>
          {(q.content || '').split(/\n(?=[A-D][.、])/)[0]}
        </Text>
        <View style={{ gap: 8, marginTop: 2 }}>
          {(q.options ?? []).map((opt, i) => {
            const label = String.fromCharCode(65 + i);
            const on = answers[q.questionId] === label;
            return (
              <Pressable
                key={`${q.questionId}-${i}`}
                onPress={() => setAnswers((prev) => ({ ...prev, [q.questionId]: label }))}
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
                  {String(opt).replace(/^[A-D][.、]\s*/, '')}
                </Text>
              </Pressable>
            );
          })}
        </View>
      </Card>

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

function QuizPage({ activityId, onBack }: { activityId: string; onBack: () => void }) {
  const sdk = useSdk();
  const t = sdk.theme;
  const c = t.colors;
  const quiz = useResource<QuizView>(
    () => sdk.api.request(`${API}/me/activities/${encodeURIComponent(activityId)}/quiz`),
    [activityId],
  );
  const [sessionId, setSessionId] = useState('');
  const [starting, setStarting] = useState(false);
  const d = quiz.data;

  const start = () => {
    if (starting) return;
    setStarting(true);
    void sdk.api
      .request<QuizView>(`${API}/me/activities/${encodeURIComponent(activityId)}/quiz/attempt`, { method: 'POST' })
      .then((v) => {
        setStarting(false);
        if (v.sessionId) setSessionId(v.sessionId);
        else quiz.reload();
      })
      .catch((e) => {
        setStarting(false);
        Alert.alert('无法开始作答', errText(e));
      });
  };

  if (sessionId) {
    return <QuizSession sessionId={sessionId} onDone={() => { setSessionId(''); quiz.reload(); }} />;
  }

  const resultNote = d?.pendingReview
    ? '含主观题，交卷后待人工复核'
    : d?.sessionStatus && d.correctCount != null
      ? `最近一次作答：对 ${Number(d.correctCount)}/${Number(d.totalCount ?? d.count)} 题`
      : '';

  return (
    <Screen>
      {quiz.loading ? <Loading /> : null}
      {d && !d.enabled ? <Empty text="该活动未配置在线答题" /> : null}
      {d && d.enabled && !d.joined ? (
        <>
          <Empty text="请先报名参与活动，再作答" />
          <PrimaryButton title="返回活动" onPress={onBack} />
        </>
      ) : null}
      {d && d.enabled && d.joined ? (
        <>
          <Card>
            <View style={{ flexDirection: 'row', alignItems: 'center', gap: 8 }}>
              <Icon name="checkmark" size={18} color={d.passed ? (c.success ?? '#16a34a') : c.accent} />
              <Text style={{ color: c.textPrimary, fontSize: t.typography.sizeSm + 1, fontWeight: '700', flex: 1 }}>
                {d.passed ? '答题已达标' : '完成作答并达标可核验参与'}
              </Text>
            </View>
            <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeXs }}>
              共 {d.count} 题 · 答对 ≥ {d.passCorrect} 题达标{d.attempts > 0 ? ` · 已作答 ${d.attempts} 次` : ''}
            </Text>
            {resultNote ? (
              <Text style={{ color: d.pendingReview ? (c.warning ?? '#d97706') : c.textSecondary, fontSize: t.typography.sizeXs }}>
                {resultNote}
              </Text>
            ) : null}
            {!d.passed ? <PrimaryButton title={d.attempts > 0 ? '继续作答' : '开始作答'} onPress={start} busy={starting} /> : null}
          </Card>
          {!d.available ? (
            <Text style={{ color: c.danger ?? '#dc2626', fontSize: t.typography.sizeXs }}>
              题库插件不可用，请联系管理员
            </Text>
          ) : null}
        </>
      ) : null}
    </Screen>
  );
}

/* ---------------- 参与证明（核验 + 盖章 PDF 下载） ---------------- */

function ProofPage({ activity, onBack }: { activity: ActivityRow; onBack: () => void }) {
  const sdk = useSdk();
  const t = sdk.theme;
  const c = t.colors;
  const id = encodeURIComponent(activity.id);
  const [verifyState, setVerifyState] = useState<{ status: string; note: string } | null>(null);
  const [verifying, setVerifying] = useState(false);
  const [dlState, setDlState] = useState('');

  const verify = () => {
    if (verifying) return;
    setVerifying(true);
    void sdk.api
      .request<{ verifyStatus?: string; verifyNote?: string }>(`${API}/me/participations/${id}/verify`, { method: 'POST' })
      .then((r) => {
        setVerifying(false);
        setVerifyState({ status: r.verifyStatus ?? '', note: r.verifyNote ?? '' });
      })
      .catch((e) => {
        setVerifying(false);
        Alert.alert('核验失败', errText(e));
      });
  };

  const download = () => {
    if (dlState) return;
    setDlState('查找中');
    void sdk.api
      .request<{ records?: ExportRow[] }>(`${API}/me/exports?page=1&size=50`)
      .then((res) => {
        const record = (res.records ?? []).find(
          (r) => r.activityId === activity.id && r.stampedPdfReady !== false,
        );
        if (!record) {
          setDlState('');
          Alert.alert('暂无可下载的证明', '管理员尚未生成本活动的盖章证明，生成后可在此下载');
          return;
        }
        setDlState('下载中');
        return sdk.api
          .request<{ filename?: string; base64?: string }>(`${API}/me/exports/${encodeURIComponent(record.id)}/stamped-pdf/base64`)
          .then((payload) => {
            const filename = payload.filename || `activity-proof-${activity.id}.pdf`;
            const to = `${RNFS.DownloadDirectoryPath}/${filename}`;
            return RNFS.writeFile(to, String(payload.base64 ?? ''), 'base64').then(() => {
              setDlState('');
              Alert.alert('已保存', `证明已保存到 Download/${filename}`);
            });
          });
      })
      .catch((e) => {
        setDlState('');
        Alert.alert('下载失败', errText(e));
      });
  };

  const passed = (verifyState?.status ?? '') === 'PASSED' || activity.verifyStatus === 'PASSED';

  return (
    <Screen>
      <Card>
        <View style={{ flexDirection: 'row', alignItems: 'center', gap: 8 }}>
          <Icon name={passed ? 'checkmark' : 'time'} size={18} color={passed ? (c.success ?? '#16a34a') : c.textTertiary} />
          <Text style={{ color: c.textPrimary, fontSize: t.typography.sizeSm + 1, fontWeight: '700', flex: 1 }}>
            {passed ? '参与核验已达标' : '完成活动任务后核验参与'}
          </Text>
        </View>
        <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeXs }}>
          {passed
            ? '核验通过的参与者会出现在活动证明名单中'
            : '核验将检查答题达标、在线时长、表单等任务完成情况'}
        </Text>
        {(verifyState?.note || activity.verifyNote) && !passed ? (
          <Text style={{ color: c.warning ?? '#d97706', fontSize: t.typography.sizeXs }}>
            {verifyState?.note || activity.verifyNote}
          </Text>
        ) : null}
        <PrimaryButton title="立即核验" onPress={verify} busy={verifying} />
      </Card>

      <SectionTitle title="盖章证明" />
      <Card>
        <View style={{ flexDirection: 'row', alignItems: 'center', gap: 8 }}>
          <Icon name="folder" size={16} color={c.accent} />
          <Text style={{ color: c.textPrimary, fontSize: t.typography.sizeSm, fontWeight: '500', flex: 1 }}>
            参与证明 PDF（含活动盖章）
          </Text>
        </View>
        <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeXs }}>
          活动结束后由管理员生成名单并盖章；生成后可随时下载到手机
        </Text>
        <PrimaryButton title={dlState || '下载盖章证明'} onPress={download} busy={Boolean(dlState)} />
      </Card>
      <Empty text="下载的 PDF 在系统通知/文件管理的 Download 目录查看" />
    </Screen>
  );
}

/* ---------------- 活动详情 ---------------- */

function ActivityDetail({
  activityId,
  onOpenQuiz,
  onOpenProof,
}: {
  activityId: string;
  onOpenQuiz: () => void;
  onOpenProof: () => void;
}) {
  const sdk = useSdk();
  const t = sdk.theme;
  const c = t.colors;
  const detail = useResource<ActivityRow>(
    () => sdk.api.request(`${API}/me/activities/${encodeURIComponent(activityId)}`),
    [activityId],
  );
  const a = detail.data;
  const [busy, setBusy] = useState(false);
  const joined = a?.participationStatus === 'JOINED' || a?.participationStatus === 'ATTENDED';
  const signupOpen = a?.status === 'SIGNUP' || a?.status === 'ONGOING';
  const requirementRows = a?.requirementDetails ?? [];
  const hasQuiz = requirementRows.some((r) => r.type === 'QUIZ');

  const signup = () => {
    if (!a || busy) return;
    setBusy(true);
    void sdk.api
      .request(`${API}/me/activities/${encodeURIComponent(a.id)}/join`, { method: 'POST' })
      .then(() => { setBusy(false); detail.reload(); })
      .catch((e) => { setBusy(false); Alert.alert('报名失败', errText(e)); });
  };

  if (detail.loading && !a) return <Screen><Loading /></Screen>;
  if (detail.error) return <Screen><Empty text={detail.error} /></Screen>;
  if (!a) return null;

  return (
    <Screen>
      <CoverImage coverUrl={a.coverUrl} style={{ width: '100%', height: 120, borderRadius: t.radii.lg }} />
      <View style={{ gap: 5 }}>
        <View style={{ flexDirection: 'row', alignItems: 'center', gap: 8 }}>
          <Text style={{ color: c.textPrimary, fontSize: t.typography.sizeXl, fontWeight: '700', flexShrink: 1 }}>
            {a.title}
          </Text>
          <Badge text={signupOpen ? '报名中' : '已结束'} tone={signupOpen ? 'success' : 'default'} />
        </View>
        <View style={{ flexDirection: 'row', gap: 12 }}>
          <Text style={{ color: c.textSecondary, fontSize: t.typography.sizeXs + 1 }}>📅 {fmtRange(a.activityStart, a.activityEnd)}</Text>
          <Text style={{ color: c.textSecondary, fontSize: t.typography.sizeXs + 1 }}>👥 已报名 {a.participantCount ?? 0}</Text>
        </View>
      </View>

      <Card>
        <View style={{ flexDirection: 'row', alignItems: 'center', gap: 10 }}>
          <View style={{ flex: 1, gap: 2 }}>
            <Text style={{ color: c.textPrimary, fontSize: t.typography.sizeSm + 1, fontWeight: '700' }}>
              {joined ? '已报名' : a.eligible === false ? '暂不可报名' : '可报名'}
            </Text>
            <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeXs }}>
              {joined
                ? (a.verifyStatus === 'PASSED' ? '参与核验已达标，活动结束后可领取盖章证明' : '可在下方完成任务并核验领取证明')
                : (a.joinDisabledReason || '报名后可参与在线答题等任务')}
            </Text>
          </View>
          {!joined && signupOpen ? (
            <Pressable
              onPress={signup}
              disabled={busy}
              style={{ paddingHorizontal: 16, paddingVertical: 9, borderRadius: 10, backgroundColor: busy ? c.fillHover : c.accent }}
            >
              <Text style={{ color: c.onAccent, fontSize: t.typography.sizeSm, fontWeight: '500' }}>立即报名</Text>
            </Pressable>
          ) : null}
        </View>
      </Card>

      <SectionTitle title="活动任务" actionText="完成可领证明" />
      <Card>
        {requirementRows.length === 0 ? (
          <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeXs, paddingVertical: 6 }}>
            本活动无额外任务，报名即达标
          </Text>
        ) : null}
        {requirementRows.map((row, i) => {
          const quiz = row.type === 'QUIZ';
          const body = (
            <View style={{ flexDirection: 'row', alignItems: 'center', gap: 10, paddingVertical: 8 }}>
              <View style={{ width: 34, height: 34, borderRadius: t.radii.sm, backgroundColor: c.fillHover, alignItems: 'center', justifyContent: 'center' }}>
                <Icon name={quiz ? 'create-outline' : row.type === 'FORM' ? 'document-text-outline' : 'checkmark'} size={16} color={c.accent} />
              </View>
              <View style={{ flex: 1, gap: 1 }}>
                <Text style={{ color: c.textPrimary, fontSize: t.typography.sizeSm, fontWeight: '500' }}>{requirementLabel(row.type)}</Text>
                <Text numberOfLines={2} style={{ color: c.textTertiary, fontSize: t.typography.sizeXs }}>
                  {quiz ? '报名后在活动期内作答并达标' : row.text}
                </Text>
              </View>
              {quiz ? <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeLg }}>{'›'}</Text> : null}
            </View>
          );
          return (
            <View key={`${row.type}-${i}`}>
              {i > 0 ? <View style={{ height: 1, backgroundColor: c.borderSubtle }} /> : null}
              {quiz ? (
                <Pressable onPress={onOpenQuiz} android_ripple={{ color: c.fillHover }}>
                  {body}
                </Pressable>
              ) : (
                body
              )}
            </View>
          );
        })}
        <View style={{ height: 1, backgroundColor: c.borderSubtle }} />
        <Pressable onPress={onOpenProof} android_ripple={{ color: c.fillHover }}>
          <View style={{ flexDirection: 'row', alignItems: 'center', gap: 10, paddingVertical: 8 }}>
            <View style={{ width: 34, height: 34, borderRadius: t.radii.sm, backgroundColor: c.fillHover, alignItems: 'center', justifyContent: 'center' }}>
              <Icon name="folder" size={16} color={c.accent} />
            </View>
            <View style={{ flex: 1, gap: 1 }}>
              <Text style={{ color: c.textPrimary, fontSize: t.typography.sizeSm, fontWeight: '500' }}>参与证明</Text>
              <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeXs }}>核验参与并下载盖章 PDF</Text>
            </View>
            <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeLg }}>{'›'}</Text>
          </View>
        </Pressable>
      </Card>

      {a.description ? (
        <Text style={{ color: c.textSecondary, fontSize: t.typography.sizeSm, lineHeight: 21 }}>
          {a.description}
        </Text>
      ) : null}
      {hasQuiz && !joined ? (
        <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeXs }}>
          提示：在线答题需先报名，作答入口在活动任务里
        </Text>
      ) : null}
    </Screen>
  );
}

/* ---------------- 根组件 ---------------- */

type View_ =
  | { name: 'square' }
  | { name: 'detail'; id: string; title: string }
  | { name: 'quiz'; id: string; title: string }
  | { name: 'proof'; id: string; title: string };

function seedStack(route?: string): View_[] {
  if (!route) return [{ name: 'square' }];
  const [head, ...rest] = route.replace(/^\/+/, '').split('/');
  const dec = (s?: string) => {
    try { return decodeURIComponent(s ?? ''); } catch { return s ?? ''; }
  };
  const id = dec(rest[0]);
  const title = dec(rest[1]);
  if ((head === 'detail' || head === 'quiz' || head === 'proof') && id) {
    const stack: View_[] = [{ name: 'square' }, { name: 'detail', id, title }];
    if (head !== 'detail') stack.push({ name: head, id, title });
    return stack;
  }
  return [{ name: 'square' }];
}

function ActivityApp({ initialRoute }: { initialRoute?: string }) {
  const [stack, setStack] = useState<View_[]>(() => seedStack(initialRoute));
  const view = stack[stack.length - 1];

  useEffect(() => {
    setStack(seedStack(initialRoute));
  }, [initialRoute]);

  useEffect(() => {
    const titles: Record<View_['name'], string> = { square: '活动广场', detail: '活动详情', quiz: '在线答题', proof: '参与证明' };
    const title = view.name === 'square' ? titles.square : view.name === 'detail' && view.title ? `${view.title} · 活动详情` : titles[view.name];
    currentSdk?.navigation?.setTitle(title);
    currentSdk?.navigation?.setBackAction?.(view.name === 'square' ? null : () => setStack((prev) => (prev.length > 1 ? prev.slice(0, -1) : prev)));
  }, [view]);

  const push = (next: View_) => setStack((prev) => [...prev, next]);

  return view.name === 'square' ? (
    <ActivitySquare onOpen={(id, title) => push({ name: 'detail', id, title })} />
  ) : view.name === 'detail' ? (
    <ActivityDetail
      activityId={view.id}
      onOpenQuiz={() => push({ name: 'quiz', id: view.id, title: view.title })}
      onOpenProof={() => push({ name: 'proof', id: view.id, title: view.title })}
    />
  ) : view.name === 'quiz' ? (
    <QuizPage activityId={view.id} onBack={() => setStack((prev) => prev.slice(0, -1))} />
  ) : (
    <ProofPage activity={{ id: view.id, title: view.title }} onBack={() => setStack((prev) => prev.slice(0, -1))} />
  );
}

const ActivityModule: MobilePluginModule = {
  default: (props: Record<string, unknown>) => {
    const sdk = (props as { sdk?: PluginMobileSdk }).sdk;
    if (!sdk) {
      throw new Error('minecraft-activity-proof: 宿主未注入 sdk');
    }
    currentSdk = sdk;
    const route = (props as { route?: string }).route;
    return (
      <UiProvider sdk={sdk}>
        <ActivityApp initialRoute={route} />
      </UiProvider>
    );
  },
};

export default ActivityModule;
