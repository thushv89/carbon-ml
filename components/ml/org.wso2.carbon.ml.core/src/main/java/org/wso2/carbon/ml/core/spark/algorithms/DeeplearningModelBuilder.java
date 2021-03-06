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

package org.wso2.carbon.ml.core.spark.algorithms;

import hex.deeplearning.DeepLearningModel;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.mllib.evaluation.MulticlassMetrics;
import org.apache.spark.mllib.regression.LabeledPoint;
import org.apache.spark.rdd.RDD;
import org.wso2.carbon.ml.commons.constants.MLConstants;
import org.wso2.carbon.ml.commons.constants.MLConstants.DEEPLEARNING_ALGORITHM;
import org.wso2.carbon.ml.commons.domain.MLModel;
import org.wso2.carbon.ml.commons.domain.ModelSummary;
import org.wso2.carbon.ml.commons.domain.Workflow;
import org.wso2.carbon.ml.core.exceptions.AlgorithmNameException;
import org.wso2.carbon.ml.core.exceptions.MLModelBuilderException;
import org.wso2.carbon.ml.core.impl.H2OServer;
import org.wso2.carbon.ml.core.interfaces.MLModelBuilder;
import org.wso2.carbon.ml.core.internal.MLModelConfigurationContext;
import org.wso2.carbon.ml.core.spark.MulticlassConfusionMatrix;
import org.wso2.carbon.ml.core.spark.models.MLDeeplearningModel;
import org.wso2.carbon.ml.core.spark.summary.DeeplearningModelSummary;
import org.wso2.carbon.ml.core.spark.transformations.DoubleArrayToLabeledPoint;
import org.wso2.carbon.ml.core.utils.MLCoreServiceValueHolder;
import org.wso2.carbon.ml.core.utils.MLUtils;
import org.wso2.carbon.ml.database.DatabaseService;

import scala.Tuple2;

public class DeeplearningModelBuilder extends MLModelBuilder {
    private static final Log log = LogFactory.getLog(DeeplearningModelBuilder.class);

    public DeeplearningModelBuilder(MLModelConfigurationContext context) {
        super(context);
    }

    @Override
    public MLModel build() throws MLModelBuilderException {

        log.info("Start building the Stacked Autoencoders...");
        MLModelConfigurationContext context = getContext();
        JavaSparkContext sparkContext = null;
        DatabaseService databaseService = MLCoreServiceValueHolder.getInstance().getDatabaseService();
        MLModel mlModel = new MLModel();
        try {
            sparkContext = context.getSparkContext();
            Workflow workflow = context.getFacts();
            long modelId = context.getModelId();

            // pre-processing
            JavaRDD<double[]> features = SparkModelUtils.preProcess(context);

            List<double[]> featureList = features.collect();

            // generate train and test datasets by converting tokens to labeled points
            int responseIndex = context.getResponseIndex();
            SortedMap<Integer, String> includedFeatures = MLUtils.getIncludedFeaturesAfterReordering(workflow,
                    context.getNewToOldIndicesList(), responseIndex);

            DoubleArrayToLabeledPoint doubleArrayToLabeledPoint = new DoubleArrayToLabeledPoint();

            JavaRDD<LabeledPoint> labeledPoints = features.map(doubleArrayToLabeledPoint);

            JavaRDD<LabeledPoint>[] dataSplit = labeledPoints.randomSplit(
                    new double[] { workflow.getTrainDataFraction(), 1 - workflow.getTrainDataFraction() },
                    MLConstants.RANDOM_SEED);
            JavaRDD<LabeledPoint> trainingData = dataSplit[0];
            JavaRDD<LabeledPoint> testingData = dataSplit[1];

            List<LabeledPoint> trpoints = trainingData.collect();
            List<LabeledPoint> tepoints = testingData.collect();

            // create a deployable MLModel object
            mlModel.setAlgorithmName(workflow.getAlgorithmName());
            mlModel.setAlgorithmClass(workflow.getAlgorithmClass());
            mlModel.setFeatures(workflow.getIncludedFeatures());
            mlModel.setResponseVariable(workflow.getResponseVariable());
            mlModel.setEncodings(context.getEncodings());
            mlModel.setNewToOldIndicesList(context.getNewToOldIndicesList());
            mlModel.setResponseIndex(responseIndex);

            ModelSummary summaryModel = null;

            DEEPLEARNING_ALGORITHM deeplearningAlgorithm = DEEPLEARNING_ALGORITHM.valueOf(workflow.getAlgorithmName());
            switch (deeplearningAlgorithm) {
            case STACKED_AUTOENCODERS:
                log.info("Building summary model for SAE");
                summaryModel = buildStackedAutoencodersModel(sparkContext, modelId, trainingData, testingData,
                        workflow, mlModel, includedFeatures);
                log.info("Successful building summary model for SAE");
                break;
            default:
                throw new AlgorithmNameException("Incorrect algorithm name");
            }

            databaseService.updateModelSummary(modelId, summaryModel);
            return mlModel;
        } catch (Exception e) {
            throw new MLModelBuilderException("An error occurred while building supervised machine learning model: "
                    + e.getMessage(), e);
        } finally {
            // do something finally
        }
    }

