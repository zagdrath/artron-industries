/*
 * Copyright (c) 2026 Zagdrath
 * SPDX-License-Identifier: MIT
 */

package net.zagdrath.artronindustries;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.zagdrath.artronindustries.boti.server.PortalWatcher;
import net.zagdrath.artronindustries.command.ArtronCommands;
import net.zagdrath.artronindustries.gametest.ArtronGameTests;
import net.zagdrath.artronindustries.gametest.SmokeTest;
import net.zagdrath.artronindustries.registry.ArtronBlockEntities;
import net.zagdrath.artronindustries.network.ArtronNetwork;
import net.zagdrath.artronindustries.registry.ArtronBlocks;
import net.zagdrath.artronindustries.registry.ArtronTickets;
import net.zagdrath.artronindustries.tardis.DoorwayCrossing;

@Mod(ArtronIndustries.MODID)
public class ArtronIndustries {
    public static final String MODID = "artronindustries";
    public static final Logger LOGGER = LogUtils.getLogger();

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MODID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);

    static {
        ArtronBlocks.init();
        CREATIVE_MODE_TABS.register("main", () -> CreativeModeTab.builder()
                .title(Component.translatable("itemGroup.artronindustries"))
                .icon(() -> ArtronBlocks.TEST_EXTERIOR_DOOR_ITEM.get().getDefaultInstance())
                .displayItems((params, output) -> {
                    output.accept(ArtronBlocks.TEST_EXTERIOR_DOOR_ITEM.get());
                    output.accept(ArtronBlocks.INTERIOR_DOOR_ITEM.get());
                })
                .build());
    }

    public ArtronIndustries(IEventBus modEventBus, ModContainer modContainer) {
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        CREATIVE_MODE_TABS.register(modEventBus);
        ArtronBlockEntities.BLOCK_ENTITY_TYPES.register(modEventBus);
        ArtronGameTests.TEST_FUNCTIONS.register(modEventBus);
        ArtronTickets.TICKET_TYPES.register(modEventBus);
        modEventBus.addListener(RegisterPayloadHandlersEvent.class, ArtronNetwork::register);

        modContainer.registerConfig(ModConfig.Type.LOCAL, Config.SPEC, MODID + "-common.toml");

        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent.class, e -> ArtronCommands.register(e.getDispatcher()));
        PortalWatcher.init();
        DoorwayCrossing.init();
        SmokeTest.registerIfEnabled();
    }
}
