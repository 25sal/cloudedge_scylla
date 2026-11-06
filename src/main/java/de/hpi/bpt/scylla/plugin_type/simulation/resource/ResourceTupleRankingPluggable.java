package de.hpi.bpt.scylla.plugin_type.simulation.resource;

import java.util.Iterator;
import java.util.List;

import de.hpi.bpt.scylla.plugin_loader.PluginLoader;
import de.hpi.bpt.scylla.plugin_type.IPluggable;
import de.hpi.bpt.scylla.simulation.ResourceObjectTuple;
import de.hpi.bpt.scylla.simulation.SimulationModel;
import de.hpi.bpt.scylla.simulation.event.ScyllaEvent;

/**
 * Plugins for this entry point order or filter the resource tuples that are valid candidates for an event.
 * The first tuple of the returned list is assigned; an empty list means the event has to wait.
 * Implementations must not have side effects, as the ranking is also evaluated when only checking for availability.
 */
public abstract class ResourceTupleRankingPluggable implements IPluggable {

    public static List<ResourceObjectTuple> runPlugins(SimulationModel model, ScyllaEvent event,
            List<ResourceObjectTuple> candidates) {
        Iterator<ResourceTupleRankingPluggable> plugins = PluginLoader.dGetPlugins(ResourceTupleRankingPluggable.class);
        List<ResourceObjectTuple> ranked = candidates;
        while (plugins.hasNext()) {
            ranked = plugins.next().rank(model, event, ranked);
        }
        return ranked;
    }

    /**
     * @param candidates
     *            tuples that satisfy resource types, amounts and timetables, best default choice first
     * @return the admissible candidates, best choice first
     */
    public abstract List<ResourceObjectTuple> rank(SimulationModel model, ScyllaEvent event,
            List<ResourceObjectTuple> candidates);

}
