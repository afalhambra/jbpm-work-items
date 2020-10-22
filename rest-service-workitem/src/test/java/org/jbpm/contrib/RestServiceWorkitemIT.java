/*
 * Copyright 2018 Red Hat, Inc. and/or its affiliates.
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
package org.jbpm.contrib;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.jbpm.bpmn2.handler.WorkItemHandlerRuntimeException;
import org.jbpm.contrib.demoservices.Service;
import org.jbpm.contrib.demoservices.dto.Callback;
import org.jbpm.contrib.demoservices.dto.PreBuildRequest;
import org.jbpm.contrib.demoservices.dto.Scm;
import org.jbpm.contrib.mockserver.WorkItems;
import org.jbpm.contrib.restservice.Constant;
import org.jbpm.contrib.restservice.SimpleRestServiceWorkItemHandler;
import org.jbpm.contrib.restservice.util.Maps;
import org.jbpm.test.JbpmJUnitBaseTestCase;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;
import org.kie.api.event.process.DefaultProcessEventListener;
import org.kie.api.event.process.ProcessCompletedEvent;
import org.kie.api.event.process.ProcessEventListener;
import org.kie.api.event.process.ProcessNodeTriggeredEvent;
import org.kie.api.event.process.ProcessVariableChangedEvent;
import org.kie.api.io.ResourceType;
import org.kie.api.runtime.KieSession;
import org.kie.api.runtime.manager.RuntimeEngine;
import org.kie.api.runtime.process.ProcessInstance;
import org.kie.internal.runtime.manager.context.ProcessInstanceIdContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.jbpm.contrib.restservice.Constant.KIE_HOST_SYSTEM_PROPERTY;

/**
 * 
 * @author <a href="mailto:matejonnet@gmail.com">Matej Lazar</a>
 * @author Ryszard Kozmik
 */
public class RestServiceWorkitemIT extends JbpmJUnitBaseTestCase {

    private static int PORT = 8080;
    private static String DEFAULT_HOST = "localhost";
    private final Logger logger = LoggerFactory.getLogger(RestServiceWorkitemIT.class);

    private ExecutorService executor = Executors.newSingleThreadExecutor();

    private ObjectMapper objectMapper = new ObjectMapper();

    public RestServiceWorkitemIT() {
        super(true, true);
    }

    @BeforeClass
    public static void mainSetUp() throws Exception {
    }

    @Before
    public void preTestSetup() throws Exception {
        System.setProperty(KIE_HOST_SYSTEM_PROPERTY, "localhost:8080");

        // Configure jBPM server with all the test processes, workitems and event listeners.
        setupPoolingDataSource();

        Map<String, ResourceType> resources = new HashMap<>();
        resources.put("execute-rest.bpmn", ResourceType.BPMN2);
        resources.put("test-process.bpmn", ResourceType.BPMN2);

        manager = createRuntimeManager(Strategy.PROCESS_INSTANCE, resources);
        customProcessListeners.add(new RestServiceProcessEventListener());
        customHandlers.put("SimpleRestService", new SimpleRestServiceWorkItemHandler(manager));

        bootUpServices();
    }

    @After
    public void postTestTeardown() {
        //TODO stop services
    }

    private void bootUpServices() throws Exception {
        ContextHandlerCollection contexts = new ContextHandlerCollection();

        final Server server = new Server(PORT);
        server.setHandler(contexts);

        ServletContextHandler demoService = new ServletContextHandler(contexts, "/demo-service", ServletContextHandler.SESSIONS);

        ServletHolder servletHolder = demoService.addServlet(org.glassfish.jersey.servlet.ServletContainer.class, "/*");
        servletHolder.setInitOrder(0);
        // Tells the Jersey Servlet which REST service/class to load.
        servletHolder.setInitParameter("jersey.config.server.provider.classnames", Service.class.getCanonicalName());

        // JBPM server mock
        ServletContextHandler jbpmMock = new ServletContextHandler(contexts, "/services/rest", ServletContextHandler.SESSIONS);
        jbpmMock.setAttribute("runtimeManager", manager);
        ServletHolder jbpmMockServlet = jbpmMock.addServlet(org.glassfish.jersey.servlet.ServletContainer.class, "/*");
        jbpmMockServlet.setInitOrder(0);
        jbpmMockServlet.setInitParameter("jersey.config.server.provider.classnames", WorkItems.class.getCanonicalName());
        server.start();
    }

