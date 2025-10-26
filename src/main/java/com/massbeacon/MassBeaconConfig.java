package com.massbeacon;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;

@ConfigGroup("massbeacon")
public interface MassBeaconConfig extends Config
{
    @ConfigItem(
            keyName = "webhookUrl",
            name = "Discord Webhook URL",
            description = "Optional. If set, the plugin will also post updates to this Discord webhook."
    )
    default String webhookUrl() { return ""; }

    @Range(min = 5, max = 120)
    @ConfigItem(
            keyName = "fetchIntervalSec",
            name = "Fetch interval (sec)",
            description = "How often to poll the beacon service.",
            position = 1
    )
    default int fetchIntervalSec() { return 20; }

    @Range(min = 1, max = 30)
    @ConfigItem(
            keyName = "minIntervalSec",
            name = "Minimum interval (sec)",
            description = "Minimum seconds between local auto-posts.",
            position = 2
    )
    default int minIntervalSec() { return 3; }

    @ConfigItem(
            keyName = "onlyAtBA",
            name = "Only at BA area",
            description = "Restrict auto-posting and fetching to Barbarian Assault regions.",
            position = 3
    )
    default boolean onlyAtBA() { return true; }

    @ConfigItem(
            keyName = "onlyAtCorp",
            name = "Only at Corp area",
            description = "Restrict auto-posting and fetching to Corporeal Beast regions.",
            position = 4
    )
    default boolean onlyAtCorp() { return true; }

    @ConfigItem(
            keyName = "showOverlay",
            name = "Show overlay",
            description = "Show the MassBeacon overlay in-game.",
            position = 5
    )
    default boolean showOverlay() { return true; }
}
