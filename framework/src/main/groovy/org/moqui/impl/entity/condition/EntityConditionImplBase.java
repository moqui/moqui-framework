package org.moqui.impl.entity.condition;

import org.moqui.Moqui;
import org.moqui.entity.EntityCondition;
import org.moqui.impl.context.ExecutionContextFactoryImpl;
import org.moqui.impl.entity.EntityConditionFactoryImpl;
import org.moqui.impl.entity.EntityQueryBuilder;

import java.util.Set;

public abstract class EntityConditionImplBase implements EntityCondition {
    protected transient EntityConditionFactoryImpl ecFactoryImpl;
    protected String tenantId;

    public EntityConditionImplBase(EntityConditionFactoryImpl ecFactoryImpl) {
        this.ecFactoryImpl = ecFactoryImpl;
        tenantId = ecFactoryImpl.getEfi().getTenantId();
    }

    /** Build SQL Where text to evaluate condition in a database. */
    public abstract void makeSqlWhere(EntityQueryBuilder eqb);

    public abstract void getAllAliases(Set<String> entityAliasSet, Set<String> fieldAliasSet);

    public EntityConditionFactoryImpl getEcFactoryImpl() {
        if (ecFactoryImpl == null) {
            ExecutionContextFactoryImpl ecfi = (ExecutionContextFactoryImpl) Moqui.getExecutionContextFactory();
            ecFactoryImpl = ecfi.getEntityFacade(tenantId).getConditionFactoryImpl();
        }
        return ecFactoryImpl;
    }
}
