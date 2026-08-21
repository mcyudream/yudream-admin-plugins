<script setup lang="ts">
import type { McguessPluginModel } from '../composables/useMcguessPlugin'
import { FaButton, FaCard, FaIcon, FaPageHeader, FaPageMain } from '@yudream/components'

const props = defineProps<{ model: McguessPluginModel }>()
const overview = () => props.model.overview
</script>

<template>
  <FaPageHeader title="MC 猜谜总览" class="mb-0">
    <FaButton variant="outline" :loading="model.loading" @click="model.loadOverview"><FaIcon name="i-ri:refresh-line" />刷新</FaButton>
  </FaPageHeader>
  <FaPageMain>
    <div v-loading="model.loading" class="mcguess-stat-grid">
      <FaCard class="mcguess-stat-card">
        <span>猜物 · 累计 / 进行中 / 已获胜</span>
        <strong>{{ overview() ? `${overview()!.item.total} / ${overview()!.item.playing} / ${overview()!.item.won}` : '-' }}</strong>
      </FaCard>
      <FaCard class="mcguess-stat-card">
        <span>猜生物 · 累计 / 进行中 / 已获胜</span>
        <strong>{{ overview() ? `${overview()!.mob.total} / ${overview()!.mob.playing} / ${overview()!.mob.won}` : '-' }}</strong>
      </FaCard>
      <FaCard class="mcguess-stat-card">
        <span>猜合成 · 累计 / 进行中 / 已获胜</span>
        <strong>{{ overview() ? `${overview()!.recipe.total} / ${overview()!.recipe.playing} / ${overview()!.recipe.won}` : '-' }}</strong>
      </FaCard>
      <FaCard class="mcguess-stat-card">
        <span>迷雾 · 累计 / 进行中 / 已获胜</span>
        <strong>{{ overview() ? `${overview()!.fog.total} / ${overview()!.fog.playing} / ${overview()!.fog.won}` : '-' }}</strong>
      </FaCard>
      <FaCard class="mcguess-stat-card">
        <span>快答 · 累计 / 进行中 / 已获胜</span>
        <strong>{{ overview() ? `${overview()!.quiz.total} / ${overview()!.quiz.playing} / ${overview()!.quiz.won}` : '-' }}</strong>
      </FaCard>
      <FaCard class="mcguess-stat-card">
        <span>宾果 · 累计 / 进行中 / 已获胜</span>
        <strong>{{ overview() ? `${overview()!.bingo.total} / ${overview()!.bingo.playing} / ${overview()!.bingo.won}` : '-' }}</strong>
      </FaCard>
      <FaCard class="mcguess-stat-card">
        <span>找茬 · 累计 / 进行中 / 已获胜</span>
        <strong>{{ overview() ? `${overview()!.spot.total} / ${overview()!.spot.playing} / ${overview()!.spot.won}` : '-' }}</strong>
      </FaCard>
      <FaCard class="mcguess-stat-card">
        <span>参与玩家</span>
        <strong>{{ overview()?.playerCount ?? '-' }}</strong>
      </FaCard>
      <FaCard class="mcguess-stat-card">
        <span>物品总数</span>
        <strong>{{ overview()?.itemCount ?? '-' }}</strong>
      </FaCard>
      <FaCard class="mcguess-stat-card">
        <span>可合成 / 目标池</span>
        <strong>{{ overview() ? `${overview()!.craftableCount} / ${overview()!.guessTargetCount}` : '-' }}</strong>
      </FaCard>
      <FaCard class="mcguess-stat-card">
        <span>生物 / 条件</span>
        <strong>{{ overview() ? `${overview()!.mobCount} / ${overview()!.conditionCount}` : '-' }}</strong>
      </FaCard>
    </div>

    <FaCard class="mcguess-help-card">
      <h3>群内玩法（群回合制，一局结束后可立即再开新局）</h3>
      <div class="mcguess-help-grid">
        <div>
          <h4><FaIcon name="i-ri:treasure-map-line" />猜物</h4>
          <p>系统随机选定目标 Minecraft 物品（JE 1.20.5），猜测区域是它的 3x3 合成配方。</p>
          <p><code>/猜物 钻石剑</code> 提交猜测，命中配方树的物品会在对应格子揭示；<code>/猜物格子 1-9</code> 查看已揭示格的配方，<code>/猜物提示</code>（连续空猜 6 次解锁）随机揭示一格，<code>/结束猜物</code> 投降揭晓。</p>
        </div>
        <div>
          <h4><FaIcon name="i-ri:bug-line" />猜生物</h4>
          <p>3 行条件 × 3 列条件的 9 格棋盘，生物须同时满足行与列条件且同盘不重复。</p>
          <p><code>/猜生物 5 僵尸</code> 填格，填错扣 1 ❤️（共 6 颗）；填满 9 格获胜，<code>/结束猜生物</code> 投降揭晓参考答案。</p>
        </div>
        <div>
          <h4><FaIcon name="i-ri:flask-line" />猜合成</h4>
          <p>目标物品公开，逐格猜它的 3x3 配方原料；猜中某格会一并揭示该物品占用的全部格子。</p>
          <p><code>/猜合成 5 木板</code> 填格，<code>/猜合成提示</code>（连续空猜 6 次解锁）随机揭示一格原料，<code>/结束猜合成</code> 投降揭晓。</p>
        </div>
        <div>
          <h4><FaIcon name="i-ri:mist-line" />图标迷雾</h4>
          <p>目标物品图标先以纯黑剪影呈现，群内每猜错一次，迷雾就散去一层（共 5 层），直到有人认出它。</p>
          <p><code>/迷雾 钻石剑</code> 提交猜测，<code>/结束迷雾</code> 投降揭晓。</p>
        </div>
        <div>
          <h4><FaIcon name="i-ri:question-answer-line" />合成快答</h4>
          <p>连续 5 题：「合成 1 个 X 共需几个 Y」，四选一抢答，每题首个答对者得分，总分最高者获胜。</p>
          <p><code>/快答 A</code> 作答当前题目，<code>/结束快答</code> 提前结束并揭晓全部答案。</p>
        </div>
        <div>
          <h4><FaIcon name="i-ri:grid-line" />MC 宾果</h4>
          <p>全群共享 5x5 物品棋盘，说出棋盘上的物品名即可认领该格；率先连成任意一整行、整列或对角线者获胜。</p>
          <p><code>/宾果 钻石</code> 认领格子，<code>/结束宾果</code> 提前结束。</p>
        </div>
        <div>
          <h4><FaIcon name="i-ri:search-eye-line" />配方找茬</h4>
          <p>展示目标物品的真实 3x3 配方，但其中一个非空格被偷偷换成了别的物品，找出被动过手脚的格子。</p>
          <p><code>/找茬 5</code> 指认格子（1-9），<code>/结束找茬</code> 投降揭晓。</p>
        </div>
        <div>
          <h4><FaIcon name="i-ri:swap-line" />比大小（个人）</h4>
          <p>个人连胜挑战：比较两个物品在全配方树中的出现次数，猜 B 比 A 高还是低，答对连胜 +1 并继续，答错清零。</p>
          <p><code>/比大小</code> 开局，<code>/高</code> / <code>/低</code> 作答，需先绑定账号。</p>
        </div>
        <div>
          <h4><FaIcon name="i-ri:book-open-line" />图鉴与战绩</h4>
          <p>猜物 / 猜合成 / 迷雾 / 找茬获胜、快答答对、宾果认领都会把对应物品收入图鉴；<code>/图鉴</code> 查看收集进度，<code>/图鉴排行</code> 查看全服收集榜。</p>
          <p><code>/猜物战绩</code>（需绑定账号）与 <code>/猜物排行</code> 查看七种模式的个人数据与总排行；猜物与猜合成支持智能匹配：可忽略颜色词、主世界木质词与材质词。</p>
        </div>
      </div>
    </FaCard>
  </FaPageMain>
</template>
