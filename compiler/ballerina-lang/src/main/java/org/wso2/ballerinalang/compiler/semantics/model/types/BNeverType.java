/*
 *  Copyright (c) 2020, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *  WSO2 Inc. licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */
package org.wso2.ballerinalang.compiler.semantics.model.types;

import io.ballerina.types.PredefinedType;
import org.wso2.ballerinalang.compiler.semantics.model.TypeVisitor;
import org.wso2.ballerinalang.compiler.util.Names;
import org.wso2.ballerinalang.compiler.util.TypeTags;
import org.wso2.ballerinalang.util.Flags;

/**
 * {@code BNeverType} represents the singleton type when functions don't have a return value.
 * The value of the {@code BNeverType} is written as 'never'
 *
 * @since 2.0.0-preview1
 */

public class BNeverType extends BType {

    protected BNeverType() {
        super(TypeTags.NEVER, null, Flags.READONLY, PredefinedType.NEVER);
    }

    @Override
    public String toString() {
        return Names.NEVER.value;
    }

    @Override
    public void accept(TypeVisitor visitor) {
        visitor.visit(this);
    }
}
