package com.karr.key.desktop.data

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Per-user application data directory for the desktop host. On Windows this is
 * `%APPDATA%\KARR Key`; elsewhere it falls back to `~/.karrkey`. Analogous to
 * the app-private storage the Android host gets from its Context.
 */
object AppPaths {
    val dataDir: Path by lazy {
        val base = System.getenv("APPDATA")
            ?.let(Paths::get)
            ?: Paths.get(System.getProperty("user.home"), ".karrkey")
        val dir = if (System.getenv("APPDATA") != null) base.resolve("KARR Key") else base
        Files.createDirectories(dir)
        dir
    }

    fun file(name: String): Path = dataDir.resolve(name)
}
