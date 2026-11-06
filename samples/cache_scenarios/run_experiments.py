#!/usr/bin/env python3
"""Experiments for the proxy-cache and distributed-cache scenarios (see docs/distributed-systems-extension.md).

  run_experiments.py write-samples   write the baseline global/simulation configs next to the BPMN files
  run_experiments.py proxy           sweep the hit ratio of the proxy cache and compare with M/D/1 theory
  run_experiments.py dist            compare affinity variants of the distributed cache
  run_experiments.py exponent        effect of the softmax exponent on the cache lookup time
    run_experiments.py write-other-scenarios  generate the three configurations in samples/other_cache_scenarios

Time model: 1 simulated second stands for 100 ms of real time, because Scylla works in whole seconds (fractions of a
task duration are truncated).
Build first (mvn -o -DskipTests package), then run from any directory.
"""
import argparse
import glob
import os
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
from collections import Counter, defaultdict
from datetime import datetime
from statistics import mean, stdev

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
NS = "http://bsim.hpi.uni-potsdam.de/scylla/simModel"

ORIGIN_SERVICE = 200  # seconds of one origin / database read
MEAN_ARRIVAL = 50  # seconds between requests

PROXY_BPMN = os.path.join(HERE, "proxy_cache", "proxycache.bpmn")
DIST_BPMN = os.path.join(HERE, "distributed_cache", "distcache.bpmn")


# ---------------------------------------------------------------- configuration templates

def task(task_id, name, resource, workload, affinity=""):
    attributes = "\n".join(f'      <bsim:attribute key="{k}" value="{v}" />' for k, v in workload.items())
    return (f'    <bsim:task id="{task_id}" name="{name}">\n{affinity}'
            f'      <bsim:resources>\n        <bsim:resource id="{resource}" amount="1" />\n      </bsim:resources>\n'
            f'{attributes}\n    </bsim:task>\n')


def comment(text):
    """XML comments; the text must not contain a double hyphen."""
    return "".join(f"<!-- {line} -->\n" for line in text.splitlines())


def exclusive_gateway(gateway_id, flows):
    """flows: (flow id, probability) pairs; a flow left out receives the remainder if it is the gateway's default flow."""
    lines = "".join(f'      <bsim:outgoingSequenceFlow id="{flow}"><bsim:branchingProbability>{round(p, 6)}</bsim:branchingProbability></bsim:outgoingSequenceFlow>\n'
                    for flow, p in flows)
    return f'    <bsim:exclusiveGateway id="{gateway_id}">\n{lines}    </bsim:exclusiveGateway>\n'


def gateway(hit):
    hit = round(hit, 6)
    miss = round(1 - hit, 6)
    return exclusive_gateway("Gateway_Hit", (("Flow_Hit", hit), ("Flow_Miss", miss)))


def sim_config(config_id, process, instances, seed, body, mean_arrival=MEAN_ARRIVAL, note=""):
    return f'''<?xml version="1.0" encoding="UTF-8"?>
<!-- Time model: 1 simulated second stands for 100 ms of real time. Speeds and demands are abstract units. -->
{comment(note)}<bsim:definitions xmlns:bsim="{NS}" id="{config_id}-definitions" targetNamespace="http://www.hpi.de">
  <bsim:simulationConfiguration id="{config_id}" processRef="{process}" processInstances="{instances}" startDateTime="2025-01-01T00:00:00.000+00:00" randomSeed="{seed}">
    <bsim:startEvent id="StartEvent_1">
      <bsim:arrivalRate timeUnit="SECONDS">
        <bsim:exponentialDistribution>
          <bsim:mean>{mean_arrival}</bsim:mean>
        </bsim:exponentialDistribution>
      </bsim:arrivalRate>
    </bsim:startEvent>
{body}  </bsim:simulationConfiguration>
</bsim:definitions>
'''


