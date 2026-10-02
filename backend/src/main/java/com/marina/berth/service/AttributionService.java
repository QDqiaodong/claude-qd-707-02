package com.marina.berth.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.marina.berth.domain.Berthing;
import com.marina.berth.domain.FactAttribution;
import com.marina.berth.domain.OwnerProfile;
import com.marina.berth.domain.SailingPlan;
import com.marina.berth.domain.ServiceRecord;
import com.marina.berth.domain.Vessel;
import com.marina.berth.domain.VesselOwnerPeriod;
import com.marina.berth.repository.FactAttributionRepository;
import com.marina.berth.repository.OwnerProfileRepository;
import com.marina.berth.repository.VesselOwnerPeriodRepository;
import com.marina.berth.security.ActorContext;

/**
 * 归属解析与可见边界（读侧统一入口）。
 *
 * <p>三个职责：
 * <ol>
 *   <li><b>责任解析</b>：一条业务事实（靠泊/出航/服务）归谁 —— 先看 fact_attribution 台账
 *       （交割定责/拆账/历史补判的显式覆盖），没有台账再用 vessel_owner_period 时间轴按
 *       「事实发生时刻的船主」还原。绝不直接拿 vessel.owner_name 当前值当历史归属。</li>
 *   <li><b>可见过滤</b>：船主端只能看到自己持有期间的船只/事实；归属待判项对船主隐藏。</li>
 *   <li><b>字段脱敏</b>：非本人的船主电话/证件/备注、服务欠费说明/内部备注一律置空。</li>
 * </ol>
 */
@Service
public class AttributionService {

    public static final String TYPE_BERTHING = "BERTHING";
    public static final String TYPE_SAILING = "SAILING";
    public static final String TYPE_SERVICE = "SERVICE";

    private final FactAttributionRepository attrRepository;
    private final VesselOwnerPeriodRepository periodRepository;
    private final OwnerProfileRepository ownerRepository;

    public AttributionService(FactAttributionRepository attrRepository,
                              VesselOwnerPeriodRepository periodRepository,
                              OwnerProfileRepository ownerRepository) {
        this.attrRepository = attrRepository;
        this.periodRepository = periodRepository;
        this.ownerRepository = ownerRepository;
    }

    /** 一条事实解析出的归属结果。 */
    public record Liability(
            String party,                 // OLD/NEW/SPLIT/PENDING/null（无据）
            String status,                // DONE/PENDING/null（无台账）
            String basis,
            Long ownerId,                 // 归责船主（SPLIT 时为 null，见 old/newOwnerId 与金额）
            Long oldOwnerId,              // SPLIT/台账中的旧船主
            Long newOwnerId,              // SPLIT/台账中的新船主
            BigDecimal amountOld,
            BigDecimal amountNew,
            boolean splitSegment,         // 本条事实是否为拆出的新段
            Long splitFromId) {
    }

    // ================================================================ 归属解析

    /**
     * 解析某事实归属。{@code factType/factId} 定位台账；{@code vesselId + at} 用于时间轴兜底。
     * 台账可能有多条（拆账原段+新段、改判链），这里取该 factId 当前生效（非 SUPERSEDED）的行集合。
     */
    @Transactional(readOnly = true)
    public Liability resolve(String factType, Long factId, Long vesselId, LocalDateTime at) {
        List<FactAttribution> rows = attrRepository.findAllByFactTypeAndFactIdOrderByIdAsc(factType, factId);
        FactAttribution live = null;
        boolean splitSegment = false;
        for (FactAttribution row : rows) {
            if ("SUPERSEDED".equals(row.getStatus())) {
                continue;
            }
            if (row.getSplitFromId() != null && row.getSplitFromId().equals(factId)) {
                splitSegment = true;
            }
            // 普通「按时间自动判定」行与持有区间时间轴同义（且多次过户会重复落行），
            // 责任一律以时间轴为准；只有拆账段、人工定责、历史补判、待判这些“特殊”行才覆盖时间轴。
            if (isSpecial(row)) {
                live = row;
            }
        }
        if (live != null) {
            Long oldId = live.getTransfer() == null ? null : live.getTransfer().getOldOwner().getId();
            Long newId = live.getTransfer() == null ? null : live.getTransfer().getNewOwner().getId();
            return new Liability(live.getParty(), live.getStatus(), live.getBasis(),
                    ownerIdOfParty(live, vesselId), oldId, newId,
                    live.getAmountOld(), live.getAmountNew(),
                    splitSegment, live.getSplitFromId());
        }
        // 无特殊台账：按持有区间时间轴还原「当时船主」；仍查不到则无据（返回 null owner）
        Long ownerId = ownerAt(vesselId, at);
        return new Liability(null, null, null, ownerId, null, null, null, null, false, null);
    }

