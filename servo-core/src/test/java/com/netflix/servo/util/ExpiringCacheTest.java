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

import java.util.concurrent.TimeUnit;

import com.netflix.servo.jsr166e.ConcurrentHashMapV8;

import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;

public class ExpiringCacheTest {
    static class CountingFun implements ConcurrentHashMapV8.Fun<String, Integer> {
        int numCalled = 0;

        @Override
        public Integer apply(String s) {
            ++numCalled;
            return s.length();
        }
    }

    @Test
    public void testGet() throws Exception {
        ManualClock clock = new ManualClock(0L);
        CountingFun fun = new CountingFun();
        ExpiringCache<String, Integer> map = 
                ExpiringCache.builder(fun)
                             .expiresAfter(100L, TimeUnit.MILLISECONDS)
                             .expirationCheckFrequency(100L, TimeUnit.MILLISECONDS)
                             .withClock(clock)
                             .build();

        Integer three = map.get("foo");
        assertEquals(three, Integer.valueOf(3));
        Integer threeAgain = map.get("foo");
        assertEquals(threeAgain, Integer.valueOf(3));

        assertEquals(fun.numCalled, 1, "Properly caches computations");
        clock.set(200L);
        Thread.sleep(200L);

        Integer threeOnceMore = map.get("foo");
        assertEquals(threeOnceMore, Integer.valueOf(3));
        assertEquals(fun.numCalled, 2, "Properly expires unused entries");
    }
}
