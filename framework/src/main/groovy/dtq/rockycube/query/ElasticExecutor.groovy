package dtq.rockycube.query

import org.moqui.context.ExecutionContext

class ElasticExecutor {
    static Map executeQuery(ExecutionContext executionContext, String indexName, Map searchMap)
    {
        // initialize client
        def es = executionContext.elastic.getClient("default")
        if (!es) return [result: false, message: 'Elastic not initialiazed']

        def result = [:]

        try {
            def es_result = es.search(indexName, searchMap)
            result = [result: true, message: '', data: es_result]
        } catch (Exception exc) {
            result = [result: false, message: exc.message]
        }

        return result
    }
}
