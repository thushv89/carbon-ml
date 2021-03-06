<%@ page language="java" contentType="text/html; charset=ISO-8859-1" pageEncoding="ISO-8859-1" %>

<%--
  ~ Copyright (c) 2015, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
  ~
  ~   WSO2 Inc. licenses this file to you under the Apache License,
  ~   Version 2.0 (the "License"); you may not use this file except
  ~   in compliance with the License.
  ~   You may obtain a copy of the License at
  ~
  ~     http://www.apache.org/licenses/LICENSE-2.0
  ~
  ~  Unless required by applicable law or agreed to in writing,
  ~  software distributed under the License is distributed on an
  ~  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
  ~  KIND, either express or implied.  See the License for the
  ~  specific language governing permissions and limitations
  ~  under the License.
  --%>

<%@ page import="org.wso2.carbon.mediator.service.ui.Mediator" %>
<%@ page import="org.wso2.carbon.ml.mediator.predict.ui.PredictMediator" %>
<%@ page import="org.apache.synapse.util.xpath.SynapseXPath" %>
<%@ page import="org.jaxen.JaxenException" %>
<%@ page import="org.wso2.carbon.sequences.ui.util.SequenceEditorHelper" %>
<%@ page import="org.wso2.carbon.sequences.ui.util.ns.XPathFactory" %>
<%@ page import="org.wso2.carbon.mediator.service.util.MediatorProperty" %>

<%
    Mediator mediator = SequenceEditorHelper.getEditingMediator(request, session);
    if (!(mediator instanceof PredictMediator)) {
        throw new RuntimeException("Unable to edit the mediator");
    }
    PredictMediator predictMediator = (PredictMediator) mediator;
    predictMediator.getFeatures().clear(); // to avoid duplicates
    String featureCountStr = request.getParameter("featureCount");
    XPathFactory xPathFactory = XPathFactory.getInstance();

    if (featureCountStr != null && !"".equals(featureCountStr)) {
        int featureCount = Integer.parseInt(featureCountStr.trim());
        for (int i = 0; i < featureCount; i++) {
            String feature = request.getParameter("featureName" + i);
            MediatorProperty mediatorProperty = new MediatorProperty();
            mediatorProperty.setName(feature);
            String valueId = "featureXpath" + i;
            String value = request.getParameter(valueId);

            if (value.trim().startsWith("json-eval(")) {
                SynapseXPath jsonPath =
                        new SynapseXPath(value.trim().substring(10, value.length() - 1));
                mediatorProperty.setExpression(jsonPath);
            } else {
                mediatorProperty.setExpression(xPathFactory.createSynapseXPath(valueId, value.trim(), session));
            }
            predictMediator.addFeature(mediatorProperty);
        }
    }

    String predictionXpath = request.getParameter("predictionProperty");
    predictMediator.setPredictionPropertyName(predictionXpath);
%>
