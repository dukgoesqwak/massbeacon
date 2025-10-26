package com.massbeacon;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class MassBeaconTest
{
    public static void main(String[] args) throws Exception
    {
        ExternalPluginManager.loadBuiltin(MassBeaconPlugin.class);
        RuneLite.main(args);
    }
}