def global_config(config_id, seed, resources, exponent=1, note=""):
    return f'''<?xml version="1.0" encoding="UTF-8"?>
<!-- cpuSpeed: instructions/s, ioRead/ioWrite: operations/s, txSpeed/rxSpeed: MB/s (abstract units) -->
{comment(note)}<bsim:globalConfiguration xmlns:bsim="{NS}" targetNamespace="http://www.hpi.de" id="{config_id}" softmaxExponent="{exponent}">
  <bsim:resourceData>
{resources}  </bsim:resourceData>
  <bsim:randomSeed>{seed}</bsim:randomSeed>
</bsim:globalConfiguration>
'''


def resource(rid, quantity, **hardware):
    attributes = " ".join(f'{k}="{v}"' for k, v in hardware.items())
    return (f'    <bsim:dynamicResource id="{rid}" defaultQuantity="{quantity}" defaultCost="1" '
            f'defaultTimeUnit="SECONDS" {attributes} />\n')


def proxy_files(hit, instances, seed):
    sim = sim_config("proxycache-config", "proxycache", instances, seed,
                     task("Proxy_Lookup", "Proxy lookup", "proxy", {"numInstructions": 2000})
                     + gateway(hit)
                     + task("Proxy_Serve_Hit", "Proxy serve hit", "proxy", {"numInstructions": 2000})
                     + task("Origin_Fetch", "Origin fetch", "origin", {"readOps": ORIGIN_SERVICE})
                     + task("Proxy_Store", "Proxy store", "proxy", {"numInstructions": 2000}))
    glob_ = global_config("proxycache-global", seed,
                          resource("proxy", 1, cpuSpeed=1000) + resource("origin", 1, ioRead=1))
    return glob_, sim


AFFINITY_VARIANTS = ("none", "owner", "hot")


def dist_files(variant, hit, instances, seed, nodes=2, exponent=1, mean_arrival=MEAN_ARRIVAL,
               db_service=ORIGIN_SERVICE, lookup_network_mb=10, note=""):
    if variant not in AFFINITY_VARIANTS:
        raise ValueError(f"unknown variant {variant}")
    lookup_affinity = write_affinity = replicate_affinity = ""
    if variant in ("owner", "hot"):
        write_affinity = ('      <bsim:affinity>\n        <bsim:history task="Cache_Lookup" relation="affinity" mode="hard" />\n'
                          '      </bsim:affinity>\n')
        replicate_affinity = ('      <bsim:affinity>\n        <bsim:history task="Cache_Lookup" relation="antiAffinity" mode="hard" />\n'
                              '      </bsim:affinity>\n')
    if variant == "hot":
        lookup_affinity = ('      <bsim:affinity>\n        <bsim:resource id="cache" instance="#0" relation="affinity" mode="soft" weight="5" />\n'
                           '      </bsim:affinity>\n')
    sim = sim_config(f"distcache-{variant}-config", "distcache", instances, seed,
                     task("Cache_Lookup", "Cache lookup", "cache",
                         {"numInstructions": 10000, "txMB": lookup_network_mb}, lookup_affinity)
                     + gateway(hit)
                     + task("Return_Hit", "Return cached value", "app", {"numInstructions": 2000})
                     + task("DB_Read", "DB read", "db", {"readOps": db_service})
                     + task("Cache_Write", "Cache write", "cache", {"numInstructions": 20000}, write_affinity)
                     + task("Cache_Replicate", "Cache replicate", "cache", {"numInstructions": 20000}, replicate_affinity),
                     mean_arrival, note)
    glob_ = global_config("distcache-global", seed,
                          resource("cache", nodes, cpuSpeed=1000, txSpeed=1)
                          + resource("db", 1, ioRead=1)
                          + resource("app", 20, cpuSpeed=1000), exponent, note)
    return glob_, sim


# ---------------------------------------------------------------- running and analysing

def default_classpath():
    return os.pathsep.join([os.path.join(ROOT, "target", "classes"), os.path.join(ROOT, "target", "libs", "*")])