    /** 是否为会覆盖时间轴的特殊归属行（普通 AUTO_TIME 不算）。 */
    private boolean isSpecial(FactAttribution row) {
        return "PENDING".equals(row.getStatus())
                || row.getSplitFromId() != null
                || (row.getResolution() != null && !"AUTO_TIME".equals(row.getResolution()));
    }

    /** 台账行的归责船主 id：优先行上钉住的 owner；否则 OLD/NEW 由该交割单的双方确定；SPLIT/PENDING 无单一船主。 */
    private Long ownerIdOfParty(FactAttribution row, Long vesselId) {
        if (row.getOwner() != null) {
            return row.getOwner().getId();
        }
        if (row.getTransfer() == null) {
            // 历史补判行无交割单：用事实发生时刻的时间轴船主
            return ownerAt(vesselId, row.getPeriodStart());
        }
        return switch (row.getParty() == null ? "" : row.getParty()) {
            case "OLD" -> row.getTransfer().getOldOwner().getId();
            case "NEW" -> row.getTransfer().getNewOwner().getId();
            default -> null;
        };
    }

    /** 某船在某时刻的船主 id（时间轴）；查不到返回 null。 */
    @Transactional(readOnly = true)
    public Long ownerAt(Long vesselId, LocalDateTime at) {
        if (vesselId == null || at == null) {
            return null;
        }
        List<VesselOwnerPeriod> hit = periodRepository.findOwnerAt(vesselId, at);
        return hit.isEmpty() ? null : hit.get(0).getOwner().getId();
    }

    /** 当前船主 id。 */
    @Transactional(readOnly = true)
    public Long currentOwner(Long vesselId) {
        if (vesselId == null) {
            return null;
        }
        return periodRepository.findFirstByVesselIdAndEndAtIsNullOrderByStartAtDesc(vesselId)
                .map(p -> p.getOwner().getId()).orElse(null);
    }

    // ================================================================ 可见过滤

    /** 当前船主可见的船只 id（持有过/正持有的并集）；员工返回 null 表示不限。 */
    @Transactional(readOnly = true)
    public java.util.Set<Long> visibleVesselIds() {
        if (ActorContext.isStaff()) {
            return null;
        }
        Long ownerId = ActorContext.ownerId();
        if (ownerId == null) {
            return java.util.Set.of();
        }
        return new java.util.HashSet<>(periodRepository.findVesselIdsByOwner(ownerId));
    }

    /** 船主端是否能看这条事实：PENDING 对船主隐藏；其余按归责船主是否为本人。 */
    public boolean canSeeFact(Liability liability, Long vesselId, LocalDateTime at) {
        if (ActorContext.isStaff()) {
            return true;
        }
        Long me = ActorContext.ownerId();
        if (me == null) {
            return false;
        }
        // 待判项一律不对船主开放
        if (liability != null && "PENDING".equals(liability.party())) {
            return false;
        }
        // 拆账：旧/新双方都可看同一条事实（各自只承担自己那段金额）
        if (liability != null && "SPLIT".equals(liability.party())) {
            return me.equals(liability.oldOwnerId()) || me.equals(liability.newOwnerId());
        }
        // 台账显式归责到某船主
        if (liability != null && liability.ownerId() != null) {
            return me.equals(liability.ownerId());
        }
        // 有显式台账（DONE）但无法解析到具体船主（无交割单的历史补判等）：
        // 船主端不予展示，仅码头运营人员可见，避免错投到时间轴上的某个船主
        if (liability != null && "DONE".equals(liability.status())) {
            return false;
        }
        // 无显式台账：用持有关系兜底（自己持有期间可见）
        Long ownerId = liability != null && liability.ownerId() != null
                ? liability.ownerId() : ownerAt(vesselId, at);
        return me.equals(ownerId);
    }

    // ================================================================ 脱敏

    /** 船主联系资料脱敏：员工看全量；船主只看本人。 */
    public OwnerViewMask maskOwner(OwnerProfile owner) {
        if (owner == null) {
            return new OwnerViewMask(null, null, null);
        }
        boolean self = !ActorContext.isStaff()
                && ActorContext.ownerId() != null
                && ActorContext.ownerId().equals(owner.getId());
        if (ActorContext.isStaff() || self) {
            return new OwnerViewMask(owner.getPhone(), owner.getIdCard(), owner.getRemark());
        }
        return new OwnerViewMask(null, null, null);
    }

