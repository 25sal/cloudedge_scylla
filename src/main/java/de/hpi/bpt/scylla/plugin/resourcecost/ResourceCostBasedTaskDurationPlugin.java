package de.hpi.bpt.scylla.plugin.resourcecost;

import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import de.hpi.bpt.scylla.exception.ScyllaRuntimeException;
import de.hpi.bpt.scylla.logger.DebugLogger;
import de.hpi.bpt.scylla.plugin_type.simulation.event.TaskBeginEventPluggable;
import de.hpi.bpt.scylla.simulation.ProcessInstance;
import de.hpi.bpt.scylla.simulation.ResourceObject;
import de.hpi.bpt.scylla.simulation.ResourceObjectTuple;
import de.hpi.bpt.scylla.simulation.SimulationModel;
import de.hpi.bpt.scylla.simulation.event.ScyllaEvent;
import de.hpi.bpt.scylla.simulation.event.TaskBeginEvent;
import de.hpi.bpt.scylla.simulation.event.TaskTerminateEvent;
import de.hpi.bpt.scylla.simulation.utils.DateTimeUtils;
import desmoj.core.simulator.TimeInstant;
import desmoj.core.simulator.TimeSpan;

/**
 * Replaces the duration of tasks that define a workload by the service time of the assigned resources:
 * 
 * <pre>
 * T_cpu = numInstructions / (cpuSpeed * (1 - idleFactor))
 * T_io  = readOps / ioRead + writeOps / ioWrite
 * T_net = txMB / txSpeed + rxMB / rxSpeed
 * T     = (T_cpu^p + T_io^p + T_net^p)^(1/p)
 * </pre>
 * 
 * Speeds are per second, so T is in seconds. p is the softmax exponent of the global configuration: 1 adds the
 * components, large values approach their maximum. If several resources are assigned, the slowest one defining a
 * speed is the bottleneck for that component.
 */
public class ResourceCostBasedTaskDurationPlugin extends TaskBeginEventPluggable {

    @Override
    public String getName() {
        return ResourceCostPluginUtils.PLUGIN_NAME;
    }

    @Override
    public void eventRoutine(TaskBeginEvent event, ProcessInstance processInstance) throws ScyllaRuntimeException {
        Map<String, Double> workload = ResourceCostPluginUtils.getWorkload(event);
        if (workload.isEmpty()) {
            return;
        }

        SimulationModel model = (SimulationModel) event.getModel();
        ResourceObjectTuple tuple = processInstance.getAssignedResources().get(event.getSource());
        if (tuple == null || tuple.getResourceObjects().isEmpty()) {
            throw new ScyllaRuntimeException("Task " + event.getDisplayName() + " has a workload but no resources.");
        }

        HardwareProfiles profiles = (HardwareProfiles) model.getGlobalConfiguration()
                .getExtensionValue(ResourceCostPluginUtils.PLUGIN_NAME, ResourceCostPluginUtils.PROFILES_KEY);
        if (profiles == null) {
            profiles = new HardwareProfiles();
        }
        Object configuredExponent = model.getGlobalConfiguration()
                .getExtensionValue(ResourceCostPluginUtils.PLUGIN_NAME, ResourceCostPluginUtils.EXPONENT_KEY);
        double exponent = configuredExponent == null ? ResourceCostPluginUtils.DEFAULT_EXPONENT
                : (Double) configuredExponent;

        ServiceTime serviceTime = serviceTime(event.getDisplayName(), workload, tuple.getResourceObjects(), profiles,
                exponent);
        TimeSpan duration = new TimeSpan(serviceTime.total, TimeUnit.SECONDS);

        String prefix = ResourceCostPluginUtils.LOG_PREFIX;
        DebugLogger.log(prefix + ResourceCostPluginUtils.context(model, event) + ": workload "
                + new TreeMap<String, Double>(workload) + " on " + ResourceCostPluginUtils.describe(tuple));
        DebugLogger.log(prefix + "  " + serviceTime);

        TimeInstant now = model.presentTime();
        for (Map.Entry<Integer, ScyllaEvent> next : event.getNextEventMap().entrySet()) {
            if (next.getValue() instanceof TaskTerminateEvent) {
                TimeInstant end = DateTimeUtils.getTaskTerminationTime(duration, now, tuple, next.getValue());
                TimeSpan scheduled = new TimeSpan(end.getTimeAsDouble() - now.getTimeAsDouble());
                event.getTimeSpanToNextEventMap().put(next.getKey(), scheduled);
                DebugLogger.log(prefix + "  task terminates after "
                        + scheduled.getTimeRounded(DateTimeUtils.getReferenceTimeUnit()) + " "
                        + DateTimeUtils.getReferenceTimeUnit() + " (at " + DateTimeUtils.getDateTime(end) + ")");
            }
        }
    }

