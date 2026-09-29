package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

public class V18__immutable_risk_history extends BaseJavaMigration {
    @Override public void migrate(Context context) throws Exception {
        if (!context.getConnection().getMetaData().getDatabaseProductName().equals("PostgreSQL")) return;
        try (var statement = context.getConnection().createStatement()) {
            statement.execute("""
                CREATE FUNCTION finx_guard_risk_history() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF TG_TABLE_NAME = 'risk_assessments' THEN
                        IF OLD.rule_version IS NULL THEN
                            IF TG_OP = 'DELETE' THEN RETURN OLD; ELSE RETURN NEW; END IF;
                        END IF;
                    END IF;
                    RAISE EXCEPTION 'Risk evidence is append-only';
                END $$
                """);
            for (String table : new String[]{"risk_score_history", "risk_factors", "risk_assessments"}) {
                statement.execute("CREATE TRIGGER immutable_" + table + " BEFORE UPDATE OR DELETE ON " + table
                        + " FOR EACH ROW EXECUTE FUNCTION finx_guard_risk_history()");
            }
        }
    }
}
