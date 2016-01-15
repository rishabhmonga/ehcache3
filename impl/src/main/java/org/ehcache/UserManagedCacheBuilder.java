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

package org.ehcache;

import org.ehcache.config.BaseCacheConfiguration;
import org.ehcache.config.Builder;
import org.ehcache.config.CacheConfiguration;
import org.ehcache.config.EvictionVeto;
import org.ehcache.config.ResourcePools;
import org.ehcache.config.ResourcePoolsBuilder;
import org.ehcache.config.ResourceType;
import org.ehcache.config.SerializerConfiguration;
import org.ehcache.config.StoreConfigurationImpl;
import org.ehcache.config.UserManagedCacheConfiguration;
import org.ehcache.config.copy.CopierConfiguration;
import org.ehcache.config.copy.DefaultCopierConfiguration;
import org.ehcache.config.serializer.DefaultSerializerConfiguration;
import org.ehcache.config.units.EntryUnit;
import org.ehcache.events.CacheEventDispatcher;
import org.ehcache.events.DisabledCacheEventNotificationService;
import org.ehcache.exceptions.CachePersistenceException;
import org.ehcache.expiry.Expirations;
import org.ehcache.expiry.Expiry;
import org.ehcache.internal.copy.SerializingCopier;
import org.ehcache.spi.LifeCycled;
import org.ehcache.spi.LifeCycledAdapter;
import org.ehcache.spi.ServiceLocator;
import org.ehcache.spi.cache.Store;
import org.ehcache.spi.copy.Copier;
import org.ehcache.spi.loaderwriter.CacheLoaderWriter;
import org.ehcache.spi.serialization.SerializationProvider;
import org.ehcache.spi.serialization.Serializer;
import org.ehcache.spi.serialization.UnsupportedTypeException;
import org.ehcache.spi.service.LocalPersistenceService;
import org.ehcache.spi.service.Service;
import org.ehcache.spi.service.ServiceConfiguration;
import org.ehcache.spi.service.ServiceCreationConfiguration;
import org.ehcache.spi.service.ServiceDependencies;
import org.ehcache.util.ClassLoading;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.ehcache.config.ResourcePoolsBuilder.newResourcePoolsBuilder;
import static org.ehcache.config.ResourceType.Core.DISK;
import static org.ehcache.config.ResourceType.Core.OFFHEAP;

/**
 * @author Alex Snaps
 */
public class UserManagedCacheBuilder<K, V, T extends UserManagedCache<K, V>> implements Builder<T> {

  @ServiceDependencies(Store.Provider.class)
  private static class ServiceDeps {
    private ServiceDeps() {
      throw new UnsupportedOperationException("This is an annotation placeholder, not to be instantiated");
    }
  }

  private static final Logger LOGGER = LoggerFactory.getLogger(UserManagedCacheBuilder.class);

  private static final AtomicLong instanceId = new AtomicLong(0L);

  private final Class<K> keyType;
  private final Class<V> valueType;
  private String id;
  private final Set<Service> services = new HashSet<Service>();
  private final Set<ServiceCreationConfiguration<?>> serviceCreationConfigurations = new HashSet<ServiceCreationConfiguration<?>>();
  private Expiry<? super K, ? super V> expiry = Expirations.noExpiration();
  private ClassLoader classLoader = ClassLoading.getDefaultClassLoader();
  private EvictionVeto<? super K, ? super V> evictionVeto;
  private CacheLoaderWriter<? super K, V> cacheLoaderWriter;
  private CacheEventDispatcher<K, V> cacheEventNotificationService = new DisabledCacheEventNotificationService<K, V>();
  private ResourcePools resourcePools = newResourcePoolsBuilder().heap(Long.MAX_VALUE, EntryUnit.ENTRIES).build();
  private Copier<K> keyCopier;
  private boolean useKeySerializingCopier;
  private Copier<V> valueCopier;
  private boolean useValueSerializingCopier;
  private Serializer<K> keySerializer;
  private Serializer<V> valueSerializer;
  private int orderedEventParallelism = 4;


  public UserManagedCacheBuilder(final Class<K> keyType, final Class<V> valueType) {
    this.keyType = keyType;
    this.valueType = valueType;
  }

