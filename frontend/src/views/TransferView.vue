<template>
  <div>
    <el-alert v-if="!isStaffView" type="info" :closable="false" show-icon style="margin-bottom:10px"
              title="船主视角：可在此查看涉及您的完整过户交割链与逐项责任，但不能发起/更正交割。" />
    <el-tabs v-model="tab" class="panel">
      <!-- ================= 办理过户（仅员工） ================= -->
      <el-tab-pane v-if="isStaffView" name="wizard">
        <template #label>
          <span>📝 办理过户</span>
        </template>

        <div class="panel-head" style="padding:0 4px 12px">
          <h3 style="margin:0">船只过户交割</h3>
          <span class="panel-sub">
            登记双方、生效时点与交割说明；提交前逐项预览靠泊 / 出航 / 服务费用归属，跨时点事项须人工选定承担方
          </span>
        </div>

        <el-form label-width="120px" style="max-width:920px">
          <el-form-item label="过户船只" required>
            <el-select v-model="form.vesselId" filterable style="width:340px" placeholder="选择要过户的船">
              <el-option v-for="v in vessels" :key="v.id"
                         :label="`${v.name} · ${v.regNo} · 当前船主 ${v.ownerName}`" :value="v.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="生效时点" required>
            <el-date-picker v-model="form.effectiveAt" type="datetime"
                            value-format="YYYY-MM-DDTHH:mm:ss" placeholder="责任分界时点" />
            <span class="muted" style="margin-left:8px;font-size:12px">此前已完成的事实归旧船主，此后责任/新增费用归新船主</span>
          </el-form-item>
          <el-form-item>
            <el-button type="primary" :loading="previewLoading" @click="loadPreview">生成责任预览</el-button>
            <el-button @click="resetWizard">重置</el-button>
          </el-form-item>
        </el-form>

        <template v-if="preview">
          <el-alert type="info" :closable="false" show-icon style="margin:8px 0 12px">
            <template #title>
              {{ preview.vesselName }}（{{ preview.vesselRegNo }}）· 原船主 {{ preview.oldOwnerName }}
              · 生效时点 {{ fmt(preview.effectiveAt) }}
            </template>
            <div style="margin-top:4px">
              系统可自动判定 <b>{{ preview.autoCount }}</b> 项，
              需人工选择承担方 <b style="color:#e65100">{{ preview.manualCount }}</b> 项，
              归属待判 <b style="color:#c62828">{{ preview.pendingCount }}</b> 项。
              建议旧船主承担 <b>¥{{ preview.oldAmountTotal }}</b>，新船主承担
              <b>¥{{ preview.newAmountTotal }}</b>（拆账项已分别计入）。
            </div>
          </el-alert>

          <el-table :data="preview.items" size="small" border row-key="key" style="margin-bottom:16px">
            <el-table-column prop="factTypeName" label="类别" width="70" />
            <el-table-column label="事项" min-width="210">
              <template #default="{ row }">
                <div><b>{{ row.factNo }}</b> · {{ row.title }}</div>
                <div class="muted" style="font-size:11px">
                  区间 {{ fmt(row.periodStart) }} ~ {{ fmt(row.periodEnd) || '进行中' }}
                </div>
                <div v-if="row.debtNote" style="font-size:11px;color:#c62828">欠费说明：{{ row.debtNote }}</div>
                <div v-if="row.internalRemark" style="font-size:11px;color:#6a1b9a">内部备注：{{ row.internalRemark }}</div>
                <div v-if="row.legacyNote" style="font-size:11px;color:#bf360c">历史缺据：{{ row.legacyNote }}</div>
              </template>
            </el-table-column>
            <el-table-column prop="amount" label="金额" width="100">
              <template #default="{ row }">{{ row.amount != null ? '¥' + row.amount : '—' }}</template>
            </el-table-column>
            <el-table-column label="系统建议" width="150">
              <template #default="{ row }">
                <el-tag size="small" :type="partyTag(row.suggestedParty)">
                  {{ row.suggestedPartyName || '待选择' }}
                </el-tag>
                <div class="muted" style="font-size:10px;line-height:1.3">{{ row.suggestedResolutionName }}</div>
              </template>
            </el-table-column>
            <el-table-column label="责任选择 / 理由" min-width="280">
              <template #default="{ row }">
                <!-- 可自动判定：展示建议结果 -->
                <div v-if="row.auto && !row.pending">
                  <span class="muted" style="font-size:12px">
                    {{ row.suggestedParty === 'SPLIT'
                       ? `旧 ¥${row.suggestedAmountOld ?? 0} / 新 ¥${row.suggestedAmountNew ?? '按续期结算'}`
                       : (row.suggestedParty === 'OLD' ? '归旧船主' : '归新船主') }}
                  </span>
                </div>
                <!-- 归属待判：可现场补判或保留待判 -->
                <div v-else-if="row.pending" style="display:flex;flex-direction:column;gap:4px">
                  <el-radio-group v-model="decisionMap[row.key].party" size="small">
                    <el-radio-button label="OLD">判旧船主</el-radio-button>
                    <el-radio-button label="NEW">判新船主</el-radio-button>
                    <el-radio-button label="">保留待判</el-radio-button>
                  </el-radio-group>
                  <el-input v-model="decisionMap[row.key].reason" size="small"
                            placeholder="补判必须填写依据（缴费凭证/台账等），禁止按当前船主定责" />
                </div>
                <!-- 必须人工选择的跨时点项 -->
                <div v-else style="display:flex;flex-direction:column;gap:4px">
                  <el-radio-group v-model="decisionMap[row.key].party" size="small">
                    <el-radio-button label="OLD">旧船主承担</el-radio-button>
                    <el-radio-button label="NEW">新船主承担</el-radio-button>
                    <el-radio-button v-if="row.factType === 'SERVICE'" label="SPLIT">人工拆账</el-radio-button>
                  </el-radio-group>
                  <div v-if="row.factType === 'SERVICE' && decisionMap[row.key].party === 'SPLIT'"
                       style="display:flex;gap:6px;align-items:center">
                    旧 <el-input-number v-model="decisionMap[row.key].amountOld" :min="0" :step="100" size="small"
                                        controls-position="right" style="width:130px" />
                    新 <el-input-number v-model="decisionMap[row.key].amountNew" :min="0" :step="100" size="small"
                                        controls-position="right" style="width:130px" />
                  </div>
                  <el-input v-model="decisionMap[row.key].reason" size="small"
                            placeholder="必须说明无法按区间拆分的理由与承担依据" />
                </div>
              </template>
            </el-table-column>
          </el-table>

          <!-- 新船主与交割说明 -->
          <el-form label-width="120px" style="max-width:920px">
            <el-form-item label="新船主" required>
              <el-select v-model="form.newOwnerId" filterable style="width:300px" placeholder="选择新船主">
                <el-option v-for="o in owners" :key="o.id"
                           :label="`${o.ownerName}${o.phone ? ' · ' + o.phone : ''}`" :value="o.id" />
              </el-select>
              <el-button link type="primary" @click="openNewOwner">＋ 新船主建档</el-button>
            </el-form-item>
            <el-form-item label="交割说明">
              <el-input v-model="form.note" type="textarea" :rows="2"
                        placeholder="如：随船移交保险单、维修尾款约定、钥匙/证件交接情况等" />
            </el-form-item>
            <el-form-item label="办理人" required>
              <el-input v-model="form.operator" style="width:200px" placeholder="码头工作人员姓名" />
            </el-form-item>
            <el-form-item>
              <el-popconfirm
                title="确认完成过户？生效时点将作为责任分界，在泊记录会按区间拆段，交割记录此后不可删除只能追加更正。"
                confirm-button-text="确认交割" cancel-button-text="再核对" @confirm="submitTransfer">
                <template #reference>
                  <el-button type="primary" size="large">提交过户交割</el-button>
                </template>
              </el-popconfirm>
            </el-form-item>
          </el-form>
        </template>
      </el-tab-pane>

      <!-- ================= 交割链 ================= -->
      <el-tab-pane name="chain">
        <template #label><span>🔗 交割链</span></template>
        <div class="panel-head" style="padding:0 4px 12px">
          <h3 style="margin:0">过户交割记录</h3>
          <span class="panel-sub">append-only：只能追加更正说明，不能删除；码头运营人员可查看完整交割链</span>
          <div class="panel-extra">
            <el-select v-model="chainVesselId" clearable filterable placeholder="全部船只" style="width:220px"
                       @change="loadChain">
              <el-option v-for="v in vessels" :key="v.id" :label="v.name" :value="v.id" />
            </el-select>
            <el-button @click="loadChain">刷新</el-button>
          </div>
        </div>
        <el-table :data="chain" size="small" border>
          <el-table-column prop="transferNo" label="交割单号" width="150" />
          <el-table-column label="船只" width="140">
            <template #default="{ row }">{{ row.vesselName }}<div class="muted" style="font-size:11px">{{ row.vesselRegNo }}</div></template>
          </el-table-column>
          <el-table-column label="原船主 → 新船主" min-width="200">
            <template #default="{ row }">
              {{ row.oldOwnerName }}
              <span v-if="isStaffView && row.oldOwnerPhone" class="muted" style="font-size:11px">（{{ row.oldOwnerPhone }}）</span>
              → <b>{{ row.newOwnerName }}</b>
              <span v-if="isStaffView && row.newOwnerPhone" class="muted" style="font-size:11px">（{{ row.newOwnerPhone }}）</span>
            </template>
          </el-table-column>
          <el-table-column prop="effectiveAt" label="生效时点" width="160" :formatter="fmtRow" />
          <el-table-column label="待判" width="70">
            <template #default="{ row }">
              <el-tag v-if="row.pendingCount > 0" size="small" type="danger">{{ row.pendingCount }}</el-tag>
              <span v-else class="muted">0</span>
            </template>
          </el-table-column>
          <el-table-column label="旧/新承担" width="180">
            <template #default="{ row }">
              <span style="color:#0277bd">¥{{ row.oldAmountTotal }}</span> /
              <span style="color:#ef6c00">¥{{ row.newAmountTotal }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="operator" label="办理人" width="100" />
          <el-table-column label="操作" width="120" fixed="right">
            <template #default="{ row }">
              <el-button size="small" link type="primary" @click="openDetail(row)">详情/更正</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- ================= 归属待判台账 ================= -->
      <el-tab-pane name="pending" v-if="isStaffView">
        <template #label><span>⚖️ 归属待判 <el-badge v-if="pendingCountTop" :value="pendingCountTop" /></span></template>
        <div class="panel-head" style="padding:0 4px 12px">
          <h3 style="margin:0">归属待判台账</h3>
          <span class="panel-sub">历史数据缺少当时船主/服务起止区间时保留原记录、标记待判；补充依据后才能定责，禁止按当前船主批量改写</span>
          <div class="panel-extra"><el-button @click="loadPending">刷新</el-button></div>
        </div>
        <el-table :data="pendingList" size="small" border>
          <el-table-column prop="id" label="#" width="60" />
          <el-table-column label="船只/事项" min-width="180">
            <template #default="{ row }">
              {{ row.vesselName }} · {{ row.factTypeName }} {{ row.factNo }}
            </template>
          </el-table-column>
          <el-table-column prop="basis" label="待判原因" min-width="240" show-overflow-tooltip />
          <el-table-column prop="createdAt" label="登记时间" width="160" :formatter="fmtRow" />
          <el-table-column label="补判" width="150" fixed="right">
            <template #default="{ row }">
              <el-button size="small" type="primary" link @click="openReconcile(row)">补充依据定责</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <!-- 交割详情抽屉：逐项责任 + 更正链 -->
    <el-drawer v-model="detailVisible" size="640px" :title="`交割单 ${detail.transferNo || ''}`">
      <template v-if="detail.id">
        <el-descriptions :column="1" border size="small" style="margin-bottom:12px">
          <el-descriptions-item label="船只">{{ detail.vesselName }}（{{ detail.vesselRegNo }}）</el-descriptions-item>
          <el-descriptions-item label="原船主">
            {{ detail.oldOwnerName }}
            <span v-if="isStaffView && detail.oldOwnerPhone" class="muted">（{{ detail.oldOwnerPhone }}）</span>
          </el-descriptions-item>
          <el-descriptions-item label="新船主">
            {{ detail.newOwnerName }}
            <span v-if="isStaffView && detail.newOwnerPhone" class="muted">（{{ detail.newOwnerPhone }}）</span>
          </el-descriptions-item>
          <el-descriptions-item label="生效时点">{{ fmt(detail.effectiveAt) }}</el-descriptions-item>
          <el-descriptions-item label="交割说明">{{ detail.note || '—' }}</el-descriptions-item>
          <el-descriptions-item label="办理人">{{ detail.operator }}</el-descriptions-item>
        </el-descriptions>

        <h4>逐项责任（{{ detail.items?.length || 0 }}）</h4>
        <el-table :data="detail.items" size="small" border style="margin-bottom:12px">
          <el-table-column prop="factTypeName" label="类" width="56" />
          <el-table-column prop="factNo" label="单号" width="120" />
          <el-table-column label="承担" width="92">
            <template #default="{ row }">
              <el-tag size="small" :type="partyTag(row.party)">
                {{ row.status === 'SUPERSEDED' ? '已更正' : row.partyName }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="金额（旧/新）" width="150">
            <template #default="{ row }">
              <span v-if="row.amountOld != null" style="color:#0277bd">旧 ¥{{ row.amountOld }}</span>
              <span v-if="row.amountNew != null" style="color:#ef6c00">新 ¥{{ row.amountNew }}</span>
              <span v-if="row.amountOld == null && row.amountNew == null" class="muted">—</span>
            </template>
          </el-table-column>
          <el-table-column prop="basis" label="判定方式/理由" min-width="200">
            <template #default="{ row }">
              <div>{{ row.resolutionName }}</div>
              <div class="muted" style="font-size:11px">{{ row.basis }}</div>
            </template>
          </el-table-column>
        </el-table>

        <h4>更正说明（{{ detail.corrections?.length || 0 }}，只追加不删改）</h4>
        <el-timeline style="margin-bottom:12px">
          <el-timeline-item v-for="c in detail.corrections" :key="c.id"
                            :timestamp="`${fmt(c.createdAt)} · ${c.operator}`">
            {{ c.text }}
          </el-timeline-item>
          <div v-if="!detail.corrections?.length" class="muted">暂无更正</div>
        </el-timeline>

        <el-form v-if="isStaffView" label-width="80px">
          <el-form-item label="追加更正">
            <el-input v-model="correctionText" type="textarea" :rows="2"
                      placeholder="原始交割记录不变，此处仅追加更正说明" />
          </el-form-item>
          <el-form-item label="办理人">
            <el-input v-model="correctionOperator" style="width:200px" />
          </el-form-item>
          <el-button type="primary" @click="submitCorrection">追加更正说明</el-button>
        </el-form>
      </template>
    </el-drawer>

    <!-- 新船主建档 -->
    <el-dialog v-model="ownerVisible" title="新船主建档" width="460px">
      <el-form label-width="90px">
        <el-form-item label="姓名" required><el-input v-model="ownerForm.ownerName" /></el-form-item>
        <el-form-item label="联系电话"><el-input v-model="ownerForm.phone" placeholder="11 位手机号" /></el-form-item>
        <el-form-item label="证件号"><el-input v-model="ownerForm.idCard" /></el-form-item>
        <el-form-item label="备注"><el-input v-model="ownerForm.remark" type="textarea" :rows="2" /></el-form-item>
        <div class="muted" style="font-size:12px">电话/证件/备注仅码头运营人员可见，不会对其他船主暴露</div>
      </el-form>
      <template #footer>
        <el-button @click="ownerVisible = false">取消</el-button>
        <el-button type="primary" @click="submitOwner">保存并选用</el-button>
      </template>
    </el-dialog>

    <!-- 归属待判补判 -->
    <el-dialog v-model="reconcileVisible" title="归属待判 · 补充依据定责" width="500px">
      <el-form label-width="90px">
        <el-form-item v-if="reconcileForm.hasTransfer" label="承担方" required>
          <el-radio-group v-model="reconcileForm.party">
            <el-radio label="OLD">旧船主承担</el-radio>
            <el-radio label="NEW">新船主承担</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-else label="承担船主" required>
          <el-select v-model="reconcileForm.ownerId" filterable style="width:100%"
                     placeholder="该历史项无交割单，请直接选择承担船主">
            <el-option v-for="o in owners" :key="o.id" :label="o.ownerName" :value="o.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="判定依据" required>
          <el-input v-model="reconcileForm.basis" type="textarea" :rows="3"
                    placeholder="依据（缴费凭证、抄表记录、纸质台账条目等）；禁止无依据按当前船主定责" />
        </el-form-item>
        <el-form-item label="办理人" required>
          <el-input v-model="reconcileForm.operator" style="width:200px" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="reconcileVisible = false">取消</el-button>
        <el-button type="primary" @click="submitReconcile">确认定责</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, inject, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { transferApi, vesselApi } from '../api'
import { identity, isStaff } from '../identity'

const route = useRoute()
const marina = inject('marina', null)
/** 模板里的身份判定必须是响应式布尔（直接引用函数 isStaff 永远为真值） */
const isStaffView = computed(() => isStaff())
const tab = ref(isStaff() ? 'wizard' : 'chain')
const vessels = ref([])
const owners = ref([])

const form = reactive({ vesselId: null, effectiveAt: '', newOwnerId: null, note: '', operator: '' })
const preview = ref(null)
const previewLoading = ref(false)
const decisionMap = reactive({})

const chain = ref([])
const chainVesselId = ref(null)
const detailVisible = ref(false)
const detail = ref({})
const correctionText = ref('')
const correctionOperator = ref('')

const pendingList = ref([])
const pendingCountTop = computed(() => pendingList.value.length)

const ownerVisible = ref(false)
const ownerForm = reactive({ ownerName: '', phone: '', idCard: '', remark: '' })

const reconcileVisible = ref(false)
const reconcileForm = reactive({ id: null, hasTransfer: true, party: 'OLD', ownerId: null, basis: '', operator: '' })

function fmt(v) {
  return v ? String(v).replace('T', ' ').slice(0, 16) : ''
}
function fmtRow(row, column, value) {
  return fmt(value)
}
function partyTag(party) {
  return { OLD: 'primary', NEW: 'warning', SPLIT: 'success', PENDING: 'danger' }[party] || 'info'
}

async function loadBase() {
  const [v, o] = await Promise.all([vesselApi.list(), transferApi.owners()])
  vessels.value = v
  owners.value = o
}

async function loadPreview() {
  if (!form.vesselId || !form.effectiveAt) {
    ElMessage.warning('请先选择船只和生效时点')
    return
  }
  previewLoading.value = true
  try {
    preview.value = await transferApi.preview(form.vesselId, form.effectiveAt)
    // 初始化人工/待判项的选择模型
    Object.keys(decisionMap).forEach((k) => delete decisionMap[k])
    for (const it of preview.value.items) {
      if (!it.auto || it.pending) {
        decisionMap[it.key] = {
          party: it.pending ? '' : (it.suggestedParty === 'OLD' || it.suggestedParty === 'NEW' ? it.suggestedParty : 'OLD'),
          amountOld: it.suggestedAmountOld != null ? Number(it.suggestedAmountOld) : 0,
          amountNew: it.suggestedAmountNew != null ? Number(it.suggestedAmountNew) : Number(it.amount || 0),
          reason: ''
        }
      }
    }
  } finally {
    previewLoading.value = false
  }
}

function resetWizard() {
  preview.value = null
  form.vesselId = null
  form.effectiveAt = ''
  form.newOwnerId = null
  form.note = ''
}

async function submitTransfer() {
  if (!form.newOwnerId) {
    ElMessage.warning('请选择新船主')
    return
  }
  if (!form.operator) {
    ElMessage.warning('请填写办理人')
    return
  }
  const decisions = []
  for (const it of preview.value.items) {
    if (it.auto && !it.pending) continue
    const d = decisionMap[it.key]
    if (it.pending) {
      if (d.party) {
        if (!d.reason) {
          ElMessage.error(`历史待判项「${it.factNo}」补判必须填写依据`)
          return
        }
        decisions.push({ key: it.key, party: d.party, reason: d.reason })
      }
      continue
    }
    if (!d.party) {
      ElMessage.error(`跨时点事项「${it.factNo}」必须选择承担方`)
      return
    }
    if (!d.reason) {
      ElMessage.error(`跨时点事项「${it.factNo}」必须填写理由`)
      return
    }
    if (d.party === 'SPLIT') {
      decisions.push({
        key: it.key, split: true,
        amountOld: d.amountOld, amountNew: d.amountNew, reason: d.reason
      })
    } else {
      decisions.push({ key: it.key, party: d.party, reason: d.reason })
    }
  }
  const created = await transferApi.submit({ ...form, decisions })
  ElMessage.success('过户交割已完成')
  preview.value = null
  resetWizard()
  await loadBase()
  await loadChain()
  if (marina) {
    await marina.reloadVessels()
    await marina.refreshOverview()
  }
  detail.value = created
  detailVisible.value = true
  tab.value = 'chain'
}

async function loadChain() {
  chain.value = await transferApi.list(chainVesselId.value || undefined)
}

async function openDetail(row) {
  detail.value = await transferApi.detail(row.id)
  correctionText.value = ''
  correctionOperator.value = form.operator || ''
  detailVisible.value = true
}

async function submitCorrection() {
  if (!correctionText.value) {
    ElMessage.warning('请填写更正说明')
    return
  }
  if (!correctionOperator.value) {
    ElMessage.warning('请填写办理人')
    return
  }
  detail.value = await transferApi.addCorrection(detail.value.id, {
    text: correctionText.value, operator: correctionOperator.value
  })
  correctionText.value = ''
  ElMessage.success('更正说明已追加')
  await loadChain()
}

function openNewOwner() {
  Object.assign(ownerForm, { ownerName: '', phone: '', idCard: '', remark: '' })
  ownerVisible.value = true
}

async function submitOwner() {
  if (!ownerForm.ownerName) {
    ElMessage.warning('请填写姓名')
    return
  }
  const o = await transferApi.createOwner({ ...ownerForm })
  await loadBase()
  form.newOwnerId = o.id
  ownerVisible.value = false
  ElMessage.success('新船主已建档并选用')
}

async function loadPending() {
  if (isStaff()) pendingList.value = await transferApi.pending()
}

function openReconcile(row) {
  Object.assign(reconcileForm, {
    id: row.id,
    hasTransfer: !!row.transferId,
    party: 'OLD',
    ownerId: null,
    basis: '',
    operator: form.operator || ''
  })
  reconcileVisible.value = true
}

async function submitReconcile() {
  if (reconcileForm.hasTransfer) {
    if (!reconcileForm.party) {
      ElMessage.warning('请选择承担方')
      return
    }
  } else if (!reconcileForm.ownerId) {
    ElMessage.warning('请选择承担船主')
    return
  }
  if (!reconcileForm.basis) {
    ElMessage.warning('必须填写判定依据')
    return
  }
  if (!reconcileForm.operator) {
    ElMessage.warning('请填写办理人')
    return
  }
  await transferApi.reconcile(reconcileForm.id, {
    party: reconcileForm.hasTransfer ? reconcileForm.party : null,
    ownerId: reconcileForm.hasTransfer ? null : reconcileForm.ownerId,
    basis: reconcileForm.basis,
    operator: reconcileForm.operator
  })
  reconcileVisible.value = false
  ElMessage.success('已完成定责，原待判记录保留可追溯')
  await loadPending()
  await loadChain()
}

onMounted(async () => {
  await loadBase()
  if (route.query.vesselId) {
    form.vesselId = Number(route.query.vesselId)
    tab.value = 'wizard'
  }
  await loadChain()
  if (isStaff()) await loadPending()
})
</script>
