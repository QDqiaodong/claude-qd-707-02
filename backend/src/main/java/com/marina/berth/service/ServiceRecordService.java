package com.marina.berth.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.marina.berth.common.BizException;
import com.marina.berth.domain.Berth;
import com.marina.berth.domain.MarinaDict;
import com.marina.berth.domain.OwnerProfile;
import com.marina.berth.domain.ServiceRecord;
import com.marina.berth.domain.Vessel;
import com.marina.berth.domain.VesselOwnerPeriod;
import com.marina.berth.dto.ServiceRecordDtos;
import com.marina.berth.dto.Views;
import com.marina.berth.repository.BerthRepository;
import com.marina.berth.repository.ServiceRecordRepository;
import com.marina.berth.repository.VesselOwnerPeriodRepository;
import com.marina.berth.repository.VesselRepository;
import com.marina.berth.security.SecurityRules;

/**
 * 模块五：码头服务与费用。
 *
 * <p>本模块的规则面：
 * <ol>
 *   <li>金额由服务端按 单价 × 数量 现算，不接受前端传值</li>
 *   <li>服务记录必须关联泊位或船只，至少要有其一</li>
 *   <li>单价、数量必须大于 0；服务类型只能是词典里那七种</li>
 *   <li>结算状态：未结 → 已结 / 挂账；已结的可以退回未结（改单）</li>
 *   <li>汇总：按月、按类型统计金额与笔数，给前端月度费用汇总卡用</li>
 * </ol>
 */
@Service
public class ServiceRecordService {

    private final ServiceRecordRepository serviceRepository;
    private final BerthRepository berthRepository;
    private final VesselRepository vesselRepository;
    private final VesselOwnerPeriodRepository periodRepository;
    private final AttributionService attributionService;

    public ServiceRecordService(ServiceRecordRepository serviceRepository,
                                BerthRepository berthRepository,
                                VesselRepository vesselRepository,
                                VesselOwnerPeriodRepository periodRepository,
                                AttributionService attributionService) {
        this.serviceRepository = serviceRepository;
        this.berthRepository = berthRepository;
        this.vesselRepository = vesselRepository;
        this.periodRepository = periodRepository;
        this.attributionService = attributionService;
    }

    @Transactional(readOnly = true)
    public List<Views.ServiceRecordView> list(String serviceType, String settleStatus, Long vesselId, Long berthId) {
        List<ServiceRecord> source;
        if (vesselId != null) {
            source = serviceRepository.findAllByVesselIdOrderByOccurredDateDesc(vesselId);
        } else if (berthId != null) {
            source = serviceRepository.findAllByBerthIdOrderByOccurredDateDesc(berthId);
        } else if (serviceType != null && !serviceType.isBlank()) {
            source = serviceRepository.findAllByServiceTypeOrderByOccurredDateDesc(serviceType.trim().toUpperCase());
        } else if (settleStatus != null && !settleStatus.isBlank()) {
            source = serviceRepository.findAllBySettleStatusOrderByOccurredDateDesc(settleStatus.trim().toUpperCase());
        } else {
            source = serviceRepository.findAllByOrderByOccurredDateDescIdDesc();
        }
        java.util.Set<Long> visibleVessels = attributionService.visibleVesselIds();
        List<Views.ServiceRecordView> list = new ArrayList<>();
        for (ServiceRecord item : source) {
            AttributionService.Liability l = attributionService.resolveService(item);
            Long vid = item.getVessel() == null ? null : item.getVessel().getId();
            if (!attributionService.canSeeFact(l, vid,
                    item.getOccurredDate() == null ? null : item.getOccurredDate().atStartOfDay())) {
                continue;
            }
            // 只挂泊位、不挂船的记录：船主端看不到（无法界定归属），员工可见
            if (vid == null && visibleVessels != null) {
                continue;
            }
            if (visibleVessels != null && vid != null && !visibleVessels.contains(vid)) {
                continue;
            }
            list.add(toView(item, l));
        }
        return list;
    }

