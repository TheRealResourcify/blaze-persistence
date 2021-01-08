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

package com.blazebit.persistence.impl.function.window.row;

import com.blazebit.persistence.impl.function.window.AbstractWindowFunction;
import com.blazebit.persistence.spi.DbmsDialect;
import com.blazebit.persistence.spi.FunctionRenderContext;

/**
 * Number of the current row within its partition, counting from 1.
 *
 * Syntax: row_number()
 *
 * @author Jan-Willem Gmelig Meyling
 * @author Sayra Ranjha
 * @since 1.4.0
 */
public class RowNumberFunction extends AbstractWindowFunction {

    public static final String FUNCTION_NAME = "ROW_NUMBER";

    public RowNumberFunction(DbmsDialect dbmsDialect) {
        super(FUNCTION_NAME, dbmsDialect.isNullSmallest(), dbmsDialect.supportsWindowNullPrecedence(), dbmsDialect.supportsFilterClause(), false);
    }

    @Override
    public Class<?> getReturnType(Class<?> firstArgumentType) {
        return Integer.class;
    }

    @Override
    protected void renderArguments(FunctionRenderContext context, WindowFunction windowFunction) {
        // No-op
    }

    @Override
    protected boolean requiresOver() {
        return true;
    }
}
