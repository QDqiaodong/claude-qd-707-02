package com.marina.berth.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
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
import com.marina.berth.domain.FactAttribution;
import com.marina.berth.domain.MarinaDict;
import com.marina.berth.domain.OwnershipTransfer;
import com.marina.berth.domain.OwnerProfile;
import com.marina.berth.domain.SailingPlan;
import com.marina.berth.domain.ServiceRecord;
import com.marina.berth.domain.TransferCorrection;
import com.marina.berth.domain.Vessel;
import com.marina.berth.domain.VesselOwnerPeriod;
import com.marina.berth.dto.TransferDtos;
import com.marina.berth.dto.Views;
import com.marina.berth.repository.BerthRepository;
import com.marina.berth.repository.BerthingRepository;
import com.marina.berth.repository.FactAttributionRepository;
import com.marina.berth.repository.OwnerProfileRepository;
import com.marina.berth.repository.OwnershipTransferRepository;
import com.marina.berth.repository.SailingPlanRepository;
import com.marina.berth.repository.ServiceRecordRepository;
import com.marina.berth.repository.TransferCorrectionRepository;
import com.marina.berth.repository.VesselOwnerPeriodRepository;
import com.marina.berth.repository.VesselRepository;
import com.marina.berth.security.ActorContext;
import com.marina.berth.security.SecurityRules;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * 模块八：船只过户交割。
 *
 * <p>核心原则：过户不是改一个名字，而是围绕「生效时点」给每一项靠泊 / 出航 / 服务事实定责。
 * <ol>
 *   <li><b>预览</b>：逐项给出系统建议（旧/新/拆账/待判）。生效前已完成 → 旧船主；生效后 → 新船主；
 *       跨时点在泊 → 按日租金 × 实际停泊区间自动拆两段（原记录保留，另立新段）；
 *       区间完整的持续服务跨时点 → 按天占比自动拆；在海未返申报、仍在续持续服务这类
 *       无法自动拆分的 → 必须工作人员选承担方并写理由；历史缺据 → 归属待判，可现场补判。</li>
 *   <li><b>提交</b>：单事务完成 在泊拆段/费用归属台账/持有区间收口新开/船主变更/联系人移交。
 *       交割单、台账、更正全部 append-only，不删除任何原记录。</li>
 *   <li><b>可见边界</b>由 {@link AttributionService} 在读侧统一执行。</li>
 * </ol>
 */
@Service
public class TransferService {

    private static final DateTimeFormatter NO_SUFFIX = DateTimeFormatter.ofPattern("yyyyMM");

    private final OwnershipTransferRepository transferRepository;
    private final TransferCorrectionRepository correctionRepository;
    private final FactAttributionRepository attrRepository;
    private final VesselOwnerPeriodRepository periodRepository;
    private final OwnerProfileRepository ownerRepository;
    private final VesselRepository vesselRepository;
    private final BerthingRepository berthingRepository;
    private final SailingPlanRepository planRepository;
    private final ServiceRecordRepository serviceRepository;
    private final BerthRepository berthRepository;
    private final AttributionService attributionService;

    @PersistenceContext
    private EntityManager entityManager;

    public TransferService(OwnershipTransferRepository transferRepository,
                           TransferCorrectionRepository correctionRepository,
                           FactAttributionRepository attrRepository,
                           VesselOwnerPeriodRepository periodRepository,
                           OwnerProfileRepository ownerRepository,
                           VesselRepository vesselRepository,
                           BerthingRepository berthingRepository,
                           SailingPlanRepository planRepository,
                           ServiceRecordRepository serviceRepository,
                           BerthRepository berthRepository,
                           AttributionService attributionService) {
        this.transferRepository = transferRepository;
        this.correctionRepository = correctionRepository;
        this.attrRepository = attrRepository;
        this.periodRepository = periodRepository;
        this.ownerRepository = ownerRepository;
        this.vesselRepository = vesselRepository;
        this.berthingRepository = berthingRepository;
        this.planRepository = planRepository;
        this.serviceRepository = serviceRepository;
        this.berthRepository = berthRepository;
        this.attributionService = attributionService;
    }

    // ================================================================ 船主名录

    @Transactional(readOnly = true)
    public List<Views.OwnerView> listOwners() {
        List<Views.OwnerView> list = new ArrayList<>();
        for (OwnerProfile o : ownerRepository.findAllByOrderByOwnerNameAsc()) {
            AttributionService.OwnerViewMask mask = attributionService.maskOwner(o);
            list.add(new Views.OwnerView(o.getId(), o.getOwnerName(), mask.phone(), mask.idCard(),
                    mask.remark(), o.getCreatedAt()));
        }
        return list;
    }

    @Transactional
    public Views.OwnerView createOwner(TransferDtos.OwnerSaveReq req) {
        SecurityRules.requireStaff("登记新船主");
        String name = trim(req.ownerName());
        if (name.isEmpty()) {
            throw new BizException("船主姓名不能为空");
        }
        if (ownerRepository.existsByOwnerName(name)) {
            throw new BizException("已存在同名船主：" + name + "，请直接选择已有名录");
        }
        OwnerProfile o = new OwnerProfile();
        o.setOwnerName(name);
        o.setPhone(trimTo(req.phone(), 20));
        o.setIdCard(trimTo(req.idCard(), 32));
        o.setRemark(trimTo(req.remark(), 256));
        o.setCreatedAt(LocalDateTime.now());
        o.setUpdatedAt(LocalDateTime.now());
        o = ownerRepository.save(o);
        AttributionService.OwnerViewMask mask = attributionService.maskOwner(o);
        return new Views.OwnerView(o.getId(), o.getOwnerName(), mask.phone(), mask.idCard(),
                mask.remark(), o.getCreatedAt());
    }

    // ================================================================ 预览