    @Transactional
    public Views.ServiceRecordView create(ServiceRecordDtos.ServiceSaveReq req) {
        SecurityRules.requireStaff("登记码头服务");
        String type = req.serviceType().trim().toUpperCase();
        if (!MarinaDict.isValidServiceType(type)) {
            throw new BizException("服务类型只能是 POWER / WATER / FUEL / HULL_WASH / LIFT / REPAIR / WASTE，"
                    + "收到的是：" + req.serviceType());
        }
        if (req.unitPrice() == null || req.unitPrice().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException("单价必须大于 0");
        }
        if (req.quantity() == null || req.quantity().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException("数量必须大于 0");
        }
        String settleStatus = req.settleStatus() == null || req.settleStatus().isBlank()
                ? "UNSETTLED"
                : req.settleStatus().trim().toUpperCase();
        if (!MarinaDict.isValidSettleStatus(settleStatus)) {
            throw new BizException("结算状态只能是 UNSETTLED / SETTLED / ON_ACCOUNT，收到的是：" + req.settleStatus());
        }
        String kind = req.serviceKind() == null || req.serviceKind().isBlank()
                ? "POINT" : req.serviceKind().trim().toUpperCase();
        if (!MarinaDict.isValidServiceKind(kind)) {
            throw new BizException("服务形态只能是 POINT（一次性）/ CONTINUOUS（持续服务）");
        }
        if ("CONTINUOUS".equals(kind) && req.startDate() != null && req.endDate() != null
                && req.endDate().isBefore(req.startDate())) {
            throw new BizException("持续服务的结束日期不能早于开始日期");
        }
        Berth berth = null;
        if (req.berthId() != null) {
            berth = berthRepository.findById(req.berthId())
                    .orElseThrow(() -> new BizException("泊位不存在：id=" + req.berthId()));
        }
        Vessel vessel = null;
        if (req.vesselId() != null) {
            vessel = vesselRepository.findById(req.vesselId())
                    .orElseThrow(() -> new BizException("船只不存在：id=" + req.vesselId()));
        }
        if (berth == null && vessel == null) {
            throw new BizException("服务记录必须关联泊位或船只");
        }

        ServiceRecord record = new ServiceRecord();
        record.setServiceType(type);
        record.setServiceKind(kind);
        record.setUnitPrice(req.unitPrice().setScale(2, RoundingMode.HALF_UP));
        record.setQuantity(req.quantity().setScale(2, RoundingMode.HALF_UP));
        // 金额一律服务端算，防止前端传入对不上的数
        record.setAmount(record.getUnitPrice().multiply(record.getQuantity()).setScale(2, RoundingMode.HALF_UP));
        LocalDate occurred = req.occurredDate() == null ? LocalDate.now() : req.occurredDate();
        record.setOccurredDate(occurred);
        record.setStartDate("CONTINUOUS".equals(kind)
                ? (req.startDate() == null ? occurred : req.startDate()) : null);
        record.setEndDate("CONTINUOUS".equals(kind) ? req.endDate() : null);
        record.setBerth(berth);
        record.setVessel(vessel);
        // 新增费用的责任船主 = 当前船主（历史归属绝不靠此字段回溯，只作新增即归当前的快照）
        if (vessel != null) {
            periodRepository.findFirstByVesselIdAndEndAtIsNullOrderByStartAtDesc(vessel.getId())
                    .map(VesselOwnerPeriod::getOwner).ifPresent(record::setOwner);
        }
        record.setSettleStatus(settleStatus);
        record.setDebtNote(trimTo(req.debtNote(), 256));
        record.setInternalRemark(trimTo(req.internalRemark(), 256));
        record.setUpdatedAt(LocalDateTime.now());
        ServiceRecord saved = serviceRepository.save(record);
        return toView(saved, attributionService.resolveService(saved));
    }

    /** 结算状态变更：已结 / 挂账 / 退回未结 */
    @Transactional
    public Views.ServiceRecordView settle(Long id, ServiceRecordDtos.SettleReq req) {
        SecurityRules.requireStaff("变更结算状态");
        ServiceRecord record = serviceRecordById(id);
        String status = req.settleStatus() == null ? "" : req.settleStatus().trim().toUpperCase();
        if (!MarinaDict.isValidSettleStatus(status)) {
            throw new BizException("结算状态只能是 UNSETTLED / SETTLED / ON_ACCOUNT，收到的是：" + req.settleStatus());
        }
        record.setSettleStatus(status);
        record.setUpdatedAt(LocalDateTime.now());
        return toView(serviceRepository.save(record));
    }

