package com.finx.verification.service;

import com.finx.verification.enums.NameMatchResult;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class NameMatchService {

    private static final Pattern PUNCTUATION_PATTERN = Pattern.compile("[^a-zA-Z0-9\\s]");
    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");

    private static final List<String> COMMON_COMPANY_SUFFIXES = List.of(
            "limited liability partnership",
            "private limited",
            "pvt ltd",
            "private",
            "pvt",
            "limited",
            "ltd",
            "llp",
            "incorporated",
            "inc",
            "corporation",
            "corp",
            "company",
            "co"
    );

    public record NameMatchEvaluation(
            double score,
            NameMatchResult result,
            String normalizedSource,
            String normalizedTarget,
            String reason
    ) {}

    public NameMatchEvaluation compareNames(String sourceName, String targetName) {
        if (sourceName == null || targetName == null || sourceName.isBlank() || targetName.isBlank()) {
            return new NameMatchEvaluation(0.0, NameMatchResult.MISMATCH, "", "", "One or both names are empty");
        }

        String norm1 = normalize(sourceName);
        String norm2 = normalize(targetName);

        if (norm1.equals(norm2)) {
            return new NameMatchEvaluation(100.0, NameMatchResult.MATCH, norm1, norm2, "Exact normalized match");
        }

        // Try stripping corporate suffixes for business entities
        String stripped1 = stripCompanySuffixes(norm1);
        String stripped2 = stripCompanySuffixes(norm2);

        if (stripped1.equals(stripped2) && !stripped1.isBlank()) {
            return new NameMatchEvaluation(98.0, NameMatchResult.MATCH, norm1, norm2, "Match after removing legal entity suffixes");
        }

        // Token set matching (e.g. "Kumar Rajesh" vs "Rajesh Kumar")
        Set<String> tokens1 = tokenize(norm1);
        Set<String> tokens2 = tokenize(norm2);

        if (tokens1.equals(tokens2) && !tokens1.isEmpty()) {
            return new NameMatchEvaluation(96.0, NameMatchResult.MATCH, norm1, norm2, "All name components match regardless of order");
        }

        // Compute similarity components
        double jaroWinkler = jaroWinklerSimilarity(norm1, norm2);
        double levenshteinRatio = levenshteinRatio(norm1, norm2);
        double tokenOverlap = tokenOverlapRatio(tokens1, tokens2);

        // Weighted composite score: 40% Token Overlap, 35% Jaro-Winkler, 25% Levenshtein Ratio
        double compositeScore = (tokenOverlap * 0.40) + (jaroWinkler * 0.35) + (levenshteinRatio * 0.25);
        double roundedScore = Math.round(compositeScore * 1000.0) / 10.0; // e.g. 87.5%

        NameMatchResult result;
        String reason;

        if (roundedScore >= 85.0) {
            result = NameMatchResult.MATCH;
            reason = String.format("High confidence similarity score: %.1f%%", roundedScore);
        } else if (roundedScore >= 70.0) {
            result = NameMatchResult.PARTIAL_MATCH;
            reason = String.format("Acceptable partial similarity (%.1f%%); phonetic or spelling variation", roundedScore);
        } else if (roundedScore >= 50.0) {
            result = NameMatchResult.MANUAL_REVIEW;
            reason = String.format("Moderate similarity (%.1f%%); flagged for compliance manual review", roundedScore);
        } else {
            result = NameMatchResult.MISMATCH;
            reason = String.format("Low similarity score (%.1f%%); significant mismatch detected", roundedScore);
        }

        return new NameMatchEvaluation(roundedScore, result, norm1, norm2, reason);
    }

    public String normalize(String name) {
        if (name == null) return "";
        String cleaned = name.trim().toLowerCase(Locale.ROOT);
        cleaned = cleaned.replace("&", " and ");
        cleaned = PUNCTUATION_PATTERN.matcher(cleaned).replaceAll(" ");
        cleaned = WHITESPACE_PATTERN.matcher(cleaned).replaceAll(" ").trim();
        return cleaned;
    }

    public String stripCompanySuffixes(String normalizedName) {
        String cleaned = normalizedName;
        boolean changed = true;
        while (changed) {
            changed = false;
            for (String suffix : COMMON_COMPANY_SUFFIXES) {
                if (cleaned.endsWith(" " + suffix)) {
                    cleaned = cleaned.substring(0, cleaned.length() - suffix.length() - 1).trim();
                    changed = true;
                    break;
                }
            }
        }
        return cleaned;
    }

    private Set<String> tokenize(String str) {
        if (str.isBlank()) return Collections.emptySet();
        return Arrays.stream(str.split("\\s+"))
                .filter(t -> t.length() > 1 || (!t.isBlank() && Character.isLetterOrDigit(t.charAt(0))))
                .collect(Collectors.toSet());
    }

    private double tokenOverlapRatio(Set<String> s1, Set<String> s2) {
        if (s1.isEmpty() || s2.isEmpty()) return 0.0;
        Set<String> intersection = new HashSet<>(s1);
        intersection.retainAll(s2);
        Set<String> union = new HashSet<>(s1);
        union.addAll(s2);
        return (double) intersection.size() / (double) union.size();
    }

    private double levenshteinRatio(String s1, String s2) {
        int maxLen = Math.max(s1.length(), s2.length());
        if (maxLen == 0) return 1.0;
        int distance = computeLevenshteinDistance(s1, s2);
        return 1.0 - ((double) distance / (double) maxLen);
    }

    private int computeLevenshteinDistance(String s1, String s2) {
        int[] costs = new int[s2.length() + 1];
        for (int j = 0; j <= s2.length(); j++) costs[j] = j;
        for (int i = 1; i <= s1.length(); i++) {
            costs[0] = i;
            int nw = i - 1;
            for (int j = 1; j <= s2.length(); j++) {
                int cj = Math.min(1 + Math.min(costs[j], costs[j - 1]),
                        s1.charAt(i - 1) == s2.charAt(j - 1) ? nw : nw + 1);
                nw = costs[j];
                costs[j] = cj;
            }
        }
        return costs[s2.length()];
    }

    private double jaroWinklerSimilarity(String s1, String s2) {
        if (s1.equals(s2)) return 1.0;
        if (s1.isEmpty() || s2.isEmpty()) return 0.0;

        int matchDistance = Math.max(s1.length(), s2.length()) / 2 - 1;
        if (matchDistance < 0) matchDistance = 0;

        boolean[] s1Matches = new boolean[s1.length()];
        boolean[] s2Matches = new boolean[s2.length()];

        int matches = 0;
        for (int i = 0; i < s1.length(); i++) {
            int start = Math.max(0, i - matchDistance);
            int end = Math.min(i + matchDistance + 1, s2.length());

            for (int j = start; j < end; j++) {
                if (s2Matches[j]) continue;
                if (s1.charAt(i) != s2.charAt(j)) continue;
                s1Matches[i] = true;
                s2Matches[j] = true;
                matches++;
                break;
            }
        }

        if (matches == 0) return 0.0;

        int transpositions = 0;
        int k = 0;
        for (int i = 0; i < s1.length(); i++) {
            if (!s1Matches[i]) continue;
            while (!s2Matches[k]) k++;
            if (s1.charAt(i) != s2.charAt(k)) transpositions++;
            k++;
        }

        double jaro = ((matches / (double) s1.length()) +
                (matches / (double) s2.length()) +
                ((matches - transpositions / 2.0) / matches)) / 3.0;

        // Winkler prefix bonus
        int prefix = 0;
        for (int i = 0; i < Math.min(4, Math.min(s1.length(), s2.length())); i++) {
            if (s1.charAt(i) == s2.charAt(i)) prefix++;
            else break;
        }

        return jaro + (prefix * 0.1 * (1.0 - jaro));
    }
}
