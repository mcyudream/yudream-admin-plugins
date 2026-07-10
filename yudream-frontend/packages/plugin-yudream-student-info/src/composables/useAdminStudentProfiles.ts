import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { AdminStudentProfileForm, StudentProfile, StudentProfileFilters } from '../types'
import { useFaToast } from '@yudream/components'
import { reactive, ref } from 'vue'
import { createStudentInfoApi } from '../api/student-info-api'
import { formatTime, trimmedFields, validate } from './useMyStudentProfile'

export function useAdminStudentProfiles(sdk: YuDreamPluginSdk) {
  const api = createStudentInfoApi(sdk)
  const toast = useFaToast()
  const loading = ref(false)
  const saving = ref(false)
  const deletingUserId = ref('')
  const records = ref<StudentProfile[]>([])
  const pagination = reactive({ page: 1, size: 10, total: 0 })
  const filters = reactive<StudentProfileFilters>({ keyword: '', college: '', className: '' })
  const form = reactive<AdminStudentProfileForm>({ userId: '', studentName: '', studentNo: '', className: '', college: '' })
  const editingUserId = ref('')

  async function load() {
    loading.value = true
    try {
      const result = await api.profiles(filters, pagination.page, pagination.size)
      records.value = result.records
      pagination.total = result.total
    }
    finally {
      loading.value = false
    }
  }

  async function search() {
    pagination.page = 1
    await load()
  }

  async function resetFilters() {
    Object.assign(filters, { keyword: '', college: '', className: '' })
    pagination.page = 1
    await load()
  }

  async function onPageChange(page: number) {
    pagination.page = page
    await load()
  }

  async function onSizeChange(size: number) {
    pagination.size = size
    pagination.page = 1
    await load()
  }

  function edit(row: StudentProfile) {
    editingUserId.value = row.userId
    Object.assign(form, { userId: row.userId, studentName: row.studentName || '', studentNo: row.studentNo || '', className: row.className || '', college: row.college || '' })
  }

  function resetForm() {
    editingUserId.value = ''
    Object.assign(form, { userId: '', studentName: '', studentNo: '', className: '', college: '' })
  }

  async function save() {
    if (!form.userId.trim()) {
      toast.warning('请填写用户 ID')
      return
    }
    if (!validate(form, toast.warning)) return
    saving.value = true
    try {
      await api.saveProfile({ userId: form.userId.trim(), ...trimmedFields(form) })
      toast.success('学生档案已保存')
      resetForm()
      await load()
    }
    finally {
      saving.value = false
    }
  }

  async function remove(row: StudentProfile) {
    deletingUserId.value = row.userId
    try {
      await api.deleteProfile(row.userId)
      toast.success('学生档案已删除')
      const totalAfterDelete = Math.max(0, pagination.total - 1)
      const lastPage = Math.max(1, Math.ceil(totalAfterDelete / pagination.size))
      pagination.page = Math.min(pagination.page, lastPage)
      await load()
    }
    finally {
      deletingUserId.value = ''
    }
  }

  function displayName(row: StudentProfile) {
    return row.nickname || row.username || row.email || `用户 ${row.userId}`
  }

  return reactive({ loading, saving, deletingUserId, records, pagination, filters, form, editingUserId, load, search, resetFilters, onPageChange, onSizeChange, edit, resetForm, save, remove, displayName, formatTime })
}

export type AdminStudentProfilesModel = ReturnType<typeof useAdminStudentProfiles>
