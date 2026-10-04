package dev.folio.scanner.backup

import android.accounts.Account
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.MutableContextWrapper
import androidx.credentials.*
import com.google.android.gms.auth.api.identity.*
import com.google.android.gms.common.api.Scope
import com.google.android.libraries.identity.googleid.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

class BackupAuthRequired : Exception("Reconnect Google Account to authorize Drive backup.")
data class DriveAuthorization(val pendingIntent: PendingIntent?)
interface DriveAuth {
    suspend fun selectAccount(activity:Context):String
    suspend fun connect(email:String):DriveAuthorization
    fun complete(intent:Intent)
    suspend fun token(email:String):String
    suspend fun invalidate(token:String)
    suspend fun signOut(email:String)
    suspend fun inspectForPurge(email:String):DriveAuthorization = connect(email)
    suspend fun purgeToken(email:String):String = token(email)
}

@Singleton
class GoogleAuthManager @Inject constructor(@ApplicationContext private val context: Context) : DriveAuth {
    companion object { const val DRIVE_FILE = "https://www.googleapis.com/auth/drive.file" }
    private val client get()=Identity.getAuthorizationClient(context)
    private val manager get()=CredentialManager.create(context)
    override suspend fun selectAccount(activity: Context): String {
        check(dev.folio.scanner.BuildConfig.GOOGLE_WEB_CLIENT_ID.isNotBlank()) { "Google sign-in is not configured. Follow docs/GOOGLE_DRIVE_SETUP.md and rebuild Folio with your public Web client ID." }
        manager.clearCredentialState(ClearCredentialStateRequest())
        val option=GetSignInWithGoogleOption.Builder(dev.folio.scanner.BuildConfig.GOOGLE_WEB_CLIENT_ID).build()
        val result=try { manager.getCredential(MutableContextWrapper(activity),GetCredentialRequest.Builder().addCredentialOption(option).build()) }
        catch(_:androidx.credentials.exceptions.NoCredentialException) { throw IllegalStateException("No Google account is available. Add an account on this device and try again.") }
        val credential=result.credential
        require(credential is CustomCredential && credential.type==GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) { "Google did not return a valid account." }
        // Only the account identifier is used locally. No relying-party server or persisted ID token exists.
        return requireNotNull(GoogleIdTokenCredential.createFrom(credential.data).email) { "Google did not share an email address." }.also { require(it.contains('@')) }
    }
    private fun request(email:String,inspection:Boolean=false)=AuthorizationRequest.builder().setAccount(Account(email,"com.google"))
        .setRequestedScopes(listOf(Scope(DRIVE_FILE))+if(inspection) listOf(Scope("https://www.googleapis.com/auth/drive.metadata.readonly")) else emptyList()).build()
    override suspend fun inspectForPurge(email:String):DriveAuthorization {
        val result=client.authorize(request(email,true)).await()
        return DriveAuthorization(if(result.hasResolution()) result.pendingIntent else null)
    }
    override suspend fun purgeToken(email:String):String {
        val result=try { client.authorize(request(email,true)).await() } catch(error:com.google.android.gms.common.api.ApiException) {
            if(error.statusCode==com.google.android.gms.common.api.CommonStatusCodes.NETWORK_ERROR || error.statusCode==com.google.android.gms.common.api.CommonStatusCodes.TIMEOUT) throw java.io.IOException("Google authorization is temporarily unavailable.")
            throw BackupAuthRequired()
        }
        if(result.hasResolution() || DRIVE_FILE !in result.grantedScopes || "https://www.googleapis.com/auth/drive.metadata.readonly" !in result.grantedScopes) throw BackupAuthRequired()
        return result.accessToken?.takeIf { it.isNotEmpty() } ?: throw BackupAuthRequired()
    }
    override suspend fun connect(email:String):DriveAuthorization {
        val result=client.authorize(request(email)).await()
        if(!result.hasResolution()) require(!result.accessToken.isNullOrEmpty() && DRIVE_FILE in result.grantedScopes) { "Google did not authorize Drive." }
        return DriveAuthorization(if(result.hasResolution()) result.pendingIntent else null)
    }
    override fun complete(intent:Intent) {
        val result=client.getAuthorizationResultFromIntent(intent)
        require(!result.hasResolution() && !result.accessToken.isNullOrEmpty() && DRIVE_FILE in result.grantedScopes) { "Google Drive permission was not granted." }
    }
    override suspend fun token(email:String):String {
        val result=try { client.authorize(request(email)).await() } catch(e:com.google.android.gms.common.api.ApiException) {
            if(e.statusCode==com.google.android.gms.common.api.CommonStatusCodes.NETWORK_ERROR || e.statusCode==com.google.android.gms.common.api.CommonStatusCodes.TIMEOUT) throw java.io.IOException("Google authorization is temporarily unavailable.")
            throw BackupAuthRequired()
        }
        if(result.hasResolution() || DRIVE_FILE !in result.grantedScopes) throw BackupAuthRequired()
        return result.accessToken?.takeIf { it.isNotEmpty() } ?: throw BackupAuthRequired()
    }
    override suspend fun invalidate(token:String) { client.clearToken(ClearTokenRequest.builder().setToken(token).build()).await() }
    override suspend fun signOut(email:String) {
        try { client.revokeAccess(RevokeAccessRequest.builder().setAccount(Account(email,"com.google")).setScopes(listOf(Scope(DRIVE_FILE))).build()).await() }
        finally { manager.clearCredentialState(ClearCredentialStateRequest()) }
    }
}
