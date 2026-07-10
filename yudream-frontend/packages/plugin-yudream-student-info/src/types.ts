export interface StudentProfile {
  userId: string
  username?: string
  nickname?: string
  email?: string
  studentName?: string | null
  studentNo?: string | null
  className?: string | null
  college?: string | null
  createdAt?: TimeValue
  updatedAt?: TimeValue
}

export interface StudentInfoSummary {
  profileCount: number
}

export interface StudentProfileFields {
  studentName: string
  studentNo: string
  className: string
  college: string
}

export interface AdminStudentProfileForm extends StudentProfileFields {
  userId: string
}

export interface StudentProfileFilters {
  keyword: string
  college: string
  className: string
}

export interface StudentProfilePage {
  records: StudentProfile[]
  total: number
}

export type TimeValue = number | string | number[] | Date | null | undefined
