import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { StudentProfile, StudentProfileFields, TimeValue } from '../types'
import { useFaToast } from '@yudream/components'
import { computed, reactive, ref } from 'vue'
import { createStudentInfoApi } from '../api/student-info-api'

export function useMyStudentProfile(sdk: YuDreamPluginSdk) {
  const api = createStudentInfoApi(sdk)
  const toast = useFaToast()
  const loading = ref(false)
  const saving = ref(false)
  const profile = ref<StudentProfile | null>(null)
  const form = reactive<StudentProfileFields>({ studentName: '', studentNo: '', className: '', college: '' })
  const accountName = computed(() => sdk.account.username || `用户 ${sdk.account.userId}`)
  const profileReady = computed(() => !!profile.value?.studentName && !!profile.value?.studentNo && !!profile.value?.className && !!profile.value?.college)

  async function load() {
    loading.value = true
    try {
      profile.value = await api.me()
      Object.assign(form, profileFields(profile.value))
    }
    finally {
      loading.value = false
    }
  }

  async function save() {
    if (!validate(form, toast.warning)) {
      return
    }
    saving.value = true
    try {
      profile.value = await api.saveMe(trimmedFields(form))
      Object.assign(form, profileFields(profile.value))
      toast.success('学生信息已保存')
    }
    finally {
      saving.value = false
    }
  }

  return reactive({ loading, saving, profile, form, accountName, profileReady, load, save, formatTime })
}

function profileFields(profile: StudentProfile | null): StudentProfileFields {
  return {
    studentName: profile?.studentName || '',
    studentNo: profile?.studentNo || '',
    className: profile?.className || '',
    college: profile?.college || '',
  }
}

export function trimmedFields(form: StudentProfileFields): StudentProfileFields {
  return {
    studentName: form.studentName.trim(),
    studentNo: form.studentNo.trim(),
    className: form.className.trim(),
    college: form.college.trim(),
  }
}

export function validate(form: StudentProfileFields, warn: (message: string) => void) {
  if (!form.studentName.trim() || !form.studentNo.trim() || !form.className.trim() || !form.college.trim()) {
    warn('请填写姓名、学号、班级和学院')
    return false
  }
  return true
}

export function formatTime(value?: TimeValue) {
  if (value == null || value === '') return '-'
  const timestamp = value instanceof Date ? value.getTime() : Array.isArray(value) ? new Date(value[0], value[1] - 1, value[2], value[3] || 0, value[4] || 0, value[5] || 0).getTime() : Number.isFinite(Number(value)) ? Number(value) : Date.parse(String(value))
  if (!Number.isFinite(timestamp) || timestamp <= 0) return '-'
  return new Date(timestamp < 10000000000 ? timestamp * 1000 : timestamp).toLocaleString('zh-CN', { hour12: false })
}

export type MyStudentProfileModel = ReturnType<typeof useMyStudentProfile>
