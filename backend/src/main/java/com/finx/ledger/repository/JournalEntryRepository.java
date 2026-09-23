package com.finx.ledger.repository;

import com.finx.ledger.entity.JournalEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface JournalEntryRepository extends JpaRepository<JournalEntry, UUID> {
    Optional<JournalEntry> findByEntryReference(String entryReference);
    List<JournalEntry> findByDealIdOrderByPostedAtDesc(UUID dealId);
    List<JournalEntry> findByReferenceId(UUID referenceId);
}
