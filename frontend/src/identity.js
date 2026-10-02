import { reactive } from 'vue'

/**
 * 演示用访问身份（系统无登录体系，用请求头模拟）。
 * role=STAFF 码头运营人员（完整交割链、电话、欠费说明、内部备注）；
 * role=OWNER 船主（只能看自己持有期间的船只与费用，看不到他人电话/欠费/备注）。
 */
export const identity = reactive({
  role: 'STAFF',
  ownerId: null
})

export function actorHeaders() {
  const headers = { 'X-Actor-Role': identity.role }
  if (identity.role === 'OWNER' && identity.ownerId) {
    headers['X-Actor-Id'] = String(identity.ownerId)
  }
  return headers
}

export function isStaff() {
  return identity.role === 'STAFF'
}