def simulate(global_xml, sim_xml, bpmn, classpath, keep, independent_seeds=True):
    """Runs Scylla in a temporary folder and returns the parsed event log."""
    with tempfile.TemporaryDirectory(prefix="scylla-cache-") as work:
        paths = {}
        for name, text in (("global.xml", global_xml), ("sim.xml", sim_xml)):
            paths[name] = os.path.join(work, name)
            with open(paths[name], "w", encoding="utf-8") as handle:
                handle.write(text)
        command = ["java", "-Dscylla.quiet=true", f"-Dscylla.independentSeeds={str(independent_seeds).lower()}",
                   "-cp", classpath, "de.hpi.bpt.scylla.Scylla", "--headless",
                   f"--config={paths['global.xml']}", f"--bpmn={bpmn}", f"--sim={paths['sim.xml']}",
                   "--enable-bps-logging"]
        result = subprocess.run(command, cwd=work, capture_output=True, text=True)
        logs = glob.glob(os.path.join(work, "output_*", "*.xes"))
        if result.returncode != 0 or not logs:
            sys.exit(f"Scylla failed (exit {result.returncode}):\n{result.stdout[-2000:]}\n{result.stderr[-2000:]}")
        traces = parse_traces(logs[0])
        if keep:
            os.makedirs(keep, exist_ok=True)
            subprocess.run(["cp", "-r", work, os.path.join(keep, os.path.basename(work))])
        return traces


def parse_traces(xes):
    traces = []
    for _, element in ET.iterparse(xes, events=("end",)):
        if element.tag != "trace":
            continue
        events = []
        for event in element.findall("event"):
            attributes = {child.get("key"): child.get("value") for child in event}
            events.append((attributes["concept:name"], attributes["lifecycle:transition"],
                           attributes.get("org:resource"),
                           datetime.fromisoformat(attributes["time:timestamp"]).timestamp()))
        events.sort(key=lambda e: e[3])  # stable: start stays before complete
        traces.append(events)
        element.clear()
    return traces


def summarise(traces, tasks, miss_marker, warmup):
    """Waits are measured from the completion of the previous step to the start of the task."""
    skip = int(len(traces) * warmup)
    waits, services = defaultdict(list), defaultdict(list)
    busy, uses = defaultdict(float), Counter()
    responses = {"hit": [], "miss": []}
    first = min(t[0][3] for t in traces)
    last = max(t[-1][3] for t in traces)
    for index, events in enumerate(traces):
        last_complete = events[0][3]
        started = {}
        for name, transition, res, time in events:
            if name in tasks and transition == "start":
                started[name] = time
                if index >= skip:
                    waits[name].append(time - last_complete)
            elif transition == "complete":
                if name in tasks:
                    duration = time - started[name]
                    services[name].append(duration)
                    busy[res] += duration
                    uses[(name, res)] += 1
                last_complete = time
        kind = "miss" if any(e[0] == miss_marker for e in events) else "hit"
        if index >= skip:
            responses[kind].append(events[-1][3] - events[0][3])
    return {"n": len(traces), "span": last - first, "waits": waits, "services": services, "busy": busy,
            "uses": uses, "responses": responses}


def fmt(values):
    return f"{mean(values):8.1f}" if values else "     n/a"


# ---------------------------------------------------------------- commands

def run(args, global_xml, sim_xml, bpmn):
    return simulate(global_xml, sim_xml, bpmn, args.classpath, args.keep, not args.shared_seeds)


def spread(values):
    """mean and sample standard deviation over replications"""
    return mean(values), (stdev(values) if len(values) > 1 else 0.0)


