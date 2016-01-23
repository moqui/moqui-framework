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

import org.apache.camel.impl.DefaultConsumer
import org.apache.camel.Processor
import org.moqui.impl.service.ServiceDefinition
import org.apache.camel.Exchange
import org.apache.camel.RuntimeCamelException
import org.apache.camel.Message

class MoquiServiceConsumer extends DefaultConsumer {
    protected final static org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(MoquiServiceConsumer.class)

    protected final MoquiServiceEndpoint moquiServiceEndpoint
    protected final String remaining


    MoquiServiceConsumer(MoquiServiceEndpoint moquiServiceEndpoint, Processor processor, String remaining) {
        super(moquiServiceEndpoint, processor)
        this.moquiServiceEndpoint = moquiServiceEndpoint
        this.remaining = remaining

        moquiServiceEndpoint.getEcfi().registerCamelConsumer(moquiServiceEndpoint.getEndpointUri(), this)
    }

    Map<String, Object> process(ServiceDefinition sd, Map<String, Object> parameters) {
        try {
            Exchange exchange = getEndpoint().createExchange()

            // populate exchange.in
            exchange.in.setBody(parameters)

            try {
                getProcessor().process(exchange)
            } catch (Exception e) {
                exchange.setException(e)
            }

            // get response (exchange.out)
            Map<String, Object> result = exchange.out.getBody(Map.class)
            return result
        } catch (Exception e) {
            throw new RuntimeCamelException("Cannot process request", e);
        }
    }
}