  private UserManagedCacheBuilder(UserManagedCacheBuilder<K, V, T> toCopy) {
    this.keyType = toCopy.keyType;
    this.valueType = toCopy.valueType;
    this.id = toCopy.id;
    this.services.addAll(toCopy.services);
    this.serviceCreationConfigurations.addAll(toCopy.serviceCreationConfigurations);
    this.expiry = toCopy.expiry;
    this.classLoader = toCopy.classLoader;
    this.evictionVeto = toCopy.evictionVeto;
    this.cacheLoaderWriter = toCopy.cacheLoaderWriter;
    this.cacheEventNotificationService = toCopy.cacheEventNotificationService;
    this.resourcePools = toCopy.resourcePools;
    this.keyCopier = toCopy.keyCopier;
    this.valueCopier = toCopy.valueCopier;
    this.keySerializer = toCopy.keySerializer;
    this.valueSerializer = toCopy.valueSerializer;
    this.useKeySerializingCopier = toCopy.useKeySerializingCopier;
    this.useValueSerializingCopier = toCopy.useValueSerializingCopier;
  }

  T build(ServiceLocator serviceLocator) throws IllegalStateException {
    try {
      for (ServiceCreationConfiguration<?> serviceCreationConfig : serviceCreationConfigurations) {
        Service service = serviceLocator.getOrCreateServiceFor(serviceCreationConfig);
        if (service == null) {
          throw new IllegalArgumentException("Couldn't resolve Service " + serviceCreationConfig.getServiceType().getName());
        }
      }
      serviceLocator.loadDependenciesOf(ServiceDeps.class);
      serviceLocator.startAllServices();
    } catch (Exception e) {
      throw new IllegalStateException("UserManagedCacheBuilder failed to build.", e);
    }

    List<ServiceConfiguration<?>> serviceConfigsList = new ArrayList<ServiceConfiguration<?>>();

    if (keyCopier != null) {
      serviceConfigsList.add(new DefaultCopierConfiguration<K>(keyCopier, CopierConfiguration.Type.KEY));
    } else if (useKeySerializingCopier) {
      serviceConfigsList.add(new DefaultCopierConfiguration<K>((Class) SerializingCopier.class, CopierConfiguration.Type.KEY));
    }
    if (valueCopier != null) {
      serviceConfigsList.add(new DefaultCopierConfiguration<V>(valueCopier, CopierConfiguration.Type.VALUE));
    } else if (useValueSerializingCopier) {
      serviceConfigsList.add(new DefaultCopierConfiguration<K>((Class) SerializingCopier.class, CopierConfiguration.Type.VALUE));
    }

    Set<ResourceType> resources = resourcePools.getResourceTypeSet();
    boolean persistent = resources.contains(DISK);
    if (persistent) {
      if (id == null) {
        throw new IllegalStateException("Persistent user managed caches must have an id set");
      }
      LocalPersistenceService persistenceService = serviceLocator.getService(LocalPersistenceService.class);
      if (!resourcePools.getPoolForResource(ResourceType.Core.DISK).isPersistent()) {
        try {
          persistenceService.destroyPersistenceSpace(id);
        } catch (CachePersistenceException cpex) {
          throw new RuntimeException("Unable to clean-up persistence space for non-restartable cache " + id, cpex);
        }
      }
      try {
        serviceConfigsList.add(persistenceService.getOrCreatePersistenceSpace(id));
      } catch (CachePersistenceException cpex) {
        throw new RuntimeException("Unable to create persistence space for cache " + id, cpex);
      }
    }

    ServiceConfiguration<?>[] serviceConfigs = serviceConfigsList.toArray(new ServiceConfiguration<?>[0]);
    List<LifeCycled> lifeCycledList = new ArrayList<LifeCycled>();

    Serializer<K> keySerializer = this.keySerializer;
    Serializer<V> valueSerializer = this.valueSerializer;

    if (keySerializer != null) {
      serviceConfigsList.add(new DefaultSerializerConfiguration<K>(this.keySerializer, SerializerConfiguration.Type.KEY));
    }
    if (valueSerializer != null) {
      serviceConfigsList.add(new DefaultSerializerConfiguration<V>(this.valueSerializer, SerializerConfiguration.Type.VALUE));
    }

    final SerializationProvider serialization = serviceLocator.getService(SerializationProvider.class);
    if (serialization != null) {
      try {
        if (keySerializer == null) {
          final Serializer<K> keySer = serialization.createKeySerializer(keyType, classLoader, serviceConfigs);
          lifeCycledList.add(
              new LifeCycledAdapter() {
                @Override
                public void close() throws Exception {
                  serialization.releaseSerializer(keySer);
                }
              }
          );
          keySerializer = keySer;
        }

        if (valueSerializer == null) {
          final Serializer<V> valueSer = serialization.createValueSerializer(valueType, classLoader, serviceConfigs);
          lifeCycledList.add(
              new LifeCycledAdapter() {
                @Override
                public void close() throws Exception {
                  serialization.releaseSerializer(valueSer);
                }
              }
          );
          valueSerializer = valueSer;
        }
      } catch (UnsupportedTypeException e) {
        if (resources.contains(OFFHEAP) || resources.contains(DISK)) {
          throw new RuntimeException(e);
        } else {
          LOGGER.info("Could not create serializers for user managed cache " + id, e);
        }
      }
    }
    final Store.Provider storeProvider = serviceLocator.getService(Store.Provider.class);
    Store.Configuration<K, V> storeConfig = new StoreConfigurationImpl<K, V>(keyType, valueType, evictionVeto, classLoader,
            expiry, resourcePools, orderedEventParallelism, keySerializer, valueSerializer);
    final Store<K, V> store = storeProvider.createStore(storeConfig, serviceConfigs);

    CacheConfiguration<K, V> cacheConfig = new BaseCacheConfiguration<K, V>(keyType, valueType, evictionVeto,
        classLoader, expiry, resourcePools);

    lifeCycledList.add(new LifeCycled() {
      @Override
      public void init() throws Exception {
        storeProvider.initStore(store);
      }

      @Override
      public void close() throws Exception {
        storeProvider.releaseStore(store);
      }
    });
    if (persistent) {
      LocalPersistenceService persistenceService = serviceLocator
          .getService(LocalPersistenceService.class);
      if (persistenceService == null) {
        throw new IllegalStateException("No LocalPersistenceService could be found - did you configure one?");
      }

      PersistentUserManagedEhcache<K, V> cache = new PersistentUserManagedEhcache<K, V>(cacheConfig, store, storeConfig, persistenceService, cacheLoaderWriter, cacheEventNotificationService, id);
      for (LifeCycled lifeCycled : lifeCycledList) {
        cache.addHook(lifeCycled);
      }
      return cast(cache);
    } else {
      String loggerName;
      if (id != null) {
        loggerName = Ehcache.class.getName() + "-" + id;
      } else {
        loggerName = Ehcache.class.getName() + "-UserManaged" + instanceId.incrementAndGet();
      }
      Ehcache<K, V> cache = new Ehcache<K, V>(cacheConfig, store, cacheLoaderWriter, cacheEventNotificationService, LoggerFactory.getLogger(loggerName));
      for (LifeCycled lifeCycled : lifeCycledList) {
        cache.addHook(lifeCycled);
      }
      return cast(cache);
    }

  }
  