def proxy_command(args):
    print(f"Proxy cache: {args.instances} requests per run, {args.replications} replication(s), mean inter-arrival "
          f"{MEAN_ARRIVAL}s, origin service {ORIGIN_SERVICE}s, first {args.warmup:.0%} ignored for waits.\n"
          f"Random streams: {'shared (Scylla default)' if args.shared_seeds else 'independent per gateway'}.\n")
    header = (f"{'hit':>5} {'rho_origin':>10} {'rho (M/D/1)':>11} {'wait origin (mean ± sd)':>24} {'Wq (M/D/1)':>10} "
              f"{'resp hit':>9} {'resp miss':>9} {'hit ratio':>9}")
    print(header + "\n" + "-" * len(header))
    for hit in args.hit:
        rows = []
        for replication in range(args.replications):
            glob_, sim = proxy_files(hit, args.instances, args.seed + replication)
            traces = run(args, glob_, sim, PROXY_BPMN)
            rows.append(summarise(traces, {"Proxy lookup", "Proxy serve hit", "Origin fetch", "Proxy store"},
                                  "Origin fetch", args.warmup))
        rho = (1 - hit) * ORIGIN_SERVICE / MEAN_ARRIVAL
        wq = f"{rho * ORIGIN_SERVICE / (2 * (1 - rho)):10.1f}" if rho < 1 else "  unstable"
        wait_mean, wait_sd = spread([mean(s["waits"]["Origin fetch"]) for s in rows])
        total = [len(s["responses"]["miss"]) + len(s["responses"]["hit"]) for s in rows]
        misses = [len(s["responses"]["miss"]) for s in rows]
        print(f"{hit:5.2f} {mean(s['busy']['origin_#0'] / s['span'] for s in rows):10.3f} {rho:11.3f} "
              f"{wait_mean:15.1f} ± {wait_sd:5.1f} {wq:>10} "
              f"{fmt([mean(s['responses']['hit']) for s in rows]):>9} "
              f"{fmt([mean(s['responses']['miss']) for s in rows]):>9} {1 - sum(misses) / sum(total):9.3f}")


def dist_report(label, s):
    cache_nodes = sorted(r for r in s["busy"] if r.startswith("cache_"))
    lookups = {n: s["uses"][("Cache lookup", n)] for n in cache_nodes}
    total_lookups = sum(lookups.values()) or 1
    node_text = " ".join(f"{n.split('_')[1]}:{s['busy'][n] / s['span']:.2f}" for n in cache_nodes)
    share0 = lookups.get("cache_#0", 0) / total_lookups
    print(f"{label:>7} {fmt(s['waits']['Cache lookup']):>12} {fmt(s['waits']['DB read']):>10} "
          f"{fmt(s['waits']['Cache write']):>11} {fmt(s['waits']['Cache replicate']):>12} "
          f"{fmt(s['responses']['hit']):>9} {fmt(s['responses']['miss']):>9} {s['busy']['db_#0'] / s['span']:7.2f} "
          f"{share0:11.2f}   {node_text}")


DIST_TASKS = {"Cache lookup", "Return cached value", "DB read", "Cache write", "Cache replicate"}


def dist_command(args):
    print(f"Distributed cache: {args.instances} requests, {args.nodes} cache nodes, softmax exponent {args.exponent}.\n"
          "wait = time between the previous step and the start of the task; ρ per cache node after 'ρ cache'.\n")
    for hit in args.hit:
        print(f"hit ratio {hit:.2f}  (analytic DB utilisation {(1 - hit) * ORIGIN_SERVICE / MEAN_ARRIVAL:.2f})")
        header = (f"{'variant':>7} {'wait lookup':>12} {'wait DB':>10} {'wait write':>11} {'wait replic.':>12} "
                  f"{'resp hit':>9} {'resp miss':>9} {'ρ DB':>7} {'lookups #0':>11}   ρ cache nodes")
        print(header + "\n" + "-" * len(header))
        for variant in args.variants:
            glob_, sim = dist_files(variant, hit, args.instances, args.seed, args.nodes, args.exponent)
            traces = run(args, glob_, sim, DIST_BPMN)
            dist_report(variant, summarise(traces, DIST_TASKS, "DB read", args.warmup))
        print()


def exponent_command(args):
    print(f"Service time of 'Cache lookup' (10 s CPU + 10 s network) for different softmax exponents p.\n")
    print(f"{'p':>5} {'mean lookup service':>20} {'analytic':>9}")
    for exponent in args.exponents:
        glob_, sim = dist_files("owner", args.hit[0], args.instances, args.seed, args.nodes, exponent)
        s = summarise(run(args, glob_, sim, DIST_BPMN), DIST_TASKS, "DB read", args.warmup)
        analytic = (10 ** exponent + 10 ** exponent) ** (1 / exponent)
        print(f"{exponent:5g} {fmt(s['services']['Cache lookup']):>20} {analytic:9.2f}")
    print("\n(Scylla truncates task durations to whole seconds: p=2 gives 14 instead of 14.14, p=4 gives 11 instead of 11.89.)")


