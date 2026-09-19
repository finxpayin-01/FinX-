package com.finx.blockchain;

import com.finx.exception.ResourceNotFoundException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.math.BigInteger;
import java.util.*;

@Repository
public class ChainStore {
    private final JdbcTemplate jdbc;
    public ChainStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public JdbcTemplate jdbc() { return jdbc; }

    public Map<String, Object> escrow(UUID deal) {
        return one("select * from blockchain_escrows where deal_id = ?", deal);
    }
    Map<String, Object> byId(UUID id) { return one("select * from blockchain_escrows where id = ?", id); }
    Map<String, Object> lock(UUID id) { return one("select * from blockchain_escrows where id = ? for update", id); }
    Map<String, Object> one(String sql, Object... args) {
        var rows = jdbc.queryForList(sql, args);
        if (rows.isEmpty()) throw new ResourceNotFoundException("Blockchain record", "id", args[0]);
        return rows.get(0);
    }
    public boolean exists(UUID deal) {
        return jdbc.queryForObject("select count(*) from blockchain_escrows where deal_id = ?", Long.class, deal) > 0;
    }
    List<Map<String, Object>> allocations(UUID id) {
        return jdbc.queryForList("select * from blockchain_allocations where escrow_id = ? order by milestone_id", id);
    }
    public static String str(Map<String, Object> row, String key) { return Objects.toString(row.get(key), ""); }
    public static UUID uuid(Map<String, Object> row, String key) { return UUID.fromString(str(row, key)); }
    public static BigInteger units(Map<String, Object> row, String key) { return new java.math.BigDecimal(str(row, key)).toBigIntegerExact(); }

    // Never serialize atomic NUMERIC columns as JS numbers.
    public static Map<String, Object> view(Map<String, Object> row) {
        var result = new LinkedHashMap<String, Object>();
        row.forEach((k, v) -> result.put(k, v instanceof Number ? v.toString() : v));
        return result;
    }
}