  @SuppressWarnings("unchecked")
  T cast(UserManagedCache<K, V> cache) {
    return (T)cache;
  }

  public final T build(final boolean init) throws IllegalStateException {
    final T build = build(new ServiceLocator(services.toArray(new Service[services.size()])));
    if (init) {
      build.init();
    }
    return build;
  }

  @Override
  public T build() {
    return build(false);
  }

  public final <N extends T> UserManagedCacheBuilder<K, V, N> with(UserManagedCacheConfiguration<K, V, N> cfg) {
    return cfg.builder(this);
  }

  public final UserManagedCacheBuilder<K, V, T> identifier(String identifier) {
    UserManagedCacheBuilder<K, V, T> otherBuilder = new UserManagedCacheBuilder<K, V, T>(this);
    otherBuilder.id = identifier;
    return otherBuilder;
  }

  public final UserManagedCacheBuilder<K, V, T> withClassLoader(ClassLoader classLoader) {
    if (classLoader == null) {
      throw new NullPointerException("Null classloader");
    }
    UserManagedCacheBuilder<K, V, T> otherBuilder = new UserManagedCacheBuilder<K, V, T>(this);
    otherBuilder.classLoader = classLoader;
    return otherBuilder;
  }
  
  public final UserManagedCacheBuilder<K, V, T> withExpiry(Expiry<K, V> expiry) {
    if (expiry == null) {
      throw new NullPointerException("Null expiry");
    }
    UserManagedCacheBuilder<K, V, T> otherBuilder = new UserManagedCacheBuilder<K, V, T>(this);
    otherBuilder.expiry = expiry;
    return otherBuilder;
  }

  public final UserManagedCacheBuilder<K, V, T> withCacheEvents(CacheEventDispatcher<K, V> cacheEventNotificationService) {
    UserManagedCacheBuilder<K, V, T> otherBuilder = new UserManagedCacheBuilder<K, V, T>(this);
    otherBuilder.cacheEventNotificationService = cacheEventNotificationService;
    return otherBuilder;
  }

  public final UserManagedCacheBuilder<K, V, T> withResourcePools(ResourcePools resourcePools) {
    UserManagedCacheBuilder<K, V, T> otherBuilder = new UserManagedCacheBuilder<K, V, T>(this);
    otherBuilder.resourcePools = resourcePools;
    return otherBuilder;
  }

