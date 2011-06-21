/**
 * Copyright 2011 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.modelmapper.internal;

import java.lang.reflect.Constructor;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.modelmapper.Condition;
import org.modelmapper.ConfigurationException;
import org.modelmapper.Converter;
import org.modelmapper.Provider;
import org.modelmapper.TypeMap;
import org.modelmapper.Provider.ProvisionRequest;
import org.modelmapper.config.Configuration;
import org.modelmapper.internal.converter.ConverterStore;
import org.modelmapper.internal.util.Iterables;
import org.modelmapper.internal.util.Primitives;
import org.modelmapper.internal.util.Types;
import org.modelmapper.spi.ConstantMapping;
import org.modelmapper.spi.Mapping;
import org.modelmapper.spi.MappingContext;
import org.modelmapper.spi.MappingEngine;
import org.modelmapper.spi.PropertyMapping;
import org.modelmapper.spi.SourceMapping;

/**
 * MappingEngine implementation that caches ConditionalConverters by source and destination type
 * pairs.
 * 
 * @author Jonathan Halterman
 */
public class MappingEngineImpl implements MappingEngine {
  /** Cache of conditional converters */
  private final Map<TypePair<?, ?>, Converter<?, ?>> converterCache = new ConcurrentHashMap<TypePair<?, ?>, Converter<?, ?>>();
  private final Configuration configuration;
  private final TypeMapStore typeMapStore;
  private final ConverterStore converterStore;

  public MappingEngineImpl(InheritingConfiguration configuration) {
    this.configuration = configuration;
    this.typeMapStore = configuration.typeMapStore;
    this.converterStore = configuration.converterStore;
  }

  /**
   * Initial entry point.
   */
  public <S, D> D map(S source, Class<S> sourceType, D destination, Class<D> destinationType) {
    MappingContextImpl<S, D> context = new MappingContextImpl<S, D>(source, sourceType,
        destination, destinationType, this);
    if (!Iterables.isIterable(destinationType))
      context.currentlyMapping(destinationType);

    D result = null;

    try {
      result = mapInitial(context);
    } catch (ConfigurationException e) {
      throw e;
    } catch (Throwable t) {
      context.errors.errorMapping(sourceType, destinationType, t);
    }

    context.errors.throwMappingExceptionIfErrorsExist();
    return result;
  }

  /**
   * Performs mapping using a TypeMap if one exists, else a converter if one applies, else a newly
   * created TypeMap.
   */
  private <S, D> D mapInitial(MappingContext<S, D> context) {
    TypeMap<S, D> typeMap = typeMapStore.get(context.getSourceType(), context.getDestinationType());
    if (typeMap != null)
      return typeMap(context, typeMap);

    Converter<S, D> converter = converterFor(context);
    if (converter != null)
      return convert(context, converter);

    // Call getOrCreate in case TypeMap was created concurrently
    typeMap = typeMapStore.getOrCreate(context.getSourceType(), context.getDestinationType(), this);
    return typeMap(context, typeMap);
  }

  /**
   * Recursive entry point.
   */
  @Override
  public <S, D> D map(MappingContext<S, D> context) {
    MappingContextImpl<S, D> contextImpl = (MappingContextImpl<S, D>) context;
    Class<D> destinationType = context.getDestinationType();
    if (!Iterables.isIterable(destinationType) && contextImpl.currentlyMapping(destinationType)) {
      throw contextImpl.errors.errorCircularMapping(context.getSourceType(), destinationType)
          .toException();
    }

    D destination = null;
    TypeMap<S, D> typeMap = typeMapStore.get(context.getSourceType(), destinationType);

    if (typeMap != null) {
      destination = typeMap(context, typeMap);
    } else {
      Converter<S, D> converter = converterFor(context);
      if (converter == null && context.getSource() != null) {
        if (context.getDestination() == null)
          contextImpl.errors.errorUnsupportedMapping(context.getSourceType(), destinationType);
        else
          destination = context.getDestination();
      } else {
        destination = convert(context, converter);
      }
    }

    contextImpl.finishedMapping(destinationType);
    return destination;
  }

  /**
   * Performs a type mapping for the {@code typeMap} and {@code context}.
   */
  <S, D> D typeMap(MappingContext<S, D> context, TypeMap<S, D> typeMap) {
    MappingContextImpl<S, D> contextImpl = (MappingContextImpl<S, D>) context;

    contextImpl.setTypeMap(typeMap);
    if (context.getDestination() == null) {
      D destination = createDestination(context);
      if (destination == null)
        return null;
    }

    Condition condition = typeMap.getCondition();
    Converter<S, D> converter = typeMap.getConverter();
    if (condition == null || condition.applies(context)) {
      if (converter != null)
        return convert(context, converter);

      for (Mapping mapping : typeMap.getMappings()) {
        propertyMap(mapping, contextImpl);
      }
    }

    return context.getDestination();
  }

  @SuppressWarnings("unchecked")
  private <S, D> void propertyMap(Mapping mapping, MappingContextImpl<S, D> context) {
    Condition condition = mapping.getCondition();
    if (condition == null && mapping.isSkipped())
      return;

    Converter<Object, Object> converter = (Converter<Object, Object>) mapping.getConverter();
    MappingContextImpl<Object, Object> propertyContext;
    Object source = resolveSourceValue(context, mapping);
    propertyContext = propertyContextFor(context, source, mapping);

    if (condition == null || condition.applies(propertyContext)) {
      if (condition != null && mapping.isSkipped())
        return;

      // Create last destination via provider prior to mapping/conversion
      createDestinationViaProvider(propertyContext);

      Object destinationValue = null;
      if (source != null)
        destinationValue = converter == null ? map(propertyContext) : convert(propertyContext,
            converter);
      setDestinationValue(context.getDestination(), destinationValue, propertyContext, mapping);
    }
  }

