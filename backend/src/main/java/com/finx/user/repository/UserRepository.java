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

    List<User> findByUidIsNull();
    List<User> findByRole(Role role);
}
