/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.druid.emitter.prometheus;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.prometheus.client.CollectorRegistry;
import io.prometheus.client.Counter;
import io.prometheus.client.Gauge;
import io.prometheus.client.Histogram;
import io.prometheus.client.exporter.PushGateway;
import org.apache.druid.java.util.common.StringUtils;
import org.apache.druid.java.util.common.concurrent.ScheduledExecutors;
import org.apache.druid.java.util.common.logger.Logger;
import org.apache.druid.java.util.emitter.service.ServiceMetricEvent;

import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

public class PrometheusReporter
{

  private static final Logger log = new Logger(PrometheusReporter.class);

  private static final Pattern UNALLOWED_CHAR_PATTERN = Pattern.compile("[^a-zA-Z0-9:_]");

  private final ScheduledExecutorService exec = ScheduledExecutors.fixed(1, "PrometheusEmitter-%s");

  private Metrics metrics;
  private String hostName;
  private PushGateway pushGateway;
  private PrometheusEmitterConfig config;

  public PrometheusReporter(PrometheusEmitterConfig config, ObjectMapper mapper)
  {
    this.config = config;
  }

  public void init()
  {
    initMetrics();
    initGatWay();

    exec.scheduleAtFixedRate(this::push, this.config.getFlushDelay(), this.config.getFlushPeriod(), TimeUnit.SECONDS);
  }

  private void initMetrics()
  {
    metrics = new Metrics(this.config.getNameSpace(), this.config.getMetricMapPath());
  }

  private void initGatWay()
  {
    String address = this.config.getPort() == 0 ? this.config.getHost() : (this.config.getHost() + ":" + this.config.getPort());
    log.info("prometheus address [%s]", address);
    pushGateway = new PushGateway(address);
  }

  public void emitMetric(ServiceMetricEvent metricEvent)
  {
    String name = metricEvent.getMetric();
    String service = metricEvent.getService();
    Map<String, Object> userDims = metricEvent.getUserDims();
    hostName = metricEvent.getHost();
    Number value = metricEvent.getValue();

    DimensionsAndCollector metric = metrics.getByName(name, service);
    if (metric != null) {
      String[] labelValues = new String[metric.getDimensions().length];
      String[] labelNames = metric.getDimensions();
      for (int i = 0; i < labelValues.length; i++) {
        String labelName = labelNames[i];
        if ("service".equals(labelName)) {
          labelValues[i] = service;
          continue;
        }
        //labelName is controlled by the user. Instead of potential NPE on invalid labelName we use "unknown" as the dimension value
        Object userDim = userDims.get(labelName);
        labelValues[i] = userDim != null ? UNALLOWED_CHAR_PATTERN.matcher(StringUtils.toLowerCase(userDim.toString())).replaceAll("_") : "unknown";
      }

      if (metric.getCollector() instanceof Counter) {
        ((Counter) metric.getCollector()).labels(labelValues).inc(value.doubleValue());
      } else if (metric.getCollector() instanceof Gauge) {
        ((Gauge) metric.getCollector()).labels(labelValues).set(value.doubleValue());
      } else if (metric.getCollector() instanceof Histogram) {
        ((Histogram) metric.getCollector()).labels(labelValues).observe(value.doubleValue() / metric.getConversionFactor());
      } else {
        log.error("Unrecognized metric type [%s]", metric.getCollector().getClass());
      }
    } else {
      log.debug("Unmapped metric [%s]", name);
    }
  }

  /**
   * push data to prometheus gateway
   *
   */
  public void push()
  {
    try {
      pushGateway.push(CollectorRegistry.defaultRegistry, hostName);
    }
    catch (Exception e) {
      log.error(e, "error occurred when sending metrics to prometheus gateway.");
    }
  }

  public void delete()
  {
    exec.shutdownNow();
  }
}
