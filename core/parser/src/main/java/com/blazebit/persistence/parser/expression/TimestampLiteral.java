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

package com.blazebit.persistence.parser.expression;

import com.blazebit.persistence.parser.util.TypeUtils;

import java.sql.Timestamp;
import java.util.Date;

/**
 *
 * @author Moritz Becker
 * @since 1.2.0
 */
public class TimestampLiteral extends TemporalLiteral {

    public TimestampLiteral(Date value) {
        super(value);
    }

    @Override
    public Expression copy(ExpressionCopyContext copyContext) {
        return new TimestampLiteral((Date) value.clone());
    }

    @Override
    public void accept(Visitor visitor) {
        visitor.visit(this);
    }

    @Override
    public <T> T accept(ResultVisitor<T> visitor) {
        return visitor.visit(this);
    }

    @Override
    public String toString() {
        if (value instanceof java.sql.Timestamp) {
            return TypeUtils.TIMESTAMP_CONVERTER.toString((Timestamp) value);
        } else {
            return TypeUtils.DATE_TIMESTAMP_CONVERTER.toString(value);
        }
    }
}
