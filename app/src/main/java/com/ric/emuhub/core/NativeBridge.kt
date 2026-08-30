package com.ric.emuhub.core

object NativeBridge {
    init { System.loadLibrary("emuhost") }

    external fun init(corePath: String, systemDir: String, saveDir: String): Boolean
    external fun loadGame(path: String): Boolean
    external fun runFrame(pixels: IntArray): Int
    external fun getWidth(): Int
    external fun getHeight(): Int
    external fun setButton(id: Int, pressed: Boolean)
    external fun reset()
    external fun unload()
}
