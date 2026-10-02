package com.marina.berth.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.marina.berth.domain.OwnershipTransfer;

import jakarta.persistence.LockModeType;

/** 过户交割单（append-only）。 */
public interface OwnershipTransferRepository extends JpaRepository<OwnershipTransfer, Long> {

    List<OwnershipTransfer> findAllByOrderByCompletedAtDescIdDesc();

    List<OwnershipTransfer> findAllByVesselIdOrderByCompletedAtDescIdDesc(Long vesselId);

    boolean existsByTransferNo(String transferNo);

    /** 涉及某船主（作为原船主或新船主）的交割单 —— 船主端可查自己的交割链 */
    @Query("select t from OwnershipTransfer t "
            + "where t.oldOwner.id = :ownerId or t.newOwner.id = :ownerId "
            + "order by t.completedAt desc, t.id desc")
    List<OwnershipTransfer> findByOwner(@Param("ownerId") Long ownerId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from OwnershipTransfer t where t.id = :id")
    Optional<OwnershipTransfer> findByIdForUpdate(@Param("id") Long id);
}
