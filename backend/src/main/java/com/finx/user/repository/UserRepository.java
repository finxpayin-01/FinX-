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
    Optional<User> findByUidIgnoreCase(String uid);

    boolean existsByEmail(String email);
    boolean existsByRole(Role role);
    boolean existsByUid(String uid);
    boolean existsByUidIgnoreCase(String uid);

    List<User> findByUidIsNull();
    List<User> findByRole(Role role);
}
