/*
 * Copyright (c) 2014, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.carbon.ml.core.spark.transformations;

import org.apache.spark.api.java.function.Function;

/**
 * A filter to remove header row
 */
public class HeaderFilter implements Function<String, Boolean> {

    private static final long serialVersionUID = -6996897057400853414L;
    private String header;

    public HeaderFilter(String header) {
        this.header = header;
    }

    @Override
    public Boolean call(String line) {
        Boolean isRow = true;
        if (line.equals(this.header)) {
            isRow = false;
        }
        return isRow;
    }
}
