package com.blockreality.fastdesign.client.node.impl.output.monitor;

import com.blockreality.fastdesign.client.node.*;

/** E2-5: 網路監控 */
public class NetworkProfilerNode extends BRNode {
    public NetworkProfilerNode() {
        super("Network Profiler", "網路監控", "output", NodeColor.OUTPUT);
        addOutput("packetsSent", PortType.INT);
        addOutput("packetsReceived", PortType.INT);
        addOutput("bytesSent", PortType.INT);
    }

    @Override
    public void evaluate() {
        getOutput("packetsSent").setValue(0);
        getOutput("packetsReceived").setValue(0);
        getOutput("bytesSent").setValue(0);
    }

    @Override public String getTooltip() { return "網路封包收發與流量監控"; }
    @Override public String typeId() { return "output.monitor.NetworkProfiler"; }
}
