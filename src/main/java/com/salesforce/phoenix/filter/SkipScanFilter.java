/*******************************************************************************
 * Copyright (c) 2013, Salesforce.com, Inc.
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 *     Redistributions of source code must retain the above copyright notice,
 *     this list of conditions and the following disclaimer.
 *     Redistributions in binary form must reproduce the above copyright notice,
 *     this list of conditions and the following disclaimer in the documentation
 *     and/or other materials provided with the distribution.
 *     Neither the name of Salesforce.com nor the names of its contributors may
 *     be used to endorse or promote products derived from this software without
 *     specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 ******************************************************************************/
package com.salesforce.phoenix.filter;

import java.io.*;
import java.util.Arrays;
import java.util.List;

import org.apache.hadoop.hbase.*;
import org.apache.hadoop.hbase.KeyValue.Type;
import org.apache.hadoop.hbase.filter.FilterBase;
import org.apache.hadoop.hbase.io.ImmutableBytesWritable;
import org.apache.hadoop.hbase.util.Bytes;

import com.google.common.base.Objects;
import com.google.common.collect.Lists;
import com.google.common.hash.*;
import com.salesforce.phoenix.compile.ScanRanges;
import com.salesforce.phoenix.query.KeyRange;
import com.salesforce.phoenix.query.KeyRange.Bound;
import com.salesforce.phoenix.schema.*;
import com.salesforce.phoenix.schema.ValueSchema.Field;
import com.salesforce.phoenix.util.ByteUtil;
import com.salesforce.phoenix.util.ScanUtil;


/**
 * 
 * Filter that seeks based on CNF containing anded and ored key ranges
 * 
 * TODO: figure out when to reset/not reset position array
 *
 * @author ryang, jtaylor
 * @since 0.1
 */
public class SkipScanFilter extends FilterBase {
    // Conjunctive normal form of or-ed ranges or point lookups
    private List<List<KeyRange>> slots;
    // schema of the row key
    private RowKeySchema schema;
    // current position for each slot
    private int[] position;
    // buffer used for skip hint
    private int maxKeyLength;
    private byte[] startKey;
    private int startKeyLength;
    private byte[] endKey; 
    private int endKeyLength;

    private final ImmutableBytesWritable ptr = new ImmutableBytesWritable();

    /**
     * We know that initially the first row will be positioned at or 
     * after the first possible key.
     */
    public SkipScanFilter() {
    }

    public SkipScanFilter(List<List<KeyRange>> slots, RowKeySchema schema) {
        int maxKeyLength = getTerminatorCount(schema);
        for (List<KeyRange> slot : slots) {
            int maxSlotLength = 0;
            for (KeyRange range : slot) {
                int maxRangeLength = Math.max(range.getLowerRange().length, range.getUpperRange().length);
                if (maxSlotLength < maxRangeLength) {
                    maxSlotLength = maxRangeLength;
                }
            }
            maxKeyLength += maxSlotLength;
        }
        init(slots, schema, maxKeyLength);
    }

    public SkipScanFilter(SkipScanFilter other) {
        init(other.slots, other.schema, other.maxKeyLength);
    }

    private void init(List<List<KeyRange>> slots, RowKeySchema schema, int maxKeyLength) {
        this.slots = slots;
        this.schema = schema;
        this.maxKeyLength = maxKeyLength;
        this.position = new int[slots.size()];
        startKey = new byte[maxKeyLength];
        setStartKey();
        endKey = new byte[maxKeyLength];
        endKeyLength = 0;
        // Start key for the scan will initially be set to start at the right place
        // We just need to set the end key for when we need to calculate the next skip hint
    }

    @Override
    public boolean filterAllRemaining() {
        return startKey == null && endKeyLength == 0;
    }

    @Override
    public ReturnCode filterKeyValue(KeyValue kv) {
        return navigate(kv.getBuffer(), kv.getRowOffset(),kv.getRowLength());
    }

    @Override
    public KeyValue getNextKeyHint(KeyValue kv) {
        // TODO: don't allocate new key value every time here if possible
        return startKey == null ? null : new KeyValue(startKey, 0, startKeyLength,
                null, 0, 0, null, 0, 0, HConstants.LATEST_TIMESTAMP, Type.Maximum, null, 0, 0);
    }

