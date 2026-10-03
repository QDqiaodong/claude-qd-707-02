// 泊位页越权裁剪的组件级验证：
// 用真实接口形状（curl 拿到的种子数据）mock API，分别以 STAFF / OWNER 挂载 BerthView，
// 断言页面渲染、抽屉详情、历史表与接口裁剪后的结果一致。
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import { identity } from '../src/identity'

// ---- 真实种子数据（与 /api 实返一致） ----
const berthA01Staff = {
  id: 1, berthNo: 'A-01', zoneCode: 'A', pierNo: 'A1', lengthM: 10.5, maxDraftM: 1.8,
  allowType: 'SAIL;FISHING;SPEED', allowTypeNames: '帆船/钓鱼艇/快艇', dailyRate: 280,
  utility: 'POWER_WATER', utilityName: '水电齐全', status: 'IN_PORT', statusName: '在泊',
  currentVesselId: 1, currentVesselName: '海风号', currentVesselType: 'SAIL',
  currentVesselTypeName: '帆船', currentVesselOwner: '陈志远',
  currentVesselLengthM: 9.8, currentVesselDraftM: 1.5,
  currentBerthingId: 1, currentBerthingNo: 'KP-2026-0001',
  currentBerthTime: '2026-09-29T19:30:16', currentEtaDepart: '2026-10-04T19:30:16'
}
const berthA02Staff = {
  id: 2, berthNo: 'A-02', zoneCode: 'A', pierNo: 'A1', lengthM: 9.8, maxDraftM: 1.6,
  allowType: 'SAIL;SPEED', allowTypeNames: '帆船/快艇', dailyRate: 260,
  utility: 'POWER', utilityName: '仅供电', status: 'IN_PORT', statusName: '在泊',
  currentVesselId: 2, currentVesselName: '逐浪者', currentVesselType: 'SPEED',
  currentVesselTypeName: '快艇', currentVesselOwner: '林浩',
  currentVesselLengthM: 8.6, currentVesselDraftM: 0.9,
  currentBerthingId: 2, currentBerthingNo: 'KP-2026-0002',
  currentBerthTime: '2026-09-26T19:30:16', currentEtaDepart: '2026-10-03T19:30:16'
}
const berthA03Free = {
  id: 3, berthNo: 'A-03', zoneCode: 'A', pierNo: 'A1', lengthM: 11, maxDraftM: 2,
  allowType: 'SAIL;FISHING;SPEED', allowTypeNames: '帆船/钓鱼艇/快艇', dailyRate: 300,
  utility: 'POWER_WATER', utilityName: '水电齐全', status: 'FREE', statusName: '空闲',
  currentVesselId: null, currentVesselName: null, currentVesselType: null,
  currentVesselTypeName: null, currentVesselOwner: null,
  currentVesselLengthM: null, currentVesselDraftM: null,
  currentBerthingId: null, currentBerthingNo: null, currentBerthTime: null, currentEtaDepart: null
}

// 后端修复后：OWNER 视角 A-02 仅保留占用状态，识别字段全部置 null
function redactForOtherOwner(b) {
  const x = { ...b }
  x.currentVesselId = null; x.currentVesselName = null; x.currentVesselType = null
  x.currentVesselTypeName = null; x.currentVesselOwner = null
  x.currentVesselLengthM = null; x.currentVesselDraftM = null
  x.currentBerthingId = null; x.currentBerthingNo = null
  x.currentBerthTime = null; x.currentEtaDepart = null
  return x
}

const staffBerths = [berthA01Staff, berthA02Staff, berthA03Free]

const ownBerthing = {
  id: 1, recordNo: 'KP-2026-0001', berthId: 1, berthNo: 'A-01', vesselId: 1, vesselName: '海风号',
  berthTime: '2026-09-29T19:30:16', etaDepart: '2026-10-04T19:30:16', actualDepart: null,
  days: null, fee: null, status: 'IN_PORT', statusName: '在泊'
}
const otherBerthing = {
  id: 10, recordNo: 'KP-2026-0010', berthId: 1, berthNo: 'A-01', vesselId: 6, vesselName: '白帆',
  berthTime: '2026-08-23T19:30:16', etaDepart: null, actualDepart: '2026-08-27T19:30:16',
  days: 4, fee: 1120, status: 'SETTLED', statusName: '已结算离泊'
}

function mountView() {
  const app = createApp({})
  app.use(ElementPlus)
  return mount(BerthView, {
    global: {
      plugins: [ElementPlus],
      provide: { marina: null }
    },
    attachTo: document.body
  })
}

let BerthView

