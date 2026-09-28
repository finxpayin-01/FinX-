package com.finx.trust;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.sql.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Real PostgreSQL only; always uses a fresh disposable schema on a local test server. */
@EnabledIfEnvironmentVariable(named="FINX_TRUST_TEST_POSTGRES_URL", matches="jdbc:postgresql://(localhost|127\\.0\\.0\\.1):.+")
class TrustPostgresMigrationTest {
    @Test void migrationsJsonIndexesAndAppendOnlyGuards() throws Exception {
        String url=System.getenv("FINX_TRUST_TEST_POSTGRES_URL");
        String user=System.getenv().getOrDefault("FINX_TRUST_TEST_POSTGRES_USER","finx_trust_test");
        String schema="trust_test_"+UUID.randomUUID().toString().replace("-","");
        try(var connection=DriverManager.getConnection(url,user,""); var sql=connection.createStatement()) {
            try {
                var flyway=Flyway.configure().dataSource(url,user,"").schemas(schema).defaultSchema(schema).load();
                flyway.migrate(); flyway.validate(); assertThat(flyway.migrate().migrationsExecuted).isZero();
                sql.execute("SET search_path TO "+schema);
                var organization=UUID.randomUUID(); var event=UUID.randomUUID(); var history=UUID.randomUUID();
                sql.execute("INSERT INTO organizations(id,name,created_at) VALUES ('"+organization+"','PostgreSQL fixture',now())");
                sql.execute("INSERT INTO trust_events(id,organization_id,role,event_type,severity,occurred_at,metadata,source,source_key,created_at) VALUES ('"+event+"','"+organization+"','OVERALL','KYB_VERIFIED',1,now(),'{\"deadlineKnown\":true}','TEST','test-event',now())");
                sql.execute("UPDATE trust_events SET processed=true WHERE id='"+event+"'");
                try(var row=sql.executeQuery("SELECT metadata->>'deadlineKnown' FROM trust_events")) { row.next(); assertThat(row.getString(1)).isEqualTo("true"); }
                sql.execute("INSERT INTO trust_score_history(id,organization_id,role,new_score,new_level,reason,config_version,calculated_at,snapshot) VALUES ('"+history+"','"+organization+"','OVERALL',50,'INSUFFICIENT_DATA','fixture',1,now(),'{}')");
                sql.execute("INSERT INTO trust_signal(id,organization_id,calculation_id,role,signal_type,category,impact,severity,description,occurred_at) VALUES ('"+UUID.randomUUID()+"','"+organization+"','"+history+"','OVERALL','RISK','TEST',-1,1,'fixture',now())");
                for(var command:List.of("UPDATE trust_events SET severity=5", "DELETE FROM trust_events", "TRUNCATE trust_events CASCADE",
                        "UPDATE trust_events SET processed=false","UPDATE trust_scoring_config SET reason='tampered'",
                        "DELETE FROM trust_score_history","TRUNCATE trust_score_history CASCADE","UPDATE trust_signal SET impact=100")) {
                    assertThatThrownBy(()->sql.execute(command)).isInstanceOf(SQLException.class)
                        .satisfies(e->assertThat(((SQLException)e).getSQLState()).isEqualTo("23514"));
                }
                assertThatThrownBy(()->sql.execute("INSERT INTO trust_events SELECT * FROM trust_events"))
                    .isInstanceOf(SQLException.class).satisfies(e->assertThat(((SQLException)e).getSQLState()).isEqualTo("23505"));
                try(var rows=sql.executeQuery("select count(*) from pg_indexes where schemaname='"+schema+"' and indexname='idx_trust_unprocessed'")) {
                    rows.next(); assertThat(rows.getInt(1)).isEqualTo(1);
                }
            } finally { sql.execute("DROP SCHEMA IF EXISTS "+schema+" CASCADE"); }
        }
    }
}