    private int[] stringArrToIntArr(String str) {
        String[] tokens = str.split(",");
        int[] arr = new int[tokens.length];
        for (int i = 0; i < tokens.length; i++) {
            arr[i] = Integer.parseInt(tokens[i]);
        }
        return arr;
    }

    /**
     * Build the stacked autoencoder model
     * 
     * @param sparkContext
     * @param modelID model ID
     * @param trainingData training data to train the classifier
     * @param testingData testing data to test the classifier and get metrics
     * @param workflow workflow
     * @param mlModel MLModel to be updated with calcualted values
     * @param includedFeatures Included features
     * @return
     * @throws MLModelBuilderException
     */
    private ModelSummary buildStackedAutoencodersModel(JavaSparkContext sparkContext, long modelID,
            JavaRDD<LabeledPoint> trainingData, JavaRDD<LabeledPoint> testingData, Workflow workflow, MLModel mlModel,
            SortedMap<Integer, String> includedFeatures) throws MLModelBuilderException {
        try {
            log.info("Starting H2O Server");
            H2OServer.startH2O();
            StackedAutoencodersClassifier saeClassifier = new StackedAutoencodersClassifier();
            Map<String, String> hyperParameters = workflow.getHyperParameters();

            // train the stacked autoencoder
            DeepLearningModel deeplearningModel = saeClassifier.train(trainingData,
                    Integer.parseInt(hyperParameters.get(MLConstants.BATCH_SIZE)),
                    stringArrToIntArr(hyperParameters.get(MLConstants.LAYER_SIZES)),
                    hyperParameters.get(MLConstants.ACTIVATION_TYPE),
                    Integer.parseInt(hyperParameters.get(MLConstants.EPOCHS)),
                    workflow.getResponseVariable(), modelID);

            if (deeplearningModel == null) {
                log.info("DeeplearningModel is Null");
            }
            // make predictions with the trained model
            JavaPairRDD<Double, Double> predictionsAndLabels = saeClassifier.test(sparkContext, deeplearningModel,
                    testingData);

            // get model summary
            DeeplearningModelSummary deeplearningModelSummary = DeeplearningModelUtils.getDeeplearningModelSummary(
                    sparkContext, testingData, predictionsAndLabels);

            mlModel.setModel(new MLDeeplearningModel(deeplearningModel));

            deeplearningModelSummary.setFeatures(includedFeatures.values().toArray(new String[0]));
            deeplearningModelSummary.setAlgorithm(MLConstants.DEEPLEARNING_ALGORITHM.STACKED_AUTOENCODERS.toString());

            // set accuracy values
            MulticlassMetrics multiclassMetrics = getMulticlassMetrics(sparkContext, predictionsAndLabels);
            deeplearningModelSummary.setMulticlassConfusionMatrix(getMulticlassConfusionMatrix(multiclassMetrics,
                    mlModel));
            Double modelAccuracy = getModelAccuracy(multiclassMetrics);
            deeplearningModelSummary.setModelAccuracy(modelAccuracy);

            return deeplearningModelSummary;

        } catch (Exception e) {
            throw new MLModelBuilderException("An error occurred while building stacked autoencoders model: "
                    + e.getMessage(), e);
        }

    }

