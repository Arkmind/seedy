package io.github.arkmind.seedy.core.session

import io.github.arkmind.seedy.core.data.SavedServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.exception.ApiClientException
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.api.client.extensions.authenticationApi
import org.jellyfin.sdk.api.client.extensions.userApi
import org.jellyfin.sdk.model.api.AuthenticateUserByName
import org.jellyfin.sdk.model.api.ForgotPasswordAction
import org.jellyfin.sdk.model.api.ForgotPasswordDto
import org.jellyfin.sdk.model.api.ForgotPasswordPinDto
import org.jellyfin.sdk.model.api.QuickConnectDto
import org.jellyfin.sdk.model.api.UserDto

/**
 * Signing in: the server's public profiles, a password, or Quick Connect (a code approved from a device that's
 * already signed in). Jellyfin 12 reports every user as having a password, even passwordless ones, so the app never
 * guesses: a profile without a saved session always asks (an empty password is allowed), and nothing is tried on the
 * person's behalf, since failed attempts count toward the server's lockout.
 */
class SignIn(private val sessions: Sessions) {

    enum class Result { Success, WrongPassword, Unreachable }

    sealed interface QuickConnect {
        data class Code(val code: String) : QuickConnect
        data object Approved : QuickConnect
        /** Quick Connect is off on this server. */
        data object Unavailable : QuickConnect
        /** The code wasn't approved in time; start again for a new one. */
        data object Expired : QuickConnect
    }

    /** The profiles the server lists on its sign-in page (empty when the admin hides them). */
    suspend fun publicUsers(server: SavedServer): List<UserDto> = withContext(Dispatchers.IO) {
        runCatching { sessions.apiFor(server).userApi.getPublicUsers().content }.getOrDefault(emptyList())
    }

    suspend fun withPassword(server: SavedServer, userName: String, password: String, remember: Boolean): Result =
        withContext(Dispatchers.IO) {
            val deviceId = sessions.deviceIdFor(server, userName)
            val api = sessions.apiFor(server, deviceId)
            try {
                val result = api.authenticationApi.authenticateUserByName(AuthenticateUserByName(username = userName, pw = password)).content
                sessions.completeSignIn(server, deviceId, result, remember)
                Result.Success
            } catch (e: InvalidStatusException) {
                if (e.status == 401 || e.status == 403) Result.WrongPassword else Result.Unreachable
            } catch (_: ApiClientException) {
                Result.Unreachable
            }
        }

    suspend fun quickConnectEnabled(server: SavedServer): Boolean = withContext(Dispatchers.IO) {
        runCatching { sessions.apiFor(server).authenticationApi.getQuickConnectEnabled().content }.getOrDefault(false)
    }

    /** Emits the code to show, then signs in once someone approves it. Cancel the collection to give up. */
    fun quickConnect(server: SavedServer, remember: Boolean): Flow<QuickConnect> = flow {
        val deviceId = sessions.quickConnectDeviceId()
        val api = sessions.apiFor(server, deviceId)
        val started = try {
            api.authenticationApi.initiateQuickConnect().content
        } catch (_: ApiClientException) {
            emit(QuickConnect.Unavailable)
            return@flow
        }
        emit(QuickConnect.Code(started.code))
        while (true) {
            delay(POLL_MS)
            val state = try {
                api.authenticationApi.getQuickConnectState(started.secret).content
            } catch (e: InvalidStatusException) {
                if (e.status == 404) {
                    emit(QuickConnect.Expired)
                    return@flow
                }
                continue
            } catch (_: ApiClientException) {
                continue
            }
            if (state.authenticated) {
                val result = api.authenticationApi.authenticateWithQuickConnect(QuickConnectDto(secret = started.secret)).content
                sessions.completeSignIn(server, deviceId, result, remember)
                emit(QuickConnect.Approved)
                return@flow
            }
        }
    }.flowOn(Dispatchers.IO)

    /** What Jellyfin's "Forgot password" says to do. */
    sealed interface Forgot {
        /** Only an admin can help (or the server couldn't be asked). */
        data object AskAdmin : Forgot
        /** Resets work only from the server's own network. */
        data object InNetworkRequired : Forgot
        /** The server wrote a PIN to [file] on its disk, good until [until] (local time); redeeming it makes it the password. */
        data class Pin(val file: String?, val until: java.time.LocalDateTime?) : Forgot
    }

    suspend fun forgotPassword(server: SavedServer, userName: String): Forgot = withContext(Dispatchers.IO) {
        val r = try {
            sessions.apiFor(server).authenticationApi.forgotPassword(ForgotPasswordDto(enteredUsername = userName)).content
        } catch (_: ApiClientException) {
            return@withContext Forgot.AskAdmin
        }
        when (r.action) {
            ForgotPasswordAction.PIN_CODE -> Forgot.Pin(
                r.pinFile?.takeIf { it.isNotBlank() },
                // The SDK gives server times in this device's zone already.
                r.pinExpirationDate,
            )
            ForgotPasswordAction.IN_NETWORK_REQUIRED -> Forgot.InNetworkRequired
            else -> Forgot.AskAdmin
        }
    }

    /** The profiles whose password became [pin], or null when the PIN didn't work (wrong or expired). */
    suspend fun redeemPin(server: SavedServer, pin: String): List<String>? = withContext(Dispatchers.IO) {
        try {
            sessions.apiFor(server).authenticationApi.forgotPasswordPin(ForgotPasswordPinDto(pin = pin)).content.takeIf { it.success }?.usersReset.orEmpty()
        } catch (_: ApiClientException) {
            null
        }
    }

    private companion object {
        const val POLL_MS = 3_000L
    }
}
