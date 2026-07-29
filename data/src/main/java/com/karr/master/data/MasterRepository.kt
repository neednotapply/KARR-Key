package com.karr.master.data

import com.karr.master.core.Session
import com.karr.master.core.TechnicianJob
import com.karr.master.core.UserRole
import com.karr.master.core.VehicleSummary

interface MasterRepository {
    suspend fun login(username: String, password: CharArray): Result<Session>
    suspend fun demoSession(role: UserRole): Session
    suspend fun vehicles(session: Session): Result<List<VehicleSummary>>
    suspend fun jobs(session: Session): Result<List<TechnicianJob>>
    suspend fun logout()
}

interface ServerApi {
    suspend fun authenticate(username: String, password: CharArray): Result<Session>
    suspend fun vehicles(session: Session): Result<List<VehicleSummary>>
    suspend fun jobs(session: Session): Result<List<TechnicianJob>>
}

class UnconfiguredServerApi : ServerApi {
    override suspend fun authenticate(
        username: String,
        password: CharArray,
    ): Result<Session> = unconfigured()

    override suspend fun vehicles(session: Session): Result<List<VehicleSummary>> =
        unconfigured()

    override suspend fun jobs(session: Session): Result<List<TechnicianJob>> =
        unconfigured()

    private fun <T> unconfigured(): Result<T> = Result.failure(
        IllegalStateException(
            "Production backend is not configured. Use demo mode or provide an authorized API adapter.",
        ),
    )
}
