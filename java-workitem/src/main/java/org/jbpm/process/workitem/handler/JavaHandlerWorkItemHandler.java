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

package org.jbpm.process.workitem.handler;

import java.util.Map;

import org.drools.core.spi.ProcessContext;
import org.jbpm.process.workitem.core.AbstractLogOrThrowWorkItemHandler;
import org.jbpm.process.workitem.core.util.RequiredParameterValidator;
import org.jbpm.process.workitem.core.util.Wid;
import org.jbpm.process.workitem.core.util.WidMavenDepends;
import org.jbpm.process.workitem.core.util.WidParameter;
import org.jbpm.process.workitem.core.util.service.WidAction;
import org.jbpm.process.workitem.core.util.service.WidAuth;
import org.jbpm.process.workitem.core.util.service.WidService;
import org.jbpm.workflow.instance.node.WorkItemNodeInstance;
import org.kie.api.runtime.KieSession;
import org.kie.api.runtime.manager.RuntimeEngine;
import org.kie.api.runtime.manager.RuntimeManager;
import org.kie.api.runtime.process.WorkItem;
import org.kie.api.runtime.process.WorkItemManager;
import org.kie.api.runtime.process.WorkflowProcessInstance;
import org.kie.internal.runtime.manager.RuntimeManagerRegistry;
import org.kie.internal.runtime.manager.context.ProcessInstanceIdContext;

@Wid(widfile = "JavaDefinitions.wid", name = "Java",
        displayName = "Java",
        defaultHandler = "mvel: new org.jbpm.process.workitem.java.JavaHandlerWorkItemHandler(\"ksession\")",
        documentation = "${artifactId}/index.html",
        category = "${artifactId}",
        icon = "Java.png",
        parameters = {
                @WidParameter(name = "Class", required = true)
        },
        mavenDepends = {
                @WidMavenDepends(group = "${groupId}", artifact = "${artifactId}", version = "${version}")
        },
        serviceInfo = @WidService(category = "${name}", description = "${description}",
                keywords = "java,handler,class,execute",
                action = @WidAction(title = "Execute an existing Java Workitem Handler"),
                authinfo = @WidAuth
        ))
public class JavaHandlerWorkItemHandler extends AbstractLogOrThrowWorkItemHandler {

    private KieSession ksession;
    
    public JavaHandlerWorkItemHandler() {        
    }
    
    public JavaHandlerWorkItemHandler(KieSession ksession) {  
        this.ksession = ksession;
    }

    @SuppressWarnings("unchecked")
    public void executeWorkItem(WorkItem workItem,
                                WorkItemManager manager) {
        try {

            RequiredParameterValidator.validate(this.getClass(),
                                                workItem);

            String className = (String) workItem.getParameter("Class");

            Class<JavaHandler> c = (Class<JavaHandler>) Class.forName(className);
            JavaHandler handler = c.newInstance();
            
            KieSession localksession = ksession;
            RuntimeManager runtimeManager = null;
            RuntimeEngine engine = null;
            if (localksession == null) {
                runtimeManager = RuntimeManagerRegistry.get().getManager(((org.drools.core.process.instance.WorkItem) workItem).getDeploymentId());
                engine = runtimeManager.getRuntimeEngine(ProcessInstanceIdContext.get(workItem.getProcessInstanceId()));
                localksession = engine.getKieSession();
            }
            ProcessContext kcontext = new ProcessContext(localksession);
            try {
                WorkflowProcessInstance processInstance = (WorkflowProcessInstance)
                localksession.getProcessInstance(workItem.getProcessInstanceId());
                kcontext.setProcessInstance(processInstance);
                WorkItemNodeInstance nodeInstance = findNodeInstance(workItem.getId(),
                                                                     processInstance);
                kcontext.setNodeInstance(nodeInstance);
                Map<String, Object> results = handler.execute(kcontext);
    
                manager.completeWorkItem(workItem.getId(),
                                         results);
            } finally {
                if (engine != null) {
                    runtimeManager.disposeRuntimeEngine(engine);
                }
            }
            return;
        } catch (Exception e) {
            handleException(e);
        }
    }

    public void abortWorkItem(WorkItem arg0,
                              WorkItemManager arg1) {
        // Do nothing
    }
}
