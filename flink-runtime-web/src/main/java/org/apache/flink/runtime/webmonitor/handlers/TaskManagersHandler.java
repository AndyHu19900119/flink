/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.runtime.webmonitor.handlers;

import com.fasterxml.jackson.core.JsonGenerator;
import org.apache.flink.runtime.instance.ActorGateway;
import org.apache.flink.runtime.instance.Instance;
import org.apache.flink.runtime.instance.InstanceID;
import org.apache.flink.runtime.messages.JobManagerMessages;
import org.apache.flink.runtime.messages.JobManagerMessages.RegisteredTaskManagers;
import org.apache.flink.runtime.messages.JobManagerMessages.TaskManagerInstance;
import org.apache.flink.runtime.webmonitor.metrics.MetricFetcher;
import org.apache.flink.runtime.webmonitor.metrics.MetricStore;
import org.apache.flink.util.StringUtils;
import scala.concurrent.Await;
import scala.concurrent.Future;
import scala.concurrent.duration.FiniteDuration;

import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static java.util.Objects.requireNonNull;

public class TaskManagersHandler extends AbstractJsonRequestHandler  {

	public static final String TASK_MANAGER_ID_KEY = "taskmanagerid";
	
	private final FiniteDuration timeout;

	private final MetricFetcher fetcher;
	
	public TaskManagersHandler(FiniteDuration timeout, MetricFetcher fetcher) {
		this.timeout = requireNonNull(timeout);
		this.fetcher = fetcher;
	}

