package com.google.mediapipe.examples.poselandmarker

object CameraOwnership {
    enum class Owner {
        NONE,
        FOREGROUND_SCAN,
        BACKGROUND_SERVICE,
    }

    private var owner: Owner = Owner.NONE
    private var generation: Long = 0L

    @Synchronized
    fun claim(newOwner: Owner): Long {
        owner = newOwner
        generation += 1L
        return generation
    }

    @Synchronized
    fun release(releasingOwner: Owner) {
        if (owner == releasingOwner) {
            owner = Owner.NONE
            generation += 1L
        }
    }

    @Synchronized
    fun isCurrent(expectedOwner: Owner, token: Long): Boolean =
        owner == expectedOwner && generation == token
}