    /** 交割责任预览：不改任何数据，逐项返回系统建议。 */
    @Transactional(readOnly = true)
    public Views.TransferPreview preview(Long vesselId, LocalDateTime effectiveAt) {
        SecurityRules.requireStaff("办理船只过户");
        Vessel vessel = vesselRepository.findById(vesselId)
                .orElseThrow(() -> new BizException("船只不存在：id=" + vesselId));
        if (effectiveAt == null) {
            throw new BizException("交割生效时点不能为空");
        }
        OwnerProfile oldOwner = currentOwner(vessel);
        List<Views.PreviewItemView> items = new ArrayList<>();

        // ① 靠泊
        for (Berthing b : berthingRepository.findAllByVesselIdOrderByBerthTimeDesc(vesselId)) {
            items.add(previewBerthing(b, effectiveAt));
        }
        // ② 出航申报
        for (SailingPlan p : planRepository.findAllByVesselIdOrderByDepartTimeDesc(vesselId)) {
            items.add(previewPlan(p, effectiveAt));
        }
        // ③ 服务
        for (ServiceRecord s : serviceRepository.findAllByVesselIdOrderByOccurredDateDesc(vesselId)) {
            items.add(previewService(s, effectiveAt));
        }

        long auto = items.stream().filter(i -> Boolean.TRUE.equals(i.auto())).count();
        long manual = items.stream().filter(i -> Boolean.FALSE.equals(i.auto()) && !Boolean.TRUE.equals(i.pending())).count();
        long pending = items.stream().filter(i -> Boolean.TRUE.equals(i.pending())).count();
        BigDecimal[] sums = sumSuggested(items);
        return new Views.TransferPreview(vessel.getId(), vessel.getName(), vessel.getRegNo(),
                oldOwner == null ? null : oldOwner.getOwnerName(), oldOwner == null ? null : oldOwner.getId(),
                effectiveAt, items, auto, manual, pending, sums[0], sums[1]);
    }

    private Views.PreviewItemView previewBerthing(Berthing b, LocalDateTime eff) {
        String key = "B-" + b.getId();
        Berth berth = b.getBerth();
        BigDecimal amount = b.getFee() == null ? null : b.getFee();
        LocalDateTime start = b.getBerthTime();
        LocalDateTime end = b.getActualDepart();
        boolean inPort = "IN_PORT".equals(b.getStatus());

        if (inPort && start != null && !start.isAfter(eff)) {
            // 跨时点在泊：按日租金拆「生效前(旧)/生效后(新)」两段，系统可自动拆
            Berth bh = berth;
            BigDecimal[] split = splitBerthingFee(bh, start, eff, end);
            String basis = "在泊记录 " + b.getRecordNo() + " 跨越生效时点 " + fmt(eff)
                    + "，按泊位日租金 ¥" + bh.getDailyRate() + " 拆分实际停泊区间："
                    + fmt(start) + "～" + fmt(eff) + " 归旧船主，" + fmt(eff) + " 起归新船主";
            return item(key, AttributionService.TYPE_BERTHING, b.getId(), b.getRecordNo(),
                    "靠泊 " + (berth == null ? "" : berth.getBerthNo()), start, end,
                    null, null, b.getStatus(), MarinaDict.berthingStatusName(b.getStatus()), null,
                    "CROSSES", "SPLIT", "AUTO_SPLIT_BERTHING", basis, split[0], split[1],
                    true, false, null, null, null, null);
        }
        // 已结算或在泊但未跨时点：按责任时间（靠泊时间；离泊则以离泊完成时间为准）
        LocalDateTime decisive = end != null ? end : start;
        boolean old = decisive == null || !decisive.isAfter(eff);
        String basis = old
                ? "靠泊记录 " + b.getRecordNo() + " 在生效时点前已完成（" + fmt(decisive) + "），保留原归属"
                : "靠泊记录 " + b.getRecordNo() + " 发生在生效时点之后（" + fmt(decisive) + "），归新船主";
        return item(key, AttributionService.TYPE_BERTHING, b.getId(), b.getRecordNo(),
                "靠泊 " + (berth == null ? "" : berth.getBerthNo()), start, end,
                null, null, b.getStatus(), MarinaDict.berthingStatusName(b.getStatus()), amount,
                old ? "BEFORE" : "AFTER", old ? "OLD" : "NEW", "AUTO_TIME", basis,
                old ? nz(amount) : null, old ? null : nz(amount),
                true, false, null, null, null, null);
    }

    private Views.PreviewItemView previewPlan(SailingPlan p, LocalDateTime eff) {
        String key = "P-" + p.getId();
        LocalDateTime depart = p.getDepartTime();
        LocalDateTime ret = p.getEtaReturn();
        String status = p.getStatus();
        boolean atSeaOpen = ("APPROVED".equals(status) || "SUSPENDED".equals(status));
        boolean pending = "PENDING".equals(status);

        // 待审批：尚未成为出航事实，生效后审批/执行 → 联系人与运营责任一并转新船主
        if (pending) {
            String basis = "申报 " + p.getPlanNo() + " 尚待审批、未形成出航事实，生效后由新船主接续处理";
            return planItem(p, key, depart, ret, "AFTER", "NEW", "AUTO_TIME", basis, null, null,
                    true, false);
        }
        // 已批准（在海/未返）跨越生效时点：无法按区间拆运营责任，必须人工选承担方并写理由
        if (atSeaOpen && depart != null && !depart.isAfter(eff)) {
            String basis = "申报 " + p.getPlanNo() + " 在生效时点仍在海/未返（出航 " + fmt(depart)
                    + "，预计返 " + fmt(ret) + "），运营责任无法按区间自动拆分，"
                    + "请选择承担方并说明理由；转新船主时联系人电话须由新船主更新";
            return planItem(p, key, depart, ret, "CROSSES", null, "MANUAL", basis, null, null,
                    false, false);
        }
        // 已返航 / 已取消：以完成时间判定（返航无单独完成列，用预计返回与当前状态，取 depart/eta 较早落定者不适用——
        // 结单的申报以出航事实所属航次判定：etaReturn 已过即视为生效前事实）
        LocalDateTime decisive = ret;
        boolean old = decisive == null || !decisive.isAfter(eff);
        String basis = old
                ? "申报 " + p.getPlanNo() + " 航次在生效时点前已结束（预计返 " + fmt(ret) + "），保留原归属"
                : "申报 " + p.getPlanNo() + " 航次在生效时点之后（" + fmt(decisive) + "），归新船主";
        return planItem(p, key, depart, ret, old ? "BEFORE" : "AFTER", old ? "OLD" : "NEW",
                "AUTO_TIME", basis, null, null, true, false);
    }

