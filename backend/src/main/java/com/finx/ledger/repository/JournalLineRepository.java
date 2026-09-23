package com.finx.ledger.repository;

import com.finx.ledger.entity.JournalLine;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface JournalLineRepository extends JpaRepository<JournalLine, UUID> {
    List<JournalLine> findByJournalEntryIdOrderBySequenceAsc(UUID journalEntryId);
}
