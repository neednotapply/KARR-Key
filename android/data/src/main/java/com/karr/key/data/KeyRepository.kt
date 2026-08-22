package com.karr.key.data

import com.karr.key.core.Session

interface KeyRepository {
    fun localSession(): Session
}
