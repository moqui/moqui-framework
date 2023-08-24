package dtq.rockycube

import org.moqui.Moqui
import org.moqui.context.ExecutionContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import spock.lang.Shared

class PersonEntityTestes {
    protected final static Logger logger = LoggerFactory.getLogger(PersonEntityTestes.class)

    @Shared
    ExecutionContext ec

    def setupSpec() {
        // set other conf - this configuration contains definition of Closure-related entities
        System.setProperty("moqui.conf", "../framework/src/test/resources/dynamic-relationship/DynamicRelationshipConf.xml")


        // init the framework, get the ec
        ec = Moqui.getExecutionContext()
        ec.user.loginUser('john.hardy', 'moqui')
    }
}
