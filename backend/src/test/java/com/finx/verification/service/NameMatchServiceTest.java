package com.finx.verification.service;

import com.finx.verification.enums.NameMatchResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class NameMatchServiceTest {

    private NameMatchService nameMatchService;

    @BeforeEach
    public void setUp() {
        nameMatchService = new NameMatchService();
    }

    @Test
    public void testExactMatch() {
        var eval = nameMatchService.compareNames("Acme Technologies Private Limited", "Acme Technologies Private Limited");
        assertEquals(NameMatchResult.MATCH, eval.result());
        assertEquals(100.0, eval.score());
    }

    @Test
    public void testCaseAndPunctuationNormalization() {
        var eval = nameMatchService.compareNames("Acme Technologies, Pvt. Ltd.", "ACME TECHNOLOGIES PVT LTD");
        assertEquals(NameMatchResult.MATCH, eval.result());
        assertTrue(eval.score() >= 95.0);
    }

    @Test
    public void testCompanySuffixStripping() {
        var eval = nameMatchService.compareNames("FinX Solutions Private Limited", "FinX Solutions Ltd");
        assertEquals(NameMatchResult.MATCH, eval.result());
        assertTrue(eval.score() >= 95.0);
    }

    @Test
    public void testTokenOrderPermutation() {
        var eval = nameMatchService.compareNames("Rajesh Kumar Sharma", "Sharma Rajesh Kumar");
        assertEquals(NameMatchResult.MATCH, eval.result());
        assertTrue(eval.score() >= 90.0);
    }

    @Test
    public void testPartialMatch() {
        var eval = nameMatchService.compareNames("Rajesh Kumar Sharma", "Rajesh Sharma");
        assertTrue(eval.result() == NameMatchResult.MATCH || eval.result() == NameMatchResult.PARTIAL_MATCH);
        assertTrue(eval.score() >= 70.0);
    }

    @Test
    public void testMismatch() {
        var eval = nameMatchService.compareNames("Tata Consultancy Services", "Reliance Jio Infocomm");
        assertEquals(NameMatchResult.MISMATCH, eval.result());
        assertTrue(eval.score() < 50.0);
    }

    @Test
    public void testEmptyOrNullNames() {
        var eval1 = nameMatchService.compareNames("", "Acme");
        assertEquals(NameMatchResult.MISMATCH, eval1.result());

        var eval2 = nameMatchService.compareNames(null, null);
        assertEquals(NameMatchResult.MISMATCH, eval2.result());
    }
}
