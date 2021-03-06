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

import com.google.auto.common.MoreTypes;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import dagger.Factory;
import dagger.MembersInjector;
import dagger.Provides.Type;
import dagger.internal.codegen.writer.ClassName;
import dagger.internal.codegen.writer.ClassWriter;
import dagger.internal.codegen.writer.ConstructorWriter;
import dagger.internal.codegen.writer.EnumWriter;
import dagger.internal.codegen.writer.FieldWriter;
import dagger.internal.codegen.writer.JavaWriter;
import dagger.internal.codegen.writer.MethodWriter;
import dagger.internal.codegen.writer.ParameterizedTypeName;
import dagger.internal.codegen.writer.Snippet;
import dagger.internal.codegen.writer.TypeName;
import dagger.internal.codegen.writer.TypeNames;
import dagger.internal.codegen.writer.TypeVariableName;
import dagger.internal.codegen.writer.TypeWriter;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import javax.annotation.Generated;
import javax.annotation.processing.Filer;
import javax.inject.Inject;
import javax.lang.model.element.Element;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.type.TypeMirror;

import static com.google.common.base.Preconditions.checkState;
import static dagger.Provides.Type.SET;
import static dagger.internal.codegen.ProvisionBinding.Kind.INJECTION;
import static dagger.internal.codegen.ProvisionBinding.Kind.PROVISION;
import static dagger.internal.codegen.SourceFiles.factoryNameForProvisionBinding;
import static dagger.internal.codegen.SourceFiles.frameworkTypeUsageStatement;
import static dagger.internal.codegen.SourceFiles.parameterizedFactoryNameForProvisionBinding;
import static dagger.internal.codegen.writer.Snippet.makeParametersSnippet;
import static javax.lang.model.element.Modifier.FINAL;
import static javax.lang.model.element.Modifier.PRIVATE;
import static javax.lang.model.element.Modifier.PUBLIC;

/**
 * Generates {@link Factory} implementations from {@link ProvisionBinding} instances for
 * {@link Inject} constructors.
 *
 * @author Gregory Kick
 * @since 2.0
 */
final class FactoryGenerator extends SourceFileGenerator<ProvisionBinding> {
  private final DependencyRequestMapper dependencyRequestMapper;

  FactoryGenerator(Filer filer, DependencyRequestMapper dependencyRequestMapper) {
    super(filer);
    this.dependencyRequestMapper = dependencyRequestMapper;
  }

  @Override
  ClassName nameGeneratedType(ProvisionBinding binding) {
    return factoryNameForProvisionBinding(binding);
  }

  @Override
  Iterable<? extends Element> getOriginatingElements(ProvisionBinding binding) {
    return ImmutableSet.of(binding.bindingElement());
  }

  @Override
  Optional<? extends Element> getElementForErrorReporting(ProvisionBinding binding) {
    return Optional.of(binding.bindingElement());
  }

