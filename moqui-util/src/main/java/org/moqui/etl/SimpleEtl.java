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
    /** Call this to set stop on error flag */
    public SimpleEtl stopOnError() { this.stopOnError = true; return this; }
    /** Set timeout in seconds; passed to Loader.init() for transactions, etc */
    public SimpleEtl setTimeout(Integer timeout) { this.timeout = timeout; return this; }

    /** Call this to process the ETL */
    public SimpleEtl process() {
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
        }

        return this;
    }

    public Extractor getExtractor() { return extractor; }
    public Loader getLoader() { return loader; }

    public SimpleEtl addMessage(String msg) { this.messages.add(msg); return this; }
    public List<String> getMessages() { return Collections.unmodifiableList(messages); }
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
        ArrayList<Entry> loadEntries = new ArrayList<>();

        if (internalConfig != null && internalConfig.hasTransformers) {
            boolean skip = internalConfig.runTransformers(this, extractEntry, loadEntries);
            if (skip) return false;
        }

        int loadEntriesSize = loadEntries.size();
        if (loadEntriesSize == 0) {
            loadEntries.add(extractEntry);
            loadEntriesSize = 1;
        }
        for (int i = 0; i < loadEntriesSize; i++) {
            Entry loadEntry = loadEntries.get(i);
            try {
                loader.load(loadEntry);
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
        private HashMap<String, ArrayList<Transformer>> typeTransformers = new HashMap<>();
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
        boolean runTransformers(SimpleEtl etl, Entry curEntry, ArrayList<Entry> loadEntries) throws StopException {
            if (curEntry == null) return true;
            for (int i = 0; i < anyTransformerSize; i++) {
                boolean skip = transformEntry(etl, anyTransformers.get(i), curEntry, loadEntries);
                if (skip) return true;
            }
            String curType = curEntry.getEtlType();
            if (curType != null && !curType.isEmpty()) {
                ArrayList<Transformer> curTypeTrans = typeTransformers.get(curType);
                int curTypeTransSize = curTypeTrans != null ? curTypeTrans.size() : 0;
                for (int i = 0; i < curTypeTransSize; i++) {
                    boolean skip = transformEntry(etl, curTypeTrans.get(i), curEntry, loadEntries);
                    if (skip) return true;
                }
            }
            return false;
        }
        // internal method, returns true to skip entry (or remove from load list)
        boolean transformEntry(SimpleEtl etl, Transformer transformer, Entry entry, ArrayList<Entry> loadEntries) throws StopException {
            try {
                if (transformer.filter(entry)) return true;
                ArrayList<Entry> newEntries = transformer.transform(entry);
                if (newEntries != null) {
                    // handle new entries, run transforms then add to load list if not skipped
                    int newEntriesSize = newEntries.size();
                    for (int i = 0; i < newEntriesSize; i++) {
                        Entry newEntry = newEntries.get(i);
                        if (newEntry == null) continue;
                        boolean skip = runTransformers(etl, newEntry, loadEntries);
                        if (!skip) loadEntries.add(newEntry);
                    }
                }
            } catch (Throwable t) {
                etl.transformErrors.add(new EtlError(entry, t));
                if (etl.stopOnError) throw new StopException(t);
                return true;
            }
            return false;
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
        private final String type;
        private final Map<String, Object> values;
        public SimpleEntry(String type, Map<String, Object> values) { this.type = type; this.values = values; }
        @Override public String getEtlType() { return type; }
        @Override public Map<String, Object> getEtlValues() { return values; }
        // TODO: add equals and hash overrides
    }

    public interface Extractor {
        /** Called once to start processing, should call etl.processEntry() for each entry and close itself once finished */
        void extract(SimpleEtl etl) throws Exception;
    }
    /** Stateless ETL entry transformer and filter */
    public interface Transformer {
        /** Return true to skip the record. This method must be implemented so always return false to skip filtering. */
        boolean filter(Entry entry);
        /** Transform the entry as needed (modify passed entry) and/or optionally return an ArrayList with one or more new entries;
         * To skip transform (filter only) just return null */
        ArrayList<Entry> transform(Entry entry) throws Exception;
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