def write_samples(args):
    proxy_global, proxy_sim = proxy_files(0.8, args.instances, args.seed)
    dist_global, dist_sim = dist_files("hot", 0.9, args.instances, args.seed, 2)
    targets = {
        os.path.join(HERE, "proxy_cache", "proxycache.global.xml"): proxy_global,
        os.path.join(HERE, "proxy_cache", "proxycache.config.xml"): proxy_sim,
        os.path.join(HERE, "distributed_cache", "distcache.global.xml"): dist_global,
        os.path.join(HERE, "distributed_cache", "distcache.config.xml"): dist_sim,
    }
    for path, text in targets.items():
        with open(path, "w", encoding="utf-8") as handle:
            handle.write(text)
        print("wrote", os.path.relpath(path, ROOT))


def write_file(path, contents):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as handle:
        handle.write(contents)
    print("wrote", os.path.relpath(path, ROOT))


def router_bpmn():
    """One exclusive routing split with a distinct, resource-pinned lookup task per cache node."""
    parts = ['''<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" id="Definitions_Asymmetric" targetNamespace="http://bpmn.io/schema/bpmn">
  <bpmn:process id="asymmetric_round_robin" isExecutable="false">
    <bpmn:startEvent id="StartEvent_1"><bpmn:outgoing>Flow_Start</bpmn:outgoing></bpmn:startEvent>
    <bpmn:exclusiveGateway id="Gateway_Route">
      <bpmn:incoming>Flow_Start</bpmn:incoming>''']
    for index in range(3):
        parts.append(f'      <bpmn:outgoing>Flow_Node{index}</bpmn:outgoing>')
    parts.append('''    </bpmn:exclusiveGateway>
    <bpmn:sequenceFlow id="Flow_Start" sourceRef="StartEvent_1" targetRef="Gateway_Route" />''')
    for index in range(3):
        parts.extend([
            f'    <bpmn:task id="Cache_Lookup_{index}" name="Cache lookup node {index}">',
            f'      <bpmn:incoming>Flow_Node{index}</bpmn:incoming>',
            f'      <bpmn:outgoing>Flow_End{index}</bpmn:outgoing>',
            '    </bpmn:task>',
            f'    <bpmn:sequenceFlow id="Flow_Node{index}" sourceRef="Gateway_Route" targetRef="Cache_Lookup_{index}" />',
            f'    <bpmn:endEvent id="End_Node{index}" name="Lookup complete on node {index}">',
            f'      <bpmn:incoming>Flow_End{index}</bpmn:incoming>',
            '    </bpmn:endEvent>',
            f'    <bpmn:sequenceFlow id="Flow_End{index}" sourceRef="Cache_Lookup_{index}" targetRef="End_Node{index}" />',
        ])
    parts.append('  </bpmn:process>\n</bpmn:definitions>\n')
    return "\n".join(parts)


def asymmetric_global(seed):
    note = ("Node identities are #0 (fast), #1 (standard), #2 (legacy). Soft-affinity weights are priorities, not "
            "probability weights in the current plugin.")
    nodes = ('    <bsim:dynamicResource id="cache" defaultQuantity="3" defaultCost="1" defaultTimeUnit="SECONDS" cpuSpeed="1000" txSpeed="1">\n'
             '      <bsim:instance name="#0" cpuSpeed="2000" />\n'
             '      <bsim:instance name="#1" cpuSpeed="1000" />\n'
             '      <bsim:instance name="#2" cpuSpeed="500" />\n'
             '    </bsim:dynamicResource>\n'
             + resource("db", 1, ioRead=1) + resource("app", 20, cpuSpeed=1000))
    return global_config("asymmetric-global", seed, nodes, note=note)


