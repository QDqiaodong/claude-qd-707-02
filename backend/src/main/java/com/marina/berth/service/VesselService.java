package com.marina.berth.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.marina.berth.common.BizException;
import com.marina.berth.domain.Berth;
import com.marina.berth.domain.Berthing;
import com.marina.berth.domain.MarinaDict;
import com.marina.berth.domain.OwnerProfile;
import com.marina.berth.domain.Vessel;
import com.marina.berth.domain.VesselOwnerPeriod;
import com.marina.berth.dto.VesselDtos;
import com.marina.berth.dto.Views;
import com.marina.berth.repository.BerthingRepository;
import com.marina.berth.repository.OwnerProfileRepository;
import com.marina.berth.repository.VesselOwnerPeriodRepository;
import com.marina.berth.repository.VesselRepository;
import com.marina.berth.security.ActorContext;
import com.marina.berth.security.SecurityRules;

/**
 * 模块二：船只台账。
 *
 * <p>本模块的规则面：
 * <ol>
 *   <li>船名、船籍注册号均不可重复</li>
 *   <li>新建船只默认「出海」状态，要变成「在港」必须走泊位上的「安排靠泊」</li>
 *   <li>状态门禁：退租前必须先离泊结算；出海前不能还占着泊位；只有靠泊在港的船能转维修</li>
 *   <li>续保：新的保险到期日必须晚于今天，且不能早于原到期日</li>
 *   <li>列表带出保险剩余天数与预警级别（已逾期 / 30 天内 / 正常）</li>
 * </ol>
 *
 * <p>「当前泊位」{@code currentBerthNo} 来自 {@link Vessel#getCurrentBerth()} ——
 * 实体上的 {@code @JoinFormula} 派生关联，读的是 berthing 表里该船的在泊行。
 */
@Service
public class VesselService {

    private final VesselRepository vesselRepository;
    private final BerthingRepository berthingRepository;
    private final VesselOwnerPeriodRepository periodRepository;
    private final OwnerProfileRepository ownerRepository;
    private final com.marina.berth.repository.FactAttributionRepository attrRepository;
    private final AttributionService attributionService;

    public VesselService(VesselRepository vesselRepository,
                         BerthingRepository berthingRepository,
                         VesselOwnerPeriodRepository periodRepository,
                         OwnerProfileRepository ownerRepository,
                         com.marina.berth.repository.FactAttributionRepository attrRepository,
                         AttributionService attributionService) {
        this.vesselRepository = vesselRepository;
        this.berthingRepository = berthingRepository;
        this.periodRepository = periodRepository;
        this.ownerRepository = ownerRepository;
        this.attrRepository = attrRepository;
        this.attributionService = attributionService;
    }

    @Transactional(readOnly = true)
    public List<Views.VesselView> list() {
        return toViews(filterByVisibility(vesselRepository.findAllByOrderByRegNoAsc()));
    }

    @Transactional(readOnly = true)
    public List<Views.VesselView> listByStatus(String status) {
        return toViews(filterByVisibility(vesselRepository.findAllByStatusOrderByRegNoAsc(status)));
    }

    @Transactional(readOnly = true)
    public List<Views.VesselView> listByType(String typeCode) {
        return toViews(filterByVisibility(vesselRepository.findAllByTypeCodeOrderByRegNoAsc(typeCode)));
    }

    /** 船主端只看自己持有过/正持有的船；员工看全量。 */
    private List<Vessel> filterByVisibility(List<Vessel> source) {
        java.util.Set<Long> visible = attributionService.visibleVesselIds();
        if (visible == null) {
            return source;
        }
        List<Vessel> out = new ArrayList<>();
        for (Vessel v : source) {
            if (visible.contains(v.getId())) {
                out.add(v);
            }
        }
        return out;
    }

