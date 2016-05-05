package org.moqui.impl.entity.condition;

import org.moqui.entity.EntityCondition;
import org.moqui.impl.entity.EntityQueryBuilder;

import java.util.Set;

public interface EntityConditionImplBase extends EntityCondition {

    /** Build SQL Where text to evaluate condition in a database. */
    void makeSqlWhere(EntityQueryBuilder eqb);

    void getAllAliases(Set<String> entityAliasSet, Set<String> fieldAliasSet);
}