    /**
     * Intersect the ranges of this filter with the ranges form by lowerInclusive and upperInclusive
     * key and filter out the ones that are not included in the region. Return the new slots.
     */
    public void intersect(byte[] lowerInclusiveKey, byte[] upperExclusiveKey) {
        ImmutableBytesWritable lowerPtr = new ImmutableBytesWritable();
        ImmutableBytesWritable upperPtr = new ImmutableBytesWritable();
        ImmutableBytesWritable lower = lowerPtr, upper = upperPtr;
        lowerPtr.set(lowerInclusiveKey, 0, lowerInclusiveKey.length);
        if (schema.first(lowerPtr, 0, ValueBitSet.EMPTY_VALUE_BITSET) == null) {
            lower = ScanRanges.UNBOUND;
        }
        upperPtr.set(upperExclusiveKey, 0, upperExclusiveKey.length);
        if (schema.first(upperPtr, 0, ValueBitSet.EMPTY_VALUE_BITSET) == null) {
            upper = ScanRanges.UNBOUND;
        }
        
        List<List<KeyRange>> newSlots = Lists.newArrayListWithCapacity(slots.size());
        int i = 0;
        int nSlots = slots.size();
        while (true) {
            // Search to the slot whose upper bound of is closest bigger or equal to our lower bound.
            position[i] = ScanUtil.searchClosestKeyRangeWithUpperHigherThanLowerPtr(slots.get(i), lower);
            if (position[i] >= slots.get(i).size()) {
                // The lower key of the intersect range is higher than the last range of the current slot.
                // No intersection with the slots is possible. This should not happen.
                // TODO:: Should warn in this case.
                slots = ScanRanges.NOTHING.getRanges();
                return;
            } else if (slots.get(i).get(position[i]).compareLowerToUpperBound(upper, i < nSlots - 1) > 0) {
                // Out upper key is less than the lower range of the current position in the current slot.
                // No intersection with the slots is possible. Again, this should not happen.
                slots = ScanRanges.NOTHING.getRanges();
                return;
            } else { 
                // We are in range, linear search to the range whose lower bound is bigger than our
                // upper bound. That would be the subset of slots that have intersection with our range.
                int end = position[i] + 1;
                while (end < slots.get(i).size() &&
                        (slots.get(i).get(end).compareLowerToUpperBound(upper, i < nSlots - 1)) <= 0) {
                    end++;
                }
                List<KeyRange> newSlot = Lists.newArrayListWithCapacity(end - position[i]);
                for (int idx = position[i]; idx < end; idx++) {
                    newSlot.add(slots.get(i).get(idx));
                }
                newSlots.add(newSlot);
                i++;
                if (i >= nSlots) { // done.
                    break;
                }
                // Move to the next part of the key
                if (lower != ScanRanges.UNBOUND) {
                    if (schema.next(lowerPtr, i, lowerInclusiveKey.length, ValueBitSet.EMPTY_VALUE_BITSET) == null) {
                        // If no more lower key parts, then we have no constraint for that part of the key,
                        // so we use unbound lower from here on out.
                        lower = ScanRanges.UNBOUND;
                    } else {
                        lower = lowerPtr;
                    }
                }
                if (upper != ScanRanges.UNBOUND) {
                    if (schema.next(upperPtr, i, upperExclusiveKey.length, ValueBitSet.EMPTY_VALUE_BITSET) == null) {
                        // If no more upper key parts, then we have no constraint for that part of the key,
                        // so we use unbound upper from here on out.
                        upper = ScanRanges.UNBOUND;
                    } else {
                        upper = upperPtr;
                    }
                }
            }
        }
        slots = newSlots;
    }