    public record OwnerViewMask(String phone, String idCard, String remark) {
    }

    /** 服务记录的欠费说明/内部备注：仅员工可见（任何船主都不可见，含本人——属码头内部信息）。 */
    public String[] maskServiceInternal(ServiceRecord record) {
        if (ActorContext.isStaff()) {
            return new String[] {record.getDebtNote(), record.getInternalRemark()};
        }
        return new String[] {null, null};
    }

    // ================================================================ 便捷装配（供四个列表页统一调用）

    /** 预载一批船的当前船主，避免列表里逐条查。 */
    @Transactional(readOnly = true)
    public Map<Long, OwnerProfile> loadCurrentOwners(List<Vessel> vessels) {
        Map<Long, OwnerProfile> map = new HashMap<>();
        for (Vessel v : vessels) {
            periodRepository.findFirstByVesselIdAndEndAtIsNullOrderByStartAtDesc(v.getId())
                    .ifPresent(p -> map.put(v.getId(), p.getOwner()));
        }
        return map;
    }

    @Transactional(readOnly = true)
    public OwnerProfile ownerById(Long id) {
        return id == null ? null : ownerRepository.findById(id).orElse(null);
    }

    public boolean isHeldByMe(Long currentOwnerId) {
        return !ActorContext.isStaff()
                && ActorContext.ownerId() != null
                && ActorContext.ownerId().equals(currentOwnerId);
    }

    // 供其它 service 构造统一的 party 名称
    public String partyName(String party) {
        return com.marina.berth.domain.MarinaDict.partyName(party);
    }

    // ---- 类型收窄的便捷 resolve 包装 ----
    @Transactional(readOnly = true)
    public Liability resolveBerthing(Berthing b) {
        return resolve(TYPE_BERTHING, b.getId(), b.getVessel() == null ? null : b.getVessel().getId(),
                b.getBerthTime());
    }

    @Transactional(readOnly = true)
    public Liability resolvePlan(SailingPlan p) {
        return resolve(TYPE_SAILING, p.getId(), p.getVessel() == null ? null : p.getVessel().getId(),
                p.getDepartTime());
    }

    @Transactional(readOnly = true)
    public Liability resolveService(ServiceRecord s) {
        // 先取台账（拆账段/人工定责/待判/补判），这些覆盖一切
        List<FactAttribution> rows = attrRepository
                .findAllByFactTypeAndFactIdOrderByIdAsc(TYPE_SERVICE, s.getId());
        FactAttribution special = null;
        for (FactAttribution row : rows) {
            if (!"SUPERSEDED".equals(row.getStatus()) && isSpecial(row)) {
                special = row;
            }
        }
        if (special != null) {
            Long oldId = special.getTransfer() == null ? null : special.getTransfer().getOldOwner().getId();
            Long newId = special.getTransfer() == null ? null : special.getTransfer().getNewOwner().getId();
            Long pinned = special.getOwner() == null
                    ? ownerIdOfParty(special, s.getVessel() == null ? null : s.getVessel().getId())
                    : special.getOwner().getId();
            return new Liability(special.getParty(), special.getStatus(), special.getBasis(),
                    pinned, oldId, newId, special.getAmountOld(), special.getAmountNew(),
                    special.getSplitFromId() != null, special.getSplitFromId());
        }
        // 无特殊台账：责任船主快照优先（新增/补判已钉住，不受同日时点边界影响）；否则按时间轴
        if (s.getOwner() != null) {
            return new Liability(null, "DONE", "责任船主快照", s.getOwner().getId(),
                    null, null, null, null, false, null);
        }
        LocalDateTime at = s.getOccurredDate() == null ? null : s.getOccurredDate().atStartOfDay();
        Long ownerId = ownerAt(s.getVessel() == null ? null : s.getVessel().getId(), at);
        return new Liability(null, null, null, ownerId, null, null, null, null, false, null);
    }

    /** 服务记录责任船主 id：优先 owner_id 快照，其次台账，再次时间轴。 */
    @Transactional(readOnly = true)
    public Long serviceOwnerId(ServiceRecord s) {
        if (s.getOwner() != null) {
            return s.getOwner().getId();
        }
        Liability l = resolveService(s);
        if (l.ownerId() != null) {
            return l.ownerId();
        }
        LocalDate d = s.getStartDate() != null ? s.getStartDate() : s.getOccurredDate();
        return ownerAt(s.getVessel() == null ? null : s.getVessel().getId(),
                d == null ? null : d.atStartOfDay());
    }
}