def asymmetric_soft_config(instances, seed):
    affinity = ('      <bsim:affinity>\n'
                '        <bsim:resource id="cache" instance="#0" relation="affinity" mode="soft" weight="4" />\n'
                '        <bsim:resource id="cache" instance="#1" relation="affinity" mode="soft" weight="2" />\n'
                '        <bsim:resource id="cache" instance="#2" relation="affinity" mode="soft" weight="1" />\n'
                '      </bsim:affinity>\n')
    body = (task("Cache_Lookup", "Cache lookup", "cache", {"numInstructions": 10000, "txMB": 10}, affinity)
            + gateway(0.9)
            + task("Return_Hit", "Return cached value", "app", {"numInstructions": 2000})
            + task("DB_Read", "DB read", "db", {"readOps": ORIGIN_SERVICE})
            + task("Cache_Write", "Cache write", "cache", {"numInstructions": 20000})
            + task("Cache_Replicate", "Cache replicate", "cache", {"numInstructions": 20000}))
    note = ("Soft weights 4:2:1 are candidate-ranking scores, not weighted random allocation. With the current "
            "scheduler, the highest-scoring available node is preferred; use the round-robin BPMN for a uniform "
            "routing control.")
    return sim_config("asymmetric-soft-config", "distcache", instances, seed, body, note=note)


def asymmetric_uniform_config(instances, seed):
    body = exclusive_gateway("Gateway_Route", (("Flow_Node0", 1 / 3), ("Flow_Node1", 1 / 3), ("Flow_Node2", 1 / 3)))
    for index in range(3):
        affinity = (f'      <bsim:affinity>\n'
                    f'        <bsim:resource id="cache" instance="#{index}" relation="affinity" mode="hard" />\n'
                    f'      </bsim:affinity>\n')
        body += task(f"Cache_Lookup_{index}", f"Cache lookup node {index}", "cache",
                     {"numInstructions": 10000, "txMB": 10}, affinity)
    note = ("Uniform gateway probabilities select one of three hard-pinned lookup tasks; this is the random "
            "routing control, not a weighted-affinity policy.")
    return sim_config("asymmetric-uniform-config", "asymmetric_round_robin", instances, seed, body, note=note)


def data_location_config(variant, path_name, tx_mb, instances, seed):
    lookup_affinity = ""
    write_affinity = ""
    replicate_affinity = ""
    if variant == "owner":
        lookup_affinity = ('      <bsim:affinity>\n'
                           '        <bsim:resource id="cache" instance="#0" relation="affinity" mode="hard" />\n'
                           '      </bsim:affinity>\n')
        write_affinity = ('      <bsim:affinity>\n'
                          '        <bsim:history task="Cache_Lookup" relation="affinity" mode="hard" />\n'
                          '      </bsim:affinity>\n')
        replicate_affinity = ('      <bsim:affinity>\n'
                              '        <bsim:history task="Cache_Lookup" relation="antiAffinity" mode="hard" />\n'
                              '      </bsim:affinity>\n')
    note = (f"Fixed {path_name} path: Cache_Lookup has txMB={tx_mb}, independent of the selected cache node. "
            "In owner mode, #0 is the fixed key owner; the current model has no dynamic key-owner/topology lookup.")
    body = (task("Cache_Lookup", "Cache lookup", "cache",
                 {"numInstructions": 10000, "txMB": tx_mb}, lookup_affinity)
            + gateway(0.9)
            + task("Return_Hit", "Return cached value", "app", {"numInstructions": 2000})
            + task("DB_Read", "DB read", "db", {"readOps": ORIGIN_SERVICE})
            + task("Cache_Write", "Cache write", "cache", {"numInstructions": 20000}, write_affinity)
            + task("Cache_Replicate", "Cache replicate", "cache", {"numInstructions": 20000}, replicate_affinity))
    return sim_config(f"dataloc-{path_name}-{variant}-config", "distcache", instances, seed, body, note=note)