    private ServiceRecord serviceRecordById(Long id) {
        return serviceRepository.findById(id)
                .orElseThrow(() -> new BizException("服务记录不存在：id=" + id));
    }

    /** 月度费用汇总：总额 / 未结 / 挂账 + 按月 + 按类型（船主端只汇总自己可见的费用） */
    @Transactional(readOnly = true)
    public Views.ServiceSummary summary() {
        List<ServiceRecord> all = serviceRepository.findAllByOrderByOccurredDateDescIdDesc();
        java.util.Set<Long> visibleVessels = attributionService.visibleVesselIds();
        BigDecimal total = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        BigDecimal unsettled = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        BigDecimal onAccount = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        // TreeMap 反向排序：月份从新到旧
        Map<String, MonthAgg> monthMap = new TreeMap<>(Comparator.reverseOrder());
        Map<String, TypeAgg> typeMap = new LinkedHashMap<>();
        long count = 0;
        for (ServiceRecord item : all) {
            AttributionService.Liability l = attributionService.resolveService(item);
            Long vid = item.getVessel() == null ? null : item.getVessel().getId();
            if (!attributionService.canSeeFact(l, vid,
                    item.getOccurredDate() == null ? null : item.getOccurredDate().atStartOfDay())) {
                continue;
            }
            if (vid == null && visibleVessels != null) {
                continue;
            }
            if (visibleVessels != null && vid != null && !visibleVessels.contains(vid)) {
                continue;
            }
            count++;
            // 船主视角：拆账项只计本人那段；员工视角：计全额
            BigDecimal rawAmount = item.getAmount() == null ? BigDecimal.ZERO : item.getAmount();
            BigDecimal amount = rawAmount;
            if (!com.marina.berth.security.ActorContext.isStaff() && "SPLIT".equals(l.party())) {
                Long me = com.marina.berth.security.ActorContext.ownerId();
                if (me != null && me.equals(l.oldOwnerId())) {
                    amount = l.amountOld() == null ? BigDecimal.ZERO : l.amountOld();
                } else if (me != null && me.equals(l.newOwnerId())) {
                    amount = l.amountNew() == null ? BigDecimal.ZERO : l.amountNew();
                }
            }
            total = total.add(amount);
            if ("UNSETTLED".equals(item.getSettleStatus())) {
                unsettled = unsettled.add(amount);
            }
            if ("ON_ACCOUNT".equals(item.getSettleStatus())) {
                onAccount = onAccount.add(amount);
            }
            String month = item.getOccurredDate() == null
                    ? "-"
                    : YearMonth.from(item.getOccurredDate()).toString();
            MonthAgg monthAgg = monthMap.computeIfAbsent(month, key -> new MonthAgg());
            monthAgg.count++;
            monthAgg.amount = monthAgg.amount.add(amount);
            if ("SETTLED".equals(item.getSettleStatus())) {
                monthAgg.settled = monthAgg.settled.add(amount);
            }
            TypeAgg typeAgg = typeMap.computeIfAbsent(item.getServiceType(), key -> new TypeAgg());
            typeAgg.count++;
            typeAgg.amount = typeAgg.amount.add(amount);
        }
        List<Views.MonthSum> byMonth = new ArrayList<>();
        monthMap.forEach((month, agg) -> byMonth.add(new Views.MonthSum(month,
                agg.amount.setScale(2, RoundingMode.HALF_UP), agg.count,
                agg.settled.setScale(2, RoundingMode.HALF_UP))));
        List<Views.TypeSum> byType = new ArrayList<>();
        List<String> order = new ArrayList<>(MarinaDict.SERVICE_TYPE_ORDER);
        typeMap.keySet().stream()
                .sorted(Comparator.comparingInt(key -> order.indexOf(key) < 0 ? 99 : order.indexOf(key)))
                .forEach(key -> byType.add(new Views.TypeSum(key, MarinaDict.serviceTypeName(key),
                        typeMap.get(key).amount.setScale(2, RoundingMode.HALF_UP), typeMap.get(key).count)));
        return new Views.ServiceSummary(total.setScale(2, RoundingMode.HALF_UP),
                unsettled.setScale(2, RoundingMode.HALF_UP), onAccount.setScale(2, RoundingMode.HALF_UP),
                count, byMonth, byType);
    }

