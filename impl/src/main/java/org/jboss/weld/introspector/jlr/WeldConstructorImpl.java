/*
 * JBoss, Home of Professional Open Source
 * Copyright 2008, Red Hat, Inc., and individual contributors
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jboss.weld.introspector.jlr;

import org.jboss.weld.exceptions.DefinitionException;
import org.jboss.weld.introspector.ConstructorSignature;
import org.jboss.weld.introspector.TypeClosureLazyValueHolder;
import org.jboss.weld.introspector.WeldClass;
import org.jboss.weld.introspector.WeldConstructor;
import org.jboss.weld.introspector.WeldParameter;
import org.jboss.weld.logging.messages.ReflectionMessage;
import org.jboss.weld.resources.ClassTransformer;
import org.jboss.weld.util.LazyValueHolder;
import org.jboss.weld.util.reflection.Formats;
import org.jboss.weld.util.reflection.Reflections;
import org.jboss.weld.util.reflection.SecureReflections;

import javax.enterprise.inject.spi.AnnotatedConstructor;
import javax.enterprise.inject.spi.AnnotatedParameter;
import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Represents an annotated constructor
 * <p/>
 * This class is immutable, and therefore threadsafe
 *
 * @param <T> exact type
 * @author Pete Muir
 * @author Ales Justin
 */
public class WeldConstructorImpl<T> extends AbstractWeldCallable<T, T, Constructor<T>> implements WeldConstructor<T> {
    private static final Annotation[] EMPTY = new Annotation[0];

    // The underlying constructor
    private final Constructor<T> constructor;

    // The list of parameter abstractions
    private final List<WeldParameter<?, T>> parameters;

    private final ConstructorSignature signature;

    public static <T> WeldConstructor<T> of(Constructor<T> constructor, WeldClass<T> declaringClass, ClassTransformer classTransformer) {
        return new WeldConstructorImpl<T>(constructor, constructor.getDeclaringClass(), constructor.getDeclaringClass(), null, new TypeClosureLazyValueHolder(constructor.getDeclaringClass()), buildAnnotationMap(constructor.getAnnotations()), buildAnnotationMap(constructor.getDeclaredAnnotations()), declaringClass, classTransformer);
    }

    public static <T> WeldConstructor<T> of(AnnotatedConstructor<T> annotatedConstructor, WeldClass<T> declaringClass, ClassTransformer classTransformer) {
        return new WeldConstructorImpl<T>(annotatedConstructor.getJavaMember(), annotatedConstructor.getJavaMember().getDeclaringClass(), annotatedConstructor.getBaseType(), annotatedConstructor, new TypeClosureLazyValueHolder(annotatedConstructor.getTypeClosure()), buildAnnotationMap(annotatedConstructor.getAnnotations()), buildAnnotationMap(annotatedConstructor.getAnnotations()), declaringClass, classTransformer);
    }

    /**
     * Constructor
     * <p/>
     * Initializes the superclass with the build annotations map
     *
     * @param constructor    The constructor method
     * @param declaringClass The declaring class
     */
    private WeldConstructorImpl(Constructor<T> constructor, final Class<T> rawType, final Type type, AnnotatedConstructor<T> annotatedConstructor, LazyValueHolder<Set<Type>> typeClosure, Map<Class<? extends Annotation>, Annotation> annotationMap, Map<Class<? extends Annotation>, Annotation> declaredAnnotationMap, WeldClass<T> declaringClass, ClassTransformer classTransformer) {
        super(annotationMap, declaredAnnotationMap, classTransformer, constructor, rawType, type, typeClosure, declaringClass);
        this.constructor = constructor;

        this.parameters = new ArrayList<WeldParameter<?, T>>();

        final Class<?>[] parameterTypes = constructor.getParameterTypes();
        if (annotatedConstructor == null) {
            final Annotation[][] parameterAnnotations = constructor.getParameterAnnotations();
            final Type[] genericParameterTypes = constructor.getGenericParameterTypes();

            if (parameterTypes.length == genericParameterTypes.length) {
                int nesting = Reflections.getNesting(declaringClass.getJavaClass());
                for (int i = 0; i < parameterTypes.length; i++) {
                    int gi = i - nesting;
                    Class<?> clazz = parameterTypes[i];

                    Type parameterType;
                    if (constructor.getGenericParameterTypes().length > gi && gi >= 0) {
                        parameterType = constructor.getGenericParameterTypes()[gi];
                    } else {
                        parameterType = clazz;
                    }

                    Annotation[] annotations;
                    if (gi >= 0 && parameterAnnotations[gi].length > 0) {
                        annotations = parameterAnnotations[gi];
                    } else {
                        annotations = EMPTY;
                    }
                    WeldParameter<?, T> parameter = WeldParameterImpl.of(annotations, clazz, parameterType, this, i, classTransformer);
                    this.parameters.add(parameter);
                }
            } else {
                /*
                 * We are seeing either
                 * http://bugs.sun.com/bugdatabase/view_bug.do?bug_id=6520205
                 * or
                 * http://bugs.sun.com/bugdatabase/view_bug.do?bug_id=5087240
                 * or both.
                 *
                 * It is difficult to detect and adapt to these bugs properly.
                 * Therefore, we pretend to see a no-args constructor. Although misleading,
                 * it is quite safe to do that since non-static inner classes are not CDI-managed
                 * beans anyway and CDI constructor injection into Enums is not supported.
                 */
            }
        } else {
            if (annotatedConstructor.getParameters().size() != parameterTypes.length) {
                throw new DefinitionException(ReflectionMessage.INCORRECT_NUMBER_OF_ANNOTATED_PARAMETERS_METHOD, annotatedConstructor.getParameters().size(), annotatedConstructor, annotatedConstructor.getParameters(), Arrays.asList(parameterTypes));
            } else {
                for (AnnotatedParameter<T> annotatedParameter : annotatedConstructor.getParameters()) {
                    WeldParameter<?, T> parameter = WeldParameterImpl.of(annotatedParameter.getAnnotations(), parameterTypes[annotatedParameter.getPosition()], annotatedParameter.getBaseType(), this, annotatedParameter.getPosition(), classTransformer);
                    this.parameters.add(parameter);
                }
            }

        }
        this.signature = new ConstructorSignatureImpl(this);
    }

