package com.karr.master.data

import com.karr.master.core.Session
import com.karr.master.core.TechnicianJob
import com.karr.master.core.VehicleSummary

interface MasterRepository {
    fun localSession(): Session
    fun vehicles(): List<VehicleSummary>
    fun jobs(): List<TechnicianJob>
}
