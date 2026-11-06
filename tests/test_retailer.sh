#!/bin/bash
java -jar target/scylla-0.0.1-SNAPSHOT.jar --headless --config=samples/retailer_global.xml --bpmn=samples/retailer_bpmn.bpmn --sim=samples/retailer_sim.xml --enable-bps-logging 