    private Views.PreviewItemView previewService(ServiceRecord s, LocalDateTime eff) {
        String key = "S-" + s.getId();
        boolean continuous = "CONTINUOUS".equals(s.getServiceKind());
        BigDecimal amount = s.getAmount();

        // 历史缺据：持续服务但无起止区间 → 归属待判，保留原记录，禁止按当前船主定责
        if (continuous && s.getStartDate() == null && s.getEndDate() == null) {
            return serviceItem(s, key, null, null, "UNKNOWN", null, "LEGACY_RECONCILED",
                    s.getLegacyNote() != null ? s.getLegacyNote() : "历史数据缺少服务起止区间，归属待判",
                    null, null, false, true,
                    "历史数据缺少服务起止区间，需工作人员补充依据后定责");
        }

        LocalDateTime start = s.getStartDate() != null ? s.getStartDate().atStartOfDay()
                : s.getOccurredDate().atStartOfDay();
        LocalDateTime end = s.getEndDate() != null ? s.getEndDate().atStartOfDay().plusDays(1) : null;
        boolean ongoing = end == null; // 仍在续

        if (continuous) {
            if (ongoing) {
                if (start.isAfter(eff)) {
                    // 生效后才开始的在续服务 → 新船主
                    String basis = "持续服务自 " + fmt(start) + " 起，晚于生效时点，归新船主";
                    return serviceItem(s, key, start, null, "AFTER", "NEW", "AUTO_TIME", basis,
                            null, nz(amount), true, false, null);
                }
                // 跨时点仍在续：后续费用随新船主，但已发生部分如何承担无法自动拆（无结算终点）
                // → 必须人工：旧/新承担，或按生效日已发生天数人工拆账
                String basis = "持续服务 " + MarinaDict.serviceTypeName(s.getServiceType())
                        + " 自 " + fmt(start) + " 起至生效时点仍在续，无结算终点无法自动拆账，"
                        + "请选择承担方（或按生效日已发生天数人工拆账）并说明理由";
                return serviceItem(s, key, start, null, "CROSSES", null, "MANUAL", basis,
                        null, null, false, false, null);
            }
            // 区间完整：跨时点按天占比自动拆；否则整段归一方
            if (!end.isAfter(eff)) {
                String basis = "持续服务区间 " + fmt(start) + "～" + fmt(end) + " 全部在生效时点前，归旧船主";
                return serviceItem(s, key, start, end, "BEFORE", "OLD", "AUTO_TIME", basis,
                        nz(amount), null, true, false, null);
            }
            if (!start.isBefore(eff)) {
                String basis = "持续服务区间 " + fmt(start) + "～" + fmt(end) + " 全部在生效时点后，归新船主";
                return serviceItem(s, key, start, end, "AFTER", "NEW", "AUTO_TIME", basis,
                        null, nz(amount), true, false, null);
            }
            BigDecimal[] split = splitServiceByDays(amount, s.getStartDate(), s.getEndDate(), eff.toLocalDate());
            String basis = "持续服务区间 " + fmt(start) + "～" + fmt(end) + " 跨生效时点，按自然天占比拆账："
                    + "生效日前 " + MarineDays(s.getStartDate(), eff.toLocalDate()) + " 天归旧船主，之后归新船主";
            return serviceItem(s, key, start, end, "CROSSES", "SPLIT", "AUTO_SPLIT_DAYS", basis,
                    split[0], split[1], true, false, null);
        }

        // 一次性服务：按发生日期相对生效时点判定
        LocalDate d = s.getOccurredDate();
        boolean old = d != null && d.isBefore(eff.toLocalDate());
        String basis = old
                ? "一次性服务发生于 " + d + "（生效日前），归旧船主"
                : "一次性服务发生于 " + d + "（生效日及以后），归新船主";
        return serviceItem(s, key, start, end, old ? "BEFORE" : "AFTER", old ? "OLD" : "NEW",
                "AUTO_TIME", basis, old ? nz(amount) : null, old ? null : nz(amount),
                true, false, null);
    }

    // ================================================================ 提交交割

    @Transactional
    public Views.TransferView submit(TransferDtos.TransferSubmitReq req) {
        SecurityRules.requireStaff("办理船只过户");
        LocalDateTime eff = req.effectiveAt();
        if (eff == null) {
            throw new BizException("交割生效时点不能为空");
        }
        if (eff.isAfter(LocalDateTime.now().plusMinutes(1))) {
            throw new BizException("交割生效时点不能晚于当前时间（过户即时生效；如需预约请另行排班）");
        }
        Vessel vessel = vesselRepository.findByIdForUpdate(req.vesselId())
                .orElseThrow(() -> new BizException("船只不存在：id=" + req.vesselId()));
        OwnerProfile newOwner = ownerRepository.findByIdForUpdate(req.newOwnerId())
                .orElseThrow(() -> new BizException("新船主名录不存在：id=" + req.newOwnerId()));
        OwnerProfile oldOwner = currentOwner(vessel);
        if (oldOwner == null) {
            throw new BizException("船只 " + vessel.getName() + " 缺少当前船主持有区间，无法办理过户");
        }
        if (oldOwner.getId().equals(newOwner.getId())) {
            throw new BizException("新船主与原船主为同一人，无需办理过户");
        }
        String operator = trim(req.operator());
        if (operator.isEmpty()) {
            throw new BizException("办理人不能为空");
        }

        LocalDateTime now = LocalDateTime.now();

        // 先建交割单（拿到 id 作为台账/区间的外键）
        OwnershipTransfer transfer = new OwnershipTransfer();
        transfer.setTransferNo(nextTransferNo());
        transfer.setVessel(vessel);
        transfer.setOldOwner(oldOwner);
        transfer.setNewOwner(newOwner);
        transfer.setEffectiveAt(eff);
        transfer.setNote(trimTo(req.note(), 512));
        transfer.setStatus("DONE");
        transfer.setOperator(operator);
        transfer.setCreatedAt(now);
        transfer.setCompletedAt(now);
        transfer.setUpdatedAt(now);
        transfer = transferRepository.save(transfer);
        attrRepository.flush();

        Map<String, TransferDtos.DecisionReq> decisionMap = new HashMap<>();
        if (req.decisions() != null) {
            for (TransferDtos.DecisionReq d : req.decisions()) {
                decisionMap.put(d.key(), d);
            }
        }

        // 逐项落归属台账
        applyBerthings(vessel, eff, transfer, operator, decisionMap);
        entityManager.flush();
        applyPlans(vessel, eff, transfer, operator, decisionMap);
        entityManager.flush();
        applyServices(vessel, eff, transfer, operator, decisionMap);
        entityManager.flush();

        // 持有区间：旧区间收口到生效时点，新开新船主持有区间（append-only）
        VesselOwnerPeriod open = periodRepository
                .findFirstByVesselIdAndEndAtIsNullOrderByStartAtDesc(vessel.getId())
                .orElseThrow(() -> new BizException("船只缺少当前持有区间"));
        open.setEndAt(eff);
        periodRepository.save(open);
        VesselOwnerPeriod next = new VesselOwnerPeriod();
        next.setVessel(vessel);
        next.setOwner(newOwner);
        next.setStartAt(eff);
        next.setEndAt(null);
        next.setBasis("交割单 " + transfer.getTransferNo());
        next.setTransfer(transfer);
        next.setCreatedAt(now);
        periodRepository.save(next);

        // 船只档案：更新当前船主与姓名快照（仅名字段，绝不动历史事实归属）
        vessel.setCurrentOwner(newOwner);
        vessel.setOwnerName(newOwner.getOwnerName());
        vessel.setUpdatedAt(now);
        vesselRepository.save(vessel);

        return toTransferView(transferRepository.findById(transfer.getId()).orElse(transfer));
    }

