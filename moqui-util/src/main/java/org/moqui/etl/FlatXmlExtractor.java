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
package org.moqui.etl;

import org.moqui.BaseException;
import org.moqui.resource.ResourceReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.Locator;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.parsers.SAXParserFactory;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;

public class FlatXmlExtractor implements SimpleEtl.Extractor {
    protected final static Logger logger = LoggerFactory.getLogger(FlatXmlExtractor.class);

    SimpleEtl etl = null;
    private ResourceReference resourceRef;

    FlatXmlExtractor(ResourceReference xmlRef) { resourceRef = xmlRef; }

    @Override
    public void extract(SimpleEtl etl) throws Exception {
        this.etl = etl;

        if (resourceRef == null || !resourceRef.getExists()) {
            logger.warn("Resource does not exist, not extracting data from " + (resourceRef != null ? resourceRef.getLocation() : "[null ResourceReference]"));
            return;
        }
        InputStream is = resourceRef.openStream();
        if (is == null) return;

        try {
            FlatXmlHandler xmlHandler = new FlatXmlHandler(this);
            XMLReader reader = SAXParserFactory.newInstance().newSAXParser().getXMLReader();
            reader.setContentHandler(xmlHandler);
            reader.parse(new InputSource(is));
        } catch (Exception e) {
            throw new BaseException("Error parsing XML from " + resourceRef.getLocation(), e);
        } finally {
            try { is.close(); }
            catch (IOException e) { logger.error("Error closing XML stream from " + resourceRef.getLocation(), e); }
        }

    }

    private static class FlatXmlHandler extends DefaultHandler {
        Locator locator = null;
        FlatXmlExtractor extractor;

        String rootName = null;
        SimpleEtl.SimpleEntry curEntry = null;
        String curTextName = null;
        StringBuilder curText = null;
        private boolean stopParse = false;

        FlatXmlHandler(FlatXmlExtractor fxe) { extractor = fxe; }

        @Override
        public void startElement(String ns, String localName, String qName, Attributes attributes) {
            if (stopParse) return;
            if (rootName == null) {
                rootName = qName;
                return;
            }

            if (curEntry == null) {
                curEntry = new SimpleEtl.SimpleEntry(qName, new HashMap<>());
                int length = attributes.getLength();
                for (int i = 0; i < length; i++) {
                    String name = attributes.getLocalName(i);
                    String value = attributes.getValue(i);
                    if (name == null || name.length() == 0) name = attributes.getQName(i);
                    curEntry.values.put(name, value);
                }
            } else {
                curTextName = qName;
                curText = new StringBuilder();
            }
        }

        @Override
        public void characters(char[] chars, int offset, int length) {
            if (stopParse) return;

            if (curText == null) curText = new StringBuilder();
            curText.append(chars, offset, length);
        }
        @Override
        public void endElement(String ns, String localName, String qName) {
            if (stopParse) return;

            if (curEntry == null) {
                // should be the root element in a flat record file
                if (rootName != null && rootName.equals(qName)) rootName = null;
                return;
            }

            if (curTextName != null) {
                curEntry.values.put(curTextName, curText.toString());
                curTextName = null;
                curText = null;
                return;
            }

            if (!qName.equals(curEntry.type)) throw new IllegalStateException("Invalid close element " + qName + ", was expecting " + curEntry.type);

            try {
                extractor.etl.processEntry(curEntry);
            } catch (SimpleEtl.StopException e) {
                logger.warn("Got StopException", e);
                stopParse = true;
            }

            curEntry = null;
            curTextName = null;
            curText = null;
        }

        @Override
        public void setDocumentLocator(Locator locator) { this.locator = locator; }
    }
}
