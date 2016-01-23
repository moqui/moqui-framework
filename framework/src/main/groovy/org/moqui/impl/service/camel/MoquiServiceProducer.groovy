/*
 * This software is in the public domain under CC0 1.0 Universal plus a 
 * Grant of Patent License.
 * 
 * To the extent possible under law, the author(s) have dedicated all
 * copyright and related and neighboring rights to this software to the
 * public domain worldwide. This software is distributed without any
 * warranty.
 * 
 * You should have received a copy of the CC0 Public Domain Dedication
 * along with this software (see the LICENSE.md file). If not, see
 * <http://creativecommons.org/publicdomain/zero/1.0/>.
 */
package org.moqui.impl.service.camel

import org.apache.camel.impl.DefaultProducer
import org.apache.camel.Exchange

/**
 * Camel Producer for the MoquiService endpoint. This processes messages send to an endpoint like:
 *
 * moquiservice:serviceName
 */
class MoquiServiceProducer extends DefaultProducer {
    protected final static org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(MoquiServiceProducer.class)

    protected final MoquiServiceEndpoint moquiServiceEndpoint
    protected final String remaining


    public MoquiServiceProducer(MoquiServiceEndpoint moquiServiceEndpoint, String remaining) {
        super(moquiServiceEndpoint)
        this.moquiServiceEndpoint = moquiServiceEndpoint
        this.remaining = remaining
    }

    public void process(Exchange exchange) throws Exception {
        String serviceName = exchange.getIn().getHeader("ServiceName", this.remaining, String.class)
        //if (serviceName == null) {
        //    throw new RuntimeExchangeException("Missing ServiceName header", exchange)
        //}
        Map parameters = exchange.getIn().getBody(Map.class)

        // logger.warn("TOREMOVE: remaining=[${remaining}], serviceName=${serviceName}, parameters: ${parameters}")

        logger.info("Calling service [${serviceName}] with parameters [${parameters}]")
        Map<String, Object> result = moquiServiceEndpoint.getEcfi().getServiceFacade().sync().name(serviceName)
                .parameters(parameters).call()
        logger.info("Service [${serviceName}] result [${result}]")

        exchange.getOut().setBody(result)
    }
}