    /** 靠泊定责落库；跨时点在泊记录在此拆分（原段归旧，新段归新）。 */
    private void applyBerthings(Vessel vessel, LocalDateTime eff, OwnershipTransfer transfer,
                                String operator, Map<String, TransferDtos.DecisionReq> decisions) {
        LocalDateTime now = LocalDateTime.now();
        for (Berthing b : berthingRepository.findAllByVesselIdOrderByBerthTimeDesc(vessel.getId())) {
            boolean inPort = "IN_PORT".equals(b.getStatus());
            boolean crosses = inPort && b.getBerthTime() != null && !b.getBerthTime().isAfter(eff);
            if (crosses) {
                Berth berth = b.getBerth();
                BigDecimal[] split = splitBerthingFee(berth, b.getBerthTime(), eff, null);
                // ① 原在泊记录结算到生效时点（旧段），原记录保留为旧船主责任事实
                long hours = Duration.between(b.getBerthTime(), eff).toHours();
                int days = (int) Math.max(MarinaDict.MIN_BERTH_DAYS, Math.ceil(hours / 24.0));
                b.setActualDepart(eff);
                b.setDays(days);
                b.setFee(split[0]);
                b.setStatus("SETTLED");
                b.setUpdatedAt(now);
                berthingRepository.save(b);

                // ② 生效时点起另立一条在泊新段（新船主），泊位保持在泊不变
                Berthing seg = new Berthing();
                seg.setRecordNo(nextBerthingNo(eff));
                seg.setBerth(berth);
                seg.setVessel(vessel);
                seg.setBerthTime(eff);
                seg.setEtaDepart(null);
                seg.setActualDepart(null);
                seg.setDays(null);
                seg.setFee(null);
                seg.setStatus("IN_PORT");
                seg.setUpdatedAt(now);
                seg = berthingRepository.save(seg);

                // ③ 两条归属台账（原段 OLD、新段 NEW），记录拆分依据与金额
                saveAttr(transfer, vessel.getId(), AttributionService.TYPE_BERTHING, b.getId(), null,
                        "OLD", "AUTO_SPLIT_BERTHING",
                        "交割 " + transfer.getTransferNo() + " 在泊跨时点拆账：生效前停泊费归旧船主",
                        b.getBerthTime(), eff, split[0], null, "DONE", operator, now);
                saveAttr(transfer, vessel.getId(), AttributionService.TYPE_BERTHING, seg.getId(), b.getId(),
                        "NEW", "AUTO_SPLIT_BERTHING",
                        "交割 " + transfer.getTransferNo() + " 在泊跨时点拆账：生效时点起停泊费归新船主",
                        eff, null, null, null, "DONE", operator, now);
                continue;
            }
            LocalDateTime decisive = b.getActualDepart() != null ? b.getActualDepart() : b.getBerthTime();
            boolean old = decisive == null || !decisive.isAfter(eff);
            saveAttr(transfer, vessel.getId(), AttributionService.TYPE_BERTHING, b.getId(), null,
                    old ? "OLD" : "NEW", "AUTO_TIME",
                    old ? "生效时点前已完成的靠泊，保留原归属" : "生效时点后的靠泊，归新船主",
                    b.getBerthTime(), b.getActualDepart(),
                    old ? nz(b.getFee()) : null, old ? null : nz(b.getFee()),
                    "DONE", operator, now);
        }
    }

    /** 出航申报定责落库；跨时点在海申报按工作人员选择定责，转新则置联系人待更新。 */
    private void applyPlans(Vessel vessel, LocalDateTime eff, OwnershipTransfer transfer,
                            String operator, Map<String, TransferDtos.DecisionReq> decisions) {
        LocalDateTime now = LocalDateTime.now();
        for (SailingPlan p : planRepository.findAllByVesselIdOrderByDepartTimeDesc(vessel.getId())) {
            String key = "P-" + p.getId();
            boolean atSeaOpen = ("APPROVED".equals(p.getStatus()) || "SUSPENDED".equals(p.getStatus()));
            boolean crosses = atSeaOpen && p.getDepartTime() != null && !p.getDepartTime().isAfter(eff);
            if (crosses) {
                TransferDtos.DecisionReq d = decisions.get(key);
                if (d == null || (d.party() == null && !Boolean.TRUE.equals(d.split()))) {
                    throw new BizException("申报 " + p.getPlanNo()
                            + " 在生效时点仍在海/未返，无法自动拆分，请选择承担方并填写理由");
                }
                String reason = trim(d.reason());
                if (reason.isEmpty()) {
                    throw new BizException("申报 " + p.getPlanNo() + " 的跨时点责任必须填写理由");
                }
                String party = "NEW".equalsIgnoreCase(d.party()) ? "NEW" : "OLD";
                saveAttr(transfer, vessel.getId(), AttributionService.TYPE_SAILING, p.getId(), null,
                        party, "MANUAL", reason, p.getDepartTime(), p.getEtaReturn(),
                        null, null, "DONE", operator, now);
                if ("NEW".equals(party)) {
                    // 联系人/运营责任移交新船主：电话置待更新，新船主补录前页面提示
                    p.setContactHandoff(true);
                    p.setUpdatedAt(now);
                    planRepository.save(p);
                }
                continue;
            }
            boolean pending = "PENDING".equals(p.getStatus());
            boolean old;
            if (pending) {
                old = false; // 待审批未形成事实，随新船主
            } else {
                LocalDateTime decisive = p.getEtaReturn();
                old = decisive == null || !decisive.isAfter(eff);
            }
            if (!old) {
                // 联系人/运营责任移交新船主：原船长电话是旧船主资料，标记待新船主更新
                p.setContactHandoff(true);
                p.setUpdatedAt(now);
                planRepository.save(p);
            }
            saveAttr(transfer, vessel.getId(), AttributionService.TYPE_SAILING, p.getId(), null,
                    old ? "OLD" : "NEW", "AUTO_TIME",
                    old ? "航次在生效时点前已结束，保留原归属"
                            : (pending ? "待审批申报未形成出航事实，生效后随新船主接续，联系人待更新"
                                    : "航次在生效时点之后，归新船主"),
                    p.getDepartTime(), p.getEtaReturn(), null, null, "DONE", operator, now);
        }
    }