    /**
     * Gets the constructor
     *
     * @return The constructor
     */
    public Constructor<T> getAnnotatedConstructor() {
        return constructor;
    }

    /**
     * Gets the delegate (constructor)
     *
     * @return The delegate
     */
    @Override
    public Constructor<T> getDelegate() {
        return constructor;
    }

    /**
     * Gets the abstracted parameters
     * <p/>
     * If the parameters are null, initalize them first
     *
     * @return A list of annotated parameter abstractions
     * @see org.jboss.weld.introspector.WeldConstructor#getWeldParameters()
     */
    public List<WeldParameter<?, T>> getWeldParameters() {
        return Collections.unmodifiableList(parameters);
    }

    /**
     * Gets parameter abstractions with a given annotation type.
     * <p/>
     * If the parameters are null, they are initializes first.
     * <p/>
     * The results of the method are not cached, as it is not called at runtime
     *
     * @param annotationType The annotation type to match
     * @return A list of matching parameter abstractions. An empty list is
     *         returned if there are no matches.
     * @see org.jboss.weld.introspector.WeldConstructor#getWeldParameters(Class)
     */
    public List<WeldParameter<?, T>> getWeldParameters(Class<? extends Annotation> annotationType) {
        List<WeldParameter<?, T>> ret = new ArrayList<WeldParameter<?, T>>();
        for (WeldParameter<?, T> parameter : parameters) {
            if (parameter.isAnnotationPresent(annotationType)) {
                ret.add(parameter);
            }
        }
        return ret;
    }

    /**
     * Creates a new instance
     *
     * @param parameters the parameters
     * @return An instance
     * @throws InvocationTargetException
     * @throws IllegalAccessException
     * @throws InstantiationException
     * @throws IllegalArgumentException
     * @see org.jboss.weld.introspector.WeldConstructor#newInstance(Object... params)
     */
    public T newInstance(Object... parameters) throws IllegalArgumentException, InstantiationException, IllegalAccessException, InvocationTargetException {
        return SecureReflections.ensureAccessible(getDelegate()).newInstance(parameters);
    }

    /**
     * The overridden equals operation
     *
     * @param other The instance to compare to
     * @return True if equal, false otherwise
     */
    @Override
    public boolean equals(Object other) {

        if (super.equals(other) && other instanceof WeldConstructor<?>) {
            WeldConstructor<?> that = (WeldConstructor<?>) other;
            return this.getJavaMember().equals(that.getJavaMember()) && this.getWeldParameters().equals(that.getWeldParameters());
        }
        return false;
    }

    @Override
    public int hashCode() {
        int hash = 1;
        hash = hash * 31 + getJavaMember().hashCode();
        hash = hash * 31 + getWeldParameters().hashCode();
        return hash;
    }

    /**
     * Gets a string representation of the constructor
     *
     * @return A string representation
     */
    @Override
    public String toString() {
        return "[constructor] " + Formats.addSpaceIfNeeded(Formats.formatAnnotations(getAnnotations())) + Formats.addSpaceIfNeeded(Formats.formatModifiers(getJavaMember().getModifiers())) + getDeclaringType().getName() + Formats.formatAsFormalParameterList(getWeldParameters());
    }

    public ConstructorSignature getSignature() {
        return signature;
    }

    public List<AnnotatedParameter<T>> getParameters() {
        return Collections.<AnnotatedParameter<T>>unmodifiableList(parameters);
    }

    public boolean isGeneric() {
        return getJavaMember().getTypeParameters().length > 0;
    }

}
