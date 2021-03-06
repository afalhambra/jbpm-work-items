/*
 * Copyright 2021 Red Hat, Inc. and/or its affiliates.
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
package org.jbpm.process.longrest;

import java.io.IOException;
import java.net.HttpCookie;
import java.text.MessageFormat;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeType;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.RequestBuilder;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.util.EntityUtils;
import org.jbpm.process.longrest.util.Mapper;
import org.jbpm.process.longrest.util.ProcessUtils;
import org.jbpm.process.longrest.util.Strings;
import org.jbpm.process.workitem.core.AbstractLogOrThrowWorkItemHandler;
import org.jbpm.process.workitem.core.util.RequiredParameterValidator;
import org.jbpm.process.workitem.core.util.Wid;
import org.jbpm.process.workitem.core.util.WidMavenDepends;
import org.jbpm.process.workitem.core.util.WidParameter;
import org.jbpm.process.workitem.core.util.WidResult;
import org.jbpm.process.workitem.core.util.service.WidAction;
import org.jbpm.process.workitem.core.util.service.WidAuth;
import org.jbpm.process.workitem.core.util.service.WidService;
import org.kie.api.runtime.KieSession;
import org.kie.api.runtime.manager.RuntimeManager;
import org.kie.api.runtime.process.WorkItem;
import org.kie.api.runtime.process.WorkItemManager;
import org.kie.api.runtime.process.WorkflowProcessInstance;
import org.mvel2.MVEL;
import org.mvel2.ParserContext;
import org.mvel2.integration.VariableResolverFactory;
import org.mvel2.integration.impl.MapVariableResolverFactory;
import org.mvel2.templates.CompiledTemplate;
import org.mvel2.templates.TemplateRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.mvel2.templates.TemplateCompiler.compileTemplate;

@Wid(widfile = "LongRunningRestService.wid",
        name = "LongRunningRestService",
        displayName = "LongRunningRestService",
        defaultHandler = "mvel: new org.jbpm.process.longrest.LongRunningRestServiceWorkItemHandler(runtimeManager)",
        category = "long-running-rest-workitem",
        documentation = "",
        parameters = {
                @WidParameter(name = "url", required = true),
                @WidParameter(name = "method", required = true),
                @WidParameter(name = "headers", required = false),
                @WidParameter(name = "template", required = false),
                @WidParameter(name = "cancelUrlJsonPointer", required = false),
                @WidParameter(name = "cancelUrlTemplate", required = false),
                @WidParameter(name = "socketTimeout", required = false),
                @WidParameter(name = "connectTimeout", required = false),
                @WidParameter(name = "connectionRequestTimeout", required = false)
        },
        results = {
                @WidResult(name = "responseCode"),
                @WidResult(name = "result"),
                @WidResult(name = "cancelUrl"),
                @WidResult(name = "error")
        },
        mavenDepends = {
                @WidMavenDepends(group = "${groupId}", artifact = "${artifactId}", version = "${version}")
        },
        serviceInfo = @WidService(category = "REST service", description = "",
                keywords = "rest,long-running",
                action = @WidAction(title = "Long running REST service handler ver. ${version}"),
                authinfo = @WidAuth(required = true, params = {"url"})
        )
)
public class LongRunningRestServiceWorkItemHandler extends AbstractLogOrThrowWorkItemHandler {

    private static final Logger logger = LoggerFactory.getLogger(LongRunningRestServiceWorkItemHandler.class);

    /**
     * Cookies are required for sticky session in cases where cancel request must hit the same node behind the load balancer.
     */
    private static final String COOKIES_KEY = "cookies";

    private final RuntimeManager runtimeManager;

    ParserContext mvelContext = new ParserContext();

    public LongRunningRestServiceWorkItemHandler(RuntimeManager runtimeManager) {
        this.runtimeManager = runtimeManager;
        logger.debug("Constructing with runtimeManager ...");
        initializeMvelContext();
        setLogThrownException(false);
    }

    public LongRunningRestServiceWorkItemHandler() {
        logger.debug("Constructing without runtimeManager ...");
        runtimeManager = null;
        initializeMvelContext();
        setLogThrownException(false);
    }

    private void initializeMvelContext() {
        mvelContext.addImport(
                "quote",
                MVEL.getStaticMethod(Strings.class, "quoteString", new Class[]{Object.class}));
        mvelContext.addImport("asJson",
                              MVEL.getStaticMethod(Mapper.class, "writeValueAsString", new Class[]{Object.class, boolean.class}));
        mvelContext.addImport("asJson",
                              MVEL.getStaticMethod(Mapper.class, "writeValueAsString", new Class[]{Object.class}));
    }

    public void executeWorkItem(WorkItem workItem, WorkItemManager manager) {
        try {
            RequiredParameterValidator.validate(this.getClass(), workItem);

            long processInstanceId = workItem.getProcessInstanceId();
            WorkflowProcessInstance processInstance = ProcessUtils.getProcessInstance(runtimeManager, processInstanceId);

            String cancelUrlJsonPointer = ProcessUtils.getStringParameter(workItem, Constant.CANCEL_URL_JSON_POINTER_VARIABLE);
            String cancelUrlTemplate = ProcessUtils.getStringParameter(workItem, Constant.CANCEL_URL_TEMPLATE_VARIABLE);
            String requestUrl = ProcessUtils.getStringParameter(workItem, "url");
            String requestMethod = ProcessUtils.getStringParameter(workItem, "method");
            String requestTemplate = ProcessUtils.getStringParameter(workItem, "template");
            String requestHeaders = ProcessUtils.getStringParameter(workItem, "headers");
            int socketTimeout = ProcessUtils.getIntParameter(workItem, "socketTimeout", 5000);
            int connectTimeout = ProcessUtils.getIntParameter(workItem, "connectTimeout", 5000);
            int connectionRequestTimeout = ProcessUtils.getIntParameter(workItem, "connectionRequestTimeout", 5000);

            KieSession kieSession = ProcessUtils.getKsession(runtimeManager, processInstanceId);
            String containerId = (String) kieSession.getEnvironment().get("deploymentId");

            //should this service run
            logger.debug("Should run ProcessInstance.id: {}.", processInstance.getId());

            try {
                invokeRemoteService(
                        processInstance,
                        manager,
                        workItem.getId(),
                        requestUrl,
                        requestMethod,
                        requestTemplate,
                        containerId,
                        cancelUrlJsonPointer,
                        cancelUrlTemplate,
                        requestHeaders,
                        socketTimeout,
                        connectTimeout,
                        connectionRequestTimeout);
            } catch (RemoteInvocationException e) {
                String message = MessageFormat.format("Failed to invoke remote service. ProcessInstanceId {0}.", processInstanceId);
                logger.warn(message, e);
                completeWorkItem(manager, workItem.getId(), e);
            } catch (ResponseProcessingException e) {
                String message = MessageFormat.format("Failed to process response. ProcessInstanceId {0}.", processInstanceId);
                logger.warn(message, e);
                completeWorkItem(manager, workItem.getId(), e);
            }
        } catch (Throwable cause) {
            logger.error("Failed to execute workitem handler due to the following error.", cause);
            completeWorkItem(manager, workItem.getId(), cause);
        }
    }

    private void invokeRemoteService(
            WorkflowProcessInstance processInstance,
            WorkItemManager manager,
            long workItemId,
            String requestUrl,
            String httpMethod,
            String requestTemplate,
            String containerId,
            String cancelUrlJsonPointer,
            String cancelUrlTemplate,
            String requestHeaders,
            int socketTimeout,
            int connectTimeout,
            int connectionRequestTimeout) throws RemoteInvocationException, ResponseProcessingException {

        logger.debug("requestTemplate: {}", requestTemplate);

        VariableResolverFactory variableResolverFactory = getVariableResolverFactory(processInstance, containerId);

        String requestBodyEvaluated;
        if (requestTemplate != null && !requestTemplate.equals("")) {
            CompiledTemplate compiled = compileTemplate(requestTemplate, mvelContext);
            requestBodyEvaluated = (String) TemplateRuntime.execute(compiled, mvelContext, variableResolverFactory);
        } else {
            requestBodyEvaluated = "";
        }

        Map<String, String> requestHeadersMap = new HashMap<>();
        // Add cookies to the request
        Map<String, String> cookies = (Map<String, String>) processInstance.getVariable(COOKIES_KEY);
        if (cookies != null) {
            String cookieHeader = cookies.entrySet().stream()
                    .map(c -> c.getKey() + "=" + c.getValue())
                    .collect(Collectors.joining("; "));
            requestHeadersMap.put("Cookie", cookieHeader);
        }
        requestHeadersMap.putAll(Strings.toMap(requestHeaders));
        HttpResponse httpResponse = httpRequest(
                requestUrl,
                requestBodyEvaluated,
                httpMethod,
                requestHeadersMap,
                socketTimeout,
                connectTimeout,
                connectionRequestTimeout);

        int statusCode = httpResponse.getStatusLine().getStatusCode();
        logger.info("Remote endpoint returned status: {}.", statusCode);

        if (statusCode < 200 || statusCode >= 300) {
            String message = MessageFormat.format("Remote service responded with error status code {0} and reason: {1}. ProcessInstanceId {2}.", statusCode, httpResponse.getStatusLine().getReasonPhrase(), processInstance.getId());
            throw new RemoteInvocationException(message);
        }

        storeCookies(httpResponse, processInstance);

        HttpEntity responseEntity = httpResponse.getEntity();
        if (statusCode == 204 || responseEntity.getContentLength() == 0L) {
            completeWorkItem(manager, workItemId, statusCode, Collections.emptyMap(), "");
        } else {
            String responseString;
            try {
                responseString = EntityUtils.toString(responseEntity, "UTF-8");
                logger.debug("Invocation response: {}", responseString);
            } catch (IOException e) {
                throw new ResponseProcessingException("Cannot read remote entity.", e);
            }
            JsonNode root;
            Map<String, Object> serviceInvocationResponse;
            try {
                root = Mapper.getInstance().readTree(responseString);
                if (JsonNodeType.ARRAY.equals(root.getNodeType())) {
                    //convert array to indexed map
                    serviceInvocationResponse = new LinkedHashMap<>();
                    Object[] array = Mapper.getInstance().convertValue(root, new TypeReference<Object[]>() {
                    });
                    for (int i = 0; i < array.length; i++) {
                        serviceInvocationResponse.put(Integer.toString(i), array[i]);
                    }
                } else {
                    serviceInvocationResponse = Mapper.getInstance()
                            .convertValue(root, new TypeReference<Map<String, Object>>() {
                            });
                }
            } catch (Exception e) {
                String message = MessageFormat.format("Cannot parse service invocation response. ProcessInstanceId {0}.",
                                                      processInstance.getId());
                throw new ResponseProcessingException(message, e);
            }
            String cancelUrl = "";
            try {
                if (!Strings.isEmpty(cancelUrlTemplate)) {
                    logger.debug("Setting cancel url from template: {}.", cancelUrlTemplate);
                    CompiledTemplate compiled = compileTemplate(cancelUrlTemplate);
                    cancelUrl = (String) TemplateRuntime
                            .execute(compiled, null, variableResolverFactory);
                } else if (!Strings.isEmpty(cancelUrlJsonPointer)) {
                    logger.debug("Setting cancel url from json pointer: {}.", cancelUrlJsonPointer);
                    JsonNode cancelUrlNode = root.at(cancelUrlJsonPointer);
                    if (!cancelUrlNode.isMissingNode()) {
                        cancelUrl = cancelUrlNode.asText();
                    }
                }
                logger.debug("Cancel url: {}.", cancelUrl);
            } catch (Exception e) {
                String message = MessageFormat.format("Cannot read cancel url from service invocation response. ProcessInstanceId {0}.", processInstance.getId());
                throw new ResponseProcessingException(message, e);
            }
            completeWorkItem(manager, workItemId, statusCode, serviceInvocationResponse, cancelUrl);
        }
    }

    private void storeCookies(HttpResponse response, WorkflowProcessInstance processInstance) {
        Map<String, String> cookies = new HashMap<>();
        Header[] cookieHeaders = response.getHeaders("Set-Cookie");
        for (Header cookieHeader : cookieHeaders) {
            List<HttpCookie> cookiesInTheHeader = HttpCookie.parse(cookieHeader.getValue());
            Map<String, String> cookiesMap = cookiesInTheHeader.stream()
                    .collect(Collectors.toMap(HttpCookie::getName, HttpCookie::getValue));
            cookies.putAll(cookiesMap);
        }
        processInstance.setVariable(COOKIES_KEY, cookies);
    }

    private VariableResolverFactory getVariableResolverFactory(WorkflowProcessInstance processInstance, String containerId) {
        Map<String, Object> systemVariables = new HashMap<>();
        String baseUrl = getKieHost() + "/services/rest/server/containers/" + containerId + "/processes/instances/";
        systemVariables.put(
                "callbackUrl", baseUrl + processInstance.getId() + "/signal/RESTResponded");
        systemVariables.put("callbackMethod", "POST");
        systemVariables.put(
                "heartBeatUrl",
                baseUrl + processInstance.getId() + "/signal/imAlive");
        systemVariables.put("heartBeatMethod", "POST");
        return getVariableResolverFactoryChain(
                systemVariables,
                processInstance);
    }

    private VariableResolverFactory getVariableResolverFactoryChain(
            Map<String, Object> systemVariables,
            WorkflowProcessInstance processInstance) {

        VariableResolverFactory variableResolverFactory = new MapVariableResolverFactory(
                Collections.singletonMap("system", systemVariables));

        VariableResolverFactory resolver = variableResolverFactory.setNextFactory(
                new ProcessVariableResolverFactory(processInstance));

        WorkflowProcessInstance currentInstance = processInstance;
        //add all parent instances to resolver chain
        int maxDepth = 100;
        int depth = 0;
        while (true) {
            depth++;
            if (depth > maxDepth) {  //circuit-breaker: allow only maxDepth nested process instances
                throw new RuntimeException("To many nested process instances, allowed only up to " + maxDepth + ".");
            }
            long parentProcessInstanceId = currentInstance.getParentProcessInstanceId();
            if (parentProcessInstanceId > 0) {
                WorkflowProcessInstance parentProcessInstance = ProcessUtils.getProcessInstance(
                        runtimeManager, parentProcessInstanceId);
                resolver.setNextFactory(new ProcessVariableResolverFactory(parentProcessInstance));
                currentInstance = parentProcessInstance;
            } else {
                //top parent reached
                break;
            }
        }
        return variableResolverFactory;
    }

    private HttpResponse httpRequest(
            String url,
            String jsonContent,
            String httpMethod,
            Map<String, String> requestHeaders,
            int socketTimeout,
            int connectTimeout,
            int connectionRequestTimeout) throws RemoteInvocationException {
        RequestConfig config = RequestConfig.custom()
                .setSocketTimeout(socketTimeout)
                .setConnectTimeout(connectTimeout)
                .setConnectionRequestTimeout(connectionRequestTimeout)
                .build();

        HttpClientBuilder clientBuilder = HttpClientBuilder.create()
                .setDefaultRequestConfig(config);

        HttpClient httpClient = clientBuilder.build();

        RequestBuilder requestBuilder = RequestBuilder.create(httpMethod).setUri(url);

        if (requestHeaders != null) {
            requestHeaders.forEach((k, v) -> requestBuilder.addHeader(k, v));
        }

        if (jsonContent != null && !jsonContent.equals("")) {
            requestBuilder.setHeader("Content-Type", "application/json");
            StringEntity entity = new StringEntity(jsonContent, ContentType.APPLICATION_JSON);
            requestBuilder.setEntity(entity);
        }

        logger.info("Invoking remote endpoint {} {} Headers: {} Body: {}.", httpMethod, url, requestHeaders, jsonContent);

        HttpResponse httpResponse;
        try {
            httpResponse = httpClient.execute(requestBuilder.build());
        } catch (IOException e) {
            throw new RemoteInvocationException("Unable to invoke remote endpoint.", e);
        }
        return httpResponse;
    }

    /**
     * Reads hostname from the system property or environment variable.
     * System property overrides the env variable.
     * Https overrides the http variable.
     *
     * @return hostName
     */
    private String getKieHost() {
        String host = System.getProperty(Constant.HOSTNAME_HTTPS);
        if (host != null) {
            host = "https://" + host;
        }
        if (host == null) {
            host = System.getProperty(Constant.HOSTNAME_HTTP);
            if (host != null) {
                host = "http://" + host;
            }
        }
        if (host == null) {
            host = System.getenv(Constant.HOSTNAME_HTTPS);
            if (host != null) {
                host = "https://" + host;
            }
        }
        if (host == null) {
            host = System.getenv(Constant.HOSTNAME_HTTP);
            if (host != null) {
                host = "http://" + host;
            }
        }
        return host;
    }

    /**
     * Complete WorkItem and store the http service response.
     * Long running operations are not completed using this handler but via REST api call.
     */
    private void completeWorkItem(
            WorkItemManager manager,
            long workItemId,
            int responseCode,
            Map<String, Object> serviceInvocationResult,
            String cancelUrl) {
        completeWorkItem(
                manager,
                workItemId,
                responseCode,
                serviceInvocationResult,
                cancelUrl,
                Optional.empty());
    }

    private void completeWorkItem(WorkItemManager manager, long workItemId, Throwable cause) {
        completeWorkItem(
                manager,
                workItemId,
                -1,
                Collections.emptyMap(),
                "",
                Optional.ofNullable(cause));
    }

    private void completeWorkItem(
            WorkItemManager manager,
            long workItemId,
            int responseCode,
            Map<String, Object> serviceInvocationResult,
            String cancelUrl,
            Optional<Throwable> cause) {
        Map<String, Object> results = new HashMap<>();
        results.put("responseCode", responseCode);
        results.put("result", serviceInvocationResult);
        results.put("cancelUrl", cancelUrl);
        cause.ifPresent(c -> {
            results.put("error", c);
        });
        logger.info("Rest service workitem completion result {}.", results);
        manager.completeWorkItem(workItemId, results);
    }

    @Override
    public void abortWorkItem(WorkItem workItem, WorkItemManager manager) {
        completeWorkItem(manager, workItem.getId(), new WorkitemAbortedException());
    }
}