    @Transactional
    public Views.VesselView create(VesselDtos.VesselSaveReq req) {
        SecurityRules.requireStaff("登记新船");
        String name = trim(req.name());
        String regNo = trim(req.regNo());
        if (vesselRepository.existsByRegNo(regNo)) {
            throw new BizException("船籍注册号 " + regNo + " 已存在，不能重复登记");
        }
        if (vesselRepository.existsByName(name)) {
            throw new BizException("船名 " + name + " 已存在，不能重复登记");
        }
        String typeCode = trim(req.typeCode()).toUpperCase();
        if (!MarinaDict.isValidVesselType(typeCode)) {
            throw new BizException("船型只能是 SAIL / POWER / FISHING / CATAMARAN / SPEED，收到的是：" + req.typeCode());
        }
        if (req.lengthM() == null || req.lengthM().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException("船长必须大于 0");
        }
        if (req.widthM() == null || req.widthM().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException("船宽必须大于 0");
        }
        if (req.draftM() == null || req.draftM().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException("吃水必须大于 0");
        }
        String status = req.status() == null || req.status().isBlank()
                ? "OUT_SEA"
                : req.status().trim().toUpperCase();
        if (!MarinaDict.isValidVesselStatus(status) || "IN_PORT".equals(status)) {
            throw new BizException("新建船只只能是 OUT_SEA（出海）/ MAINTENANCE（维修）/ TERMINATED（退租），"
                    + "在港请走泊位上的「安排靠泊」");
        }
        LocalDate regDate = req.regDate() == null ? LocalDate.now() : req.regDate();
        LocalDate insuranceDue = req.insuranceDue() == null ? LocalDate.now().plusYears(1) : req.insuranceDue();
        if (!insuranceDue.isAfter(LocalDate.now())) {
            throw new BizException("保险到期日必须晚于今天");
        }

        // 船主名录：同名复用，否则按建档（新建船只的初始船主）
        String ownerName = trim(req.ownerName());
        com.marina.berth.domain.OwnerProfile owner = ownerRepository.findByOwnerName(ownerName).orElse(null);
        if (owner == null) {
            owner = new com.marina.berth.domain.OwnerProfile();
            owner.setOwnerName(ownerName);
            owner.setCreatedAt(LocalDateTime.now());
            owner.setUpdatedAt(LocalDateTime.now());
            owner = ownerRepository.save(owner);
        }

        Vessel vessel = new Vessel();
        vessel.setName(name);
        vessel.setRegNo(regNo);
        vessel.setOwnerName(ownerName);
        vessel.setCurrentOwner(owner);
        vessel.setTypeCode(typeCode);
        vessel.setLengthM(req.lengthM());
        vessel.setWidthM(req.widthM());
        vessel.setDraftM(req.draftM());
        vessel.setRegDate(regDate);
        vessel.setInsuranceDue(insuranceDue);
        vessel.setStatus(status);
        vessel.setUpdatedAt(LocalDateTime.now());
        Vessel saved = vesselRepository.save(vessel);

        // 初始持有区间（append-only 时间轴起点）
        VesselOwnerPeriod p0 = new VesselOwnerPeriod();
        p0.setVessel(saved);
        p0.setOwner(owner);
        p0.setStartAt(regDate.atStartOfDay());
        p0.setEndAt(null);
        p0.setBasis("初始登记");
        p0.setCreatedAt(LocalDateTime.now());
        periodRepository.save(p0);

        return toView(saved, new HashMap<>(), new HashMap<>());
    }

    /** 状态变更：在港 / 出海 / 维修 / 退租 */
    @Transactional
    public Views.VesselView changeStatus(Long vesselId, VesselDtos.VesselStatusReq req) {
        SecurityRules.requireStaff("变更船只状态");
        Vessel vessel = vesselRepository.findById(vesselId)
                .orElseThrow(() -> new BizException("船只不存在：id=" + vesselId));
        String status = req.status() == null ? "" : req.status().trim().toUpperCase();
        if (!MarinaDict.isValidVesselStatus(status)) {
            throw new BizException("船只状态只能是 IN_PORT / OUT_SEA / MAINTENANCE / TERMINATED，收到的是：" + req.status());
        }
        boolean inPort = berthingRepository
                .findFirstByVesselIdAndStatusOrderByBerthTimeDesc(vesselId, "IN_PORT").isPresent();
        if ("TERMINATED".equals(status) && inPort) {
            throw new BizException("船只 " + vessel.getName() + " 还占着泊位，请先办理离泊结算再退租");
        }
        if ("OUT_SEA".equals(status) && inPort) {
            throw new BizException("船只 " + vessel.getName() + " 还在泊，请先办理离泊再出海");
        }
        if ("MAINTENANCE".equals(status) && !inPort && !"IN_PORT".equals(vessel.getStatus())) {
            throw new BizException("只有靠泊在港的船只才能转入维修状态");
        }
        vessel.setStatus(status);
        vessel.setUpdatedAt(LocalDateTime.now());
        Vessel saved = vesselRepository.save(vessel);
        return toView(saved, new HashMap<>(), new HashMap<>());
    }