    /** 服务定责落库；历史缺据项可现场补判或保留 PENDING。 */
    private void applyServices(Vessel vessel, LocalDateTime eff, OwnershipTransfer transfer,
                               String operator, Map<String, TransferDtos.DecisionReq> decisions) {
        LocalDateTime now = LocalDateTime.now();
        OwnerProfile oldOwner = transfer.getOldOwner();
        OwnerProfile newOwner = transfer.getNewOwner();
        for (ServiceRecord s : serviceRepository.findAllByVesselIdOrderByOccurredDateDesc(vessel.getId())) {
            String key = "S-" + s.getId();
            boolean continuous = "CONTINUOUS".equals(s.getServiceKind());
            boolean legacyMissing = continuous && s.getStartDate() == null && s.getEndDate() == null;

            if (legacyMissing) {
                TransferDtos.DecisionReq d = decisions.get(key);
                if (d != null && d.party() != null) {
                    // 现场补判：原记录保留，写 DONE + MANUAL/LEGACY_RECONCILED，依据必填
                    String basis = trim(d.reason());
                    if (basis.isEmpty()) {
                        throw new BizException("历史服务记录（#" + s.getId() + "）补判必须填写判定依据");
                    }
                    String party = "NEW".equalsIgnoreCase(d.party()) ? "NEW" : "OLD";
                    OwnerProfile responsible = "NEW".equals(party) ? newOwner : oldOwner;
                    FactAttribution doneRow = saveAttr(transfer, vessel.getId(), AttributionService.TYPE_SERVICE, s.getId(), null,
                            party, "LEGACY_RECONCILED", basis,
                            null, null, "NEW".equals(party) ? null : nz(s.getAmount()),
                            "NEW".equals(party) ? nz(s.getAmount()) : null,
                            "DONE", operator, now);
                    doneRow.setOwner(responsible);
                    attrRepository.save(doneRow);
                    // 服务责任船主快照随补判落定
                    s.setOwner(responsible);
                    s.setUpdatedAt(now);
                    serviceRepository.save(s);
                    // 旧的 PENDING 行（交割单上的或历史挂账的）统一收口到新 DONE 行
                    supersedePendingTo(AttributionService.TYPE_SERVICE, s.getId(), doneRow.getId(), now, true);
                } else {
                    // 保留原记录，维持归属待判：交割单上挂一条 PENDING（若历史上已有 PENDING，则收口到本条）
                    FactAttribution pendingRow = saveAttr(transfer, vessel.getId(), AttributionService.TYPE_SERVICE, s.getId(), null,
                            "PENDING", "LEGACY_RECONCILED",
                            "历史缺起止区间，交割时未补判，保留待判，禁止按当前船主改写",
                            null, null, null, null, "PENDING", operator, now);
                    supersedePendingTo(AttributionService.TYPE_SERVICE, s.getId(), pendingRow.getId(), now, false);
                }
                continue;
            }

            LocalDate startD = s.getStartDate() != null ? s.getStartDate() : s.getOccurredDate();
            LocalDate endD = s.getEndDate();
            LocalDateTime start = startD.atStartOfDay();
            LocalDateTime end = endD != null ? endD.atStartOfDay().plusDays(1) : null;

            if (continuous && end == null && !start.isAfter(eff)) {
                // 跨时点仍在续：必须人工选择
                TransferDtos.DecisionReq d = decisions.get(key);
                if (d == null) {
                    throw new BizException("持续服务（#" + s.getId() + "）在生效时点仍在续，"
                            + "请选择承担方或人工拆账并填写理由");
                }
                String reason = trim(d.reason());
                if (reason.isEmpty()) {
                    throw new BizException("持续服务（#" + s.getId() + "）的跨时点责任必须填写理由");
                }
                if (Boolean.TRUE.equals(d.split())) {
                    BigDecimal oldAmt = d.amountOld() == null ? BigDecimal.ZERO : d.amountOld();
                    BigDecimal newAmt = d.amountNew() == null
                            ? s.getAmount().subtract(oldAmt) : d.amountNew();
                    // 人工拆账两值可能与原总额有尾差，按工作人员录入为准（留理由）
                    saveAttr(transfer, vessel.getId(), AttributionService.TYPE_SERVICE, s.getId(), null,
                            "SPLIT", "MANUAL_SPLIT", reason, start, null,
                            oldAmt.setScale(2, RoundingMode.HALF_UP),
                            newAmt.setScale(2, RoundingMode.HALF_UP), "DONE", operator, now);
                } else {
                    String party = "NEW".equalsIgnoreCase(d.party()) ? "NEW" : "OLD";
                    if ("NEW".equals(party)) {
                        s.setOwner(newOwner);
                        s.setUpdatedAt(now);
                        serviceRepository.save(s);
                    }
                    saveAttr(transfer, vessel.getId(), AttributionService.TYPE_SERVICE, s.getId(), null,
                            party, "MANUAL", reason, start, null,
                            "OLD".equals(party) ? nz(s.getAmount()) : null,
                            "NEW".equals(party) ? nz(s.getAmount()) : null,
                            "DONE", operator, now);
                }
                continue;
            }

            if (continuous && end != null && start.isBefore(eff) && end.isAfter(eff)) {
                // 区间完整跨时点：按天自动拆（人工不可改默认拆账；如需改走交割后的更正流程）
                BigDecimal[] split = splitServiceByDays(s.getAmount(), startD, endD, eff.toLocalDate());
                saveAttr(transfer, vessel.getId(), AttributionService.TYPE_SERVICE, s.getId(), null,
                        "SPLIT", "AUTO_SPLIT_DAYS",
                        "持续服务跨生效时点，按自然天占比自动拆账（" + startD + "～" + endD + "）",
                        start, end, split[0], split[1], "DONE", operator, now);
                continue;
            }

            // 其余：按时间自动判 OLD/NEW（一次性按发生日；在续但生效后才开始→NEW；全段在前→OLD）
            boolean old;
            if (continuous) {
                old = end != null && !end.isAfter(eff);
            } else {
                old = s.getOccurredDate() != null && s.getOccurredDate().isBefore(eff.toLocalDate());
            }
            // 归新船主的费用：责任船主快照转新船主（旧费用保留原快照/留空，绝不转给新船主）
            if (!old) {
                s.setOwner(newOwner);
                s.setUpdatedAt(now);
                serviceRepository.save(s);
            }
            saveAttr(transfer, vessel.getId(), AttributionService.TYPE_SERVICE, s.getId(), null,
                    old ? "OLD" : "NEW", "AUTO_TIME",
                    old ? "生效时点前发生的服务费用，保留原归属" : "生效时点后发生的服务费用，归新船主",
                    start, end, old ? nz(s.getAmount()) : null, old ? null : nz(s.getAmount()),
                    "DONE", operator, now);
        }
    }

