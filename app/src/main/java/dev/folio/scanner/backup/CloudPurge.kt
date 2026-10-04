package dev.folio.scanner.backup

import dev.folio.scanner.data.*
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.IOException

fun validPurgeConfirmation(value:String)=value=="DELETE"
internal fun BackupRecord.blocksBackup()=state!="cloud-active"
internal fun purgeKey(email:String)="$email:cloud-purge"
internal fun purgeItemsPrefix(email:String,operation:String)="$email:purge-item:$operation:"
data class CloudPurgeProgress(val deleted:Int=0,val absent:Int=0,val pending:Int=0,val failed:Int=0,val review:Int=0,val message:String="") {
    val total get()=deleted+absent+pending+failed+review
    companion object { fun read(record:BackupRecord?):CloudPurgeProgress {
        val data=record?.session?.takeIf { it.isNotEmpty() }?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
        return CloudPurgeProgress(data.optInt("deleted"),data.optInt("absent"),data.optInt("pending"),data.optInt("failed"),data.optInt("review"),data.optString("message"))
    } }
}
internal fun removalReason(error:Exception)=when(error) {
    is DriveOwnershipReview -> error.message.orEmpty()
    is BackupAuthRequired -> "Reconnect Google Account and authorize safe folder inspection if requested."
    is DriveFailure -> "Drive HTTP ${error.code} (${error.reason.ifEmpty { "request failed" }}). ${error.message}"
    is org.json.JSONException -> "Folio metadata cannot be decoded safely. The file was kept for review."
    is IOException -> "Connection interrupted. Retry will resume remaining cloud removals."
    is IllegalArgumentException -> when {
        error.message.orEmpty().startsWith("Drive manifest changed") -> "Drive manifest changed during removal. Retry to read its latest version."
        error.message.orEmpty().startsWith("Google account changed") -> "Google account changed. Confirm again for the connected account."
        error.message=="Type exact uppercase DELETE." -> "Type exact uppercase DELETE."
        else -> "Cloud backup metadata failed validation. Folio kept affected resources for review."
    }
    else -> "Cloud removal failed. Retry or reconnect the original Google account."
}

/** Durable per-resource receipts reuse the backup journal, not document metadata. */
internal suspend fun purgeCloud(dao:DocumentDao,receipt:BackupRecord,remote:DriveStore,guard:()->Unit):BackupRecord {
    val email=receipt.key.removeSuffix(":cloud-purge"); val prefix=purgeItemsPrefix(email,receipt.hash)
    suspend fun saveItem(resource:DriveResource,state:String="pending",message:String="") {
        val key=prefix+resource.id; val previous=dao.backupRecord(key)
        if(previous!=null && state in listOf("pending","absent") && previous.state in listOf("deleted","absent")) return
        dao.save(BackupRecord(key,resource.id,resource.role,if(resource.folder) 1 else 0,System.currentTimeMillis(),state,message))
    }
    suspend fun checkpoint(state:String="purge-running",message:String=""):BackupRecord {
        val items=dao.backupRecords(prefix)
        val data=JSONObject()
        for(label in listOf("deleted","absent","pending","failed","review")) data.put(label,items.count { it.state==label })
        data.put("message",message.take(500))
        val saved=receipt.copy(state=state,modifiedAt=System.currentTimeMillis(),session=data.toString())
        guard(); dao.save(saved); return saved
    }
    guard(); checkpoint()
    try {
        // No name/extension heuristics. Inventory includes trashed and moved Folio resources.
        for(resource in remote.inventory()) { guard(); saveItem(resource) }
        // Retain known IDs even if their ownership marker has disappeared: review, never infer.
        for(record in dao.backupRecords("$email:")) {
            val role=when { ":asset:" in record.key -> "asset"; ":manifest:" in record.key -> "manifest"; ":document:" in record.key || ":association:" in record.key -> "root"; else -> continue }
            if(record.remoteId.isEmpty()) continue
            val current=remote.metadata(record.remoteId); guard()
            if(current==null) saveItem(DriveResource(record.remoteId,role,emptyList(),role=="root"),"absent")
            else saveItem(current.copy(role=role),if(current.role==role && current.ownedByMe) "pending" else "review",if(current.role==role && current.ownedByMe) "" else "Ownership marker is missing or changed for ${record.remoteId}.")
        }
        checkpoint()
        suspend fun remove(item:BackupRecord):Boolean {
            guard()
            val current=remote.metadata(item.remoteId)
            if(current==null) { dao.save(item.copy(state="absent",session="")); checkpoint(); return true }
            if(current.role!=item.hash || !current.ownedByMe) {
                dao.save(item.copy(state="review",session="Ownership changed for ${item.remoteId}. Resource was kept.")); checkpoint(); return false
            }
            if(current.folder) {
                val children=remote.children(current.id); guard()
                if(children.any { it.role !in listOf("root","manifest","asset") || !it.ownedByMe }) {
                    dao.save(item.copy(state="review",session="Folder ${item.remoteId} contains unrelated or ambiguous files. Folder was kept.")); checkpoint(); return false
                }
                if(children.isNotEmpty()) return false // Child folders are removed first on the next pass.
            }
            try {
                val deleted=remote.deleteOwned(current); guard()
                check(remote.metadata(current.id)==null) { "Drive has not confirmed absence of ${current.id}." }
                dao.save(item.copy(state=if(deleted) "deleted" else "absent",session="")); checkpoint(); return true
            } catch(cancel:CancellationException) { throw cancel }
            catch(error:Exception) {
                dao.save(item.copy(state=if(error is DriveOwnershipReview) "review" else "failed",session=removalReason(error)))
                checkpoint()
                if(error !is DriveOwnershipReview && !(error is DriveFailure && error.code==403)) throw error
                return false
            }
        }
        for(item in dao.backupRecords(prefix).filter { it.bytes==0L && it.state !in listOf("deleted","absent") }) remove(item)
        var progressed:Boolean
        do {
            progressed=false
            for(item in dao.backupRecords(prefix).filter { it.bytes==1L && it.state !in listOf("deleted","absent","review") }) if(remove(item)) progressed=true
        } while(progressed)
        // Re-enumerate and GET every known ID. Request success alone never completes the purge.
        val remaining=remote.inventory(); guard()
        for(resource in remaining) {
            val old=dao.backupRecord(prefix+resource.id)
            if(old==null) saveItem(resource)
            else if(old.state in listOf("deleted","absent")) dao.save(old.copy(state="pending",session="Resource is still present in final Drive inventory."))
        }
        for(item in dao.backupRecords(prefix).filter { it.state in listOf("deleted","absent") }) {
            if(remote.metadata(item.remoteId)!=null) dao.save(item.copy(state="review",session="Resource is retrievable despite its deletion response."))
        }
        guard()
        val items=dao.backupRecords(prefix)
        val state=when { items.any { it.state=="review" } -> "purge-review"; items.any { it.state=="failed" } -> "purge-failed"; remaining.isNotEmpty() || items.any { it.state=="pending" } -> "purge-pending"; else -> "purge-verified" }
        return checkpoint(state,when(state) { "purge-verified" -> "Verified: Folio cloud backup resources are absent."; "purge-review" -> "Some folders or resources require review. Unrelated files were kept."; "purge-failed" -> "Some cloud files could not be deleted. Retry or reconnect the original account."; else -> "Some cloud files still need to be removed." })
    } catch(cancel:CancellationException) { throw cancel }
    catch(error:Exception) { checkpoint(if(error is DriveOwnershipReview) "purge-review" else "purge-failed",removalReason(error)); throw error }
}