describe('BerthView 船主视角越权裁剪', () => {
  beforeEach(() => {
    vi.resetModules()
  })

  it('OWNER：自有船完整、他人船只只显示占用状态，页面无任何识别信息', async () => {
    // 固定为 OWNER 1（陈志远）
    identity.role = 'OWNER'
    identity.ownerId = 1

    vi.doMock('../src/api', () => {
      const ownerBerths = staffBerths.map((b) =>
        b.berthNo === 'A-02' ? redactForOtherOwner(b) : b)
      return {
        berthApi: {
          list: vi.fn().mockResolvedValue(ownerBerths),
          berthings: vi.fn().mockResolvedValue([ownBerthing]) // 服务端已过滤：只回自己
        },
        vesselApi: { list: vi.fn().mockResolvedValue([{ id: 1, name: '海风号' }]) },
        berthingApi: { list: vi.fn().mockResolvedValue([ownBerthing]) },
        serviceApi: {}
      }
    })

    BerthView = (await import('../src/views/BerthView.vue')).default
    const wrapper = mountView()
    await flushPromises()

    const html = document.body.innerHTML
    // 自有船：船名/靠泊单出现
    expect(html).toContain('海风号')
    expect(html).toContain('KP-2026-0001')
    // 他人船只识别信息一律不出现
    expect(html).not.toContain('逐浪者')
    expect(html).not.toContain('林浩')
    expect(html).not.toContain('KP-2026-0002')
    // 他人在泊泊位保留「在泊占用」提示
    expect(html).toContain('在泊占用')

    // 打开他人在泊泊位 A-02 的抽屉（直接访问详情）
    const cells = wrapper.findAll('.berth-cell')
    const a02 = cells.find((c) => c.text().includes('A-02'))
    await a02.trigger('click')
    await flushPromises()
    const drawerHtml = document.body.innerHTML
    expect(drawerHtml).toContain('属其他船主，不予展示')
    expect(drawerHtml).not.toContain('逐浪者')
    expect(drawerHtml).not.toContain('KP-2026-0002')
    // 历史表里只有自己的一条（服务端裁剪结果）
    expect(drawerHtml).toContain('海风号')

    // 打开自有泊位 A-01：详情完整
    const a01 = cells.find((c) => c.text().includes('A-01'))
    await a01.trigger('click')
    await flushPromises()
    const ownDrawer = document.body.innerHTML
    expect(ownDrawer).toContain('海风号')
    expect(ownDrawer).toContain('KP-2026-0001')
    expect(ownDrawer).toContain('陈志远')

    wrapper.unmount()
  })

  it('STAFF：完整信息全部展示，业务按钮可用', async () => {
    identity.role = 'STAFF'
    identity.ownerId = null

    vi.doMock('../src/api', () => ({
      berthApi: {
        list: vi.fn().mockResolvedValue(staffBerths),
        berthings: vi.fn(async (id) => id === 1
          ? [ownBerthing, otherBerthing]
          : [{ ...ownBerthing, id: 2, recordNo: 'KP-2026-0002', berthId: 2,
              vesselId: 2, vesselName: '逐浪者', berthNo: 'A-02' }])
      },
      vesselApi: { list: vi.fn().mockResolvedValue([
        { id: 1, name: '海风号', typeName: '帆船', lengthM: 9.8, draftM: 1.5 }
      ]) },
      berthingApi: { list: vi.fn().mockResolvedValue([ownBerthing, otherBerthing]) },
      serviceApi: {}
    }))

    BerthView = (await import('../src/views/BerthView.vue')).default
    const wrapper = mountView()
    await flushPromises()

    const html = document.body.innerHTML
    // 平面图上两艘在泊船的船名都在
    expect(html).toContain('海风号')
    expect(html).toContain('逐浪者')
    expect(html).toContain('KP-2026-0001') // 流水表在泊记录
    expect(html).toContain('KP-2026-0010') // 流水表他人已结算记录也在
    // 员工视角出现办理业务按钮
    expect(html).toContain('安排靠泊')
    expect(html).toContain('办理离泊')

    // 打开 A-02 抽屉：员工能看到他人船只的完整详情
    const cells = wrapper.findAll('.berth-cell')
    const a02 = cells.find((c) => c.text().includes('A-02'))
    await a02.trigger('click')
    await flushPromises()
    const drawerHtml = document.body.innerHTML
    expect(drawerHtml).toContain('逐浪者')
    expect(drawerHtml).toContain('林浩')
    expect(drawerHtml).toContain('KP-2026-0002')
    expect(drawerHtml).not.toContain('属其他船主，不予展示')
    wrapper.unmount()
  })
})