  public final UserManagedCacheBuilder<K, V, T> withResourcePools(ResourcePoolsBuilder resourcePoolsBuilder) {
    return withResourcePools(resourcePoolsBuilder.build());
  }

  public final UserManagedCacheBuilder<K, V, T> withOrderedEventParallelism(int parallelism) {
    this.orderedEventParallelism = parallelism;
    return this;
  }

  public UserManagedCacheBuilder<K, V, T> withEvictionVeto(EvictionVeto<K, V> evictionVeto) {
    if (evictionVeto == null) {
      throw new NullPointerException("Null eviction veto");
    }
    UserManagedCacheBuilder<K, V, T> otherBuilder = new UserManagedCacheBuilder<K, V, T>(this);
    otherBuilder.evictionVeto = evictionVeto;
    return otherBuilder;
  }

  public UserManagedCacheBuilder<K, V, T> withLoaderWriter(CacheLoaderWriter<K, V> loaderWriter) {
    if (loaderWriter == null) {
      throw new NullPointerException("Null loaderWriter");
    }
    UserManagedCacheBuilder<K, V, T> otherBuilder = new UserManagedCacheBuilder<K, V, T>(this);
    otherBuilder.cacheLoaderWriter = loaderWriter;
    return otherBuilder;
  }

  public UserManagedCacheBuilder<K, V, T> withKeySerializingCopier() {
    UserManagedCacheBuilder<K, V, T> otherBuilder = new UserManagedCacheBuilder<K, V, T>(this);
    otherBuilder.keyCopier = null;
    otherBuilder.useKeySerializingCopier = true;
    return otherBuilder;
  }

  public UserManagedCacheBuilder<K, V, T> withValueSerializingCopier() {
    UserManagedCacheBuilder<K, V, T> otherBuilder = new UserManagedCacheBuilder<K, V, T>(this);
    otherBuilder.valueCopier = null;
    otherBuilder.useValueSerializingCopier = true;
    return otherBuilder;
  }

  public UserManagedCacheBuilder<K, V, T> withKeyCopier(Copier<K> keyCopier) {
    if (keyCopier == null) {
      throw new NullPointerException("Null key copier");
    }
    UserManagedCacheBuilder<K, V, T> otherBuilder = new UserManagedCacheBuilder<K, V, T>(this);
    otherBuilder.keyCopier = keyCopier;
    otherBuilder.useKeySerializingCopier = false;
    return otherBuilder;
  }

  public UserManagedCacheBuilder<K, V, T> withValueCopier(Copier<V> valueCopier) {
    if (valueCopier == null) {
      throw new NullPointerException("Null value copier");
    }
    UserManagedCacheBuilder<K, V, T> otherBuilder = new UserManagedCacheBuilder<K, V, T>(this);
    otherBuilder.valueCopier = valueCopier;
    otherBuilder.useValueSerializingCopier = false;
    return otherBuilder;
  }

  public UserManagedCacheBuilder<K, V, T> withKeySerializer(Serializer<K> keySerializer) {
    if (keySerializer == null) {
      throw new NullPointerException("Null key serializer");
    }
    UserManagedCacheBuilder<K, V, T> otherBuilder = new UserManagedCacheBuilder<K, V, T>(this);
    otherBuilder.keySerializer = keySerializer;
    return otherBuilder;
  }

  public UserManagedCacheBuilder<K, V, T> withValueSerializer(Serializer<V> valueSerializer) {
    if (valueSerializer == null) {
      throw new NullPointerException("Null value serializer");
    }
    UserManagedCacheBuilder<K, V, T> otherBuilder = new UserManagedCacheBuilder<K, V, T>(this);
    otherBuilder.valueSerializer = valueSerializer;
    return otherBuilder;
  }

  public static <K, V, T extends UserManagedCache<K, V>> UserManagedCacheBuilder<K, V, T> newUserManagedCacheBuilder(Class<K> keyType, Class<V> valueType) {
    return new UserManagedCacheBuilder<K, V, T>(keyType, valueType);
  }

  public UserManagedCacheBuilder<K, V, T> using(Service service) {
    UserManagedCacheBuilder<K, V, T> otherBuilder = new UserManagedCacheBuilder<K, V, T>(this);
    otherBuilder.services.add(service);
    return otherBuilder;
  }

  public UserManagedCacheBuilder<K, V, T> using(ServiceCreationConfiguration<?> serviceConfiguration) {
    UserManagedCacheBuilder<K, V, T> otherBuilder = new UserManagedCacheBuilder<K, V, T>(this);
    otherBuilder.serviceCreationConfigurations.add(serviceConfiguration);
    return otherBuilder;
  }
}
