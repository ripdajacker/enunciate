/**
 * Copyright © 2006-2016 Web Cohesion (info@webcohesion.com)
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
package com.webcohesion.enunciate.modules.jackson.model.types;

import com.fasterxml.jackson.annotation.JacksonAnnotationsInside;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.webcohesion.enunciate.javac.decorations.Annotations;
import com.webcohesion.enunciate.javac.decorations.DecoratedProcessingEnvironment;
import com.webcohesion.enunciate.javac.decorations.element.DecoratedElement;
import com.webcohesion.enunciate.javac.decorations.type.DecoratedTypeMirror;
import com.webcohesion.enunciate.metadata.rs.TypeHint;
import com.webcohesion.enunciate.modules.jackson.EnunciateJacksonContext;
import com.webcohesion.enunciate.modules.jackson.model.Accessor;
import com.webcohesion.enunciate.modules.jackson.model.adapters.Adaptable;
import com.webcohesion.enunciate.modules.jackson.model.util.MapType;
import com.webcohesion.enunciate.util.TypeHintUtils;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * A decorator that decorates the relevant type mirrors as json type mirrors.
 *
 * @author Ryan Heaton
 */
@SuppressWarnings ( "unchecked" )
public class JsonTypeFactory {

  /**
   * Find the specified type of the given adaptable element, if it exists.
   *
   * @param adaptable The adaptable element for which to find the specified type.
   * @param context The context
   * @return The specified JSON type, or null if it doesn't exist.
   */
  public static JsonType findSpecifiedType(Adaptable adaptable, EnunciateJacksonContext context) {
    JsonType jsonType = null;

    if (adaptable instanceof Accessor) {
      Accessor accessor = (Accessor) adaptable;
      jsonType = getTypeFromAnnotations(accessor, context);
      if(jsonType != null) {
        return jsonType;
      }
    }

    if (adaptable.isAdapted()) {
      jsonType = getJsonType(adaptable.getAdapterType().getAdaptingType(), context);
    }

    return jsonType;
  }

  /**
   * Get json type of an element from annotations.
   *
   * @param element the element for which to find the type of.
   * @param context the context
   * @return the json type from annotations, or null if not found.
   */
  static JsonType getTypeFromAnnotations(Element element, EnunciateJacksonContext context) {
    TypeHint typeHint = element.getAnnotation(TypeHint.class);
    if (typeHint != null) {
      TypeMirror hint = TypeHintUtils.getTypeHint(typeHint, context.getContext().getProcessingEnvironment(), null);
      if (hint != null) {
        return getJsonType(hint, context);
      }
    }

    JsonFormat format = element.getAnnotation(JsonFormat.class);
    if (format != null) {
      switch (format.shape()) {
        case ARRAY:
        return KnownJsonType.ARRAY;
        case BOOLEAN:
        return KnownJsonType.BOOLEAN;
        case NUMBER:
        case NUMBER_FLOAT:
        return KnownJsonType.NUMBER;
        case NUMBER_INT:
        return KnownJsonType.WHOLE_NUMBER;
        case OBJECT:
        return KnownJsonType.OBJECT;
        case STRING:
        case SCALAR:
        return KnownJsonType.STRING;
        case ANY:
        default:
        //fall through...
      }
    }

    final JsonSerialize serializeInfo = element.getAnnotation(JsonSerialize.class);

    if (serializeInfo != null) {
      DecoratedProcessingEnvironment env = context.getContext().getProcessingEnvironment();

      DecoratedTypeMirror using = Annotations.mirrorOf(serializeInfo::using, env, JsonSerializer.None.class);

      if (using != null) {
        //we're using some custom serialization, so we just have to return a generic object.
        return KnownJsonType.OBJECT;
      }
      else {
        DecoratedTypeMirror as = Annotations.mirrorOf(serializeInfo::as, env, Void.class);

        if (as != null) {
          return getJsonType(as, context);
        }
        else {
          DecoratedTypeMirror contentAs = Annotations.mirrorOf(serializeInfo::contentAs, env, Void.class);

          DecoratedTypeMirror contentUsing = Annotations.mirrorOf(serializeInfo::contentUsing, env, JsonSerializer.None.class);

          DecoratedTypeMirror accessorType = (DecoratedTypeMirror) element.asType();
          if (accessorType.isCollection() || accessorType.isArray() || accessorType.isStream()) {
            if (contentUsing != null) {
              //we're using some custom serialization of the elements of the collection, so
              //the json type has to be just a list of object.
              return new JsonArrayType(KnownJsonType.OBJECT);
            }
            else if (contentAs != null) {
              return new JsonArrayType(getJsonType(contentAs, context));
            }
          }
          else {
            MapType mapType = MapType.findMapType(accessorType, context);
            if (mapType != null) {
              DecoratedTypeMirror keyAs = Annotations.mirrorOf(serializeInfo::keyAs, env, Void.class);

              DecoratedTypeMirror keyUsing = Annotations.mirrorOf(serializeInfo::keyUsing, env, JsonSerializer.None.class);

              if (keyAs != null || contentAs != null) {
                JsonType keyType = keyUsing == null ? getJsonType(keyAs == null ? (DecoratedTypeMirror) mapType.getKeyType() : keyAs, context) : KnownJsonType.OBJECT;
                JsonType valueType = contentUsing == null ? getJsonType(contentAs == null ? (DecoratedTypeMirror) mapType.getValueType() : contentAs, context) : KnownJsonType.OBJECT;
                return new JsonMapType(keyType, valueType);
              }
            }
          }
        }
      }
    }

    List<? extends AnnotationMirror> annotations = element.getAnnotationMirrors();
    for(AnnotationMirror annotation : annotations) {
      Element type = annotation.getAnnotationType().asElement();
      JacksonAnnotationsInside annotationsInside = type.getAnnotation(JacksonAnnotationsInside.class);
      if(annotationsInside != null) {
        return getTypeFromAnnotations(type, context);
      }
    }

    return null;
  }

  /**
   * Get the json type for the specified type mirror.
   *
   * @param typeMirror The type mirror.
   * @param context The context.
   * @return The json type for the specified type mirror.
   */
  public static JsonType getJsonType(TypeMirror typeMirror, EnunciateJacksonContext context) {
    return typeMirror.accept(new JsonTypeVisitor(), new JsonTypeVisitor.Context(context, false, false, new LinkedList<String>()));
  }

}