    static double computeSeconds(String taskName, Map<String, Double> workload, Collection<ResourceObject> resources,
            HardwareProfiles profiles, double exponent) throws ScyllaRuntimeException {
        return serviceTime(taskName, workload, resources, profiles, exponent).total;
    }

    static ServiceTime serviceTime(String taskName, Map<String, Double> workload, Collection<ResourceObject> resources,
            HardwareProfiles profiles, double exponent) throws ScyllaRuntimeException {
        double cpu = componentSeconds(taskName, workload, ResourceCostPluginUtils.INSTRUCTIONS,
                ResourceCostPluginUtils.CPU_SPEED, resources, profiles::effectiveCpuSpeed);
        double io = componentSeconds(taskName, workload, ResourceCostPluginUtils.READ_OPS,
                ResourceCostPluginUtils.IO_READ, resources, each -> profiles.value(each, ResourceCostPluginUtils.IO_READ))
                + componentSeconds(taskName, workload, ResourceCostPluginUtils.WRITE_OPS,
                        ResourceCostPluginUtils.IO_WRITE, resources,
                        each -> profiles.value(each, ResourceCostPluginUtils.IO_WRITE));
        double net = componentSeconds(taskName, workload, ResourceCostPluginUtils.TX_MB,
                ResourceCostPluginUtils.TX_SPEED, resources,
                each -> profiles.value(each, ResourceCostPluginUtils.TX_SPEED))
                + componentSeconds(taskName, workload, ResourceCostPluginUtils.RX_MB,
                        ResourceCostPluginUtils.RX_SPEED, resources,
                        each -> profiles.value(each, ResourceCostPluginUtils.RX_SPEED));
        return new ServiceTime(cpu, io, net, exponent, softmax(exponent, cpu, io, net));
    }

    static final class ServiceTime {
        final double cpu;
        final double io;
        final double net;
        final double exponent;
        final double total;

        ServiceTime(double cpu, double io, double net, double exponent, double total) {
            this.cpu = cpu;
            this.io = io;
            this.net = net;
            this.exponent = exponent;
            this.total = total;
        }

        @Override
        public String toString() {
            return String.format(Locale.ROOT, "T_cpu=%.3fs T_io=%.3fs T_net=%.3fs, p=%s -> service time T=%.3fs", cpu,
                    io, net, exponent, total);
        }
    }

    /** (sum of t^p)^(1/p), evaluated relative to the largest component so that large p cannot overflow. */
    static double softmax(double exponent, double... times) {
        double largest = 0;
        for (double time : times) {
            largest = Math.max(largest, time);
        }
        if (largest == 0) {
            return 0;
        }
        double sum = 0;
        for (double time : times) {
            sum += Math.pow(time / largest, exponent);
        }
        return largest * Math.pow(sum, 1 / exponent);
    }

    private static double componentSeconds(String taskName, Map<String, Double> workload, String demandKey,
            String speedKey, Collection<ResourceObject> resources, Function<ResourceObject, Double> speedOf)
                    throws ScyllaRuntimeException {
        double demand = workload.getOrDefault(demandKey, 0.0);
        if (demand == 0) {
            return 0;
        }
        Double slowest = null;
        for (ResourceObject resource : resources) {
            Double speed = speedOf.apply(resource);
            if (speed != null && (slowest == null || speed < slowest)) {
                slowest = speed;
            }
        }
        if (slowest == null) {
            throw new ScyllaRuntimeException("Task " + taskName + " needs " + demandKey
                    + ", but no assigned resource defines " + speedKey + ".");
        }
        return demand / slowest;
    }
}
