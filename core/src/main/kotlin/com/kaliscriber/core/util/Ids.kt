package com.kaliscriber.core.util

import java.util.UUID

object Ids {
    fun new(): String = UUID.randomUUID().toString()
}

/** Injectable time source so the pipeline is testable. */
fun interface Clock {
    fun now(): Long

    companion object {
        val System = Clock { java.lang.System.currentTimeMillis() }
    }
}
