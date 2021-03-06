/*
 * Copyright 2018 the original author or authors.
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

package org.gradle.api.internal;

import org.gradle.api.Action;

import javax.annotation.Nullable;

public interface CollectionCallbackActionDecorator {

    // TODO when removing this feature toggle, also remove this class from the allowed
    //      classes in org.gradle.integtests.tooling.fixture.ToolingApiClasspathProvider
    String CALLBACK_EXECUTION_BUILD_OPS_TOGGLE = "org.gradle.internal.domain-collection-callback-ops";

    @Nullable
    <T> Action<T> decorate(@Nullable Action<T> action);

    CollectionCallbackActionDecorator NOOP = new CollectionCallbackActionDecorator() {
        @Override
        public <T> Action<T> decorate(@Nullable Action<T> action) {
            return action;
        }
    };
}
