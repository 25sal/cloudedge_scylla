# Additional cache scenarios

This document describes the three synthetic experiments generated from `samples/other_cache_scenarios/*.txt`.
They extend the distributed-cache example in [cache-experiments.md](cache-experiments.md). The XML files are generated
by `samples/cache_scenarios/run_experiments.py`; edit its templates and rerun the generator rather than hand-editing
those XML files.

## Generate the files

From the repository root:

```bash
python3 samples/cache_scenarios/run_experiments.py write-other-scenarios
```

Generated directories:

| Directory | Scenario |
|---|---|
| `samples/other_cache_scenarios/scalable/` | Cache-cluster scale-out: N = 2, 4, 8, 16 |
| `samples/other_cache_scenarios/data_loc_network/` | Fixed local / same-zone / cross-zone transfer-size cases, with `none` and `owner` variants |
| `samples/other_cache_scenarios/asymmetric_cluster/` | Heterogeneous cache nodes, uniform routing control and weighted soft-affinity case |

Each config requests 20,000 process instances, mean inter-arrival 50 simulated seconds and hit probability 0.9, unless
otherwise noted. `1` simulated second represents `100 ms` in the illustrative time mapping. Speeds and workloads are
abstract. These experiments have not been run as part of generating this document; the values below are configured or
analytical expectations, not measured results.

## Run configurations

Build once from the repository root:

```bash
mvn -o -DskipTests clean package
```

