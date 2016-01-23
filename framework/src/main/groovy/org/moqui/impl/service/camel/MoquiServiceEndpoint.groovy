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

import org.apache.camel.Producer
import org.apache.camel.Consumer
import org.apache.camel.Processor
import org.apache.camel.impl.DefaultEndpoint

import org.moqui.impl.context.ExecutionContextFactoryImpl

class MoquiServiceEndpoint extends DefaultEndpoint {
    protected String remaining
    protected ExecutionContextFactoryImpl ecfi

    public MoquiServiceEndpoint(String uri, MoquiServiceComponent component, String remaining) {
        super(uri, component)
        this.remaining = remaining
        this.ecfi = component.getEcfi()
    }

    public Producer createProducer() throws Exception {
        return new MoquiServiceProducer(this, remaining)
    }

    public Consumer createConsumer(Processor processor) throws Exception {
        return new MoquiServiceConsumer(this, processor, remaining)
    }

    public boolean isSingleton() { return true }
    public ExecutionContextFactoryImpl getEcfi() { return ecfi }
}
