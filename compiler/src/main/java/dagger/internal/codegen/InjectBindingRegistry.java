/*
 * Copyright (C) 2014 Google, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dagger.internal.codegen;

import com.google.auto.common.MoreElements;
import com.google.common.base.Optional;
import com.google.common.base.Predicate;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import dagger.Component;
import dagger.Provides;
import dagger.internal.codegen.writer.ClassName;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Set;
import javax.annotation.processing.Messager;
import javax.inject.Inject;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic.Kind;

import static com.google.auto.common.MoreElements.isAnnotationPresent;
import static com.google.auto.common.MoreTypes.asDeclared;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static dagger.internal.codegen.MembersInjectionBinding.Strategy.INJECT_MEMBERS;
import static dagger.internal.codegen.SourceFiles.generatedClassNameForBinding;
import static javax.lang.model.util.ElementFilter.constructorsIn;

/**
 * Maintains the collection of provision bindings from {@link Inject} constructors and members
 * injection bindings from {@link Inject} fields and methods known to the annotation processor.
 * Note that this registry <b>does not</b> handle any explicit bindings (those from {@link Provides}
 * methods, {@link Component} dependencies, etc.).
 *
 * @author Gregory Kick
 */
final class InjectBindingRegistry {
  private final Elements elements;
  private final Types types;
  private final Messager messager;
  private final ProvisionBinding.Factory provisionBindingFactory;
  private final MembersInjectionBinding.Factory membersInjectionBindingFactory;

  final class BindingsCollection<B extends Binding> {
    private final Map<Key, B> bindingsByKey = Maps.newLinkedHashMap();
    private final Deque<B> bindingsRequiringGeneration = new ArrayDeque<>();
    private final Set<Key> materializedBindingKeys = Sets.newLinkedHashSet();

    void generateBindings(SourceFileGenerator<B> generator) throws SourceFileGenerationException {
      for (B binding = bindingsRequiringGeneration.poll();
          binding != null;
          binding = bindingsRequiringGeneration.poll()) {
        checkState(!binding.unresolved().isPresent());
        generator.generate(binding);
        materializedBindingKeys.add(binding.key());
      }
      // Because Elements instantiated across processing rounds are not guaranteed to be equals() to
      // the logically same element, clear the cache after generating 
      bindingsByKey.clear();
    }

    /** Returns a previously cached binding. */
    B getBinding(Key key) {
      return bindingsByKey.get(key);
    }

    /** Caches the binding and generates it if it needs generation. */
    void tryRegisterBinding(B binding, ClassName factoryName, boolean warnIfNotAlreadyGenerated) {
      tryToCacheBinding(binding);
      tryToGenerateBinding(binding, factoryName, warnIfNotAlreadyGenerated);
    }

    /**
     * Tries to generate a binding, not generating if it already is generated. For resolved
     * bindings, this will try to generate the unresolved version of the binding.
     */
    void tryToGenerateBinding(B binding, ClassName factoryName, boolean warnIfNotAlreadyGenerated) {
      if (shouldGenerateBinding(binding, factoryName)) {
        bindingsRequiringGeneration.offer(binding);
        if (warnIfNotAlreadyGenerated) {
          messager.printMessage(Kind.NOTE, String.format(
              "Generating a MembersInjector or Factory for %s. "
                    + "Prefer to run the dagger processor over that class instead.",
              types.erasure(binding.key().type()))); // erasure to strip <T> from msgs.
        }
      }
    }

    /** Returns true if the binding needs to be generated. */
    private boolean shouldGenerateBinding(B binding, ClassName factoryName) {
      return !binding.unresolved().isPresent()
          && elements.getTypeElement(factoryName.canonicalName()) == null
          && !materializedBindingKeys.contains(binding.key())
          && !bindingsRequiringGeneration.contains(binding);

    }

    /** Caches the binding for future lookups by key. */
    private void tryToCacheBinding(B binding) {
      // We only cache resolved bindings or unresolved bindings w/o type arguments.
      // Unresolved bindings w/ type arguments aren't valid for the object graph.
      if (binding.unresolved().isPresent()
          || binding.bindingTypeElement().getTypeParameters().isEmpty()) {
        Key key = binding.key();
        Binding previousValue = bindingsByKey.put(key, binding);
        checkState(previousValue == null || binding.equals(previousValue),
            "couldn't register %s. %s was already registered for %s",
            binding, previousValue, key);
      }
    }
  }

  private final BindingsCollection<ProvisionBinding> provisionBindings = new BindingsCollection<>();
  private final BindingsCollection<MembersInjectionBinding> membersInjectionBindings =
      new BindingsCollection<>();

  InjectBindingRegistry(Elements elements,
      Types types,
      Messager messager,
      ProvisionBinding.Factory provisionBindingFactory,
      MembersInjectionBinding.Factory membersInjectionBindingFactory) {
    this.elements = elements;
    this.types = types;
    this.messager = messager;
    this.provisionBindingFactory = provisionBindingFactory;
    this.membersInjectionBindingFactory = membersInjectionBindingFactory;
  }