Scylla writes output beside its global configuration, so the commands below copy each run's inputs to a separate
`/tmp` directory. They enable independent exclusive-gateway random streams, discussed in
[cache-experiments.md](cache-experiments.md#34-why-the-default-seeding-biases-the-result):

```bash
ROOT="$PWD"
CP="$ROOT/target/classes:$ROOT/target/libs/*"
BPMN="$ROOT/samples/other_cache_scenarios/scalable/distcache.bpmn"
for n in 2 4 8 16; do
  dir="/tmp/scalable-n$n"
  mkdir -p "$dir"
  cp "samples/other_cache_scenarios/scalable/scalable_n$n.global.xml" "$dir/global.xml"
  cp "samples/other_cache_scenarios/scalable/scalable_n$n.config.xml" "$dir/sim.xml"
  java -Dscylla.quiet=true -Dscylla.independentSeeds=true -cp "$CP" \
    de.hpi.bpt.scylla.Scylla --headless --config="$dir/global.xml" --bpmn="$BPMN" \
    --sim="$dir/sim.xml" --enable-bps-logging
done
```

For data locality, copy `dataloc.global.xml` and one of the six `dataloc_*.config.xml` files to a run directory,
then use `data_loc_network/distcache.bpmn` as `--bpmn`. For the asymmetric cluster use
`asymmetric.global.xml` with either config: `asymmetric_soft_affinity.config.xml` uses `distcache.bpmn`, while
`asymmetric_uniform.config.xml` requires `asymmetric_round_robin.bpmn`.

## Scenario 1: cluster scalability

**Question:** does adding cache nodes increase throughput or reduce response time, or is the database already the
bottleneck?

The four files `scalable_n2`, `scalable_n4`, `scalable_n8` and `scalable_n16` keep the workload, hit ratio, cache
capacity and database hardware fixed. Only `cache.defaultQuantity` changes. All use the `none` affinity variant, meaning
no affinity rules are declared and the simulator's default available-resource assignment is used. It is not a custom
uniform-random or round-robin policy.

Baseline hardware: each cache node has `cpuSpeed=1000`, `txSpeed=1`; the single database node has `ioRead=1`. At
`p=1`, a lookup has 10 s CPU + 10 s network service; cache write and replication each have 20 s CPU service; a DB
read takes 200 s. The miss probability is 0.1.

The analytical DB utilisation is

$$\rho_{DB} = \lambda(1-h)S_{DB} = \frac{1}{50}\cdot 0.1\cdot 200 = 0.4.$$

If requests are balanced across cache nodes, total cache service demand is approximately
`20 + 0.1 * (20 + 20) = 24 s/request`; estimated per-node utilisation is therefore `24/(50N)`, approximately
0.24, 0.12, 0.06 and 0.03 for N = 2, 4, 8 and 16. These are queueing estimates, not measurements; default resource
assignment need not distribute work perfectly evenly. Since DB utilisation remains at 0.4 and DB service dominates
a miss, cache scale-out should reduce cache pressure more than end-to-end miss latency. A flattening response-time
curve is evidence of the fixed database bottleneck.

Record for each N:

- per-cache-node utilisation `rho_cache_i` and the maximum across nodes;
- DB utilisation `rho_DB` and mean wait before `DB read`;
- mean hit and miss response times, plus throughput or completion time for the fixed 20,000 requests;
- cache-node request shares, to reveal imbalance hidden by average utilisation.

## Scenario 2: network locality and owner affinity

**Question:** how do hard ownership rules and anti-affinity interact with a transfer penalty?

The files in `data_loc_network/` use four equal cache instances (`#0` to `#3`), interpreted as `#0/#1` in Zone A and
`#2/#3` in Zone B. Every file keeps the same model and workloads except for the `txMB` assigned to `Cache lookup`:

| Path label | Configured `txMB` | `txSpeed` | Resulting network service at p=1 |
|---|---:|---:|---:|
| `local` | 0 | 1 MB/s | 0 s |
| `same_zone` | 2 | 1 MB/s | 2 s |
| `cross_zone` | 40 | 1 MB/s | 40 s |

Each path has `none` and `owner` variants. `none` declares no affinity and leaves selection to Scylla's default
resource allocator. In `owner`, lookup is hard-pinned to cache instance `#0`; a miss writes to the lookup resource via
hard history affinity, and replication is constrained by hard anti-affinity to that same lookup resource.

**Important interpretation:** the current extension has no zone topology, key-owner map or network-link model. `txMB`
is a data-volume demand, so the table represents a fixed amount of traffic with service time `txMB / txSpeed`; it does
not implement latency as a function of whether the selected node is local, in-zone or cross-zone. Also, the `owner`
config treats `#0` as the owner in all three path cases. The variants are therefore controlled proxies for comparing
fixed transfer work and resource constraints, not a dynamic locality-aware cache. A true topology experiment requires
an extension that maps `(key owner, selected node)` to a link/latency or per-pair bandwidth and changes task demand or
duration based on that pair.

Compare lookup service, cache-node utilization and share, write/replication waits, and total miss response time. In the
current fixed-volume setup, `owner` may wait for `#0` while `none` can use any available cache node; it does not
necessarily reduce the configured transfer service time.

## Scenario 3: heterogeneous cache cluster

**Question:** does routing by resource capability improve on a uniform routing baseline?

`samples/other_cache_scenarios/asymmetric_cluster/asymmetric.global.xml` defines three cache instances:

| Instance | `cpuSpeed` | `txSpeed` |
|---|---:|---:|
| `#0` (fast) | 2000 | 1 MB/s |
| `#1` (standard) | 1000 | 1 MB/s |
| `#2` (legacy) | 500 | 1 MB/s |

The standard lookup workload is 10,000 instructions and 10 MB transmitted. At `p=1`, nominal lookup service times
are 15, 20 and 30 s respectively. Arrivals, hit ratio, DB, app tier, write and replication workloads match Scenario B.

### Uniform routing control

`asymmetric_uniform.config.xml` is paired with `asymmetric_round_robin.bpmn`. Its exclusive gateway selects one of
three hard-pinned lookup tasks with probabilities 1/3 each. This is a uniform random split, not deterministic
round-robin scheduling. Since each branch is pinned, a request does not fail over to another node when its selected
node is busy; it waits for that node.

### Soft-affinity variant

`asymmetric_soft_affinity.config.xml` uses the ordinary distributed-cache BPMN. The lookup task declares soft
affinities with weights 4, 2 and 1 for `#0`, `#1` and `#2`. In the current ranker, satisfied weights are summed and
candidates are sorted by descending score; it does not sample a node with probability `weight/sum(weights)`. With one
resource per candidate, it prefers the highest-ranked *available* node. It can therefore behave as a fast-node
preference/load-aware fallback, not as the requested proportional-weight routing strategy.

Compare node-specific utilisation, task assignment shares, lookup queue waits, hit/miss response and completion time.
For capacity-aware proportional allocation, implement weighted random assignment (or a scheduler extension that
accounts for expected service time) rather than interpreting these soft-affinity weights as probabilities.

## Scope and limitations

- Hit probability is fixed at the gateway. The scenarios do not maintain cache contents, model key popularity or
  compute hit/miss outcomes from cache capacity.
- The current `none` variant means no affinity constraints; it does not promise random placement.
- `owner` in data locality is statically `#0`; zone membership is documentation, not parsed model state.
- `txMB` models data transfer work through bandwidth, not propagation latency, serialization delay or inter-node
  contention.
- Soft-affinity weights rank candidate tuples. They are not proportional probabilities and do not account for
  heterogeneous execution speed by themselves.
- No simulations or performance measurements are reported in this document; it describes generated configurations
  and analytical expectations only.
