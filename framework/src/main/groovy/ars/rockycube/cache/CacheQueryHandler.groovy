package ars.rockycube.cache

import ars.rockycube.entity.ConditionHandler
import org.moqui.entity.EntityCondition
import org.moqui.impl.entity.EntityDefinition
import org.moqui.impl.entity.condition.EntityConditionImplBase

import javax.cache.Cache

class CacheQueryHandler {
    protected Cache internalCache
    protected EntityDefinition entityDefinition

    CacheQueryHandler(Cache cache, EntityDefinition ed){
        this.internalCache = cache
        this.entityDefinition = ed
    }

    public ArrayList<String> fetch(EntityConditionImplBase conditions){
        ArrayList res = new ArrayList()

        // evaluate each condition
        // with each next run taking into account the previous one

        for (def item in this.internalCache)
        {
            // no conditions
            if (conditions.toString() == "") { res.add(item.key); continue }

            // conditions set
            def matches = conditions.mapMatches(item.value as Map<String, Object>)
            if (matches) res.add(item.key)
        }

        // sort IDs
        return res.sort()
    }

    /*
     * Method that returns simple query result
     * requires `term` and `joinOperator`
     */
    public ArrayList<String> fetch(EntityCondition.JoinOperator joinOperator, ArrayList<HashMap> term){
        def conditions = ConditionHandler.getFieldsCondition(term)
        ArrayList res = new ArrayList()

        // evaluate each condition
        // with each next run taking into account the previous one

        for (def item in this.internalCache)
        {
            boolean matches
            // no conditions, quit right-away
            if (conditions.empty){
                res.add(item.key)
                continue
            }

            if (joinOperator == EntityCondition.JoinOperator.AND)
            {
                matches = conditions.every {c-> return ConditionHandler.evaluateCondition(c, item.value) }
            } else {
                matches = conditions.any {c-> return ConditionHandler.evaluateCondition(c, item.value) }
            }
            if (matches) res.add(item.key)
        }

        // sort IDs
        return res.sort()
    }
}
