package com.finx.common.config;

import com.finx.common.enums.Role;
import com.finx.common.enums.UserStatus;
import com.finx.user.entity.User;
import com.finx.user.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Initializes default system data such as the administrative account on application startup.
 */
@Component
public class DataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final com.finx.user.service.UserUidService userUidService;
    private final com.finx.user.service.PmIdService pmIdService;
    private final String initialAdminPassword;

    public DataInitializer(UserRepository userRepository,
                           PasswordEncoder passwordEncoder,
                           com.finx.user.service.UserUidService userUidService,
                           com.finx.user.service.PmIdService pmIdService,
                           @Value("${finx.admin.initial-password:}") String initialAdminPassword) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.userUidService = userUidService;
        this.pmIdService = pmIdService;
        this.initialAdminPassword = initialAdminPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            if (!userRepository.existsByRole(Role.ADMIN)) {
                if (initialAdminPassword == null || initialAdminPassword.isBlank()) {
                    log.warn("No admin user exists and FINX_ADMIN_INITIAL_PASSWORD is not configured; skipping admin bootstrap.");
                } else {
                    User admin = new User(
                            "FINX System Administrator",
                            "admin@finx.com",
                            passwordEncoder.encode(initialAdminPassword),
                            Role.ADMIN,
                            UserStatus.ACTIVE
                    );
                    admin.setUid(userUidService.generateUniqueUid());
                    userRepository.save(admin);
                    log.info("Default system administrator initialized with UID '{}': admin@finx.com", admin.getUid());
                }
            }
        } catch (Exception ex) {
            log.warn("[STARTUP ERROR] Admin initialization failed due to: {}", ex.getMessage());
        }

        // Safety startup check: backfill any existing accounts missing a UID
        try {
            int backfilled = userUidService.backfillMissingUids();
            if (backfilled > 0) {
                log.info("[STARTUP BACKFILL] Successfully backfilled UIDs for {} existing user accounts.", backfilled);
            }
        } catch (Exception ex) {
            log.warn("[STARTUP BACKFILL] Note: startup UID backfill skipped or deferred: {}", ex.getMessage());
        }

        // Safety startup check: backfill any existing project managers missing a PM ID
        try {
            int pmBackfilled = pmIdService.backfillMissingPmIds();
            if (pmBackfilled > 0) {
                log.info("[STARTUP BACKFILL] Successfully backfilled PM IDs for {} existing Project Managers.", pmBackfilled);
            }
        } catch (Exception ex) {
            log.warn("[STARTUP BACKFILL] Note: startup PM ID backfill skipped or deferred: {}", ex.getMessage());
        }
    }
}
