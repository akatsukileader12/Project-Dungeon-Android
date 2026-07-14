package com.example.dungeon.android

import com.jme3.app.AndroidHarness

/**
 * Android entry point. AndroidHarness loads [com.example.dungeon.DungeonGame]
 * (the same engine-agnostic class the desktop build uses) via reflection and
 * drives it with an OpenGL ES context instead of LWJGL.
 */
class MainActivity : AndroidHarness() {

    init {
        appClass = "com.example.dungeon.DungeonGame"

        // Rendering surface configuration
        eglBitsPerPixel = 24
        eglAlphaBits = 0
        eglDepthBits = 24
        eglSamples = 0
        eglStencilBits = 0
        frameRate = -1 // uncapped

        // Touch drives the same click-to-move logic as a mouse click
        mouseEventsEnabled = true
        joystickEventsEnabled = false

        // Exit behavior
        finishOnAppStop = true
        handleExitHook = true
        exitDialogTitle = "Exit Project Dungeon?"
        exitDialogMessage = "Press Home to keep the game running in the background, or confirm to exit."

        screenFullScreen = true
        screenShowTitle = false
    }
}