    public Views.ServiceRecordView toView(ServiceRecord item) {
        return toView(item, attributionService.resolveService(item));
    }

    public Views.ServiceRecordView toView(ServiceRecord item, AttributionService.Liability l) {
        Berth berth = item.getBerth();
        Vessel vessel = item.getVessel();
        // 欠费说明 / 内部备注仅员工可见（任何船主，包括本人，都不可见）
        String[] internal = attributionService.maskServiceInternal(item);
        String debtNote = internal[0];
        String internalRemark = internal[1];
        // 责任船主名：优先 owner 快照，其次台账/时间轴解析出的船主
        Long ownerId = attributionService.serviceOwnerId(item);
        String ownerName = null;
        if (ownerId != null) {
            com.marina.berth.domain.OwnerProfile owner = attributionService.ownerById(ownerId);
            ownerName = owner == null ? null : owner.getOwnerName();
        }
        String party = l == null ? null : l.party();
        String attrStatus = l == null ? null : l.status();
        if (attrStatus == null && l != null && l.ownerId() != null) {
            attrStatus = "DONE";
        }
        boolean splitSegment = l != null && l.splitSegment();
        // 当前视角应承担金额：员工看全额；船主在拆账项上只看自己那段
        BigDecimal liableAmount = item.getAmount();
        if (!com.marina.berth.security.ActorContext.isStaff() && l != null && "SPLIT".equals(l.party())) {
            Long me = com.marina.berth.security.ActorContext.ownerId();
            if (me != null && me.equals(l.oldOwnerId())) {
                liableAmount = l.amountOld();
            } else if (me != null && me.equals(l.newOwnerId())) {
                liableAmount = l.amountNew();
            }
        }
        return new Views.ServiceRecordView(
                item.getId(),
                item.getServiceType(),
                MarinaDict.serviceTypeName(item.getServiceType()),
                item.getServiceKind() == null ? "POINT" : item.getServiceKind(),
                MarinaDict.serviceKindName(item.getServiceKind() == null ? "POINT" : item.getServiceKind()),
                MarinaDict.serviceUnit(item.getServiceType()),
                item.getUnitPrice(),
                item.getQuantity(),
                item.getAmount(),
                item.getOccurredDate(),
                item.getStartDate(),
                item.getEndDate(),
                item.getOccurredDate() == null ? null : YearMonth.from(item.getOccurredDate()).toString(),
                berth == null ? null : berth.getId(),
                berth == null ? null : berth.getBerthNo(),
                vessel == null ? null : vessel.getId(),
                vessel == null ? null : vessel.getName(),
                ownerId,
                ownerName,
                item.getSettleStatus(),
                MarinaDict.settleStatusName(item.getSettleStatus()),
                party,
                MarinaDict.partyName(party),
                attrStatus,
                l == null ? null : l.basis(),
                liableAmount,
                debtNote,
                internalRemark,
                item.getLegacyNote(),
                splitSegment,
                l == null ? null : l.splitFromId(),
                item.getUpdatedAt());
    }

    /** ownerId 即 owner_profile 主键，责任船主名由 attributionService.ownerById 取名录。 */

    private String trimTo(String s, int max) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        if (t.isEmpty()) {
            return null;
        }
        return t.length() > max ? t.substring(0, max) : t;
    }

    private static final class MonthAgg {
        private BigDecimal amount = BigDecimal.ZERO;
        private BigDecimal settled = BigDecimal.ZERO;
        private long count;
    }

    private static final class TypeAgg {
        private BigDecimal amount = BigDecimal.ZERO;
        private long count;
    }
}
