package com.finx.user.repository;

import com.finx.common.enums.Role;
import com.finx.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmail(String email);
    Optional<User> findByUid(String uid);
    
    @org.springframework.data.jpa.repository.Query("SELECT u FROM User u WHERE LOWER(u.uid) = LOWER(:uid)")
    Optional<User> findByUidIgnoreCase(@org.springframework.data.repository.query.Param("uid") String uid);

    boolean existsByEmail(String email);
    boolean existsByRole(Role role);
    boolean existsByUid(String uid);
    
    @org.springframework.data.jpa.repository.Query("SELECT COUNT(u) > 0 FROM User u WHERE LOWER(u.uid) = LOWER(:uid)")
    boolean existsByUidIgnoreCase(@org.springframework.data.repository.query.Param("uid") String uid);

    Optional<User> findByPmId(String pmId);

    @org.springframework.data.jpa.repository.Query("SELECT u FROM User u WHERE LOWER(u.pmId) = LOWER(:pmId)")
    Optional<User> findByPmIdIgnoreCase(@org.springframework.data.repository.query.Param("pmId") String pmId);

    boolean existsByPmId(String pmId);

    @org.springframework.data.jpa.repository.Query("SELECT COUNT(u) > 0 FROM User u WHERE LOWER(u.pmId) = LOWER(:pmId)")
    boolean existsByPmIdIgnoreCase(@org.springframework.data.repository.query.Param("pmId") String pmId);

    List<User> findByUidIsNull();
    List<User> findByRole(Role role);

    @org.springframework.data.jpa.repository.Query("SELECT u FROM User u WHERE u.role = 'PROJECT_MANAGER' AND (u.pmId IS NULL OR TRIM(u.pmId) = '')")
    List<User> findProjectManagersMissingPmId();
}
