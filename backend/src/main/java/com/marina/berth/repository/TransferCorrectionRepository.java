package com.marina.berth.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.marina.berth.domain.TransferCorrection;

/** 交割更正说明（append-only）。 */
public interface TransferCorrectionRepository extends JpaRepository<TransferCorrection, Long> {

    List<TransferCorrection> findAllByTransferIdOrderByCreatedAtAsc(Long transferId);
}
