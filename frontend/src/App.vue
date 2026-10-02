<template>
  <div class="shell">
    <!-- 顶部固定条：品牌 + 码头计数器 -->
    <header class="topbar">
      <div class="brand">
        <span class="brand-mark">⚓</span>
        <div>
          游艇码头 Marina 泊位运营管理平台
          <div class="brand-sub">{{ todayText }} · 泊位占用 {{ overview.berthInPort || 0 }}/{{ overview.berthTotal || 0 }}</div>
        </div>
      </div>

      <!-- 身份切换（演示无登录：码头运营人员 / 船主视角，用于验证可见边界） -->
      <div class="identity-box">
        <span class="muted" style="font-size:12px">当前视角</span>
        <el-radio-group v-model="identity.role" size="small" @change="onRoleChange">
          <el-radio-button label="STAFF">码头运营</el-radio-button>
          <el-radio-button label="OWNER">船主</el-radio-button>
        </el-radio-group>
        <el-select v-if="identity.role === 'OWNER'" v-model="identity.ownerId" size="small"
                   filterable style="width:150px" placeholder="选择船主" @change="onRoleChange">
          <el-option v-for="o in owners" :key="o.id"
                     :label="`${o.ownerName}`" :value="o.id" />
        </el-select>
      </div>

      <div class="top-stats" v-if="identity.role === 'STAFF'">
        <div class="stat-chip"><b>{{ overview.berthFree || 0 }}</b><span>空闲泊位</span></div>
        <div class="stat-chip"><b>{{ overview.berthReserved || 0 }}</b><span>预留</span></div>
        <div class="stat-chip" :class="{ 'is-alert': (overview.berthDisabled || 0) > 0 }">
          <b>{{ overview.berthDisabled || 0 }}</b><span>停用维修</span>
        </div>
        <div class="stat-chip"><b>{{ overview.vesselInPort || 0 }}</b><span>在港船</span></div>
        <div class="stat-chip"><b>{{ overview.vesselAtSea || 0 }}</b><span>出海中</span></div>
        <div class="stat-chip"><b>{{ overview.planPending || 0 }}</b><span>待审批</span></div>
        <div class="stat-chip" :class="{ 'is-alert': (overview.planSuspended || 0) > 0 }">
          <b>{{ overview.planSuspended || 0 }}</b><span>暂停放行</span>
        </div>
        <div class="stat-chip" :class="{ 'is-alert': (overview.closureEffective || 0) > 0 }">
          <b>{{ overview.closureActive || 0 }}</b><span>封航令</span>
        </div>
        <div class="stat-chip" :class="{ 'is-alert': (overview.dispRiskOpen || 0) > 0 }">
          <b>{{ overview.dispRiskOpen || 0 }}</b><span>返港风险</span>
        </div>
        <div class="stat-chip" :class="{ 'is-alert': (overview.planOverdue || 0) > 0 }">
          <b>{{ overview.planOverdue || 0 }}</b><span>超期未返</span>
        </div>
        <div class="stat-chip" :class="{ 'is-warn': (overview.serviceUnsettled || 0) > 0 }">
          <b>{{ overview.serviceUnsettled || 0 }}</b><span>未结费用</span>
        </div>
        <div class="stat-chip" :class="{ 'is-warn': (overview.insuranceWarn || 0) > 0 }">
          <b>{{ overview.insuranceWarn || 0 }}</b><span>保险预警</span>
        </div>
      </div>

      <!-- 船主视角提示条：只能看自己持有期间的船只/费用，运营动作受限 -->
      <div v-else class="top-stats owner-note">
        <el-tag type="warning" size="small">船主视角</el-tag>
        <span>仅显示您持有期间的船只、靠泊、出航与费用；看不到其他船主电话、欠费说明与内部备注。</span>
      </div>
    </header>

    <!-- 横向模块条 -->
    <nav class="navbar">
      <button
        v-for="item in visibleNavItems"
        :key="item.key"
        class="nav-btn"
        :class="{ 'is-on': activeKey === item.key }"
        type="button"
        :title="item.hint"
        @click="go(item.path)"
      >
        <span class="nav-ico">{{ item.icon }}</span>{{ item.label }}
      </button>
    </nav>

    <main class="stage">
      <router-view :key="`${identity.role}-${identity.ownerId || 0}-${viewEpoch}`" />
    </main>
  </div>
</template>

<script setup>
import { computed, getCurrentInstance, onMounted, provide, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import router, { navItems } from './router'
import { marinaApi, transferApi, vesselApi } from './api'
import { identity } from './identity'

/**
 * 模板自带的 main.js 只装了 Element Plus，没装 router（那份文件不动它），
 * 所以在这里把 router 装到根组件所属的 app 实例上。
 * vue-router 的 install 自带幂等保护，重复调用无副作用。
 */
getCurrentInstance().appContext.app.use(router)

const route = useRoute()
const nav = useRouter()

const overview = ref({})
const vessels = ref([])
const owners = ref([])

/** 全局上下文：顶栏计数 + 船只下拉（各页 dialog 直接用） */
const marina = {
  overview,
  vessels,
  refreshOverview: loadOverview,
  reloadVessels: loadVessels
}
provide('marina', marina)

const todayText = computed(() => {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
})

const activeKey = computed(() => {
  const hit = visibleNavItems.value.find((item) => route.path.startsWith(item.path))
  return hit ? hit.key : 'berths'
})

/** 员工看全部模块；船主隐藏纯运营模块（封航返港、过户办理仍可在交割页只读自己的交割链）。 */
const visibleNavItems = computed(() =>
  navItems.filter((item) => !item.staffOnly || identity.role === 'STAFF'))

function go(path) {
  nav.push(path)
}

async function loadOverview() {
  if (identity.role !== 'STAFF') {
    overview.value = {}
    return
  }
  overview.value = await marinaApi.overview()
}

async function loadVessels() {
  vessels.value = await vesselApi.list()
}

async function loadOwners() {
  owners.value = await transferApi.owners()
}

/** 切换视角：重载当前页数据（各页自行按身份过滤/脱敏，这里用 key 强制 router-view 重挂载） */
const viewEpoch = ref(0)
async function onRoleChange() {
  if (identity.role === 'OWNER' && !identity.ownerId && owners.value.length) {
    identity.ownerId = owners.value[0].id
  }
  viewEpoch.value++
  if (identity.role === 'STAFF') {
    await loadOverview()
  }
  await loadVessels()
}

onMounted(async () => {
  await loadOwners()
  await loadOverview()
  await loadVessels()
})
</script>
