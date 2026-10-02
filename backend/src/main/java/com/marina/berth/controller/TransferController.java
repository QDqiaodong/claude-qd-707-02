package com.marina.berth.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.marina.berth.common.ApiResponse;
import com.marina.berth.dto.TransferDtos;
import com.marina.berth.dto.Views;
import com.marina.berth.service.TransferService;

import jakarta.validation.Valid;

/**
 * 模块八：船只过户交割。
 *
 * <p>预览 → 提交（逐项定责）→ 更正/补判 全流程；交割单与台账 append-only。
 * 写操作仅码头工作人员；船主端只读自己作为一方的交割单。
 */
@RestController
@RequestMapping("/api/transfers")
public class TransferController {

    private final TransferService transferService;

    public TransferController(TransferService transferService) {
        this.transferService = transferService;
    }

    /** 船主名录（电话等仅员工可见，船主端自动脱敏） */
    @GetMapping("/owners")
    public ApiResponse<List<Views.OwnerView>> owners() {
        return ApiResponse.success(transferService.listOwners());
    }

    /** 登记新船主名录（仅员工） */
    @PostMapping("/owners")
    public ApiResponse<Views.OwnerView> createOwner(@Valid @RequestBody TransferDtos.OwnerSaveReq req) {
        return ApiResponse.success("新船主已登记", transferService.createOwner(req));
    }

    /** 交割责任预览：逐项给出 旧/新/拆账/待判 建议，不改数据 */
    @GetMapping("/preview")
    public ApiResponse<Views.TransferPreview> preview(@RequestParam Long vesselId,
                                                      @RequestParam String effectiveAt) {
        return ApiResponse.success(transferService.preview(vesselId, parse(effectiveAt)));
    }

    /** 提交交割（在泊拆段、逐项定责、持有区间、船主变更单事务完成） */
    @PostMapping
    public ApiResponse<Views.TransferView> submit(@Valid @RequestBody TransferDtos.TransferSubmitReq req) {
        return ApiResponse.success("过户交割已完成，责任按生效时点逐项落账（交割记录不可删除，仅可追加更正）",
                transferService.submit(req));
    }

    /** 交割链列表（可按船过滤）；员工看全量 */
    @GetMapping
    public ApiResponse<List<Views.TransferView>> list(@RequestParam(required = false) Long vesselId) {
        return ApiResponse.success(transferService.listTransfers(vesselId));
    }

    /** 交割详情（含每项责任选择与更正链；船主仅可看自己作为一方的单） */
    @GetMapping("/{id}")
    public ApiResponse<Views.TransferView> detail(@PathVariable("id") Long id) {
        return ApiResponse.success(transferService.detail(id));
    }

    /** 追加更正说明（不改原记录，只追加） */
    @PostMapping("/{id}/corrections")
    public ApiResponse<Views.TransferView> correction(@PathVariable("id") Long id,
                                                      @Valid @RequestBody TransferDtos.CorrectionReq req) {
        return ApiResponse.success("更正说明已追加，原始交割记录保持不变", transferService.addCorrection(id, req));
    }

    /** 归属待判台账（仅员工） */
    @GetMapping("/pending")
    public ApiResponse<List<Views.AttributionView>> pending() {
        return ApiResponse.success(transferService.listPending());
    }

    /** 归属待判补判（仅员工，必须填依据；旧行留痕、新行定责） */
    @PostMapping("/attributions/{id}/reconcile")
    public ApiResponse<Views.AttributionView> reconcile(@PathVariable("id") Long id,
                                                        @Valid @RequestBody TransferDtos.ReconcileReq req) {
        return ApiResponse.success("已按补充依据完成定责，原待判记录保留可追溯", transferService.reconcile(id, req));
    }

    private java.time.LocalDateTime parse(String text) {
        if (text == null || text.isBlank()) {
            throw new com.marina.berth.common.BizException("交割生效时点不能为空");
        }
        try {
            return java.time.LocalDateTime.parse(text.trim().replace(' ', 'T'));
        } catch (Exception e) {
            try {
                return java.time.LocalDate.parse(text.trim()).atStartOfDay();
            } catch (Exception ex) {
                throw new com.marina.berth.common.BizException("生效时间格式有误：" + text
                        + "（应为 yyyy-MM-dd HH:mm）");
            }
        }
    }
}