def write_other_scenarios(args):
    """Generate configuration variants requested in samples/other_cache_scenarios/*.txt."""
    output = os.path.join(ROOT, "samples", "other_cache_scenarios")
    bpmn_source = os.path.join(HERE, "distributed_cache", "distcache.bpmn")
    with open(bpmn_source, encoding="utf-8") as handle:
        distributed_bpmn = handle.read()

    # Scalability: hold workload, hit ratio and node capacity fixed while scaling node count.
    scale_dir = os.path.join(output, "scalable")
    write_file(os.path.join(scale_dir, "distcache.bpmn"), distributed_bpmn)
    for nodes in (2, 4, 8, 16):
        note = f"Scalability run: 20,000 requests, hit ratio 0.9, no affinity, {nodes} cache nodes."
        glob_, sim = dist_files("none", 0.9, 20000, args.seed, nodes=nodes, note=note)
        stem = f"scalable_n{nodes}"
        write_file(os.path.join(scale_dir, stem + ".global.xml"), glob_)
        write_file(os.path.join(scale_dir, stem + ".config.xml"), sim)

    # Data locality: one fixed owner/path category per config. The network penalty is input, not topology-derived.
    locality_dir = os.path.join(output, "data_loc_network")
    write_file(os.path.join(locality_dir, "distcache.bpmn"), distributed_bpmn)
    locality_glob = (resource("cache", 4, cpuSpeed=1000, txSpeed=1)
                     + resource("db", 1, ioRead=1)
                     + resource("app", 20, cpuSpeed=1000))
    write_file(os.path.join(locality_dir, "dataloc.global.xml"),
               global_config("dataloc-global", args.seed, locality_glob,
                             note="Four equal cache nodes: #0/#1 are Zone A; #2/#3 are Zone B."))
    for path_name, penalty in (("local", 0), ("same_zone", 2), ("cross_zone", 40)):
        for variant in ("none", "owner"):
            name = f"dataloc_{path_name}_{variant}.config.xml"
            write_file(os.path.join(locality_dir, name),
                       data_location_config(variant, path_name, penalty, 20000, args.seed))

    # Asymmetric resources: uniform router control and the closest current weighted-affinity setup.
    asym_dir = os.path.join(output, "asymmetric_cluster")
    write_file(os.path.join(asym_dir, "distcache.bpmn"), distributed_bpmn)
    write_file(os.path.join(asym_dir, "asymmetric_round_robin.bpmn"), router_bpmn())
    write_file(os.path.join(asym_dir, "asymmetric.global.xml"), asymmetric_global(args.seed))
    write_file(os.path.join(asym_dir, "asymmetric_uniform.config.xml"),
               asymmetric_uniform_config(20000, args.seed))
    write_file(os.path.join(asym_dir, "asymmetric_soft_affinity.config.xml"),
               asymmetric_soft_config(20000, args.seed))


def floats(text):
    return [float(x) for x in text.split(",")]


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)

    def common(p, instances):
        p.add_argument("--instances", type=int, default=instances, help="simulated requests per run")
        p.add_argument("--seed", type=int, default=42)
        p.add_argument("--warmup", type=float, default=0.1, help="fraction of requests ignored for waiting times")
        p.add_argument("--classpath", default=default_classpath())
        p.add_argument("--keep", help="folder in which the generated inputs and outputs of every run are kept")
        p.add_argument("--shared-seeds", action="store_true",
                       help="keep Scylla's default of one seed for all distributions (gateway and arrivals correlated)")

    p = sub.add_parser("write-samples")
    common(p, 2000)
    p.set_defaults(run=write_samples)
    p = sub.add_parser("write-other-scenarios")
    p.add_argument("--seed", type=int, default=42)
    p.set_defaults(run=write_other_scenarios)
    p = sub.add_parser("proxy")
    common(p, 20000)
    p.add_argument("--hit", type=floats, default=floats("0.7,0.8,0.85,0.9,0.95"))
    p.add_argument("--replications", type=int, default=5, help="independent runs (seeds) per hit ratio")
    p.set_defaults(run=proxy_command)
    p = sub.add_parser("dist")
    common(p, 20000)
    p.add_argument("--hit", type=floats, default=[0.9])
    p.add_argument("--nodes", type=int, default=2)
    p.add_argument("--exponent", type=float, default=1)
    p.add_argument("--variants", type=lambda t: t.split(","), default=list(AFFINITY_VARIANTS))
    p.set_defaults(run=dist_command)
    p = sub.add_parser("exponent")
    common(p, 2000)
    p.add_argument("--hit", type=floats, default=[0.9])
    p.add_argument("--nodes", type=int, default=2)
    p.add_argument("--exponents", type=floats, default=floats("1,2,4,8"))
    p.set_defaults(run=exponent_command)

    args = parser.parse_args()
    args.run(args)


if __name__ == "__main__":
    main()
