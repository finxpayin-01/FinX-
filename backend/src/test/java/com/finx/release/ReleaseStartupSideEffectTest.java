package com.finx.release;

import com.finx.outbox.repository.FinancialOutboxRepository;
import com.finx.release.repository.ReleaseInstructionRepository;
import com.finx.release.repository.ReleaseReservationRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:startup_test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "rate-limit.enabled=false"
})
public class ReleaseStartupSideEffectTest {

    @Autowired
    private ReleaseInstructionRepository instructionRepository;

    @Autowired
    private ReleaseReservationRepository reservationRepository;

    @Autowired
    private FinancialOutboxRepository outboxRepository;

    @Test
    @DisplayName("Spring context startup produces zero outbox messages, reservations, or instructions")
    void testStartupCreatesZeroSideEffects() {
        assertThat(outboxRepository.count()).isEqualTo(0);
        assertThat(reservationRepository.count()).isEqualTo(0);
        assertThat(instructionRepository.count()).isEqualTo(0);
    }
}
