/**
 * Copyright 2014 Netflix, Inc.
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

/**
 * A listener that can be used to listen for cache
 * removal events. Used in conjunction with {@link ExpiringCache}
 * @param <K>  the cache key
 * @param <V>  the cache value
 */
public interface CacheRemovalListener<K, V> {
    
    /**
     * Occurs after an entry has been removed from the cache.
     * @param key    the key removed
     * @param value  the value removed
     */
    void onRemoval(K key, V value);

}
