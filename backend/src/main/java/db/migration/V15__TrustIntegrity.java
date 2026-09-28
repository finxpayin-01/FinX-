package db.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finx.trust.config.TrustRules;
import org.flywaydb.core.api.migration.*;

public class V15__TrustIntegrity extends BaseJavaMigration {
    @Override public Integer getChecksum() { return 15001; }
    @Override public void migrate(Context context) throws Exception {
        boolean pg = "PostgreSQL".equals(context.getConnection().getMetaData().getDatabaseProductName());
        try (var insert = context.getConnection().prepareStatement("INSERT INTO trust_scoring_config(rules,reason,created_at) VALUES ("
                +(pg ? "CAST(? AS jsonb)" : "? FORMAT JSON")+",'Initial TRUST_V1 configuration',CURRENT_TIMESTAMP)")) {
            insert.setString(1,new ObjectMapper().writeValueAsString(TrustRules.defaults())); insert.executeUpdate();
        }
        if (!pg) return;
        try (var sql = context.getConnection().createStatement()) {
            sql.execute("""
                CREATE FUNCTION finx_reject_trust_mutation() RETURNS trigger
                LANGUAGE plpgsql SET search_path=pg_catalog AS $$
                BEGIN RAISE EXCEPTION 'Trust history is append-only' USING ERRCODE='23514'; END; $$
                """);
            for (String table : java.util.List.of("trust_score_history","trust_scoring_config","trust_signal")) {
                sql.execute("CREATE TRIGGER "+table+"_immutable BEFORE UPDATE OR DELETE ON "+table
                        +" FOR EACH ROW EXECUTE FUNCTION finx_reject_trust_mutation()");
                sql.execute("CREATE TRIGGER "+table+"_immutable_truncate BEFORE TRUNCATE ON "+table
                        +" FOR EACH STATEMENT EXECUTE FUNCTION finx_reject_trust_mutation()");
            }
            sql.execute("""
                CREATE FUNCTION finx_trust_event_guard() RETURNS trigger
                LANGUAGE plpgsql SET search_path=pg_catalog AS $$
                BEGIN
                    IF TG_OP <> 'UPDATE' THEN RAISE EXCEPTION 'Trust events are immutable' USING ERRCODE='23514'; END IF;
                    IF (to_jsonb(OLD)-'processed') IS DISTINCT FROM (to_jsonb(NEW)-'processed')
                       OR (OLD.processed AND NOT NEW.processed) THEN
                        RAISE EXCEPTION 'Trust event facts are immutable' USING ERRCODE='23514';
                    END IF;
                    RETURN NEW;
                END; $$
                """);
            sql.execute("CREATE TRIGGER trust_events_immutable BEFORE UPDATE OR DELETE ON trust_events FOR EACH ROW EXECUTE FUNCTION finx_trust_event_guard()");
            sql.execute("CREATE TRIGGER trust_events_immutable_truncate BEFORE TRUNCATE ON trust_events FOR EACH STATEMENT EXECUTE FUNCTION finx_reject_trust_mutation()");
            sql.execute("CREATE INDEX idx_trust_unprocessed ON trust_events(organization_id,created_at) WHERE processed=false");
        }
    }
}
