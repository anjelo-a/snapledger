package com.snapledger.core.profile

import android.content.Context
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

object ProfileSessionResolver {
    fun resolveActiveSession(context: Context): ProfileSession? {
        val appContext = context.applicationContext
        return runBlocking {
            DataStoreProfileRepository.getInstance(appContext)
                .profileFlow
                .first()
                ?.toProfileSession()
        }
    }

    fun resolveSession(
        context: Context,
        localProfileId: String,
    ): ProfileSession? {
        val appContext = context.applicationContext
        return runBlocking {
            DataStoreProfileRepository.getInstance(appContext)
                .getProfile(localProfileId)
                ?.toProfileSession()
        }
    }
}
