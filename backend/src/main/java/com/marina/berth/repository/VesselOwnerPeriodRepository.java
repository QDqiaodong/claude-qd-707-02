package com.marina.berth.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.marina.berth.domain.VesselOwnerPeriod;

/** 船只持有区间（append-only 时间轴）。 */
public interface VesselOwnerPeriodRepository extends JpaRepository<VesselOwnerPeriod, Long> {

    List<VesselOwnerPeriod> findAllByVesselIdOrderByStartAtAsc(Long vesselId);

    List<VesselOwnerPeriod> findAllByOwnerIdOrderByStartAtDesc(Long ownerId);

    /** 某船当前仍开放（end_at 为空）的持有区间，正常至多一条 */
    Optional<VesselOwnerPeriod> findFirstByVesselIdAndEndAtIsNullOrderByStartAtDesc(Long vesselId);

    /**
     * 某船在指定时刻的持有人：start_at <= t 且（end_at 为空或 end_at > t），取最晚开始的一条。
     * 用于按时间轴还原「当时船主」，不依赖 vessel.owner_name 当前值。
     */
    @Query("select p from VesselOwnerPeriod p where p.vessel.id = :vesselId "
            + "and p.startAt <= :at and (p.endAt is null or p.endAt > :at) "
            + "order by p.startAt desc, p.id desc")
    List<VesselOwnerPeriod> findOwnerAt(@Param("vesselId") Long vesselId,
                                        @Param("at") java.time.LocalDateTime at);

    /** 某船主持有过（或正持有）的全部船只 id（含已过户出去的历史持有）。 */
    @Query("select distinct p.vessel.id from VesselOwnerPeriod p where p.owner.id = :ownerId")
    List<Long> findVesselIdsByOwner(@Param("ownerId") Long ownerId);
}
