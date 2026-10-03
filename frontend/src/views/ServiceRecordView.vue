<template>
  <div>
    <!-- 月度费用汇总 -->
    <div class="panel">
      <div class="panel-head">
        <h3>费用汇总</h3>
        <span class="panel-sub">按月份、按服务类型统计（金额均为服务端 单价 × 数量）</span>
      </div>

      <div class="kv-grid" style="margin-bottom:12px">
        <div class="kv"><span>费用总额</span><b style="color:#0277bd">¥{{ summary.totalAmount }}</b></div>
        <div class="kv"><span>未结金额</span><b style="color:#ef6c00">¥{{ summary.unsettledAmount }}</b></div>
        <div class="kv"><span>挂账金额</span><b style="color:#c62828">¥{{ summary.onAccountAmount }}</b></div>
        <div class="kv"><span>记录笔数</span><b>{{ summary.totalCount }}</b></div>
      </div>

      <el-table :data="summary.byMonth || []" size="small" border style="margin-bottom:12px">
        <el-table-column prop="month" label="月份" width="120" />
        <el-table-column prop="count" label="笔数" width="90" />
        <el-table-column prop="amount" label="月度金额" width="140">
          <template #default="{ row }"><b>¥{{ row.amount }}</b></template>
        </el-table-column>
        <el-table-column prop="settledAmount" label="其中已结" width="140" />
        <el-table-column label="占比">
          <template #default="{ row }">
            <div style="display:flex; align-items:center; gap:8px">
              <div style="flex:1; height:10px; background:#e1f5fe; border-radius:5px; overflow:hidden">
                <div :style="{ width: percent(row.amount) + '%', height: '100%', background: '#0277bd' }"></div>
              </div>
              <span class="muted" style="width:48px">{{ percent(row.amount) }}%</span>
            </div>
          </template>
        </el-table-column>
      </el-table>

      <el-table :data="summary.byType || []" size="small" border>
        <el-table-column prop="serviceTypeName" label="服务类型" width="140" />
        <el-table-column prop="count" label="笔数" width="90" />
        <el-table-column prop="amount" label="金额" width="140">
          <template #default="{ row }"><b>¥{{ row.amount }}</b></template>
        </el-table-column>
        <el-table-column label="占比">
          <template #default="{ row }">
            <div style="display:flex; align-items:center; gap:8px">
              <div style="flex:1; height:10px; background:#e1f5fe; border-radius:5px; overflow:hidden">
                <div :style="{ width: percent(row.amount) + '%', height: '100%', background: '#0288d1' }"></div>
              </div>
              <span class="muted" style="width:48px">{{ percent(row.amount) }}%</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="筛选">
          <template #default="{ row }">
            <el-button size="small" link type="primary" @click="typeFilter = row.serviceType; load()">
              只看这一类
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- 服务记录明细 -->
    <div class="panel">
      <div class="panel-head">
        <h3>服务记录</h3>
        <span class="panel-sub">接电 / 加水 / 加油 / 船体清洗 / 吊机上下排 / 维修 / 污油水接收</span>
        <div class="panel-extra">
          <el-select v-model="typeFilter" clearable placeholder="全部类型" style="width:150px" @change="load">
            <el-option v-for="t in SERVICE_TYPES" :key="t.value" :label="t.label" :value="t.value" />
          </el-select>
          <el-select v-model="settleFilter" clearable placeholder="全部结算状态" style="width:140px" @change="load">
            <el-option label="未结" value="UNSETTLED" />
            <el-option label="已结" value="SETTLED" />
            <el-option label="挂账" value="ON_ACCOUNT" />
          </el-select>
          <el-button v-if="isStaffView" type="primary" @click="openCreate">登记服务</el-button>
          <el-button @click="loadAll">刷新</el-button>
        </div>
      </div>

      <el-table :data="records" size="small" border stripe>
        <el-table-column prop="id" label="#" width="56" />
        <el-table-column label="服务类型" width="150">
          <template #default="{ row }">
            {{ row.serviceTypeName }}
            <el-tag v-if="row.serviceKind === 'CONTINUOUS'" size="small" type="warning" effect="plain">持续</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="发生/服务区间" width="180">
          <template #default="{ row }">
            <div>{{ row.occurredDate }}</div>
            <div v-if="row.serviceKind === 'CONTINUOUS'" class="muted" style="font-size:11px">
              {{ row.startDate || '?' }} ~ {{ row.endDate || '进行中' }}
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="month" label="月份" width="90" />
        <el-table-column prop="unitPrice" label="单价" width="90" />
        <el-table-column label="数量" width="90">
          <template #default="{ row }">{{ row.quantity }} {{ row.serviceUnit }}</template>
        </el-table-column>
        <el-table-column label="金额（我承担）" width="120">
          <template #default="{ row }">
            <b>¥{{ row.liableAmount != null ? row.liableAmount : row.amount }}</b>
            <div v-if="row.party === 'SPLIT'" class="muted" style="font-size:10px">
              原额 ¥{{ row.amount }}（拆账）
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="berthNo" label="泊位" width="80" />
        <el-table-column label="船只 / 责任船主" width="150">
          <template #default="{ row }">
            <div>{{ row.vesselName }}</div>
            <div v-if="row.ownerName" class="muted" style="font-size:11px">责任船主 {{ row.ownerName }}</div>
          </template>
        </el-table-column>
        <el-table-column label="责任归属" width="110">
          <template #default="{ row }">
            <el-tag v-if="row.attributionStatus === 'PENDING'" size="small" type="danger">归属待判</el-tag>
            <el-tag v-else-if="row.party" size="small" :type="partyTag(row.party)">{{ row.partyName }}</el-tag>
            <el-tag v-else size="small" type="info" effect="plain">按持有期</el-tag>
            <div v-if="row.isSplitSegment" class="muted" style="font-size:10px">交割拆出段</div>
          </template>
        </el-table-column>
        <el-table-column prop="settleStatusName" label="结算" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="settleTag(row.settleStatus)">{{ row.settleStatusName }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column v-if="isStaffView" label="欠费说明 / 内部备注" min-width="180">
          <template #default="{ row }">
            <div v-if="row.debtNote" style="color:#c62828;font-size:11px">欠费：{{ row.debtNote }}</div>
            <div v-if="row.internalRemark" style="color:#6a1b9a;font-size:11px">备注：{{ row.internalRemark }}</div>
            <div v-if="row.legacyNote" style="color:#bf360c;font-size:11px">历史缺据：{{ row.legacyNote }}</div>
            <span v-if="!row.debtNote && !row.internalRemark && !row.legacyNote" class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="160" fixed="right">
          <template #default="{ row }">
            <template v-if="isStaffView">
              <el-button v-if="row.settleStatus !== 'SETTLED'" type="success" size="small" link
                         @click="settle(row, 'SETTLED')">结算</el-button>
              <el-button v-if="row.settleStatus !== 'ON_ACCOUNT'" type="warning" size="small" link
                         @click="settle(row, 'ON_ACCOUNT')">挂账</el-button>
              <el-button v-if="row.settleStatus !== 'UNSETTLED'" size="small" link
                         @click="settle(row, 'UNSETTLED')">退回未结</el-button>
            </template>
            <el-tag v-else size="small" type="info" effect="plain">只读</el-tag>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-dialog v-model="createVisible" title="登记码头服务" width="500px">
      <el-form label-width="110px">
        <el-form-item label="服务类型">
          <el-select v-model="form.serviceType" style="width:100%" @change="onTypeChange">
            <el-option v-for="t in SERVICE_TYPES" :key="t.value" :label="t.label + '（元/' + t.unit + '）'" :value="t.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="服务形态">
          <el-radio-group v-model="form.serviceKind">
            <el-radio label="POINT">一次性</el-radio>
            <el-radio label="CONTINUOUS">持续服务</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="单价"><el-input-number v-model="form.unitPrice" :min="0.01" :step="1" /></el-form-item>
        <el-form-item label="数量"><el-input-number v-model="form.quantity" :min="0.01" :step="1" /></el-form-item>
        <el-form-item label="发生日期">
          <el-date-picker v-model="form.occurredDate" type="date" value-format="YYYY-MM-DD" style="width:100%" />
        </el-form-item>
        <template v-if="form.serviceKind === 'CONTINUOUS'">
          <el-form-item label="服务起始">
            <el-date-picker v-model="form.startDate" type="date" value-format="YYYY-MM-DD" style="width:100%" />
          </el-form-item>
          <el-form-item label="服务结束">
            <el-date-picker v-model="form.endDate" type="date" value-format="YYYY-MM-DD"
                            placeholder="留空=仍在续" style="width:100%" />
          </el-form-item>
        </template>
        <el-form-item label="关联泊位">
          <el-select v-model="form.berthId" clearable filterable style="width:100%">
            <el-option v-for="b in berths" :key="b.id" :label="`${b.berthNo}（${b.zoneCode} 区）`" :value="b.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="关联船只">
          <el-select v-model="form.vesselId" clearable filterable style="width:100%">
            <el-option v-for="v in vessels" :key="v.id" :label="v.name" :value="v.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="结算状态">
          <el-select v-model="form.settleStatus" style="width:100%">
            <el-option label="未结" value="UNSETTLED" />
            <el-option label="已结" value="SETTLED" />
            <el-option label="挂账" value="ON_ACCOUNT" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="isStaffView" label="欠费说明">
          <el-input v-model="form.debtNote" placeholder="仅码头运营可见" />
        </el-form-item>
        <el-form-item v-if="isStaffView" label="内部备注">
          <el-input v-model="form.internalRemark" type="textarea" :rows="2" placeholder="仅码头运营可见" />
        </el-form-item>
        <div style="font-size:13px">金额：<b style="color:#c62828">¥{{ amount }}</b>（服务端现算，不接受前端传值）</div>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" @click="submit">登记</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, inject, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { berthApi, serviceApi, vesselApi } from '../api'
import { isStaff } from '../identity'

/** 模板里的身份判定必须是响应式布尔（直接引用函数 isStaff 永远为真值） */
const isStaffView = computed(() => isStaff())

const SERVICE_TYPES = [
  { value: 'POWER', label: '接电', unit: '度', price: 12 },
  { value: 'WATER', label: '加水', unit: '吨', price: 15 },
  { value: 'FUEL', label: '加油', unit: '升', price: 8.6 },
  { value: 'HULL_WASH', label: '船体清洗', unit: '米', price: 45 },
  { value: 'LIFT', label: '吊机上下排', unit: '次', price: 680 },
  { value: 'REPAIR', label: '维修', unit: '工时', price: 260 },
  { value: 'WASTE', label: '污油水接收', unit: '次', price: 120 }
]

const marina = inject('marina', null)
const records = ref([])
const summary = ref({})
const berths = ref([])
const vessels = ref([])
const typeFilter = ref('')
const settleFilter = ref('')
const createVisible = ref(false)
const form = ref({})

const amount = computed(() => (Number(form.value.unitPrice || 0) * Number(form.value.quantity || 0)).toFixed(2))

function percent(value) {
  const total = Number(summary.value.totalAmount || 0)
  if (!total) return 0
  return Math.round((Number(value || 0) / total) * 100)
}

function settleTag(status) {
  return { SETTLED: 'success', ON_ACCOUNT: 'warning', UNSETTLED: 'danger' }[status] || 'info'
}

function partyTag(party) {
  return { OLD: 'primary', NEW: 'warning', SPLIT: 'success', PENDING: 'danger' }[party] || 'info'
}

async function load() {
  const params = {}
  if (typeFilter.value) params.serviceType = typeFilter.value
  if (settleFilter.value) params.settleStatus = settleFilter.value
  records.value = await serviceApi.list(params)
}

async function loadAll() {
  await Promise.all([
    load(),
    serviceApi.summary().then((data) => { summary.value = data }),
    berthApi.list().then((data) => { berths.value = data }),
    vesselApi.list().then((data) => { vessels.value = data })
  ])
}

function openCreate() {
  form.value = {
    serviceType: 'POWER', serviceKind: 'POINT', unitPrice: 12, quantity: 100,
    occurredDate: new Date().toISOString().slice(0, 10),
    startDate: null, endDate: null,
    berthId: null, vesselId: null, settleStatus: 'UNSETTLED',
    debtNote: '', internalRemark: ''
  }
  createVisible.value = true
}

function onTypeChange(type) {
  const hit = SERVICE_TYPES.find((t) => t.value === type)
  form.value.unitPrice = hit ? hit.price : 1
  form.value.quantity = type === 'LIFT' || type === 'WASTE' ? 1 : 100
}

async function submit() {
  await serviceApi.create(form.value)
  createVisible.value = false
  ElMessage.success('服务记录已登记')
  await loadAll()
  if (marina) await marina.refreshOverview()
}

async function settle(row, settleStatus) {
  await serviceApi.settle(row.id, settleStatus)
  ElMessage.success('结算状态已更新')
  await loadAll()
  if (marina) await marina.refreshOverview()
}

onMounted(loadAll)
</script>