    /**
     * 把同一事实下其余 PENDING 行收口（SUPERSEDED）到 liveId：
     * 补判定责时指向 DONE 行；交割保留待判时指向本交割的 PENDING 行（全链仍只有一条待判）。
     */
    private void supersedePendingTo(String type, Long factId, Long liveId, LocalDateTime now, boolean done) {
        for (FactAttribution row : attrRepository.findAllByFactTypeAndFactIdOrderByIdAsc(type, factId)) {
            if (row.getId().equals(liveId)) {
                continue;
            }
            if ("PENDING".equals(row.getStatus()) || (done && "SUPERSEDED".equals(row.getStatus()))) {
                row.setStatus("SUPERSEDED");
                row.setSupersededBy(liveId);
                row.setUpdatedAt(now);
                attrRepository.save(row);
            }
        }
    }

    private FactAttribution saveAttr(OwnershipTransfer transfer, Long vesselId, String type, Long factId, Long splitFrom,
                          String party, String resolution, String basis,
                          LocalDateTime start, LocalDateTime end,
                          BigDecimal amtOld, BigDecimal amtNew, String status,
                          String operator, LocalDateTime now) {
        FactAttribution a = new FactAttribution();
        a.setTransfer(transfer);
        a.setVesselId(vesselId);
        a.setFactType(type);
        a.setFactId(factId);
        a.setSplitFromId(splitFrom);
        a.setParty(party);
        a.setResolution(resolution);
        a.setBasis(basis);
        a.setPeriodStart(start);
        a.setPeriodEnd(end);
        a.setAmountOld(amtOld);
        a.setAmountNew(amtNew);
        a.setStatus(status);
        a.setSource("TRANSFER");
        a.setOperator(operator);
        a.setCreatedAt(now);
        a.setUpdatedAt(now);
        a = attrRepository.save(a);
        return a;
    }

    // ================================================================ 更正 / 补判 / 查询

    /** 追加更正说明（不删不改交割单）。 */
    @Transactional
    public Views.TransferView addCorrection(Long transferId, TransferDtos.CorrectionReq req) {
        SecurityRules.requireStaff("追加交割更正");
        OwnershipTransfer t = transferRepository.findById(transferId)
                .orElseThrow(() -> new BizException("交割单不存在：id=" + transferId));
        String text = trim(req.text());
        if (text.isEmpty()) {
            throw new BizException("更正说明不能为空");
        }
        TransferCorrection c = new TransferCorrection();
        c.setTransfer(t);
        c.setText(trimTo(req.text(), 512));
        c.setOperator(trim(req.operator()));
        c.setCreatedAt(LocalDateTime.now());
        correctionRepository.save(c);
        t.setUpdatedAt(LocalDateTime.now());
        transferRepository.save(t);
        return toTransferView(t);
    }

    /** 历史/交割遗留的归属待判项补判（append-only：旧行 SUPERSEDED，新行 DONE）。 */
    @Transactional
    public Views.AttributionView reconcile(Long attrId, TransferDtos.ReconcileReq req) {
        SecurityRules.requireStaff("归属待判补判");
        FactAttribution pending = attrRepository.findById(attrId)
                .orElseThrow(() -> new BizException("归属记录不存在：id=" + attrId));
        if (!"PENDING".equals(pending.getStatus())) {
            throw new BizException("该归属项已定责，如需修改请走「更正」流程");
        }
        String party = req.party() == null ? "" : req.party().trim().toUpperCase();
        String basis = trim(req.basis());
        if (basis.isEmpty()) {
            throw new BizException("必须补充判定依据，禁止无依据按当前船主定责");
        }

        // 解析责任船主：有交割单按 OLD/NEW 双方；无交割单的历史待判必须显式指定 ownerId
        OwnerProfile responsible;
        if (pending.getTransfer() != null) {
            if (!"OLD".equals(party) && !"NEW".equals(party)) {
                throw new BizException("有交割单的待判项，承担方只能是 OLD（旧船主）/ NEW（新船主）");
            }
            responsible = "OLD".equals(party) ? pending.getTransfer().getOldOwner()
                    : pending.getTransfer().getNewOwner();
        } else {
            if (req.ownerId() == null) {
                throw new BizException("该历史待判项无交割单，请直接选择承担船主");
            }
            responsible = ownerRepository.findById(req.ownerId())
                    .orElseThrow(() -> new BizException("承担船主名录不存在：id=" + req.ownerId()));
            party = null; // 无交割双方，仅以 owner 钉住责任
        }

        LocalDateTime now = LocalDateTime.now();
        FactAttribution fresh = new FactAttribution();
        fresh.setTransfer(pending.getTransfer());
        fresh.setVesselId(pending.getVesselId());
        fresh.setOwner(responsible);
        fresh.setFactType(pending.getFactType());
        fresh.setFactId(pending.getFactId());
        fresh.setSplitFromId(pending.getSplitFromId());
        fresh.setParty(party == null ? "MANUAL" : party);
        fresh.setResolution("LEGACY_RECONCILED");
        fresh.setBasis(basis);
        fresh.setPeriodStart(pending.getPeriodStart());
        fresh.setPeriodEnd(pending.getPeriodEnd());
        ServiceRecord svc = "SERVICE".equals(pending.getFactType())
                ? serviceRepository.findById(pending.getFactId()).orElse(null) : null;
        if (svc != null) {
            if (pending.getTransfer() != null) {
                fresh.setAmountOld("OLD".equals(party) ? nz(svc.getAmount()) : null);
                fresh.setAmountNew("NEW".equals(party) ? nz(svc.getAmount()) : null);
            } else {
                fresh.setAmountNew(nz(svc.getAmount()));
            }
            // 服务费用责任船主快照随补判落定（原始记录保留，不改变金额/日期）
            svc.setOwner(responsible);
            svc.setUpdatedAt(now);
            serviceRepository.save(svc);
        }
        fresh.setStatus("DONE");
        fresh.setSource("LEGACY");
        fresh.setOperator(trim(req.operator()));
        fresh.setCreatedAt(now);
        fresh.setUpdatedAt(now);
        fresh = attrRepository.save(fresh);

        pending.setStatus("SUPERSEDED");
        pending.setSupersededBy(fresh.getId());
        pending.setUpdatedAt(now);
        attrRepository.save(pending);
        return toAttributionView(fresh);
    }

