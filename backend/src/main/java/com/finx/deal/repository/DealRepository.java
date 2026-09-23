package com.finx.deal.repository;

import com.finx.deal.entity.Deal;
import com.finx.deal.entity.DealStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DealRepository extends JpaRepository<Deal, UUID> {

    List<Deal> findByBuyerId(UUID buyerId);

    List<Deal> findBySellerId(UUID sellerId);

    List<Deal> findByProjectManagerId(UUID projectManagerId);

    List<Deal> findByStatus(DealStatus status);

    Optional<Deal> findByDealReference(String dealReference);

    List<Deal> findByBuyerOrganizationId(UUID buyerOrganizationId);

    List<Deal> findBySellerOrganizationId(UUID sellerOrganizationId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from Deal d where d.id = :id")
    Optional<Deal> findByIdForUpdate(@Param("id") UUID id);
}
