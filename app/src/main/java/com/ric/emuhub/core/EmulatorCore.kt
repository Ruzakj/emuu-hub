package com.ric.emuhub.core

import android.net.Uri

interface EmulatorCore {
    val id: String
    val displayName: String
    val supportedExtensions: Set<String>
    fun initialize(): Result<Unit>
    fun loadGame(uri: Uri): Result<Unit>
    fun start(): Result<Unit>
    fun pause()
    fun resume()
    fun reset()
    fun stop()
    fun saveState(slot: Int): Result<Unit>
    fun loadState(slot: Int): Result<Unit>
}
