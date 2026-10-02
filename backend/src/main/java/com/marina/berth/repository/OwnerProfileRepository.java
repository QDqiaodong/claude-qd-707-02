package com.marina.berth.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.marina.berth.domain.OwnerProfile;

import jakarta.persistence.LockModeType;

/** 船主名录。 */
public interface OwnerProfileRepository extends JpaRepository<OwnerProfile, Long> {

    List<OwnerProfile> findAllByOrderByOwnerNameAsc();

    Optional<OwnerProfile> findByOwnerName(String ownerName);

    boolean existsByOwnerName(String ownerName);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from OwnerProfile o where o.id = :id")
    Optional<OwnerProfile> findByIdForUpdate(@Param("id") Long id);
}
