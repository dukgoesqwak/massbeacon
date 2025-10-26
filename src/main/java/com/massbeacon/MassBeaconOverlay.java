package com.massbeacon;

import java.awt.Dimension;
import java.awt.Graphics2D;
import java.util.List;
import javax.inject.Inject;

import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

public class MassBeaconOverlay extends OverlayPanel
{
    private final MassBeaconConfig config;
    private final MassBeaconPlugin plugin;

    @Inject
    public MassBeaconOverlay(MassBeaconConfig config, MassBeaconPlugin plugin)
    {
        this.config = config;
        this.plugin = plugin;
        setPosition(OverlayPosition.TOP_LEFT);
    }

    @Override
    public Dimension render(Graphics2D g)
    {
        if (!config.showOverlay())
        {
            return null;
        }

        panelComponent.getChildren().clear();

        // Header
        panelComponent.getChildren().add(
                TitleComponent.builder()
                        .text("MassBeacon")
                        .build()
        );

        final List<MassBeaconPlugin.WorldCount> worlds = plugin.getLatestWorlds();

        if (worlds == null || worlds.isEmpty())
        {
            panelComponent.getChildren().add(
                    LineComponent.builder()
                            .left("No data yet")
                            .build()
            );
            return super.render(g);
        }

        // Subheader
        panelComponent.getChildren().add(
                LineComponent.builder()
                        .left("Top worlds")
                        .build()
        );

        // Show top 5 worlds (world number on left, count on right)
        int shown = 0;
        for (MassBeaconPlugin.WorldCount wc : worlds)
        {
            panelComponent.getChildren().add(
                    LineComponent.builder()
                            .left("W" + wc.world)
                            .right(Integer.toString(wc.count))
                            .build()
            );
            if (++shown == 5) break;
        }

        return super.render(g);
    }
}
