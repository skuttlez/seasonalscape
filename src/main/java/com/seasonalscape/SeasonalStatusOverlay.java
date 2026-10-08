package com.seasonalscape;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

public class SeasonalStatusOverlay extends OverlayPanel
{
    private final SeasonalScapePlugin plugin;
    private final SeasonalScapeConfig config;
    private final Client client;

    @Inject
    SeasonalStatusOverlay(SeasonalScapePlugin plugin, SeasonalScapeConfig config, Client client)
    {
        super(plugin);
        this.plugin = plugin;
        this.config = config;
        this.client = client;
        setPosition(OverlayPosition.TOP_LEFT);
    }

    @Override
    public Dimension render(Graphics2D graphics)
    {
        if (!config.showStatus() || client.getGameState() != GameState.LOGGED_IN) { return null; }
        panelComponent.setPreferredSize(new Dimension(245, 0));
        panelComponent.getChildren().add(TitleComponent.builder().text("SeasonalScape").color(new Color(224, 190, 114)).build());
        Season season = plugin.getActiveSeason();
        panelComponent.getChildren().add(LineComponent.builder().left("Season").right(season == null ? "Loading" : season.toString()).build());
        panelComponent.getChildren().add(LineComponent.builder().left(plugin.getStatus()).build());
        return super.render(graphics);
    }
}
