/*
 * Copyright (c) 2026 Zagdrath
 * SPDX-License-Identifier: MIT
 */

package net.zagdrath.artronindustries.tardis;

import com.mojang.serialization.Codec;

import net.minecraft.util.StringRepresentable;

/**
 * How far a TARDIS's double doors are open. Using a door steps through the states in order: the right leaf opens, then
 * the left, then both shut. "Right" is the right-hand leaf of someone standing outside, looking in.
 */
public enum DoorState implements StringRepresentable {
    CLOSED("closed"),
    RIGHT_OPEN("right_open"),
    BOTH_OPEN("both_open");

    public static final Codec<DoorState> CODEC = StringRepresentable.fromEnum(DoorState::values);

    private final String name;

    DoorState(String name) {
        this.name = name;
    }

    public boolean isOpen() {
        return this != CLOSED;
    }

    public boolean rightOpen() {
        return this != CLOSED;
    }

    public boolean leftOpen() {
        return this == BOTH_OPEN;
    }

    /** The state a click on the door moves to. */
    public DoorState next() {
        return switch (this) {
            case CLOSED -> RIGHT_OPEN;
            case RIGHT_OPEN -> BOTH_OPEN;
            case BOTH_OPEN -> CLOSED;
        };
    }

    @Override
    public String getSerializedName() {
        return this.name;
    }
}
