/**
 * Copyright 2013 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.netflix.servo.util;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.netflix.servo.jsr166e.ConcurrentHashMapV8;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * A semi-persistent mapping from keys to values. Values are automatically loaded
 * by the cache, and are stored in the cache until evicted.
 *
 * @param <K> The type of keys maintained
 * @param <V> The type of values maintained
 */
public class ExpiringCache<K, V> {
    private final ConcurrentHashMapV8<K, Entry<V>> map;
    private final long expireAfterMs;
    private final ConcurrentHashMapV8.Fun<K, Entry<V>> entryGetter;
    private final Clock clock;
    private final CacheRemovalListener<K, V> removalListener;

    /**
     * Creates a builder for an {@link ExpiringCache} using the specified getter.
     * @param getter the getter the cache uses
     * @return       the builder
     */
    public static <K, V> Builder<K, V> builder(ConcurrentHashMapV8.Fun<K, V> getter) {
        return new Builder<K, V>(getter);
    }

    /**
     * The builder for {@link ExpiringCache}.
     * @param <K> the key type for the cache being built
     * @param <V> the value type for the cache being built
     */
    public static final class Builder<K, V> {

        private final ConcurrentHashMapV8.Fun<K, V> getter;
        private long expirationDurationMs = TimeUnit.MINUTES.toMillis(15);
        private long expirationFrequencyMs = TimeUnit.MINUTES.toMillis(1);
        private CacheRemovalListener<K, V> removalListener;
        private Clock expirationClock = ClockWithOffset.INSTANCE;

        private Builder(ConcurrentHashMapV8.Fun<K, V> getter) {
            this.getter = getter;
        }

        /**
         * The amount of time in which non-accessed records expire.
         * Defaults to 15 minute expiration.
         * @param duration the time duration
         * @param unit     the time unit
         * @return         the builder
         */
        public Builder<K, V> expiresAfter(long duration, TimeUnit unit) {
            this.expirationDurationMs = unit.toMillis(duration);
            return this;
        }

        /**
         * How often the cache checks for and evicts expired entries.
         * Defaults to 1 minute checks.
         * @param duration  the time duration
         * @param unit      the time unit
         * @return          the builder
         */
        public Builder<K, V> expirationCheckFrequency(long duration, TimeUnit unit) {
            this.expirationFrequencyMs = unit.toMillis(duration);
            return this;
        }

        /**
         * Specifies a removal listener which is called when entries are expired
         * in the cache.
         * @param listener the removal listener
         * @return         the builder
         */
        public Builder<K, V> withRemovalListener(CacheRemovalListener<K, V> listener) {
            this.removalListener = listener;
            return this;
        }

        /**
         * The clock to used to determine expiration. This is mainly used for unit
         * testing to modify the timing behavior.
         * @param clock  the clock
         * @return       the builder
         */
        public Builder<K, V> withClock(Clock clock) {
            this.expirationClock = clock;
            return this;
        }

        /**
         * Builds the {@link ExpiringCache} given the configuration.
         * @return the Expiring Cache
         */
        public ExpiringCache<K, V> build() {
            return new ExpiringCache<K, V>(this);
        }

    }

    private static class Entry<V> {
        private volatile long accessTime;
        private final V value;
        private final Clock clock;

        private Entry(V value, long accessTime, Clock clock) {
            this.value = value;
            this.accessTime = accessTime;
            this.clock = clock;
        }

        private V getValue() {
            accessTime = clock.now();
            return value;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            Entry entry = (Entry) o;

            return accessTime == entry.accessTime && value.equals(entry.value);
        }

        @Override
        public int hashCode() {
            int result = (int) (accessTime ^ (accessTime >>> 32));
            result = 31 * result + value.hashCode();
            return result;
        }

        @Override
        public String toString() {
            return "Entry{" +
                    "accessTime=" + accessTime +
                    ", value=" + value +
                    '}';
        }
    }

    private static final ScheduledExecutorService service;

    static {
        final ThreadFactory threadFactory = new ThreadFactoryBuilder()
                .setDaemon(true)
                .setNameFormat("expiringMap-%d")
                .build();
        service = Executors.newSingleThreadScheduledExecutor(threadFactory);
    }

    private ExpiringCache(Builder<K, V> builder) {
        Preconditions.checkArgument(builder.expirationDurationMs > 0,
                "Expiration duration must be 1ms or higher.");
        Preconditions.checkArgument(builder.expirationFrequencyMs > 0, 
                "Expiration frequency must be 1ms or higher.");
        this.map = new ConcurrentHashMapV8<K, Entry<V>>();
        this.expireAfterMs = builder.expirationDurationMs;
        this.entryGetter = toEntry(builder.getter);
        this.clock = builder.expirationClock;
        this.removalListener = builder.removalListener;
        final Runnable expirationJob = new Runnable() {
            @Override
            public void run() {
                long tooOld = clock.now() - expireAfterMs;
                for (Map.Entry<K, Entry<V>> entry : map.entrySet()) {
                    if (entry.getValue().accessTime < tooOld) {
                        map.remove(entry.getKey(), entry.getValue());
                        if (removalListener != null) {
                            removalListener.onRemoval(entry.getKey(), entry.getValue().getValue());
                        }
                    }
                }
            }
        };
        service.scheduleWithFixedDelay(expirationJob, 1, builder.expirationFrequencyMs, TimeUnit.MILLISECONDS);
    }

    private ConcurrentHashMapV8.Fun<K, Entry<V>> toEntry(final ConcurrentHashMapV8.Fun<K, V> underlying) {
        return new ConcurrentHashMapV8.Fun<K, Entry<V>>() {
            @Override
            public Entry<V> apply(K key) {
                return new Entry<V>(underlying.apply(key), 0L, clock);
            }
        };
    }

    /**
     * Get the (possibly cached) value for a given key.
     */
    public V get(final K key) {
        Entry<V> entry = map.computeIfAbsent(key, entryGetter);
        return entry.getValue();
    }

    /**
     * Get the list of all values that are members of this cache. Does not
     * affect the access time used for eviction.
     */
    public List<V> values() {
        ImmutableList.Builder<V> builder = ImmutableList.builder();
        for (Entry<V> e : map.values()) {
            builder.add(e.value); // avoid updating the access time
        }
        return builder.build();
    }

    /**
     * Return the number of entries in the cache.
     */
    public int size() {
        return map.size();
    }

    /**{@inheritDoc}*/
    @Override
    public String toString() {
        return "ExpiringCache{"
                + "map=" + map
                + ", expireAfterMs=" + expireAfterMs
                + '}';
    }
}
