package com.marina.berth.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.marina.berth.domain.FactAttribution;

/** 事实责任归属台账（append-only）。 */
public interface FactAttributionRepository extends JpaRepository<FactAttribution, Long> {

    List<FactAttribution> findAllByOrderByIdDesc();

    List<FactAttribution> findByStatusOrderByIdDesc(String status);

    List<FactAttribution> findAllByVesselIdOrderByIdDesc(Long vesselId);

    List<FactAttribution> findAllByTransferIdOrderByIdAsc(Long transferId);

    List<FactAttribution> findAllByFactTypeAndFactIdOrderByIdAsc(String factType, Long factId);

    /** 全部归属待判项（历史缺据 / 交割时暂挂），供运营补判台账 */
    @Query("select a from FactAttribution a where a.status = 'PENDING' order by a.id desc")
    List<FactAttribution> findPending();

    /** 某船主在其名下事实中，待判项计数（一般不向船主暴露，这里按船过滤给运营视角用） */
    @Query("select a from FactAttribution a where a.vesselId = :vesselId and a.status = 'PENDING'")
    List<FactAttribution> findPendingByVessel(@Param("vesselId") Long vesselId);
}
