package de.hpi.bpt.scylla.plugin.affinity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import de.hpi.bpt.scylla.simulation.ResourceObject;
import de.hpi.bpt.scylla.simulation.ResourceObjectTuple;

public class AffinityRankingTests {

    private final ResourceObject server1 = new ResourceObject("server", "server1");
    private final ResourceObject server2 = new ResourceObject("server", "server2");
    private final ResourceObject server3 = new ResourceObject("server", "server3");

    private ResourceObjectTuple tuple(ResourceObject resource) {
        ResourceObjectTuple tuple = new ResourceObjectTuple();
        tuple.getResourceObjects().add(resource);
        return tuple;
    }

    private List<ResourceObjectTuple> candidates() {
        return Arrays.asList(tuple(server1), tuple(server2), tuple(server3));
    }

    private ResourceObject first(List<ResourceObjectTuple> ranked) {
        return ranked.get(0).getResourceObjects().iterator().next();
    }

    @Test
    public void softAffinityPrefersMatchingInstance() {
        AffinityRule rule = AffinityRule.forResource(false, false, 1, "server", "server2");
        List<ResourceObjectTuple> ranked = AffinityRankingPlugin.order(candidates(), Arrays.asList(rule),
                new AffinityHistory());
        assertEquals(3, ranked.size());
        assertEquals(server2, first(ranked));
    }

    @Test
    public void equalScoresKeepDefaultOrder() {
        AffinityRule rule = AffinityRule.forResource(false, false, 1, "server", "server3");
        List<ResourceObjectTuple> ranked = AffinityRankingPlugin.order(candidates(), Arrays.asList(rule),
                new AffinityHistory());
        assertEquals(Arrays.asList(server3, server1, server2), resources(ranked));
    }

    @Test
    public void hardAntiAffinityRemovesCandidate() {
        AffinityRule rule = AffinityRule.forResource(true, true, 1, "server", "server1");
        List<ResourceObjectTuple> ranked = AffinityRankingPlugin.order(candidates(), Arrays.asList(rule),
                new AffinityHistory());
        assertEquals(Arrays.asList(server2, server3), resources(ranked));
    }

    @Test
    public void unsatisfiableHardRuleYieldsNoCandidates() {
        AffinityRule rule = AffinityRule.forResource(true, false, 1, "server", "other");
        assertTrue(AffinityRankingPlugin.order(candidates(), Arrays.asList(rule), new AffinityHistory()).isEmpty());
    }

    @Test
    public void historyAffinityFollowsReferencedTask() {
        AffinityHistory history = new AffinityHistory();
        history.record(7, Collections.singleton(server3));
        AffinityRule rule = AffinityRule.forHistory(true, false, 1, 7, "T7");
        List<ResourceObjectTuple> ranked = AffinityRankingPlugin.order(candidates(), Arrays.asList(rule), history);
        assertEquals(Arrays.asList(server3), resources(ranked));
    }

    @Test
    public void historyRuleWithoutHistoryDoesNotApply() {
        AffinityRule rule = AffinityRule.forHistory(true, false, 1, 7, "T7");
        assertEquals(3, AffinityRankingPlugin.order(candidates(), Arrays.asList(rule), new AffinityHistory()).size());
    }

    @Test
    public void previousTaskAntiAffinityAvoidsLastResource() {
        AffinityHistory history = new AffinityHistory();
        history.record(1, Collections.singleton(server1));
        history.record(2, Collections.singleton(server2));
        AffinityRule rule = AffinityRule.forHistory(true, true, 1, null, null);
        List<ResourceObjectTuple> ranked = AffinityRankingPlugin.order(candidates(), Arrays.asList(rule), history);
        assertEquals(Arrays.asList(server1, server3), resources(ranked));
    }

    @Test
    public void higherWeightWins() {
        AffinityRule light = AffinityRule.forResource(false, false, 1, "server", "server1");
        AffinityRule heavy = AffinityRule.forResource(false, false, 5, "server", "server3");
        List<ResourceObjectTuple> ranked = AffinityRankingPlugin.order(candidates(), Arrays.asList(light, heavy),
                new AffinityHistory());
        assertEquals(server3, first(ranked));
    }

    @Test
    public void logNamesRejectingRuleAndScores() {
        AffinityRule hard = AffinityRule.forResource(true, true, 1, "server", "server1");
        AffinityRule soft = AffinityRule.forResource(false, false, 2, "server", "server2");
        AffinityRule notYet = AffinityRule.forHistory(false, false, 1, 9, "T9");
        List<String> log = new ArrayList<String>();
        AffinityRankingPlugin.order(candidates(), Arrays.asList(hard, soft, notYet), new AffinityHistory(), log::add);

        assertTrue(log.contains("rule not applicable yet (referenced task has not started): " + notYet));
        assertTrue(log.contains("[server_server1] rejected, violates HARD anti-affinity to resource server instance server1"));
        assertTrue(log.contains("[server_server2] admissible, score 2.0 +2.0"));
        assertTrue(log.contains("[server_server3] admissible, score 0.0"));
    }

    private List<ResourceObject> resources(List<ResourceObjectTuple> tuples) {
        return tuples.stream().map(each -> each.getResourceObjects().iterator().next())
                .collect(java.util.stream.Collectors.toList());
    }
}