  @SuppressWarnings("unchecked")
  private Object resolveSourceValue(MappingContextImpl<?, ?> context, Mapping mapping) {
    Object source = context.getSource();
    if (mapping instanceof PropertyMapping)
      for (Accessor accessor : (List<Accessor>) ((PropertyMapping) mapping).getSourceProperties()) {
        source = accessor.getValue(source);
        if (source == null)
          return null;
      }
    else if (mapping instanceof ConstantMapping)
      source = ((ConstantMapping) mapping).getConstant();

    return source;
  }

  @SuppressWarnings({ "unchecked", "rawtypes" })
  private void setDestinationValue(Object destination, Object destinationValue,
      MappingContextImpl<?, ?> context, Mapping mapping) {
    List<Mutator> mutatorChain = (List<Mutator>) mapping.getDestinationProperties();

    for (int i = 0; i < mutatorChain.size(); i++) {
      Mutator mutator = mutatorChain.get(i);

      // Handle last mutator in chain
      if (i == mutatorChain.size() - 1) {
        context.destinationCache.put(mutator, destinationValue);
        mutator.setValue(destination,
            destinationValue == null ? Primitives.defaultValue(mutator.getType())
                : destinationValue);
      } else {
        Object intermediateDest = context.destinationCache.get(mutator);
        if (intermediateDest == null) {
          // Create intermediate destination via global provider
          if (configuration.getProvider() != null)
            intermediateDest = configuration.getProvider().get(
                new ProvisionRequestImpl(mutator.getType()));
          else
            intermediateDest = instantiate(mutator.getType(), context.errors);

          if (intermediateDest == null)
            return;

          context.destinationCache.put(mutator, intermediateDest);
        }

        mutator.setValue(destination, intermediateDest);
        destination = intermediateDest;
      }
    }
  }

  /**
   * Returns a property context.
   */
  @SuppressWarnings("unchecked")
  private MappingContextImpl<Object, Object> propertyContextFor(MappingContextImpl<?, ?> context,
      Object source, Mapping mapping) {
    Class<Object> sourceType;
    if (mapping instanceof PropertyMapping) {
      sourceType = (Class<Object>) ((PropertyMapping) mapping).getLastSourceProperty().getType();
    } else if (mapping instanceof ConstantMapping) {
      sourceType = Types.deProxy(((ConstantMapping) mapping).getConstant().getClass());
    } else {
      sourceType = (Class<Object>) ((SourceMapping) mapping).getSourceType();
    }

    Class<Object> destinationType = (Class<Object>) mapping.getLastDestinationProperty().getType();
    return new MappingContextImpl<Object, Object>(context, source, sourceType, null,
        destinationType, mapping);
  }

  /**
   * Performs a mapping using a Converter.
   */
  private <S, D> D convert(MappingContext<S, D> context, Converter<S, D> converter) {
    try {
      return converter.convert(context);
    } catch (Exception e) {
      ((MappingContextImpl<S, D>) context).errors.errorConverting(converter,
          context.getSourceType(), context.getDestinationType(), e);
      return null;
    }
  }

  /**
   * Retrieves a converter from the store or from the cache.
   */
  @SuppressWarnings("unchecked")
  private <S, D> Converter<S, D> converterFor(MappingContext<S, D> context) {
    TypePair<?, ?> typePair = TypePair.of(context.getSourceType(), context.getDestinationType());
    Converter<S, D> converter = (Converter<S, D>) converterCache.get(typePair);
    if (converter == null) {
      converter = converterStore.getFirstSupported(context.getSourceType(),
          context.getDestinationType());
      if (converter != null)
        converterCache.put(typePair, converter);
    }

    return converter;
  }

  private <T> T instantiate(Class<T> type, Errors errors) {
    try {
      Constructor<T> constructor = type.getDeclaredConstructor();
      constructor.setAccessible(true);
      return constructor.newInstance();
    } catch (Exception e) {
      errors.errorInstantiatingDestination(type, e);
      return null;
    }
  }

  @SuppressWarnings("unchecked")
  private <S, D> D createDestinationViaProvider(MappingContextImpl<S, D> context) {
    Provider<D> provider = null;
    if (context.getMapping() != null)
      provider = (Provider<D>) context.getMapping().getProvider();
    if (provider == null && context.typeMap() != null)
      provider = context.typeMap().getProvider();
    if (provider == null && configuration.getProvider() != null)
      provider = (Provider<D>) configuration.getProvider();
    if (provider == null)
      return null;

    D destination = provider.get(context);
    context.setDestination(destination);
    return destination;
  }

  @Override
  public <S, D> D createDestination(MappingContext<S, D> context) {
    MappingContextImpl<S, D> contextImpl = (MappingContextImpl<S, D>) context;
    D destination = createDestinationViaProvider(contextImpl);
    if (destination != null)
      return destination;

    destination = instantiate(context.getDestinationType(), contextImpl.errors);
    contextImpl.setDestination(destination);
    return destination;
  }

  private static class ProvisionRequestImpl<T extends Object> implements ProvisionRequest<T> {
    private final Class<T> requestedType;

    ProvisionRequestImpl(Class<T> requestedType) {
      this.requestedType = requestedType;
    }

    @Override
    public Class<T> getRequestedType() {
      return requestedType;
    }
  }
}