    @Test (timeout=15000)
    public void shouldInvokeRemoteServiceAndReceiveCallback() throws Exception {
        BlockingQueue<ProcessVariableChangedEvent> variableChangedQueue = new ArrayBlockingQueue(1000);
        ProcessEventListener processEventListener = new DefaultProcessEventListener() {
            public void afterVariableChanged(ProcessVariableChangedEvent event) {
                String variableId = event.getVariableId();
                logger.info("Process: {}, variable: {}, changed to: {}.",
                        event.getProcessInstance().getProcessName(), variableId,
                        event.getNewValue());
                String[] enqueueEvents = new String[]{
                        "preBuildResult",
                        "buildResult",
                        "completionResult"
                };
                if (Arrays.asList(enqueueEvents).contains(variableId)) {
                    variableChangedQueue.add(event);
                }
            }
        };
        customProcessListeners.add(processEventListener);

        RuntimeEngine runtimeEngine = getRuntimeEngine();
        KieSession kieSession = runtimeEngine.getKieSession();

        //when
        ProcessInstance processInstance = kieSession.startProcess(
                "testProcess",
                Collections.singletonMap("in_initData", getProcessParameters(1, 30)));

        manager.disposeRuntimeEngine(runtimeEngine);

        //then
        Map<String, Object> preBuildCallbackResult  = (Map<String, Object>) variableChangedQueue.take().getNewValue();
        System.out.println("preBuildCallbackResult: " + preBuildCallbackResult);
        Map<String, Object> preBuildResponse = Maps.getStringObjectMap(preBuildCallbackResult, "response");
        Assert.assertEquals("new-scm-tag", Maps.getStringObjectMap(preBuildResponse, "scm").get("revision"));
        Map<String, Object> initialResponse = Maps.getStringObjectMap(preBuildCallbackResult, "initialResponse");
        Assert.assertTrue(initialResponse.get("cancelUrl").toString().startsWith("http://localhost:8080/demo-service/service/cancel/"));

        Map<String, Object> buildCallbackResult  = (Map<String, Object>) variableChangedQueue.take().getNewValue();
        System.out.println("buildCallbackResult: " + buildCallbackResult);
        Map<String, Object> buildResponse = Maps.getStringObjectMap(preBuildCallbackResult, "response");
        Assert.assertEquals("SUCCESS", buildResponse.get("status"));

        Map<String, Object> completionResult  = (Map<String, Object>) variableChangedQueue.take().getNewValue();
        System.out.println("completionResult: " + completionResult);
        Assert.assertEquals("SUCCESS", completionResult.get("status"));

        customProcessListeners.remove(processEventListener);
    }

    @Test (timeout=15000)
    public void shouldCatchException() throws Exception {
        BlockingQueue<ProcessVariableChangedEvent> variableChangedQueue = new ArrayBlockingQueue(1000);
        ProcessEventListener processEventListener = new DefaultProcessEventListener() {
            public void afterVariableChanged(ProcessVariableChangedEvent event) {
                String variableId = event.getVariableId();
                logger.info("Process: {}, variable: {}, changed to: {}.",
                        event.getProcessInstance().getProcessName(), variableId,
                        event.getNewValue());
                String[] enqueueEvents = new String[]{
                        "preBuildResult"
                };
                if (Arrays.asList(enqueueEvents).contains(variableId)) {
                    variableChangedQueue.add(event);
                }
            }
        };
        customProcessListeners.add(processEventListener);

        RuntimeEngine runtimeEngine = getRuntimeEngine();
        KieSession kieSession = runtimeEngine.getKieSession();

        //when
        Map<String, Object> processParameters = new HashMap<>();
        processParameters.put("containerId", "mock");
        processParameters.put("serviceBaseUrl", "http://localhost:8080/demo-service/service");
        processParameters.put("preBuildServiceUrl", "http://localhost:8080/demo-service/service/prebuild");
        processParameters.put("preBuildTimeout", "not-a-number");

        ProcessInstance processInstance = kieSession.startProcess(
                "testProcess",
                Collections.singletonMap("in_initData", processParameters));

        manager.disposeRuntimeEngine(runtimeEngine);

        //then
        Map<String, Object> preBuildCallbackResult  = (Map<String, Object>) variableChangedQueue.take().getNewValue();
        System.out.println("preBuildCallbackResult: " + preBuildCallbackResult);
        WorkItemHandlerRuntimeException exception = (WorkItemHandlerRuntimeException) preBuildCallbackResult.get("error");
        logger.info("Expected exception: {}.", exception.getMessage());
        Assert.assertNotNull(exception);

        customProcessListeners.remove(processEventListener);
    }

