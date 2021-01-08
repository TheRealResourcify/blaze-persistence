/*
 * Copyright 2014 - 2021 Blazebit.
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

package com.blazebit.persistence.view.testsuite.basic.model;

import com.blazebit.persistence.view.EntityView;
import com.blazebit.persistence.view.Mapping;
import com.blazebit.persistence.view.testsuite.entity.EmbeddableTestEntity2;
import com.blazebit.persistence.view.testsuite.entity.EmbeddableTestEntityId2;

import java.io.Serializable;

/**
 *
 * @author Christian Beikov
 * @since 1.2.1
 */
@EntityView(EmbeddableTestEntity2.class)
public interface EmbeddableTestEntityIdView extends IdHolderView<EmbeddableTestEntityIdView.Id> {

    @Mapping("id.key")
    public String getIdKey();

    @Mapping("embeddable.name")
    String getName();

    @EntityView(EmbeddableTestEntityId2.class)
    interface Id extends Serializable {
        @Mapping("intIdEntity.id")
        Integer getIntIdEntityId();
        String getKey();
    }

}