    private ReturnCode navigate(final byte[] currentKey, int offset, int length) {
        int nSlots = slots.size();
        // First check to see if we're in-range until we reach our end key
        if (endKeyLength > 0) {
            if (Bytes.compareTo(currentKey, offset, length, endKey, 0, endKeyLength) < 0) {
                return ReturnCode.INCLUDE;
            }

            // If key range of last slot is a single key, we can increment our position
            // since we know we'll be past the current row after including it.
            if (slots.get(nSlots-1).get(position[nSlots-1]).isSingleKey()) {
                if (incrementKey(nSlots-1) < 0) {
                    // Current row will be included, but we have no more
                    startKey = null;
                }
            }
        }
        endKeyLength = 0;
        
        // We could have included the previous
        if (startKey == null) {
            return ReturnCode.NEXT_ROW;
        }

        int i = 0;
        final int originalOffset = offset;
        final int originalLength = length;
        boolean seek = false;
        int earliestRangeIndex = nSlots-1;
        ptr.set(currentKey, offset, length);
        schema.first(ptr, i, ValueBitSet.EMPTY_VALUE_BITSET);
        while (true) {
            // Increment to the next range while the upper bound of our current slot is less than our current key
            while (position[i] < slots.get(i).size() && slots.get(i).get(position[i]).compareUpperToLowerBound(ptr) < 0) {
                position[i]++;
            }
            Arrays.fill(position, i+1, position.length, 0);
            if (position[i] >= slots.get(i).size()) {
                // Our current key is bigger than the last range of the current slot.
                // Backtrack and increment the key of the previous slot values.
                if (i == 0) {
                    startKey = null;
                    return ReturnCode.NEXT_ROW;
                }
                // Increment key and backtrack until in range. We know at this point that we'll be
                // issuing a seek next hint.
                seek = true;
                Arrays.fill(position, i, position.length, 0);
                i--;
                // If we're positioned at a single key, no need to copy the current key and get the next key .
                // Instead, just increment to the next key and continue.
                boolean incremented = false;
                while (i >= 0 && slots.get(i).get(position[i]).isSingleKey() && (incremented=true) && (position[i] = (position[i] + 1) % slots.get(i).size()) == 0) {
                    i--;
                    incremented = false;
                }
                if (i < 0) {
                    startKey = null;
                    return ReturnCode.NEXT_ROW;
                }
                if (incremented) {
                    // Continue the loop after setting the start key, because our start key maybe smaller than
                    // the current key, so we'll end up incrementing the start key until it's bigger than the
                    // current key.
                    setStartKey();
                    ptr.set(ptr.get(), offset, length);
                    // Reinitialize iterator to be positioned at previous slot position
                    schema.setAccessor(ptr, i, ValueBitSet.EMPTY_VALUE_BITSET);
                } else {
                    // Copy the leading part of the actual current key into startKey which we'll use
                    // as our buffer through the rest of the loop.
                    startKey = copyKey(startKey, ptr.getOffset() - offset + this.maxKeyLength, ptr.get(), offset, ptr.getOffset() - offset);
                    int nextKeyLength = ptr.getOffset() - offset;
                    startKeyLength = ptr.getOffset() - offset;
                    appendToStartKey(i+1, ptr.getOffset() - offset);
                    // From here on, we use startKey as our buffer with offset reset to 0
                    // We've copied the part of the current key above that we need into startKey
                    offset = 0;
                    length = startKeyLength;
                    ptr.set(startKey, offset, startKeyLength);
                    // Reinitialize iterator to be positioned at previous slot position
                    // TODO: a schema.previous would potentially be more efficient
                    schema.setAccessor(ptr, i, ValueBitSet.EMPTY_VALUE_BITSET);
                    // Do nextKey after setting the accessor b/c otherwise the null byte may have
                    // been incremented causing us not to find it
                    ByteUtil.nextKey(startKey, nextKeyLength);
                }
            } else if (slots.get(i).get(position[i]).compareLowerToUpperBound(ptr) > 0) {
                // Our current key is less than the lower range of the current position in the current slot.
                // Seek to the lower range, since it's bigger than the current key
                int currentLength = ptr.getOffset() - offset;
                setStartKey(currentLength + this.maxKeyLength, ptr.get(), offset, currentLength);
                appendToStartKey(i, currentLength);
                Arrays.fill(position, earliestRangeIndex+1, position.length, 0);
                return ReturnCode.SEEK_NEXT_USING_HINT;
            } else { // We're in range, check the next slot
                if (!slots.get(i).get(position[i]).isSingleKey() && i < earliestRangeIndex) {
                    earliestRangeIndex = i;
                }
                i++;
                // If we're past the last slot or we know we're seeking to the next (in
                // which case the previously updated slot was verified to be within the
                // range, so we don't need to check the rest of the slots. If we were
                // to check the rest of the slots, we'd get into trouble because we may
                // have a null byte that was incremented which screws up our schema.next call)
                if (i >= nSlots || seek) {
                    break;
                }
                // We should never not be able to find the value at a given slot. This would mean
                // we have an invalid key or we manufactured an invalid seek hint. Either way,
                // better if we just throw, otherwise we'll end up in an infinite loop.
                if (schema.next(ptr, i, offset + length, ValueBitSet.EMPTY_VALUE_BITSET) == null) {
                    throw new IllegalStateException("Unexpected key structure for " + 
                            Bytes.toStringBinary(currentKey, originalOffset,originalLength) + " with slots " + slots);
                }
            }
        }
            
        if (seek) {
            return ReturnCode.SEEK_NEXT_USING_HINT;
        }
        // Else, we're in range for all slots and can include this row plus all rows 
        // up to the upper range of our last slot. We do this for ranges and single keys
        // since we potentially have multiple key values for the same row key.
        setEndKey(ptr.getOffset() - offset + this.maxKeyLength, ptr.get(), offset, ptr.getOffset() - offset);
        appendToEndKey(nSlots-1, ptr.getOffset() - offset);
        if (!slots.get(nSlots-1).get(position[nSlots-1]).isSingleKey()) {
            // Reset the positions to zero from the next slot after the earliest ranged slot, since the
            // next key could be bigger at this ranged slot, and smaller than the current position of
            // less significant slots.
            Arrays.fill(position, earliestRangeIndex+1, position.length, 0);
        }
        return ReturnCode.INCLUDE;
    }