	@Override
	public String handleJsonRequest(Map<String, String> pathParams, Map<String, String> queryParams, ActorGateway jobManager) throws Exception {
		try {
			if (jobManager != null) {
				// whether one task manager's metrics are requested, or all task manager, we
				// return them in an array. This avoids unnecessary code complexity.
				// If only one task manager is requested, we only fetch one task manager metrics.
				final List<Instance> instances = new ArrayList<>();
				if (pathParams.containsKey(TASK_MANAGER_ID_KEY)) {
					try {
						InstanceID instanceID = new InstanceID(StringUtils.hexStringToByte(pathParams.get(TASK_MANAGER_ID_KEY)));
						Future<Object> future = jobManager.ask(new JobManagerMessages.RequestTaskManagerInstance(instanceID), timeout);
						TaskManagerInstance instance = (TaskManagerInstance) Await.result(future, timeout);
						if (instance.instance().nonEmpty()) {
							instances.add(instance.instance().get());
						}
					}
					// this means the id string was invalid. Keep the list empty.
					catch (IllegalArgumentException e){
						// do nothing.
					}
				} else {
					Future<Object> future = jobManager.ask(JobManagerMessages.getRequestRegisteredTaskManagers(), timeout);
					RegisteredTaskManagers taskManagers = (RegisteredTaskManagers) Await.result(future, timeout);
					instances.addAll(taskManagers.asJavaCollection());
				}

				StringWriter writer = new StringWriter();
				JsonGenerator gen = JsonFactory.jacksonFactory.createGenerator(writer);

				gen.writeStartObject();
				gen.writeArrayFieldStart("taskmanagers");

				for (Instance instance : instances) {
					gen.writeStartObject();
					gen.writeStringField("id", instance.getId().toString());
					gen.writeStringField("path", instance.getTaskManagerGateway().getAddress());
					gen.writeNumberField("dataPort", instance.getTaskManagerLocation().dataPort());
					gen.writeNumberField("timeSinceLastHeartbeat", instance.getLastHeartBeat());
					gen.writeNumberField("slotsNumber", instance.getTotalNumberOfSlots());
					gen.writeNumberField("freeSlots", instance.getNumberOfAvailableSlots());
					gen.writeNumberField("cpuCores", instance.getResources().getNumberOfCPUCores());
					gen.writeNumberField("physicalMemory", instance.getResources().getSizeOfPhysicalMemory());
					gen.writeNumberField("freeMemory", instance.getResources().getSizeOfJvmHeap());
					gen.writeNumberField("managedMemory", instance.getResources().getSizeOfManagedMemory());

					// only send metrics when only one task manager requests them.
					if (pathParams.containsKey(TASK_MANAGER_ID_KEY)) {
						fetcher.update();
						MetricStore.TaskManagerMetricStore metrics = fetcher.getMetricStore().getTaskManagerMetricStore(instance.getId().toString());
						if (metrics != null) {
							gen.writeObjectFieldStart("metrics");
							long heapUsed = Long.valueOf( metrics.getMetric("Status.JVM.Memory.Heap.Used", "0"));
							long heapCommitted = Long.valueOf( metrics.getMetric("Status.JVM.Memory.Heap.Committed", "0"));
							long heapTotal = Long.valueOf( metrics.getMetric("Status.JVM.Memory.Heap.Max", "0"));

							gen.writeNumberField("heapCommitted", heapCommitted);
							gen.writeNumberField("heapUsed", heapUsed);
							gen.writeNumberField("heapMax", heapTotal);

							long nonHeapUsed = Long.valueOf( metrics.getMetric("Status.JVM.Memory.NonHeap.Used", "0"));
							long nonHeapCommitted = Long.valueOf( metrics.getMetric("Status.JVM.Memory.NonHeap.Committed", "0"));
							long nonHeapTotal = Long.valueOf( metrics.getMetric("Status.JVM.Memory.NonHeap.Max", "0"));

							gen.writeNumberField("nonHeapCommitted", nonHeapCommitted);
							gen.writeNumberField("nonHeapUsed", nonHeapUsed);
							gen.writeNumberField("nonHeapMax", nonHeapTotal);

							gen.writeNumberField("totalCommitted", heapCommitted + nonHeapCommitted);
							gen.writeNumberField("totalUsed", heapUsed + nonHeapUsed);
							gen.writeNumberField("totalMax", heapTotal + nonHeapTotal);

							gen.writeStringField("directCount", metrics.getMetric("Status.JVM.Memory.Direct.Count", "0"));
							gen.writeStringField("directUsed", metrics.getMetric("Status.JVM.Memory.Direct.MemoryUsed", "0"));
							gen.writeStringField("directMax", metrics.getMetric("Status.JVM.Memory.Direct.TotalCapacity", "0"));

							gen.writeStringField("mappedCount", metrics.getMetric("Status.JVM.Memory.Mapped.Count", "0"));
							gen.writeStringField("mappedUsed", metrics.getMetric("Status.JVM.Memory.Mapped.MemoryUsed", "0"));
							gen.writeStringField("mappedMax", metrics.getMetric("Status.JVM.Memory.Mapped.TotalCapacity", "0"));

							gen.writeStringField("memorySegmentsAvailable", metrics.getMetric("Status.Network.AvailableMemorySegments", "0"));
							gen.writeStringField("memorySegmentsTotal", metrics.getMetric("Status.Network.TotalMemorySegments", "0"));

							gen.writeArrayFieldStart("garbageCollectors");

							for (String gcName : metrics.garbageCollectorNames) {
								String count = metrics.getMetric("Status.JVM.GarbageCollector." + gcName + ".Count", null);
								String time = metrics.getMetric("Status.JVM.GarbageCollector." + gcName + ".Time", null);
								if (count != null  && time != null) {
									gen.writeStartObject();
									gen.writeStringField("name", gcName);
									gen.writeStringField("count", count);
									gen.writeStringField("time", time);
									gen.writeEndObject();
								}
							}

							gen.writeEndArray();
							gen.writeEndObject();
						}
					}

					gen.writeEndObject();
				}

				gen.writeEndArray();
				gen.writeEndObject();

				gen.close();
				return writer.toString();
			}
			else {
				throw new Exception("No connection to the leading JobManager.");
			}
		}
		catch (Exception e) {
			throw new RuntimeException("Failed to fetch list of all task managers: " + e.getMessage(), e);
		}
	}
}