  @Override
  ImmutableSet<JavaWriter> write(ClassName generatedTypeName, ProvisionBinding binding) {
    // We don't want to write out resolved bindings -- we want to write out the generic version.
    checkState(!binding.isResolved());

    TypeMirror keyType = binding.provisionType().equals(Type.MAP)
        ? Util.getProvidedValueTypeOfMap(MoreTypes.asDeclared(binding.key().type()))
        : binding.key().type();
    TypeName providedTypeName = TypeNames.forTypeMirror(keyType);
    JavaWriter writer = JavaWriter.inPackage(generatedTypeName.packageName());

    final TypeWriter factoryWriter;
    final Optional<ConstructorWriter> constructorWriter;
    List<TypeVariableName> typeParameters = Lists.newArrayList();
    for (TypeParameterElement typeParameter : binding.bindingTypeElement().getTypeParameters()) {
     typeParameters.add(TypeVariableName.fromTypeParameterElement(typeParameter));          
    }
    switch (binding.factoryCreationStrategy()) {
      case ENUM_INSTANCE:
        EnumWriter enumWriter = writer.addEnum(generatedTypeName.simpleName());
        enumWriter.addConstant("INSTANCE");
        constructorWriter = Optional.absent();
        factoryWriter = enumWriter;
        // If we have type parameters, then remove the parameters from our providedTypeName,
        // since we'll be implementing an erased version of it.
        if (!typeParameters.isEmpty()) {
          factoryWriter.annotate(SuppressWarnings.class).setValue("rawtypes");
          providedTypeName = ((ParameterizedTypeName)providedTypeName).type();
        }
        break;
      case CLASS_CONSTRUCTOR:
        ClassWriter classWriter = writer.addClass(generatedTypeName.simpleName());
        classWriter.addTypeParameters(typeParameters);
        classWriter.addModifiers(FINAL);
        constructorWriter = Optional.of(classWriter.addConstructor());
        constructorWriter.get().addModifiers(PUBLIC);
        factoryWriter = classWriter;
        if (binding.bindingKind().equals(PROVISION)) {
          factoryWriter.addField(binding.bindingTypeElement(), "module")
              .addModifiers(PRIVATE, FINAL);
          constructorWriter.get().addParameter(binding.bindingTypeElement(), "module");
          constructorWriter.get().body()
              .addSnippet("assert module != null;")
              .addSnippet("this.module = module;");
        }
        break;
      default:
        throw new AssertionError();
    }

    factoryWriter.annotate(Generated.class).setValue(ComponentProcessor.class.getName());
    factoryWriter.addModifiers(PUBLIC);
    factoryWriter.addImplementedType(
        ParameterizedTypeName.create(ClassName.fromClass(Factory.class), providedTypeName));
    

    MethodWriter getMethodWriter = factoryWriter.addMethod(providedTypeName, "get");
    getMethodWriter.annotate(Override.class);
    getMethodWriter.addModifiers(PUBLIC);

    if (binding.memberInjectionRequest().isPresent()) {
      ParameterizedTypeName membersInjectorType = ParameterizedTypeName.create(
          MembersInjector.class, providedTypeName);
      factoryWriter.addField(membersInjectorType, "membersInjector").addModifiers(PRIVATE, FINAL);
      constructorWriter.get().addParameter(membersInjectorType, "membersInjector");
      constructorWriter.get().body()
          .addSnippet("assert membersInjector != null;")
          .addSnippet("this.membersInjector = membersInjector;");
    }

    ImmutableMap<BindingKey, FrameworkField> fields =
        SourceFiles.generateBindingFieldsForDependencies(
            dependencyRequestMapper, binding.dependencies());

    for (FrameworkField bindingField : fields.values()) {
      TypeName fieldType = bindingField.frameworkType();
      FieldWriter field = factoryWriter.addField(fieldType, bindingField.name());
      field.addModifiers(PRIVATE, FINAL);
      constructorWriter.get().addParameter(field.type(), field.name());
      constructorWriter.get().body()
          .addSnippet("assert %s != null;", field.name())
          .addSnippet("this.%1$s = %1$s;", field.name());
    }
    
    // If constructing a factory for @Inject bindings, we use a static create method
    // so that generated components can avoid having to refer to the generic types
    // of the factory.  (Otherwise they may have visibility problems referring to the types.)
    if (binding.bindingKind().equals(INJECTION)) {
      // The return type is usually the same as the implementing type, except in the case
      // of enums with type variables (where we cast).
      TypeName returnType = ParameterizedTypeName.create(ClassName.fromClass(Factory.class),
          TypeNames.forTypeMirror(keyType));
      MethodWriter createMethodWriter = factoryWriter.addMethod(returnType, "create");
      createMethodWriter.addTypeParameters(typeParameters);
      createMethodWriter.addModifiers(Modifier.PUBLIC, Modifier.STATIC);
      Map<String, TypeName> params = constructorWriter.isPresent()
          ? constructorWriter.get().parameters() : ImmutableMap.<String, TypeName>of();
      for (Map.Entry<String, TypeName> param : params.entrySet()) {
        createMethodWriter.addParameter(param.getValue(), param.getKey());      
      }
      switch (binding.factoryCreationStrategy()) {
        case ENUM_INSTANCE:
          if (typeParameters.isEmpty()) {
            createMethodWriter.body().addSnippet(" return INSTANCE;");
          } else {
            // We use an unsafe cast here because the types are different.
            // It's safe because the type is never referenced anywhere.
            createMethodWriter.annotate(SuppressWarnings.class).setValue("unchecked");
            createMethodWriter.body().addSnippet(" return (Factory) INSTANCE;");
          }
          break;
        case CLASS_CONSTRUCTOR:
          createMethodWriter.body().addSnippet(" return new %s(%s);",
              parameterizedFactoryNameForProvisionBinding(binding),
              Joiner.on(", ").join(params.keySet()));
          break;
        default:
          throw new AssertionError();
      }
    }

    List<Snippet> parameters = Lists.newArrayList();
    for (DependencyRequest dependency : binding.dependencies()) {
      parameters.add(frameworkTypeUsageStatement(
          Snippet.format(fields.get(BindingKey.forDependencyRequest(dependency)).name()),
          dependency.kind()));
    }
    Snippet parametersSnippet = makeParametersSnippet(parameters);

    if (binding.bindingKind().equals(PROVISION)) {
      if (binding.provisionType().equals(SET)) {
        getMethodWriter.body().addSnippet("return %s.singleton(module.%s(%s));",
            ClassName.fromClass(Collections.class),
            binding.bindingElement().getSimpleName(),
            parametersSnippet);
      } else {
        getMethodWriter.body().addSnippet("return module.%s(%s);",
            binding.bindingElement().getSimpleName(),
            parametersSnippet);
      }
    } else if (binding.memberInjectionRequest().isPresent()) {
      getMethodWriter.body().addSnippet("%1$s instance = new %1$s(%2$s);",
          providedTypeName, parametersSnippet);
      getMethodWriter.body().addSnippet("membersInjector.injectMembers(instance);");
      getMethodWriter.body().addSnippet("return instance;");
    } else {
      getMethodWriter.body()
          .addSnippet("return new %s(%s);", providedTypeName, parametersSnippet);
    }

    // TODO(gak): write a sensible toString
    return ImmutableSet.of(writer);
  }
}
