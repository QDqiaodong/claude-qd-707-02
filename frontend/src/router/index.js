import { createRouter, createWebHistory } from 'vue-router'

/**
 * 导航形态：顶部一条横向模块条（不是侧边菜单）。
 * staffOnly 的「封航返港」仅码头运营人员可见（含他船船长电话与处置备注）。
 */
export const navItems = [
  {
    key: 'berths',
    path: '/berths',
    icon: '⚓',
    label: '泊位图',
    hint: 'SVG 手绘码头泊位俯视图 · 点泊位办靠泊/离泊'
  },
  {
    key: 'plans',
    path: '/plans',
    icon: '🧭',
    label: '出航申报',
    hint: '待审批 → 已批准 → 已返航 · 封航期间暂停放行'
  },
  {
    key: 'closures',
    path: '/closures',
    icon: '🌊',
    label: '封航返港',
    hint: '封航令四阶段 · 返港处置 · 失联升级 · 安全靠泊一笔一致',
    staffOnly: true
  },
  {
    key: 'vessels',
    path: '/vessels',
    icon: '⛵',
    label: '船只',
    hint: '卡片墙 · 保险到期预警 · 历史靠泊抽屉'
  },
  {
    key: 'transfers',
    path: '/transfers',
    icon: '🤝',
    label: '过户交割',
    hint: '双方登记 · 生效时点分责预览 · 交割链不可删只能更正 · 归属待判补判',
    staffOnlyWizard: true
  },
  {
    key: 'services',
    path: '/services',
    icon: '🧾',
    label: '服务费用',
    hint: '月度费用汇总 · 类型筛选 · 结算挂账'
  }
]

const routes = [
  { path: '/', redirect: '/berths' },
  { path: '/berths', name: 'berths', component: () => import('../views/BerthView.vue') },
  { path: '/plans', name: 'plans', component: () => import('../views/SailingPlanView.vue') },
  { path: '/closures', name: 'closures', component: () => import('../views/ClosureView.vue') },
  { path: '/vessels', name: 'vessels', component: () => import('../views/VesselView.vue') },
  { path: '/transfers', name: 'transfers', component: () => import('../views/TransferView.vue') },
  { path: '/services', name: 'services', component: () => import('../views/ServiceRecordView.vue') },
  { path: '/:pathMatch(.*)*', redirect: '/berths' }
]

export default createRouter({
  history: createWebHistory(),
  routes
})
