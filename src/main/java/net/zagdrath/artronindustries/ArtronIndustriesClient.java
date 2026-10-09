/*
 * Copyright (c) 2026 Zagdrath
 * SPDX-License-Identifier: MIT
 */

package net.zagdrath.artronindustries;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RegisterSpecialModelRendererEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;
import net.zagdrath.artronindustries.client.ArtronClientCommands;
import net.zagdrath.artronindustries.client.ArtronClientConfig;
import net.zagdrath.artronindustries.client.ClientSmokeTest;
import net.zagdrath.artronindustries.client.boti.ArtronClientNetwork;
import net.zagdrath.artronindustries.client.boti.BotiDebugEntry;
import net.zagdrath.artronindustries.client.boti.BotiPipelines;
import net.zagdrath.artronindustries.client.boti.BotiRenderer;
import net.zagdrath.artronindustries.client.boti.SeamlessTransition;
import net.zagdrath.artronindustries.client.exterior.HudolinExteriorItemRenderer;
import net.zagdrath.artronindustries.client.exterior.HudolinExteriorModel;
import net.zagdrath.artronindustries.client.exterior.HudolinExteriorRenderer;
import net.zagdrath.artronindustries.registry.ArtronBlockEntities;

@Mod(value = ArtronIndustries.MODID, dist = Dist.CLIENT)
public class ArtronIndustriesClient {
    public ArtronIndustriesClient(IEventBus modEventBus, ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
        container.registerConfig(ModConfig.Type.CLIENT, ArtronClientConfig.SPEC, ArtronIndustries.MODID + "-client.toml");
        ArtronClientNetwork.init(modEventBus);
        BotiPipelines.init(modEventBus);
        BotiRenderer.init();
        SeamlessTransition.init(modEventBus);
        BotiDebugEntry.init(modEventBus);
        modEventBus.addListener(EntityRenderersEvent.RegisterLayerDefinitions.class,
                e -> e.registerLayerDefinition(HudolinExteriorRenderer.LAYER, HudolinExteriorModel::createBodyLayer));
        modEventBus.addListener(EntityRenderersEvent.RegisterRenderers.class,
                e -> e.registerBlockEntityRenderer(ArtronBlockEntities.TARDIS.get(), HudolinExteriorRenderer::new));
        modEventBus.addListener(RegisterSpecialModelRendererEvent.class,
                e -> e.register(HudolinExteriorItemRenderer.ID, HudolinExteriorItemRenderer.Unbaked.MAP_CODEC));
        NeoForge.EVENT_BUS.addListener(RegisterClientCommandsEvent.class, e -> ArtronClientCommands.register(e.getDispatcher()));
        ClientSmokeTest.registerIfEnabled();
    }
}
