package com.karr.master.data

import com.karr.master.core.Session

interface MasterRepository {
    fun localSession(): Session
}