    /** 续保：登记新的保险到期日 */
    @Transactional
    public Views.VesselView renewInsurance(Long vesselId, VesselDtos.InsuranceReq req) {
        SecurityRules.requireStaff("办理续保");
        Vessel vessel = vesselRepository.findById(vesselId)
                .orElseThrow(() -> new BizException("船只不存在：id=" + vesselId));
        LocalDate due = req.insuranceDue();
        if (due == null || !due.isAfter(LocalDate.now())) {
            throw new BizException("新的保险到期日必须晚于今天");
        }
        if (due.isBefore(vessel.getInsuranceDue())) {
            throw new BizException("续保到期日不能早于原到期日 " + vessel.getInsuranceDue());
        }
        vessel.setInsuranceDue(due);
        vessel.setUpdatedAt(LocalDateTime.now());
        Vessel saved = vesselRepository.save(vessel);
        return toView(saved, new HashMap<>(), new HashMap<>());
    }

    @Transactional(readOnly = true)
    public Views.VesselView detail(Long vesselId) {
        Vessel vessel = vesselRepository.findById(vesselId)
                .orElseThrow(() -> new BizException("船只不存在：id=" + vesselId));
        java.util.Set<Long> visible = attributionService.visibleVesselIds();
        if (visible != null && !visible.contains(vesselId)) {
            throw new BizException("该船只不在您持有期间，无权查看");
        }
        return toView(vessel, new HashMap<>(), new HashMap<>());
    }

    private List<Views.VesselView> toViews(List<Vessel> vessels) {
        List<Berthing> all = berthingRepository.findAllByOrderByBerthTimeDesc();
        Map<Long, Integer> countMap = new HashMap<>();
        Map<Long, BigDecimal> feeSum = new HashMap<>();
        for (Berthing item : all) {
            if (item.getVessel() == null) {
                continue;
            }
            Long vesselId = item.getVessel().getId();
            countMap.put(vesselId, countMap.getOrDefault(vesselId, 0) + 1);
            if (item.getFee() != null) {
                feeSum.put(vesselId, feeSum.getOrDefault(vesselId, BigDecimal.ZERO).add(item.getFee()));
            }
        }
        List<Views.VesselView> list = new ArrayList<>();
        for (Vessel vessel : vessels) {
            list.add(toView(vessel, countMap, feeSum));
        }
        return list;
    }

    public Views.VesselView toView(Vessel vessel, Map<Long, Integer> berthingCount, Map<Long, BigDecimal> feeSum) {
        Berth berth = vessel.getCurrentBerth();
        LocalDate today = LocalDate.now();
        long days = ChronoUnit.DAYS.between(today, vessel.getInsuranceDue());
        String level = days < 0 ? "EXPIRED" : (days <= MarinaDict.INSURANCE_WARN_DAYS ? "WARN" : "OK");
        BigDecimal fee = feeSum == null ? null : feeSum.get(vessel.getId());
        // 当前船主与联系资料脱敏：员工全量；旧船主看自己曾持有船时只回显姓名，不回显新船主电话/备注
        OwnerProfile owner = periodRepository
                .findFirstByVesselIdAndEndAtIsNullOrderByStartAtDesc(vessel.getId())
                .map(VesselOwnerPeriod::getOwner).orElse(vessel.getCurrentOwner());
        Long ownerId = owner == null ? null : owner.getId();
        AttributionService.OwnerViewMask mask = attributionService.maskOwner(owner);
        boolean heldByMe = attributionService.isHeldByMe(ownerId);
        String liabilityHint = buildLiabilityHint(vessel.getId());
        return new Views.VesselView(
                vessel.getId(),
                vessel.getName(),
                vessel.getRegNo(),
                ownerId,
                vessel.getOwnerName(),
                mask.phone(),
                mask.idCard(),
                mask.remark(),
                heldByMe,
                vessel.getTypeCode(),
                MarinaDict.vesselTypeName(vessel.getTypeCode()),
                vessel.getLengthM(),
                vessel.getWidthM(),
                vessel.getDraftM(),
                vessel.getRegDate(),
                vessel.getInsuranceDue(),
                days,
                level,
                vessel.getStatus(),
                MarinaDict.vesselStatusName(vessel.getStatus()),
                berth == null ? null : berth.getId(),
                berth == null ? null : berth.getBerthNo(),
                berthingCount == null ? 0 : berthingCount.getOrDefault(vessel.getId(), 0),
                fee == null ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP) : fee,
                liabilityHint,
                vessel.getUpdatedAt());
    }

    /** 该船是否存在归属待判项（仅员工视图提示，船主端不暴露待判存在性）。 */
    private String buildLiabilityHint(Long vesselId) {
        if (!ActorContext.isStaff()) {
            return null;
        }
        long pending = attrRepository.findPendingByVessel(vesselId).size();
        return pending > 0 ? ("有 " + pending + " 项归属待判，需补充依据后定责") : null;
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
