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

import javax.annotation.Nonnull;
import java.util.*;

@SuppressWarnings("unused")
public class SimpleEtl {
    private Extractor extractor;
    private TransformConfiguration internalConfig = null;
    private Loader loader;

    private List<String> messages = new LinkedList<>();
    private Exception extractException = null;
    private List<EtlError> transformErrors = new LinkedList<>();
    private List<EtlError> loadErrors = new LinkedList<>();
    private boolean stopOnError = false;
    private Integer timeout = 3600; // default to one hour

    private int extractCount = 0, skipCount = 0, loadCount = 0;
    private long startTime = 0, endTime = 0;

    public SimpleEtl(@Nonnull Extractor extractor, @Nonnull Loader loader) {
        this.extractor = extractor;
        this.loader = loader;
    }


    /** Call this to add a transformer to run for any type, will be run in order added */
    public SimpleEtl addTransformer(@Nonnull Transformer transformer) {
        if (internalConfig == null) internalConfig = new TransformConfiguration();
        internalConfig.addTransformer(transformer);
        return this;
    }
    /** Call this to add a transformer to run a particular type, which will be run in order for entries of the type */
    public SimpleEtl addTransformer(@Nonnull String type, @Nonnull Transformer transformer) {
        if (internalConfig == null) internalConfig = new TransformConfiguration();
        internalConfig.addTransformer(type, transformer);
        return this;
    }
    /** Add from an external TransformConfiguration, copies configuration to avoid modification */
    public SimpleEtl addConfiguration(TransformConfiguration config) {
        if (internalConfig == null) internalConfig = new TransformConfiguration();
        internalConfig.copyFrom(config);
        return this;
    }
    /** Use an external configuration as-is. Overrides any previous addTransformer() and addConfiguration() calls.
     * Any calls to addTransformer() and addConfiguration() will modify this configuration. */
    public SimpleEtl setConfiguration(TransformConfiguration config) {
        internalConfig = config;
        return this;
    }
    /** Call this to set stop on error flag */
    public SimpleEtl stopOnError() { this.stopOnError = true; return this; }
    /** Set timeout in seconds; passed to Loader.init() for transactions, etc */
    public SimpleEtl setTimeout(Integer timeout) { this.timeout = timeout; return this; }

    /** Call this to process the ETL */
    public SimpleEtl process() {
        startTime = System.currentTimeMillis();
        // initialize loader
        loader.init(timeout);

        try {
            // kick off extraction to process extracted entries
            extractor.extract(this);
        } catch (Exception e) {
            extractException = e;
        } finally {
            // close the loader
            loader.complete(this);
            endTime = System.currentTimeMillis();
        }

        return this;
    }

    public Extractor getExtractor() { return extractor; }
    public Loader getLoader() { return loader; }

    public SimpleEtl addMessage(String msg) { this.messages.add(msg); return this; }
    public List<String> getMessages() { return Collections.unmodifiableList(messages); }
    public int getExtractCount() { return extractCount; }
    public int getSkipCount() { return skipCount; }
    public int getLoadCount() { return loadCount; }
    public long getRunTime() { return endTime - startTime; }

    public Exception getExtractException() { return extractException; }
    public List<EtlError> getTransformErrors() { return Collections.unmodifiableList(transformErrors); }
    public List<EtlError> getLoadErrors() { return Collections.unmodifiableList(loadErrors); }
    public boolean hasError() { return extractException != null || transformErrors.size() > 0 || loadErrors.size() > 0; }
    public Throwable getSingleErrorCause() {
        if (extractException != null) return extractException;
        if (transformErrors.size() > 0) return transformErrors.get(0).error;
        if (loadErrors.size() > 0) return loadErrors.get(0).error;
        return null;
    }

    /**
     * Called by the Extractor to process an extracted entry.
     * @return true if the entry loaded, false otherwise
     * @throws StopException if thrown extraction should stop and return
     */
    public boolean processEntry(Entry extractEntry) throws StopException {
        if (extractEntry == null) return false;
        extractCount++;
        ArrayList<Entry> loadEntries = new ArrayList<>();

        if (internalConfig != null && internalConfig.hasTransformers) {
            EntryTransform entryTransform = new EntryTransform(extractEntry);
            internalConfig.runTransformers(this, entryTransform, loadEntries);
            if (entryTransform.loadCurrent != null ? entryTransform.loadCurrent :
                    entryTransform.newEntries == null || entryTransform.newEntries.size() == 0) {
                loadEntries.add(0, entryTransform.entry);
            } else if (entryTransform.newEntries == null || entryTransform.newEntries.size() == 0) {
                skipCount++;
                return false;
            }
        } else {
            loadEntries.add(extractEntry);
        }

        int loadEntriesSize = loadEntries.size();
        for (int i = 0; i < loadEntriesSize; i++) {
            Entry loadEntry = loadEntries.get(i);
            try {
                loader.load(loadEntry);
                loadCount++;
            } catch (Throwable t) {
                loadErrors.add(new EtlError(loadEntry, t));
                if (stopOnError) throw new StopException(t);
                return false;
            }
        }
        return true;
    }

    public static class TransformConfiguration {
        private ArrayList<Transformer> anyTransformers = new ArrayList<>();
        private int anyTransformerSize = 0;
        private LinkedHashMap<String, ArrayList<Transformer>> typeTransformers = new LinkedHashMap<>();
        boolean hasTransformers = false;

