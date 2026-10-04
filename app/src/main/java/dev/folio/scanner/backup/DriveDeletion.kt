package dev.folio.scanner.backup

import dev.folio.scanner.data.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** The receipt's account/root and each checked manifest's document UUID are the association;
 * filenames and disappearing local records never authorize remote deletion. */
internal suspend fun reconcileDriveDeletion(dao: DocumentDao, receipt: BackupRecord, remote: DriveStore, folder: File) {
    val pageOnly=receipt.pageRemoval
    val documentId=if(pageOnly) receipt.key.substringAfter(":delete:page:").split(':')[1] else receipt.hash
    BackupManifest.uuid(documentId); BackupManifest.uuid(receipt.hash)
    fun targeted(p:BackupPage)=p.page.documentId==documentId && (!pageOnly || p.page.id==receipt.hash)
    val email=receipt.key.substringBefore(":delete:")
    val candidates=if(receipt.session.isEmpty()) JSONObject() else JSONObject(receipt.session)
    suspend fun checkpoint() { dao.save(receipt.copy(state="delete-pending",session=candidates.toString())) }
    suspend fun scan(root:String=receipt.remoteId,action:suspend(DriveFile,BackupManifest,String)->Unit) {
        for(file in remote.list(root,"manifest",includeTrashed=true)) {
            currentCoroutineContext().ensureActive()
            val target=File(folder,"manifest.json")
            try { remote.download(file.id,target,BackupManifest.MAX_MANIFEST_BYTES.toLong()) }
            catch(error:DriveFailure) { if(error.code==404) continue else throw error }
            val bytes=target.readBytes(); action(file,BackupManifest.decode(bytes),sha256(bytes))
        }
    }
    // Also account for partial uploads that never committed a manifest.
    val associatedHashes=mutableSetOf<String>()
    val associations=if(pageOnly) listOfNotNull(dao.backupRecord("$email:page:${receipt.remoteId}:${receipt.hash}")) else dao.documentBackups(documentId)
    associations.filter { it.remoteId==receipt.remoteId && it.key.startsWith("$email:") }.forEach { association ->
        if(association.session.isNotEmpty()) {
            val hashes=JSONArray(association.session)
            repeat(hashes.length()) { index ->
                val hash=hashes.getString(index)
                associatedHashes+=hash
                (dao.backupRecord("$email:asset:${receipt.remoteId}:$hash") ?: dao.backupRecord("$email:asset:$hash"))
                    ?.takeIf { it.remoteId.isNotEmpty() && (it.session.isEmpty() || it.session==receipt.remoteId) }?.let { candidates.put(it.remoteId,hash) }
            }
        }
    }
    // A process can die after Drive commits an upload but before its file ID is saved locally.
    if(associatedHashes.isNotEmpty()) remote.list(receipt.remoteId,"asset",includeTrashed=true).filter { it.hash in associatedHashes }
        .forEach { candidates.put(it.id,it.hash) }
    scan { file,manifest,hash ->
        if(if(pageOnly) manifest.pages.none(::targeted) else manifest.documents.none { it.id==documentId }) return@scan
        val removed=manifest.pages.filter(::targeted).flatMap { listOf(it.original,it.processed) }.toSet()
        manifest.assets.filter { it.hash in removed }.forEach { candidates.put(it.remoteId,it.hash) }
        checkpoint() // Persist before patching: a crash must not lose now-unreferenced asset IDs.
        remote.replaceManifest(file.id,receipt.remoteId,hash,if(pageOnly) manifest.withoutPages(setOf(receipt.hash)) else manifest.withoutDocuments(setOf(documentId)))
    }
    checkpoint()
    val remaining=mutableSetOf<String>()
    scan { _,manifest,_ ->
        require(if(pageOnly) manifest.pages.none(::targeted) else manifest.documents.none { it.id==documentId }) { "Drive backup changed. Removal will retry." }
        remaining+=manifest.assets.map { it.remoteId }
    }
    // Older restored libraries could reference assets across Folio folders. Protect those too.
    for(root in remote.list(null,"root",includeTrashed=true).map { it.id }.filter { it!=receipt.remoteId })
        scan(root) { _,manifest,_ -> remaining+=manifest.assets.map { it.remoteId } }
    for(id in candidates.keys().asSequence().toList()) {
        val hash=candidates.getString(id)
        if(id !in remaining) remote.deleteAsset(id,receipt.remoteId,hash)
    }
    dao.save(receipt.copy(state="delete-complete",session="",modifiedAt=System.currentTimeMillis()))
}