  /**
   * This method ensures that sources for all registered {@link Binding bindings} (either
   * {@linkplain #registerBinding explicitly} or implicitly via
   * {@link #getOrFindMembersInjectionBinding} or {@link #getOrFindProvisionBinding}) are generated.
   */
  void generateSourcesForRequiredBindings(FactoryGenerator factoryGenerator,
      MembersInjectorGenerator membersInjectorGenerator) throws SourceFileGenerationException {
    provisionBindings.generateBindings(factoryGenerator);
    membersInjectionBindings.generateBindings(membersInjectorGenerator);
  }

  ProvisionBinding registerBinding(ProvisionBinding binding) {
    return registerBinding(binding, false);
  }

  MembersInjectionBinding registerBinding(MembersInjectionBinding binding) {
    return registerBinding(binding, false);
  }

  /**
   * Registers the binding for generation and later lookup. If the binding is resolved, we also
   * attempt to register an unresolved version of it.
   */
  private ProvisionBinding registerBinding(
      ProvisionBinding binding, boolean warnIfNotAlreadyGenerated) {
    ClassName factoryName = generatedClassNameForBinding(binding);
    provisionBindings.tryRegisterBinding(binding, factoryName, warnIfNotAlreadyGenerated);
    if (binding.unresolved().isPresent()) {
      provisionBindings.tryToGenerateBinding(
          binding.unresolved().get(), factoryName, warnIfNotAlreadyGenerated);
    }
    return binding;
  }

  /**
   * Registers the binding for generation and later lookup. If the binding is resolved, we also
   * attempt to register an unresolved version of it.
   */
  private MembersInjectionBinding registerBinding(
      MembersInjectionBinding binding, boolean warnIfNotAlreadyGenerated) {
    /*
     * We generate MembersInjector classes for types with @Inject constructors only if they have any
     * injection sites.
     *
     * We generate MembersInjector classes for types without @Inject constructors only if they have
     * local (non-inherited) injection sites.
     *
     * Warn only when registering bindings post-hoc for those types.
     */
    warnIfNotAlreadyGenerated =
        warnIfNotAlreadyGenerated
            && (!injectedConstructors(binding.bindingElement()).isEmpty()
                ? !binding.injectionSites().isEmpty()
                : binding.hasLocalInjectionSites());
    ClassName membersInjectorName = generatedClassNameForBinding(binding);
    membersInjectionBindings.tryRegisterBinding(
        binding, membersInjectorName, warnIfNotAlreadyGenerated);
    if (binding.unresolved().isPresent()) {
      membersInjectionBindings.tryToGenerateBinding(
          binding.unresolved().get(), membersInjectorName, warnIfNotAlreadyGenerated);
    }
    return binding;
  }

  Optional<ProvisionBinding> getOrFindProvisionBinding(Key key) {
    checkNotNull(key);
    if (!key.isValidImplicitProvisionKey(types)) {
      return Optional.absent();
    }
    ProvisionBinding binding = provisionBindings.getBinding(key);
    if (binding != null) {
      return Optional.of(binding);
    }

    // ok, let's see if we can find an @Inject constructor
    TypeElement element = MoreElements.asType(types.asElement(key.type()));
    ImmutableSet<ExecutableElement> injectConstructors = injectedConstructors(element);
    switch (injectConstructors.size()) {
      case 0:
        // No constructor found.
        return Optional.absent();
      case 1:
        ProvisionBinding constructorBinding =
            provisionBindingFactory.forInjectConstructor(
                Iterables.getOnlyElement(injectConstructors), Optional.of(key.type()));
        return Optional.of(registerBinding(constructorBinding, true));
      default:
        throw new IllegalStateException("Found multiple @Inject constructors: "
            + injectConstructors);
    }
  }

  private ImmutableSet<ExecutableElement> injectedConstructors(TypeElement element) {
    return FluentIterable.from(constructorsIn(element.getEnclosedElements()))
        .filter(
            new Predicate<ExecutableElement>() {
              @Override
              public boolean apply(ExecutableElement constructor) {
                return isAnnotationPresent(constructor, Inject.class);
              }
            })
        .toSet();
  }

  /**
   * Returns a {@link MembersInjectionBinding} for {@code key}. If none has been registered yet,
   * registers one, along with all necessary members injection bindings for superclasses.
   */
  MembersInjectionBinding getOrFindMembersInjectionBinding(Key key) {
    checkNotNull(key);
    // TODO(gak): is checking the kind enough?
    checkArgument(key.isValidMembersInjectionKey());
    MembersInjectionBinding binding = membersInjectionBindings.getBinding(key);
    if (binding != null) {
      return binding;
    }
    MembersInjectionBinding newBinding =
        membersInjectionBindingFactory.forInjectedType(
            asDeclared(key.type()), Optional.of(key.type()));
    registerBinding(newBinding, true);
    if (newBinding.parentKey().isPresent()
        && newBinding.injectionStrategy().equals(INJECT_MEMBERS)) {
      getOrFindMembersInjectionBinding(newBinding.parentKey().get());
    }
    return newBinding;
  }
}