    @Ignore
    @Test (timeout=15000)
    public void testCancel() throws InterruptedException {
        BlockingQueue<ProcessVariableChangedEvent> variableChangedQueue = new ArrayBlockingQueue(1000);
        ProcessEventListener processEventListener = new DefaultProcessEventListener() {
            public void afterVariableChanged(ProcessVariableChangedEvent event) {
                String variableId = event.getVariableId();
                logger.info("Process: {}, variable: {}, changed to: {}.",
                        event.getProcessInstance().getProcessName(), variableId,
                        event.getNewValue());

                    String[] enqueueEvents = new String[]{
                        "restResponse"
                };
                if (Arrays.asList(enqueueEvents).contains(variableId)) {
                    variableChangedQueue.add(event);
                }
            }
        };
        customProcessListeners.add(processEventListener);

        RuntimeEngine runtimeEngine = getRuntimeEngine();
        KieSession kieSession = runtimeEngine.getKieSession();

        //when
        ProcessInstance processInstance = kieSession.startProcess(
                "testProcess",
                Collections.singletonMap("in_initData", getProcessParameters(10, 30)));
        manager.disposeRuntimeEngine(runtimeEngine);

        //then wait for first service to start
        variableChangedQueue.take().getNewValue();
        RuntimeEngine runtimeEngineCancel = getRuntimeEngine(processInstance.getId());
        runtimeEngineCancel.getKieSession().signalEvent(Constant.CANCEL_SIGNAL_TYPE, null);
        manager.disposeRuntimeEngine(runtimeEngineCancel);

        //TODO verify result

        customProcessListeners.remove(processEventListener);
    }

    /**
     * Prebuild service time-out and cancel is invoked on it. Cancel completes successfully.
     */
    @Test @Ignore
    public void testTimeoutServiceDoesNotRespondCancelSuccess() throws InterruptedException {
        BlockingQueue<ProcessVariableChangedEvent> variableChangedQueue = new ArrayBlockingQueue(1000);
        ProcessEventListener processEventListener = new DefaultProcessEventListener() {
            public void afterVariableChanged(ProcessVariableChangedEvent event) {
                String variableId = event.getVariableId();
                logger.info("Process: {}, variable: {}, changed to: {}.",
                        event.getProcessInstance().getProcessName(), variableId,
                        event.getNewValue());

                String[] enqueueEvents = new String[]{
                        "restResponse" //TODO get cancel result
                };
                if (Arrays.asList(enqueueEvents).contains(variableId)) {
                    variableChangedQueue.add(event);
                }
            }
        };
        customProcessListeners.add(processEventListener);

        RuntimeEngine runtimeEngine = getRuntimeEngine();
        KieSession kieSession = runtimeEngine.getKieSession();

        //when
        ProcessInstance processInstance = kieSession.startProcess(
                "testProcess",
                Collections.singletonMap("in_initData", getProcessParameters(10, 5)));
        manager.disposeRuntimeEngine(runtimeEngine);

        //then wait for first service to start
        variableChangedQueue.take().getNewValue();


        //TODO verify result

        customProcessListeners.remove(processEventListener);
    }

    /**
     * The test will execute a process with just one task that is set with 2s timeout while the REST service invoked in it is set with 10sec callback time.
     * After 2 seconds the timeout process will kick in by finishing the REST workitem and setting the information that it has failed.
     *
     * @throws InterruptedException
     */
    @Test(timeout=15000) @Ignore
    public void testTimeoutServiceDoesNotRespondCancelTimeouts() throws InterruptedException {

        final Semaphore nodeACompleted = new Semaphore(0);
        final AtomicReference<String> resultA = new AtomicReference<>();

        ProcessEventListener processEventListener = new DefaultProcessEventListener() {
            public void afterVariableChanged(ProcessVariableChangedEvent event) {
                if (event.getVariableId().equals("resultA")) {
                    resultA.set(event.getNewValue().toString());
                    nodeACompleted.release();
                }
            }
        };

        KieSession kieSession = getRuntimeEngine().getKieSession();
        kieSession.addEventListener(processEventListener);

        Map<String, Object> parameters = new HashMap<String, Object>();
        parameters.put("containerId", "mock");
        ProcessInstance processInstance = (ProcessInstance) kieSession.startProcess("testProcess", parameters);

        boolean completed = nodeACompleted.tryAcquire(15, TimeUnit.SECONDS);
        if (!completed) {
            Assert.fail("Failed to complete the process.");
        }
        kieSession.removeEventListener(processEventListener);
        Assert.assertEquals("{\"remote-cancel-failed\":true}", resultA.get());

        assertProcessInstanceCompleted(processInstance.getId());
        disposeRuntimeManager();
    }

