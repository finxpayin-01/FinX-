package com.finx.release.service;

import com.finx.escrow.entity.EscrowAccount;
import com.finx.milestone.entity.Milestone;
import com.finx.release.entity.ReleaseInstruction;
import com.finx.release.entity.ReleaseReservation;
import com.finx.release.repository.ReleaseReservationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/**
 * Internal-only service for creating durable fund reservations on EscrowAccount.
 * Strictly enforces Propagation.MANDATORY — reservations cannot be created outside
 * an active authorization transaction (TX2).
 */
@Service
public class EscrowReservationService {

    private final ReleaseReservationRepository reservationRepository;

    public EscrowReservationService(ReleaseReservationRepository reservationRepository) {
        this.reservationRepository = Objects.requireNonNull(reservationRepository, "reservationRepository must not be null");
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public ReleaseReservation createActiveReservation(
            ReleaseInstruction instruction,
            EscrowAccount escrowAccount,
            Milestone milestone
    ) {
        Objects.requireNonNull(instruction, "instruction must not be null");
        Objects.requireNonNull(escrowAccount, "escrowAccount must not be null");
        Objects.requireNonNull(milestone, "milestone must not be null");

        ReleaseReservation reservation = new ReleaseReservation(
                null,
                instruction.getId(),
                escrowAccount.getId(),
                milestone.getId(),
                instruction.getAmount(),
                instruction.getCurrency()
        );

        return reservationRepository.save(reservation);
    }
}