        public TransformConfiguration() { }

        /** Call this to add a transformer to run for any type, which will be run in order */
        public TransformConfiguration addTransformer(@Nonnull Transformer transformer) {
            anyTransformers.add(transformer);
            anyTransformerSize = anyTransformers.size();
            hasTransformers = true;
            return this;
        }
        /** Call this to add a transformer to run a particular type, which will be run in order for entries of the type */
        public TransformConfiguration addTransformer(@Nonnull String type, @Nonnull Transformer transformer) {
            typeTransformers.computeIfAbsent(type, k -> new ArrayList<>()).add(transformer);
            hasTransformers = true;
            return this;
        }

        // returns true to skip the entry (or remove from load list)
        void runTransformers(SimpleEtl etl, EntryTransform entryTransform, ArrayList<Entry> loadEntries) throws StopException {
            for (int i = 0; i < anyTransformerSize; i++) {
                transformEntry(etl, anyTransformers.get(i), entryTransform);
            }
            String curType = entryTransform.entry.getEtlType();
            if (curType != null && !curType.isEmpty()) {
                ArrayList<Transformer> curTypeTrans = typeTransformers.get(curType);
                int curTypeTransSize = curTypeTrans != null ? curTypeTrans.size() : 0;
                for (int i = 0; i < curTypeTransSize; i++) {
                    transformEntry(etl, curTypeTrans.get(i), entryTransform);
                }
            }
            // handle new entries, run transforms then add to load list if not skipped
            int newEntriesSize = entryTransform.newEntries != null ? entryTransform.newEntries.size() : 0;
            for (int i = 0; i < newEntriesSize; i++) {
                Entry newEntry = entryTransform.newEntries.get(i);
                if (newEntry == null) continue;

                EntryTransform newTransform = new EntryTransform(newEntry);
                runTransformers(etl, newTransform, loadEntries);
                if (newTransform.loadCurrent != null ? newTransform.loadCurrent : newTransform.newEntries == null || newTransform.newEntries.size() == 0) {
                    loadEntries.add(newEntry);
                }
            }
        }
        // internal method, returns true to skip entry (or remove from load list)
        void transformEntry(SimpleEtl etl, Transformer transformer, EntryTransform entryTransform) throws StopException {
            try {
                transformer.transform(entryTransform);
            } catch (Throwable t) {
                etl.transformErrors.add(new EtlError(entryTransform.entry, t));
                if (etl.stopOnError) throw new StopException(t);
                entryTransform.loadCurrent(false);
            }
        }

        void copyFrom(TransformConfiguration conf) {
            if (conf == null) return;
            anyTransformers.addAll(conf.anyTransformers);
            anyTransformerSize = anyTransformers.size();
            for (Map.Entry<String, ArrayList<Transformer>> entry : conf.typeTransformers.entrySet()) {
                typeTransformers.computeIfAbsent(entry.getKey(), k -> new ArrayList<>()).addAll(entry.getValue());
            }
        }
    }

    public static class StopException extends Exception {
        public StopException(Throwable t) { super(t); }
    }

    public static class EtlError {
        public final Entry entry;
        public final Throwable error;
        EtlError(Entry entry, Throwable t) { this.entry = entry; this.error = t; }
    }

    public interface Entry {
        String getEtlType();
        Map<String, Object> getEtlValues();
    }
    public static class SimpleEntry implements Entry {
        public final String type;
        public final Map<String, Object> values;
        public SimpleEntry(String type, Map<String, Object> values) { this.type = type; this.values = values; }
        @Override public String getEtlType() { return type; }
        @Override public Map<String, Object> getEtlValues() { return values; }
        // TODO: add equals and hash overrides
    }
    public static class EntryTransform {
        final Entry entry;
        ArrayList<Entry> newEntries = null;
        Boolean loadCurrent = null;
        EntryTransform(Entry entry) { this.entry = entry; }
        /** Get the current entry to get type and get/put values as needed */
        public Entry getEntry() { return entry; }
        /** By default the current entry is loaded only if no new entries are added; set to false to not load even if no entries are
         * added (filter); set to true to load even if no new entries are added */
        public EntryTransform loadCurrent(boolean load) { loadCurrent = load; return this; }
        /** Add a new entry to be transformed and if not filtered then loaded */
        public EntryTransform addEntry(Entry newEntry) {
            if (newEntries == null) newEntries = new ArrayList<>();
            newEntries.add(newEntry);
            return this;
        }
    }

    public interface Extractor {
        /** Called once to start processing, should call etl.processEntry() for each entry and close itself once finished */
        void extract(SimpleEtl etl) throws Exception;
    }
    /** Stateless ETL entry transformer and filter interface */
    public interface Transformer {
        /** Call methods on EntryTransform to add new entries (generally with different types), modify the current entry's values, or filter the entry. */
        void transform(EntryTransform entryTransform) throws Exception;
    }
    public interface Loader {
        /** Called before SimpleEtl processing begins */
        void init(Integer timeout);
        /** Load a single, optionally transformed, entry into the data destination */
        void load(Entry entry) throws Exception;
        /** Called after all entries processed to close files, commit/rollback transactions, etc;  */
        void complete(SimpleEtl etl);
    }
}