    @Test(timeout=15000)
    public void shouldStartAndCompleteExecuteRestProcess() throws InterruptedException {
        // Semaphore for process completed event
        final Semaphore processFinished = new Semaphore(0);
        BlockingQueue<ProcessVariableChangedEvent> variableChangedQueue = new ArrayBlockingQueue(1000);

        ProcessEventListener processEventListener = new DefaultProcessEventListener() {
            @Override
            public void beforeNodeTriggered(ProcessNodeTriggeredEvent event) {
                logger.info("Event ID: {}, event node ID: {}, event node name: {}", event.getNodeInstance().getId(), event.getNodeInstance().getNodeId(), event.getNodeInstance().getNodeName());
            }

            public void afterProcessCompleted(ProcessCompletedEvent event) {
                logger.info("Process completed, unblocking test.");
                processFinished.release();
            }

            public void afterVariableChanged(ProcessVariableChangedEvent event) {
                String variableId = event.getVariableId();
                logger.info("Process: {}, variable: {}, changed to: {}.",
                        event.getProcessInstance().getProcessName(), variableId,
                        event.getNewValue());

                String[] enqueueEvents = new String[]{
                        "result"
                };
                if (Arrays.asList(enqueueEvents).contains(variableId)) {
                    variableChangedQueue.add(event);
                }
            }
        };
        customProcessListeners.add(processEventListener);
        RuntimeEngine runtimeEngine = getRuntimeEngine();
        KieSession kieSession = runtimeEngine.getKieSession();

        ProcessInstance processInstance = (ProcessInstance) kieSession.startProcess("kogito.executerest", getExecuteRestParameters());
        manager.disposeRuntimeEngine(runtimeEngine);

        boolean completed = processFinished.tryAcquire(15, TimeUnit.SECONDS);
        if (!completed) {
            Assert.fail("Failed to complete the process.");
        }

        Map<String, Object> result  = (Map<String, Object>) variableChangedQueue.take().getNewValue();
        System.out.println("result: " + result);
        Assert.assertEquals("new-scm-tag", Maps.getStringObjectMap(Maps.getStringObjectMap(result, "response"), "scm").get("revision"));

        customProcessListeners.remove(processEventListener);
    }

    private Map<String, Object> getExecuteRestParameters() {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("requestMethod", "POST");
        parameters.put("requestHeaders", null);
        parameters.put("requestUrl", "http://localhost:8080/demo-service/service/prebuild");
        parameters.put("requestTemplate", getPreBuildRequestBody());
        parameters.put("taskTimeout", "10");
        parameters.put("cancel", false);
        parameters.put("cancelTimeout", null);
        parameters.put("cancelUrlJsonPointer", null);
        parameters.put("cancelUrlTemplate", null);
        parameters.put("cancelUrlTemplate", null);
        parameters.put("cancelMethod", null);
        parameters.put("cancelHeaders", null);
        parameters.put("successEvalTemplate", null);

        //kcontext.setVariable("containerId","mock");
        parameters.put("containerId", "mock");
        return parameters;
    }

    private String getPreBuildRequestBody() {
        PreBuildRequest request = new PreBuildRequest();
        Scm scm = new Scm();
        scm.setUrl("https://github.com/kiegroup/jbpm-work-items.git");
        request.setScm(scm);
        Callback callback = new Callback();
        callback.setMethod("POST");
        callback.setUrl("@{system.callbackUrl}");
        request.setCallback(callback);
        try {
            return objectMapper.writeValueAsString(request);
        } catch (JsonProcessingException e) {
            Assert.fail("Cannot serialize preBuildRequest: " + e.getMessage());
            return null;
        }
    }

    private Map<String, Object> getProcessParameters(int preBuildCallbackDelay, int preBuildTimeout) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("containerId", "mock");
        parameters.put("serviceBaseUrl", "http://localhost:8080/demo-service/service");
        parameters.put("preBuildServiceUrl", "http://localhost:8080/demo-service/service/prebuild?callbackDelay=" + preBuildCallbackDelay);
        parameters.put("preBuildTimeout", preBuildTimeout);
        Map<String, Object> buildConfiguration = new HashMap<>();
        buildConfiguration.put("id", "1");
        buildConfiguration.put("scmRepoURL", "https://github.com/kiegroup/jbpm-work-items.git");
        buildConfiguration.put("scmRevision", "master");
        buildConfiguration.put("preBuildSyncEnabled", "true");
        buildConfiguration.put("buildScript", "true");
        parameters.put("buildConfiguration", buildConfiguration);
        return parameters;
    }

    private KieSession getKieSession(long processInstanceId) {
        RuntimeEngine runtimeEngine = getRuntimeEngine(processInstanceId);
        return runtimeEngine.getKieSession();
    }

    private RuntimeEngine getRuntimeEngine(long processInstanceId) {
        ProcessInstanceIdContext processInstanceContext = ProcessInstanceIdContext.get(processInstanceId);
        return manager.getRuntimeEngine(processInstanceContext);
    }

}