/*
 * Copyright 2014 Nicolas Morel
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

package com.github.nmorel.gwtjackson.benchmark.client.mechanism;

import com.github.nmorel.gwtjackson.benchmark.client.data.DataContainer;
import com.github.nmorel.gwtjackson.client.ObjectMapper;
import com.google.gwt.core.client.GWT;

/**
 * @author Nicolas Morel
 */
public class GwtJackson extends Mechanism {

    public static interface DataContainerMapper extends ObjectMapper<DataContainer> {}

    public GwtJackson() {
        super( "gwt-jackson" );
    }

    @Override
    protected ObjectMapper<DataContainer> newMapper() {
        return GWT.create( DataContainerMapper.class );
    }
}
