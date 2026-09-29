package db.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finx.risk.config.RiskPolicies;
import com.finx.risk.dto.RiskDtos.UserType;
import com.finx.risk.service.RiskPolicyService;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

public class V19__initial_risk_policies extends BaseJavaMigration {
    @Override public void migrate(Context context) throws Exception {
        ObjectMapper mapper=new ObjectMapper();
        try(var insert=context.getConnection().prepareStatement("insert into risk_rules(id,user_type,configuration,version) values(?,?,?,1)")) {
            for(UserType type:UserType.values()) {
                insert.setObject(1,RiskPolicyService.id(type)); insert.setString(2,type.name());
                insert.setString(3,mapper.writeValueAsString(RiskPolicies.defaults(type))); insert.executeUpdate();
            }
        }
    }
}
