package orestes.bloomfilter.memory;

import java.util.BitSet;

import orestes.bloomfilter.BloomFilter;
import orestes.bloomfilter.CountingBloomFilter;
import orestes.bloomfilter.FilterBuilder;

public class CountingBloomFilterMemory<T> extends CountingBloomFilter<T> {
    private FilterBuilder config;
    private BloomFilterMemory<T> filter;
    private BitSet counts;
    private Runnable overflowHandler = new Runnable() {
        @Override
        public void run() {
        }
    };

    public CountingBloomFilterMemory(FilterBuilder config) {
        config.complete();
        this.config = config;
        this.filter = new BloomFilterMemory<>(config.clone());
        this.counts = new BitSet(config.size() * config().countingBits());
    }

    @Override
    public boolean contains(byte[] element) {
        return filter.contains(element);
    }

    @Override
    public synchronized long addAndEstimateCount(byte[] element) {
        int[] hashes = hash(element);
        long min = Long.MAX_VALUE;
        for (int hash : hashes) {
            filter.setBit(hash, true);
            long inc = increment(hash);
            min = (min >= inc ? inc : min);
        }
        return min;
    }

    @Override
    public synchronized long removeAndEstimateCount(byte[] element) {
        if (!contains(element)) return 0;

        long min = Long.MAX_VALUE;
        for (int hash : hash(element)) {
            long count = decrement(hash);
            filter.setBit(hash, count > 0);
            min = (min >= count ? count : min);
        }

        return min;
    }

    /**
     * Increment the internal counter upon insertion of new elements.
     * 
     * @param index position at which to increase
     */
    protected long increment(int index) {
        int low = index * config().countingBits();
        int high = (index + 1) * config().countingBits();

        // Do a binary +1 on the slice of length countingBits
        boolean incremented = false;
        long count = 0;
        int pos = 0;
        for (int i = (high - 1); i >= low; i--) {
            if (!counts.get(i) && !incremented) {
                counts.set(i);
                incremented = true;
            } else if (!incremented) {
                counts.set(i, false);
            }

            if (counts.get(i)) {
                count += Math.pow(2, pos);
            }
            pos++;
        }

        // If the counter overflowed, call the handler
        // and set the counter to the maximum value
        if (!incremented) {
            overflowHandler.run();
            for (int i = (high - 1); i >= low; i--) {
                counts.set(i);
            }
        }
        return count;
    }

    protected long count(int index) {
        int low = index * config().countingBits();
        int high = (index + 1) * config().countingBits();

        long count = 0;
        int pos = 0;
        // bit * 2^0 + bit * 2^1 ...
        for (int i = (high - 1); i >= low; i--) {
            if (counts.get(i)) {
                count += Math.pow(2, pos);
            }
            pos++;
        }
        return count;
    }

    /**
     * Decrements the internal counter upon deletion and unsets the Bloom filter bit if necessary.
     * 
     * @param index position at which to decrease
     */
    protected long decrement(int index) {
        int low = index * config().countingBits();
        int high = (index + 1) * config().countingBits();

        // Do a binary -1 on the counter's slice of length countingBits
        boolean decremented = false;
        boolean nonZero = false;
        long count = 0;
        int pos = 0;
        for (int i = (high - 1); i >= low; i--) {
            if (!decremented) {
                // Flip every bit until you reach the first bit that is one. Flip that and stop flipping.
                if (counts.get(i)) {
                    counts.set(i, false);
                    decremented = true;
                } else {
                    counts.set(i, true);
                    nonZero = true;
                }
            } else {
                // While there is still one bit that is not zero the counter isn't zero
                if (counts.get(i)) {
                    nonZero = true;
                }
            }
            if (counts.get(i)) {
                count += Math.pow(2, pos);
            }
            pos++;
        }

        return count;
    }

    @Override
    public synchronized long getEstimatedCount(T element) {
        int[] hashes = hash(toBytes(element));
        long min = Long.MAX_VALUE;
        for (int hash : hashes) {
            long count = count(hash);
            min = (min >= count ? count : min);
        }
        return min;
    }

    @Override
    public boolean union(BloomFilter<T> other) {
        // TODO
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean intersect(BloomFilter<T> other) {
        // TODO
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isEmpty() {
        return filter.isEmpty();
    }

    @Override
    public synchronized String toString() {
        StringBuilder sb = new StringBuilder(asString() + "\n");
        for (int i = 0; i < config().size(); i++) {
            sb.append(filter.getBit(i) ? 1 : 0);
            sb.append(" ");
            if (counts != null) {
                for (int j = 0; j < config().countingBits(); j++) {
                    sb.append(counts.get(config().countingBits() * i + j) ? 1 : 0);
                }
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    @Override
    public synchronized CountingBloomFilterMemory<T> clone() {
        CountingBloomFilterMemory<T> o = null;
        o = (CountingBloomFilterMemory<T>) super.clone();
        o.filter = (BloomFilterMemory<T>) this.filter.clone();
        if (this.counts != null) o.counts = (BitSet) this.counts.clone();
        o.config = this.config.clone();
        return o;
    }

    @Override
    public void clear() {
        filter.clear();
        counts.clear();
    }

    @Override
    public BitSet getBitSet() {
        return filter.getBitSet();
    }

    @Override
    public FilterBuilder config() {
        return this.config;
    }

    @Override
    public synchronized boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CountingBloomFilterMemory)) return false;

        CountingBloomFilterMemory that = (CountingBloomFilterMemory) o;

        if (config != null ? !config.isCompatibleTo(that.config) : that.config != null) return false;
        if (counts != null ? !counts.equals(that.counts) : that.counts != null) return false;
        if (filter != null ? !filter.equals(that.filter) : that.filter != null) return false;

        return true;
    }

    public void setOverflowHandler(Runnable callback) {
        this.overflowHandler = callback;
    }
}
