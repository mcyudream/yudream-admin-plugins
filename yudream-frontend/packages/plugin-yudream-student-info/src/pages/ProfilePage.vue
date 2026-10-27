<script setup lang="ts">
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import { YdButton, YdCard, YdForm, YdFormItem, YdIcon, YdInput, YdPageHeader, YdPageMain, YdTag } from '@yudream/components'
import { onMounted } from 'vue'
import { useMyStudentProfile } from '../composables/useMyStudentProfile'

const props = defineProps<{ sdk: YuDreamPluginSdk }>()
const model = useMyStudentProfile(props.sdk)

onMounted(model.load)
</script>

<template>
  <section class="student-info-page">
    <YdPageHeader title="我的学生信息" description="维护与当前账号绑定的个人学籍资料。" class="mb-0">
      <YdTag :variant="model.profileReady ? 'default' : 'secondary'">
        {{ model.profileReady ? '资料完整' : '待完善' }}
      </YdTag>
    </YdPageHeader>

    <YdPageMain>
      <div class="student-info-profile-grid">
        <YdCard title="学籍资料" :description="model.accountName" content-class="student-info-card-content">
          <YdForm :model="model.form" layout="vertical">
            <div class="flex flex-col gap-4">
              <YdFormItem label="姓名">
                <YdInput v-model="model.form.studentName" maxlength="40" />
              </YdFormItem>
              <YdFormItem label="学号">
                <YdInput v-model="model.form.studentNo" maxlength="64" />
              </YdFormItem>
              <YdFormItem label="班级">
                <YdInput v-model="model.form.className" maxlength="80" />
              </YdFormItem>
              <YdFormItem label="学院">
                <YdInput v-model="model.form.college" maxlength="80" />
              </YdFormItem>
              <YdFormItem>
                <YdButton :loading="model.saving" :disabled="model.loading" @click="model.save">
                  <YdIcon name="morph:save" />
                  保存
                </YdButton>
              </YdFormItem>
            </div>
          </YdForm>
        </YdCard>

        <YdCard title="当前记录" description="仅展示当前登录账号的资料" content-class="student-info-card-content">
          <dl class="student-info-detail">
            <div><dt>姓名</dt><dd>{{ model.profile?.studentName || '-' }}</dd></div>
            <div><dt>学号</dt><dd>{{ model.profile?.studentNo || '-' }}</dd></div>
            <div><dt>班级</dt><dd>{{ model.profile?.className || '-' }}</dd></div>
            <div><dt>学院</dt><dd>{{ model.profile?.college || '-' }}</dd></div>
            <div><dt>更新时间</dt><dd>{{ model.formatTime(model.profile?.updatedAt) }}</dd></div>
          </dl>
        </YdCard>
      </div>
    </YdPageMain>
  </section>
</template>
