package com.finx.release.repository;

import com.finx.release.entity.ReleaseReservation;
import com.finx.release.model.ReleaseReservationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ReleaseReservationRepository extends JpaRepository<ReleaseReservation, UUID> {

    Optional<ReleaseReservation> findByReleaseInstructionId(UUID releaseInstructionId);

    Optional<ReleaseReservation> findFirstByMilestoneIdAndStatus(UUID milestoneId, ReleaseReservationStatus status);

    List<ReleaseReservation> findByEscrowAccountIdAndStatus(UUID escrowAccountId, ReleaseReservationStatus status);

    @Query("SELECT COALESCE(SUM(r.amount), 0) FROM ReleaseReservation r WHERE r.escrowAccountId = :escrowAccountId AND r.status = 'ACTIVE'")
    BigDecimal sumActiveReservationsForEscrow(@Param("escrowAccountId") UUID escrowAccountId);
}
