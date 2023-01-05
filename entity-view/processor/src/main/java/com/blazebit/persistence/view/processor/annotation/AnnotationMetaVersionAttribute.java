/*
 * Copyright 2014 - 2023 Blazebit.
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

package com.blazebit.persistence.view.processor.annotation;

import com.blazebit.persistence.view.processor.Constants;
import com.blazebit.persistence.view.processor.Context;
import com.blazebit.persistence.view.processor.TypeUtils;

import javax.lang.model.element.Element;

/**
 * @author Christian Beikov
 * @since 1.5.0
 */
public class AnnotationMetaVersionAttribute extends AnnotationMetaAttribute {

    public AnnotationMetaVersionAttribute(AnnotationMetaEntityView parent, Element entityVersionAttribute, Context context) {
        super(parent, entityVersionAttribute, TypeUtils.getType(entityVersionAttribute, context), TypeUtils.getRealType(entityVersionAttribute, context), null, context, true);
    }

    @Override
    public final String getMetaType() {
        return Constants.SINGULAR_ATTRIBUTE;
    }

    @Override
    public boolean isSynthetic() {
        return true;
    }
}
