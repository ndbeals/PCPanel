package com.getpcpanel.integration.volume.command;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

import com.getpcpanel.integration.volume.platform.AudioDevice;

/** What the overlay names after a press of the advanced default-device action: the devices it switched to. */
class CommandVolumeDefaultDeviceAdvancedTest {
    private static AudioDevice device(String name, String id) {
        return new AudioDevice(null, name, id);
    }

    private static String text(AudioDevice... switched) {
        return CommandVolumeDefaultDeviceAdvanced.switchedToText(Arrays.stream(switched));
    }

    @Test
    void namesTheOneDeviceItSwitchedTo() {
        assertEquals("Speakers", text(device("Speakers", "a"), null, null, null));
    }

    @Test
    void namesEachDeviceOnALineOfItsOwnInFieldOrder() {
        assertEquals("Speakers\nYeti\nHeadset", text(device("Speakers", "a"), device("Yeti", "b"), device("Headset", "c"), null));
    }

    @Test
    void aNameSetForSeveralFieldsShowsOnce() {
        var headset = device("Headset", "a");
        assertEquals("Headset\nHeadset Microphone", text(headset, device("Headset Microphone", "b"), headset, device("Headset Microphone", "b")));
    }

    @Test
    void twoDevicesSharingANameShowItOnce() {
        assertEquals("Headphones (USB Audio)", text(device("Headphones (USB Audio)", "a"), null, device("Headphones (USB Audio)", "b"), null));
    }

    @Test
    void nothingSwitchedNamesNothing() {
        assertEquals("", text(null, null, null, null));
    }
}