    private int incrementKey(int i) {
        while (i >= 0 && slots.get(i).get(position[i]).isSingleKey() && (position[i] = (position[i] + 1) % slots.get(i).size()) == 0) {
            i--;
        }
        return i;
    }

    private static byte[] copyKey(byte[] targetKey, int targetLength, byte[] sourceKey, int offset, int length) {
        if (targetLength > targetKey.length) {
            targetKey = new byte[targetLength];
        }
        System.arraycopy(sourceKey, offset, targetKey, 0, length);
        return targetKey;
    }

    private void setStartKey(int maxLength, byte[] sourceKey, int offset, int length) {
        startKey = copyKey(startKey, maxLength, sourceKey, offset, length);
        startKeyLength = length;
    }

    private void setEndKey(int maxLength, byte[] sourceKey, int offset, int length) {
        endKey = copyKey(endKey, maxLength, sourceKey, offset, length);
        endKeyLength = length;
    }

    private int setKey(Bound bound, byte[] key, int keyOffset, int slotStartIndex) {
        return setKey(bound, key, keyOffset, slotStartIndex, position.length);
    }

    private int setKey(Bound bound, byte[] key, int keyOffset, int slotStartIndex, int slotEndIndex) {
        return setKey(bound, position, key, keyOffset, slotStartIndex, slotEndIndex);
    }

    private int setKey(Bound bound, int[] position, byte[] key, int keyOffset, 
            int slotStartIndex, int slotEndIndex) {
        return ScanUtil.setKey(schema, slots, position, bound, key, keyOffset, slotStartIndex, slotEndIndex);
    }

    private void setStartKey() {
        startKeyLength = setKey(Bound.LOWER, startKey, 0, 0);
    }

    private void appendToStartKey(int slotIndex, int byteOffset) {
        startKeyLength += setKey(Bound.LOWER, startKey, byteOffset, slotIndex);
    }

    private void appendToEndKey(int slotIndex, int byteOffset) {
        endKeyLength += setKey(Bound.UPPER, endKey, byteOffset, slotIndex);
    }

    private int getTerminatorCount(RowKeySchema schema) {
        int nTerminators = 0;
        for (int i = 0; i < schema.getFieldCount(); i++) {
            Field field = schema.getField(i);
            // We won't have a terminator on the last PK column
            // unless it is variable length and exclusive, but
            // having the extra byte irregardless won't hurt anything
            if (!field.getType().isFixedWidth()) {
                nTerminators++;
            }
        }
        return nTerminators;
    }

    @Override
    public void readFields(DataInput in) throws IOException {
        RowKeySchema schema = new RowKeySchema();
        schema.readFields(in);
        int maxLength = getTerminatorCount(schema);
        int andLen = in.readInt();
        List<List<KeyRange>> slots = Lists.newArrayListWithExpectedSize(andLen);
        for (int i=0; i<andLen; i++) {
            int orlen = in.readInt();
            List<KeyRange> orclause = Lists.newArrayListWithExpectedSize(orlen);
            slots.add(orclause);
            int maxSlotLength = 0;
            for (int j=0; j<orlen; j++) {
                KeyRange range = new KeyRange();
                range.readFields(in);
                if (range.getLowerRange().length > maxSlotLength) {
                    maxSlotLength = range.getLowerRange().length;
                }
                if (range.getUpperRange().length > maxSlotLength) {
                    maxSlotLength = range.getUpperRange().length;
                }
                orclause.add(range);
            }
            maxLength += maxSlotLength;
        }
        this.init(slots, schema, maxLength);
    }

    @Override
    public void write(DataOutput out) throws IOException {
        schema.write(out);
        out.writeInt(slots.size());
        for (List<KeyRange> orclause : slots) {
            out.writeInt(orclause.size());
            for (KeyRange range : orclause) {
                range.write(out);
            }
        }
    }

    @Override
    public int hashCode() {
        HashFunction hf = Hashing.goodFastHash(32);
        Hasher h = hf.newHasher();
        h.putInt(slots.size());
        for (int i=0; i<slots.size(); i++) {
            h.putInt(slots.get(i).size());
            for (int j=0; j<slots.size(); j++) {
                h.putBytes(slots.get(i).get(j).getLowerRange());
                h.putBytes(slots.get(i).get(j).getUpperRange());
            }
        }
        return h.hash().asInt();
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof SkipScanFilter)) return false;
        SkipScanFilter other = (SkipScanFilter)obj;
        return Objects.equal(slots, other.slots) && Objects.equal(schema, other.schema);
    }

    @Override
    public String toString() {
        // TODO: make static util methods in ExplainTable that use type to print
        // key ranges
        return "SkipScanFilter "+ slots.toString() ;
    }
}
