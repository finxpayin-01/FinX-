package com.finx.auth.dto.response;

import com.finx.common.enums.Role;
import com.finx.common.enums.UserStatus;
import com.finx.user.entity.User;

import java.time.Instant;
import java.util.UUID;

public class UserSummaryResponse {

    private UUID id;
    private String uid;
    private String pmId;
    private String name;
    private String email;
    private Role role;
    private UserStatus status;
    private Instant createdAt;

    public UserSummaryResponse() {
    }

    public UserSummaryResponse(UUID id, String uid, String name, String email, Role role, UserStatus status, Instant createdAt) {
        this(id, uid, null, name, email, role, status, createdAt);
    }

    public UserSummaryResponse(UUID id, String uid, String pmId, String name, String email, Role role, UserStatus status, Instant createdAt) {
        this.id = id;
        this.uid = uid;
        this.pmId = pmId;
        this.name = name;
        this.email = email;
        this.role = role;
        this.status = status;
        this.createdAt = createdAt;
    }

    public static UserSummaryResponse fromEntity(User user) {
        return new UserSummaryResponse(
                user.getId(),
                user.getUid(),
                user.getPmId(),
                user.getName(),
                user.getEmail(),
                user.getRole(),
                user.getStatus(),
                user.getCreatedAt()
        );
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getUid() {
        return uid;
    }

    public void setUid(String uid) {
        this.uid = uid;
    }

    public String getPmId() {
        return pmId;
    }

    public void setPmId(String pmId) {
        this.pmId = pmId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public Role getRole() {
        return role;
    }

    public void setRole(Role role) {
        this.role = role;
    }

    public UserStatus getStatus() {
        return status;
    }

    public void setStatus(UserStatus status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
