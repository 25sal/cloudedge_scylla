package de.hpi.bpt.scylla.plugin.gateway_exclusive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

public class GatewaySeedTests {

    private static final String SWITCH = "scylla.independentSeeds";

    @AfterEach
    public void reset() {
        System.clearProperty(SWITCH);
    }

    @Test
    public void defaultKeepsTheSharedSeed() {
        assertEquals(42L, ExclusiveGatewayDistributionConversionPlugin.gatewaySeed(42L, 3));
        assertEquals(42L, ExclusiveGatewayDistributionConversionPlugin.gatewaySeed(42L, 4));
    }

    @Test
    public void independentSeedsDifferPerGatewayAndFromTheConfigurationSeed() {
        System.setProperty(SWITCH, "true");
        long first = ExclusiveGatewayDistributionConversionPlugin.gatewaySeed(42L, 3);
        long second = ExclusiveGatewayDistributionConversionPlugin.gatewaySeed(42L, 4);
        assertNotEquals(42L, first);
        assertNotEquals(first, second);
        assertEquals(first, ExclusiveGatewayDistributionConversionPlugin.gatewaySeed(42L, 3));
    }
}
