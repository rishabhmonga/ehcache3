/*
 * Copyright Terracotta, Inc.
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
package org.ehcache.events;

import org.ehcache.event.CacheEvent;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

public class UnorderedEventDispatcher<K, V> implements EventDispatcher<K, V> {
  private ExecutorService executorService;

  public UnorderedEventDispatcher(ExecutorService executorService) {
    this.executorService = executorService;
  }

  @Override
  public Future<?> dispatch(CacheEvent<K, V> cacheEvent, Iterable<EventListenerWrapper> listenerWrappers) {
    return executorService.submit(new EventDispatchTask<K, V>(cacheEvent, listenerWrappers));
  }

  public ExecutorService getExecutorService() {
    return executorService;
  }
}
