package org.moqui.impl.entity.condition;

import org.moqui.entity.EntityCondition;
import org.moqui.impl.entity.EntityConditionFactoryImpl;
import org.moqui.impl.entity.EntityQueryBuilder;

import java.util.Set;

public abstract class EntityConditionImplBase implements EntityCondition {
    protected EntityConditionFactoryImpl ecFactoryImpl;

    public EntityConditionImplBase(EntityConditionFactoryImpl ecFactoryImpl) {
        this.ecFactoryImpl = ecFactoryImpl;
    }

    /** Build SQL Where text to evaluate condition in a database. */
    public abstract void makeSqlWhere(EntityQueryBuilder eqb);

    public abstract void getAllAliases(Set<String> entityAliasSet, Set<String> fieldAliasSet);

    public EntityConditionFactoryImpl getEcFactoryImpl() {
        return ecFactoryImpl;
    }
}
