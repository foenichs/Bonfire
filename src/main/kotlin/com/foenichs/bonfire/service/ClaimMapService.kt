package com.foenichs.bonfire.service

import com.foenichs.bonfire.model.Claim
import java.util.UUID

interface ClaimMapService {
    fun refreshAll()
    fun updateClaim(claim: Claim)
    fun removeClaim(id: Int, worldId: UUID)
    fun shutdown() {}
}

class BlueMapClaimMapService(
    private val delegate: BlueMapService
) : ClaimMapService {
    override fun refreshAll() = delegate.refreshAll()

    override fun updateClaim(claim: Claim) = delegate.updateClaim(claim)

    override fun removeClaim(id: Int, worldId: UUID) = delegate.removeClaim(id, worldId)
}