    @Transactional(readOnly = true)
    public List<Views.TransferView> listTransfers(Long vesselId) {
        List<OwnershipTransfer> source;
        if (!ActorContext.isStaff()) {
            // 船主端：只列自己作为原/新船主的交割单；若按船过滤，必须是自己持有过的船
            Long me = ActorContext.ownerId();
            source = new ArrayList<>();
            for (OwnershipTransfer t : transferRepository.findByOwner(me)) {
                if (vesselId == null || vesselId.equals(t.getVessel().getId())) {
                    source.add(t);
                }
            }
        } else if (vesselId != null) {
            source = transferRepository.findAllByVesselIdOrderByCompletedAtDescIdDesc(vesselId);
        } else {
            source = transferRepository.findAllByOrderByCompletedAtDescIdDesc();
        }
        List<Views.TransferView> list = new ArrayList<>();
        for (OwnershipTransfer t : source) {
            list.add(toTransferView(t));
        }
        return list;
    }

    @Transactional(readOnly = true)
    public Views.TransferView detail(Long transferId) {
        OwnershipTransfer t = transferRepository.findById(transferId)
                .orElseThrow(() -> new BizException("交割单不存在：id=" + transferId));
        // 船主端只能看自己作为一方的交割单
        if (!ActorContext.isStaff()) {
            Long me = ActorContext.ownerId();
            if (me == null || (!me.equals(t.getOldOwner().getId()) && !me.equals(t.getNewOwner().getId()))) {
                throw new BizException("无权查看该交割单");
            }
        }
        return toTransferView(t);
    }

    @Transactional(readOnly = true)
    public List<Views.AttributionView> listPending() {
        SecurityRules.requireStaff("查看归属待判台账");
        List<Views.AttributionView> list = new ArrayList<>();
        for (FactAttribution a : attrRepository.findPending()) {
            list.add(toAttributionView(a));
        }
        return list;
    }

    // ================================================================ 视图装配 / 金额拆分

    private Views.TransferView toTransferView(OwnershipTransfer t) {
        List<Views.AttributionView> items = new ArrayList<>();
        BigDecimal oldSum = BigDecimal.ZERO;
        BigDecimal newSum = BigDecimal.ZERO;
        long pending = 0;
        for (FactAttribution a : attrRepository.findAllByTransferIdOrderByIdAsc(t.getId())) {
            items.add(toAttributionView(a));
            if ("PENDING".equals(a.getStatus())) {
                pending++;
            }
            if (a.getAmountOld() != null) {
                oldSum = oldSum.add(a.getAmountOld());
            }
            if (a.getAmountNew() != null) {
                newSum = newSum.add(a.getAmountNew());
            }
        }
        List<Views.CorrectionView> corrections = new ArrayList<>();
        for (TransferCorrection c : correctionRepository.findAllByTransferIdOrderByCreatedAtAsc(t.getId())) {
            corrections.add(new Views.CorrectionView(c.getId(), c.getTransfer().getId(),
                    c.getText(), c.getOperator(), c.getCreatedAt()));
        }
        boolean staff = ActorContext.isStaff();
        Vessel v = t.getVessel();
        return new Views.TransferView(
                t.getId(), t.getTransferNo(), v.getId(), v.getName(), v.getRegNo(),
                t.getOldOwner().getId(), t.getOldOwner().getOwnerName(),
                staff ? t.getOldOwner().getPhone() : null,
                t.getNewOwner().getId(), t.getNewOwner().getOwnerName(),
                staff ? t.getNewOwner().getPhone() : null,
                t.getEffectiveAt(), t.getNote(), t.getStatus(), t.getOperator(),
                t.getCreatedAt(), t.getCompletedAt(), items, corrections, pending,
                oldSum.setScale(2, RoundingMode.HALF_UP), newSum.setScale(2, RoundingMode.HALF_UP));
    }

    private Views.AttributionView toAttributionView(FactAttribution a) {
        String factNo = factNo(a.getFactType(), a.getFactId());
        String vesselName = vesselRepository.findById(a.getVesselId()).map(Vessel::getName).orElse(null);
        String transferNo = a.getTransfer() == null ? null : a.getTransfer().getTransferNo();
        return new Views.AttributionView(
                a.getId(), a.getTransfer() == null ? null : a.getTransfer().getId(), transferNo,
                a.getVesselId(), vesselName, a.getFactType(), MarinaDict.factTypeName(a.getFactType()),
                a.getFactId(), factNo, a.getSplitFromId(),
                a.getParty(), MarinaDict.partyName(a.getParty()),
                a.getResolution(), MarinaDict.resolutionName(a.getResolution()),
                a.getBasis(), a.getPeriodStart(), a.getPeriodEnd(),
                a.getAmountOld(), a.getAmountNew(),
                a.getStatus(), MarinaDict.attrStatusName(a.getStatus()),
                a.getSource(), MarinaDict.attrSourceName(a.getSource()),
                a.getSupersededBy(), a.getOperator(), a.getCreatedAt(), a.getUpdatedAt());
    }

    private String factNo(String type, Long factId) {
        if (factId == null) {
            return null;
        }
        return switch (type) {
            case AttributionService.TYPE_BERTHING -> berthingRepository.findById(factId)
                    .map(Berthing::getRecordNo).orElse("#" + factId);
            case AttributionService.TYPE_SAILING -> planRepository.findById(factId)
                    .map(SailingPlan::getPlanNo).orElse("#" + factId);
            case AttributionService.TYPE_SERVICE -> serviceRepository.findById(factId)
                    .map(s -> MarinaDict.serviceTypeName(s.getServiceType()) + " #" + s.getId())
                    .orElse("#" + factId);
            default -> "#" + factId;
        };
    }

