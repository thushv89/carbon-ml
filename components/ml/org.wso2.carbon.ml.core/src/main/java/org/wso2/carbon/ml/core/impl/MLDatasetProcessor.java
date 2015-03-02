/*
 * Copyright (c) 2015, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
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
package org.wso2.carbon.ml.core.impl;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.ml.commons.domain.SamplePoints;
import org.wso2.carbon.ml.core.domain.MLDataset;
import org.wso2.carbon.ml.core.exceptions.MLDataProcessingException;
import org.wso2.carbon.ml.core.exceptions.MLInputAdapterException;
import org.wso2.carbon.ml.core.exceptions.MLMalformedDatasetException;
import org.wso2.carbon.ml.core.exceptions.MLOutputAdapterException;
import org.wso2.carbon.ml.core.interfaces.MLInputAdapter;
import org.wso2.carbon.ml.core.interfaces.MLOutputAdapter;
import org.wso2.carbon.ml.core.utils.MLUtils;
import org.wso2.carbon.ml.core.utils.ThreadExecutor;
import org.wso2.carbon.ml.dataset.exceptions.MLConfigurationParserException;

/**
 * This object is responsible for reading a data-set using a {@link MLInputAdapter}, extracting meta-data, persist in ML
 * db and store the data stream in a user defined target path.
 */
public class MLDatasetProcessor {
    private static final Log log = LogFactory.getLog(MLDatasetProcessor.class);
    private Properties configuration;
    private MLConfigurationParser mlConfiguration;
    private ThreadExecutor threadExecutor;

    public MLDatasetProcessor(MLConfigurationParser configParser) {
        mlConfiguration = configParser;
        try {
            configuration = configParser.getProperties();
        } catch (MLConfigurationParserException ignore) {
            log.warn("Failed to extract properties from ML configuration file.");
            configuration = new Properties();
        }
        threadExecutor = new ThreadExecutor(configuration);
    }

    /**
     * Checks whether a given name for a data-set is valid against a given tenant id.
     * 
     * @param tenantId tenant who's uploading the data-set.
     * @param datasetName name of the data-set to be uploaded.
     * @return if it is existed in a different tenant -> return false, if it is existed in the same tenant -> return
     *         true
     */
    public boolean isValidName(int tenantId, String datasetName) {
        // TODO call db and check whether the datasetName is existed in a different tenant
        // if it is existed -> return false
        // if it is existed in the same tenant -> return true
        return false;
    }

    /**
     * Checks whether a given version is valid against the given data-set.
     * 
     * @param tenantId tenant who's requesting the validation.
     * @param datasetName name of the data-set to be uploaded.
     * @param datasetVersion version of the data-set to be uploaded.
     * @return if it is existed -> return false, if it is not -> return true
     */
    public boolean isValidVersion(int tenantId, String datasetName, String datasetVersion) {
        // TODO call db and check whether the same version is existed for this dataset name in this tenant.
        return false;
    }

    /**
     * Process a given data-set; read the data-set as a stream, extract meta-data, persist the data-set in a target path
     * and persist meta-data in ML db.
     * 
     * @param dataset
     */
    public void process(MLDataset dataset) throws MLDataProcessingException {

        MLIOFactory ioFactory = new MLIOFactory(configuration);
        MLInputAdapter inputAdapter = ioFactory.getInputAdapter(dataset.getDataSourceType());
        handleNull(
                inputAdapter,
                String.format("Invalid data source type: %s [data-set] %s", dataset.getDataSourceType(),
                        dataset.getName()));
        MLOutputAdapter outputAdapter = ioFactory.getOutputAdapter(dataset.getDataTargetType());
        handleNull(
                outputAdapter,
                String.format("Invalid data target type: %s [data-set] %s", dataset.getDataTargetType(),
                        dataset.getName()));
        handleNull(dataset.getSourcePath(),
                String.format("Null data source path provided [data-set] %s", dataset.getName()));
        InputStream input = null;
        try {
            // write the data-set to a server side location
            input = inputAdapter.readDataset(dataset.getSourcePath());
            handleNull(
                    input,
                    String.format("Null input stream read from the source data-set path: %s [data-set] %s",
                            dataset.getSourcePath(), dataset.getName()));
            String targetPath = ioFactory.getTargetPath(dataset.getName() + dataset.getTenantId());
            handleNull(targetPath, String.format("Null target path for the [data-set] %s ", dataset.getName()));
            outputAdapter.writeDataset(targetPath, input);

            // extract sample points
            SamplePoints samplePoints = MLUtils.getSamplePoints(input, dataset.getDataType(), mlConfiguration.getSummaryStatisticsSettings()
                    .getSampleSize());

            // start summary stats generation in a new thread, pass data set version id
            threadExecutor.execute(new SummaryStatsGenerator(mlConfiguration, samplePoints));

            // TODO persist into the ML db
                // persist sample points
        } catch (MLInputAdapterException e) {
            throw new MLDataProcessingException(e);
        } catch (MLOutputAdapterException e) {
            throw new MLDataProcessingException(e);
        } catch (MLMalformedDatasetException e) {
            throw new MLDataProcessingException(e);
        } catch (MLConfigurationParserException e) {
            throw new MLDataProcessingException(e);
        } finally {
            if (input != null) {
                try {
                    input.close();
                } catch (IOException e) {
                    log.error("Failed to close the input stream.", e);
                }
            }
        }
    }

    private void handleNull(Object obj, String msg) throws MLDataProcessingException {
        if (obj == null) {
            throw new MLDataProcessingException(msg);
        }
    }

}
