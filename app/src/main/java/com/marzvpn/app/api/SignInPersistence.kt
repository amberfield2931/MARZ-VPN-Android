package com.marzvpn.app.api

/** A server response is not a completed sign-in until the pair is durable. No logging or cleanup. */
object SignInPersistence {
    fun save(access: String?, refresh: String?, sessionId: String?, write: (RefreshClient.Tokens) -> Boolean): ApiResult<Unit> {
        if (access.isNullOrBlank() || refresh.isNullOrBlank()) return ApiResult.Error("invalid_response")
        return if (write(RefreshClient.Tokens(access, refresh, sessionId.orEmpty()))) ApiResult.Ok(Unit)
        else ApiResult.Error("storage_unavailable")
    }
}