    /** 在泊跨时点拆费：旧段=生效前实际停泊天数×日租金；新段金额在结算前未知（置 null，台账记录区间责任）。 */
    private BigDecimal[] splitBerthingFee(Berth berth, LocalDateTime start, LocalDateTime eff, LocalDateTime endIgnored) {
        long hours = Duration.between(start, eff).toHours();
        int days = (int) Math.max(MarinaDict.MIN_BERTH_DAYS, Math.ceil(hours / 24.0));
        BigDecimal oldFee = berth.getDailyRate().multiply(BigDecimal.valueOf(days)).setScale(2, RoundingMode.HALF_UP);
        return new BigDecimal[] {oldFee, null};
    }

    /** 持续服务按自然天占比拆金额：含首不含尾；尾差（四舍五入）全部并入新段，保证两段之和=总额。 */
    private BigDecimal[] splitServiceByDays(BigDecimal amount, LocalDate start, LocalDate end, LocalDate eff) {
        long total = ChronoUnit.DAYS.between(start, end);
        long before = ChronoUnit.DAYS.between(start, eff);
        if (total <= 0) {
            return new BigDecimal[] {BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP), nz(amount)};
        }
        before = Math.max(0, Math.min(before, total));
        BigDecimal oldFee = amount.multiply(BigDecimal.valueOf(before))
                .divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP);
        BigDecimal newFee = amount.subtract(oldFee).setScale(2, RoundingMode.HALF_UP);
        return new BigDecimal[] {oldFee, newFee};
    }

    private long MarineDays(LocalDate start, LocalDate eff) {
        return ChronoUnit.DAYS.between(start, eff);
    }

    private OwnerProfile currentOwner(Vessel vessel) {
        return periodRepository.findFirstByVesselIdAndEndAtIsNullOrderByStartAtDesc(vessel.getId())
                .map(VesselOwnerPeriod::getOwner).orElse(vessel.getCurrentOwner());
    }

    private String nextTransferNo() {
        String prefix = "GH-" + LocalDateTime.now().format(NO_SUFFIX) + "-";
        long seq = transferRepository.count() + 1;
        String no = prefix + String.format("%04d", seq);
        while (transferRepository.existsByTransferNo(no)) {
            seq++;
            no = prefix + String.format("%04d", seq);
        }
        return no;
    }

    private String nextBerthingNo(LocalDateTime at) {
        String prefix = "KP-" + at.format(NO_SUFFIX) + "-";
        long seq = berthingRepository.count() + 1;
        String no = prefix + String.format("%04d", seq);
        while (berthingRepository.existsByRecordNo(no)) {
            seq++;
            no = prefix + String.format("%04d", seq);
        }
        return no;
    }

    // ---------------- 预览项装配辅助 ----------------

    private Views.PreviewItemView item(String key, String type, Long factId, String factNo, String title,
                                      LocalDateTime start, LocalDateTime end,
                                      LocalDate sStart, LocalDate sEnd,
                                      String settle, String settleName, BigDecimal amount,
                                      String crosses, String party, String resolution, String basis,
                                      BigDecimal amtOld, BigDecimal amtNew, boolean auto, boolean pending,
                                      String pendingReason, String debt, String internal, String legacy) {
        return new Views.PreviewItemView(key, type, MarinaDict.factTypeName(type), factId, factNo, title,
                start, end, sStart, sEnd, settle, settleName, amount, crosses,
                party, MarinaDict.partyName(party), resolution, MarinaDict.resolutionName(resolution),
                basis, amtOld, amtNew, auto, pending, pendingReason, debt, internal, legacy);
    }

    private Views.PreviewItemView planItem(SailingPlan p, String key, LocalDateTime depart, LocalDateTime ret,
                                           String crosses, String party, String resolution, String basis,
                                           BigDecimal amtOld, BigDecimal amtNew, boolean auto, boolean pending) {
        return item(key, AttributionService.TYPE_SAILING, p.getId(), p.getPlanNo(),
                "出航 " + p.getPlanNo() + " · " + p.getRoute(), depart, ret, null, null,
                p.getStatus(), MarinaDict.planStatusName(p.getStatus()), null, crosses,
                party, resolution, basis, amtOld, amtNew, auto, pending, null, null, null, null);
    }

    private Views.PreviewItemView serviceItem(ServiceRecord s, String key,
                                              LocalDateTime start, LocalDateTime end,
                                              String crosses, String party, String resolution, String basis,
                                              BigDecimal amtOld, BigDecimal amtNew,
                                              boolean auto, boolean pending, String pendingReason) {
        return item(key, AttributionService.TYPE_SERVICE, s.getId(),
                MarinaDict.serviceTypeName(s.getServiceType()) + " #" + s.getId(),
                MarinaDict.serviceTypeName(s.getServiceType())
                        + ("CONTINUOUS".equals(s.getServiceKind()) ? "（持续）" : "（一次性）"),
                start, end, s.getStartDate(), s.getEndDate(),
                s.getSettleStatus(), MarinaDict.settleStatusName(s.getSettleStatus()), s.getAmount(),
                crosses, party, resolution, basis, amtOld, amtNew, auto, pending, pendingReason,
                s.getDebtNote(), s.getInternalRemark(), s.getLegacyNote());
    }

    private BigDecimal[] sumSuggested(List<Views.PreviewItemView> items) {
        BigDecimal oldSum = BigDecimal.ZERO;
        BigDecimal newSum = BigDecimal.ZERO;
        for (Views.PreviewItemView i : items) {
            if (Boolean.TRUE.equals(i.pending())) {
                continue;
            }
            if (i.suggestedAmountOld() != null && !"SPLIT".equals(i.suggestedParty())) {
                oldSum = oldSum.add(i.suggestedAmountOld());
            }
            if (i.suggestedAmountNew() != null && !"SPLIT".equals(i.suggestedParty())) {
                newSum = newSum.add(i.suggestedAmountNew());
            }
            if ("SPLIT".equals(i.suggestedParty())) {
                if (i.suggestedAmountOld() != null) {
                    oldSum = oldSum.add(i.suggestedAmountOld());
                }
                if (i.suggestedAmountNew() != null) {
                    newSum = newSum.add(i.suggestedAmountNew());
                }
            }
        }
        return new BigDecimal[] {oldSum.setScale(2, RoundingMode.HALF_UP), newSum.setScale(2, RoundingMode.HALF_UP)};
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? null : v.setScale(2, RoundingMode.HALF_UP);
    }

    private String fmt(LocalDateTime t) {
        return t == null ? "—" : t.toString().replace('T', ' ').substring(0, 16);
    }

    private String trim(String s) {
        return s == null ? "" : s.trim();
    }

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
}
