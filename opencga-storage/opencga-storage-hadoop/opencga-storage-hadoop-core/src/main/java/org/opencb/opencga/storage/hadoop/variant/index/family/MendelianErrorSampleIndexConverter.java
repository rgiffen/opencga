package org.opencb.opencga.storage.hadoop.variant.index.family;

import org.apache.commons.lang.StringUtils;
import org.apache.hadoop.hbase.util.Bytes;
import org.opencb.biodata.models.variant.Variant;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ListIterator;
import java.util.NoSuchElementException;

import static org.opencb.opencga.storage.hadoop.variant.index.sample.SampleIndexVariantBiConverter.*;

/**
 * Created on 11/04/19.
 *
 * @author Jacobo Coll &lt;jacobo167@gmail.com&gt;
 */
public class MendelianErrorSampleIndexConverter {

    protected static final byte SEPARATOR = ',';
    protected static final byte MENDELIAN_ERROR_SEPARATOR = '_';
    protected static final byte MENDELIAN_ERROR_CODE_SEPARATOR = ':'; // optional

    public static void toBytes(ByteArrayOutputStream stream, Variant variant, String gt, int gtIdx, int errorCode) throws IOException {
        if (stream.size() != 0) {
            stream.write(SEPARATOR);
        }
        stream.write(Bytes.toBytes(variant.toString()));
        stream.write(MENDELIAN_ERROR_SEPARATOR);
        stream.write(Bytes.toBytes(gt));
        stream.write(MENDELIAN_ERROR_SEPARATOR);
        stream.write(Bytes.toBytes(Integer.toString(gtIdx)));
        stream.write(MENDELIAN_ERROR_CODE_SEPARATOR);
        stream.write(Bytes.toBytes(Integer.toString(errorCode)));
    }

    public static MendelianErrorSampleIndexVariantIterator toVariants(byte[] bytes, int offset, int length) {
        return new MendelianErrorSampleIndexVariantIterator(bytes, offset, length);
    }

    public static class MendelianErrorSampleIndexVariantIterator implements SampleIndexVariantIterator {
        private final ListIterator<String> variants;
        private Variant next;
        private int nextIndex;
        private String nextGt;

        public MendelianErrorSampleIndexVariantIterator(byte[] value, int offset, int length) {
            variants = split(value, offset, length).listIterator();
        }

        @Override
        public int nextIndex() {
            if (next == null) {
                fetchNext();
            }
            return nextIndex;
        }

        public String nextGenotype() {
            if (next == null) {
                fetchNext();
            }
            return nextGt;
        }

        @Override
        public boolean hasNext() {
            return variants.hasNext();
        }

        @Override
        public void skip() {
            next();
        }

        @Override
        public Variant next() {
            if (next == null) {
                fetchNext();
            }
            if (next == null) {
                throw new NoSuchElementException();
            }
            Variant variant = next;
            next = null; // Clean next variant
            return variant;
        }

        private void fetchNext() {
            if (variants.hasNext()) {
                String s = variants.next();
                int idx2 = s.lastIndexOf(MENDELIAN_ERROR_SEPARATOR);
                int idx1 = s.lastIndexOf(MENDELIAN_ERROR_SEPARATOR, idx2 - 1);
                String variantStr = s.substring(0, idx1);
                nextGt = s.substring(idx1 + 1, idx2);
                String idxCode = s.substring(idx2 + 1);
                int i = idxCode.lastIndexOf(MENDELIAN_ERROR_CODE_SEPARATOR);
                nextIndex = i == StringUtils.INDEX_NOT_FOUND
                        ? Integer.valueOf(idxCode)
                        : Integer.valueOf(idxCode.substring(0, i));
                next = new Variant(variantStr);
            } else {
                next = null;
                nextGt = null;
                nextIndex = -1;
            }
        }


    }

}
