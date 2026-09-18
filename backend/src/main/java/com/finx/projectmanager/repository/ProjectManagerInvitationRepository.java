package com.finx.projectmanager.repository;

import com.finx.projectmanager.entity.InvitationStatus;
import com.finx.projectmanager.entity.ProjectManagerInvitation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ProjectManagerInvitationRepository extends JpaRepository<ProjectManagerInvitation, UUID> {

    List<ProjectManagerInvitation> findByProjectManagerIdOrderByCreatedAtDesc(UUID projectManagerId);

    List<ProjectManagerInvitation> findByProjectManagerIdAndStatusOrderByCreatedAtDesc(UUID projectManagerId, InvitationStatus status);

    List<ProjectManagerInvitation> findByDealIdOrderByCreatedAtDesc(UUID dealId);

    Optional<ProjectManagerInvitation> findFirstByDealIdAndStatus(UUID dealId, InvitationStatus status);

    Optional<ProjectManagerInvitation> findFirstByDealIdOrderByCreatedAtDesc(UUID dealId);

    boolean existsByDealIdAndStatus(UUID dealId, InvitationStatus status);

    boolean existsByDealIdAndProjectManagerIdAndStatus(UUID dealId, UUID projectManagerId, InvitationStatus status);

    long countByProjectManagerIdAndStatus(UUID projectManagerId, InvitationStatus status);
}