    /**
     * This method gets multi class metrics for a given set of prediction and label values
     * 
     * @param sparkContext JavaSparkContext
     * @param predictionsAndLabels Prediction and label values RDD
     */
    private MulticlassMetrics getMulticlassMetrics(JavaSparkContext sparkContext,
            JavaPairRDD<Double, Double> predictionsAndLabels) {
        List<Tuple2<Double, Double>> predictionsAndLabelsDoubleList = predictionsAndLabels.collect();
        List<Tuple2<Object, Object>> predictionsAndLabelsObjectList = new ArrayList<Tuple2<Object, Object>>();
        for (Tuple2<Double, Double> predictionsAndLabel : predictionsAndLabelsDoubleList) {
            Object prediction = predictionsAndLabel._1;
            Object label = predictionsAndLabel._2;
            Tuple2<Object, Object> tupleElement = new Tuple2<Object, Object>(prediction, label);
            predictionsAndLabelsObjectList.add(tupleElement);
        }
        JavaRDD<Tuple2<Object, Object>> predictionsAndLabelsJavaRDD = sparkContext
                .parallelize(predictionsAndLabelsObjectList);
        RDD<Tuple2<Object, Object>> scoresAndLabelsRDD = JavaRDD.toRDD(predictionsAndLabelsJavaRDD);
        MulticlassMetrics multiclassMetrics = new MulticlassMetrics(scoresAndLabelsRDD);
        return multiclassMetrics;
    }

    /**
     * This method returns multiclass confusion matrix for a given multiclass metric object
     * 
     * @param multiclassMetrics Multiclass metric object
     */
    private MulticlassConfusionMatrix getMulticlassConfusionMatrix(MulticlassMetrics multiclassMetrics, MLModel mlModel) {
        MulticlassConfusionMatrix multiclassConfusionMatrix = new MulticlassConfusionMatrix();
        if (multiclassMetrics != null) {
            int size = multiclassMetrics.confusionMatrix().numCols();
            double[] matrixArray = multiclassMetrics.confusionMatrix().toArray();
            double[][] matrix = new double[size][size];
            // set values of matrix into a 2D array
            for (int i = 0; i < size; i++) {
                for (int j = 0; j < size; j++) {
                    matrix[i][j] = matrixArray[(j * size) + i];
                }
            }
            multiclassConfusionMatrix.setMatrix(matrix);

            List<Map<String, Integer>> encodings = mlModel.getEncodings();
            // decode only if encodings are available
            if (encodings != null) {
                // last index is response variable encoding
                Map<String, Integer> encodingMap = encodings.get(encodings.size() - 1);
                List<String> decodedLabels = new ArrayList<String>();
                for (double label : multiclassMetrics.labels()) {
                    Integer labelInt = (int) label;
                    String decodedLabel = MLUtils.getKeyByValue(encodingMap, labelInt);
                    if (decodedLabel != null) {
                        decodedLabels.add(decodedLabel);
                    } else {
                        continue;
                    }
                }
                multiclassConfusionMatrix.setLabels(decodedLabels);
            } else {
                List<String> labelList = toStringList(multiclassMetrics.labels());
                multiclassConfusionMatrix.setLabels(labelList);
            }

            multiclassConfusionMatrix.setSize(size);
        }
        return multiclassConfusionMatrix;
    }

    /**
     * This method gets model accuracy from given multi-class metrics
     * 
     * @param multiclassMetrics multi-class metrics object
     */
    private Double getModelAccuracy(MulticlassMetrics multiclassMetrics) {
        DecimalFormat decimalFormat = new DecimalFormat(MLConstants.DECIMAL_FORMAT);

        Double modelAccuracy = 0.0;
        int confusionMatrixSize = multiclassMetrics.confusionMatrix().numCols();
        int confusionMatrixDiagonal = 0;
        long totalPopulation = arraySum(multiclassMetrics.confusionMatrix().toArray());
        for (int i = 0; i < confusionMatrixSize; i++) {
            int diagonalValueIndex = multiclassMetrics.confusionMatrix().index(i, i);
            confusionMatrixDiagonal += multiclassMetrics.confusionMatrix().toArray()[diagonalValueIndex];
        }
        if (totalPopulation > 0) {
            modelAccuracy = (double) confusionMatrixDiagonal / totalPopulation;
        }
        return Double.parseDouble(decimalFormat.format(modelAccuracy * 100));
    }

    /**
     * This summation of a given double array
     * 
     * @param array Double array
     */
    private long arraySum(double[] array) {
        long sum = 0;
        for (double i : array) {
            sum += i;
        }
        return sum;
    }

    private List<String> toStringList(double[] doubleArray) {
        List<String> stringList = new ArrayList<String>(doubleArray.length);
        for (int i = 0; i < doubleArray.length; i++) {
            stringList.add(String.valueOf(doubleArray[i]));
        }
        return stringList;
    }
}
