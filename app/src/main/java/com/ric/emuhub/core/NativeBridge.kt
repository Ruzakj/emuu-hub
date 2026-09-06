package com.ric.emuhub.core

object NativeBridge {
    init { System.loadLibrary("emuhost") }

    external fun setLogPath(path: String)
    external fun init(corePath: String, systemDir: String, saveDir: String): Boolean
    external fun setControllerDevice(device: Int)
    external fun loadGame(path: String): Boolean
    external fun runFrame(pixels: IntArray): Int
    external fun getWidth(): Int
    external fun getHeight(): Int
    external fun getSampleRate(): Int
    external fun readAudio(samples: ShortArray): Int
    external fun saveState(path: String): Boolean
    external fun loadState(path: String): Boolean
    external fun setButton(id: Int, pressed: Boolean)
    external fun setAnalog(x: Int, y: Int)
    external fun decodeEcm(inputPath: String, outputPath: String): Boolean
    external fun reset()
    external fun unload()
}
