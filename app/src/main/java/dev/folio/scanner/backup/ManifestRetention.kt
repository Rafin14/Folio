package dev.folio.scanner.backup

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File

internal data class ManifestGeneration(val file:DriveFile,val createdAt:Long,val checksum:String)

internal suspend fun manifestGenerations(remote:DriveStore,root:String,folder:File):List<ManifestGeneration> {
    val result=mutableListOf<ManifestGeneration>()
    val temp=File(folder,"generation.json")
    for(file in remote.list(root,"manifest")) {
        currentCoroutineContext().ensureActive()
        try {
            remote.download(file.id,temp,BackupManifest.MAX_MANIFEST_BYTES.toLong())
            val hash=hashFile(temp)
            // Changed/unrecognized generations stay intact and never authorize cleanup.
            if(file.hash.isNotEmpty() && file.hash!=hash) continue
            val manifest=BackupManifest.decode(temp.readBytes())
            result+=ManifestGeneration(file,manifest.createdAt,hash)
        } catch(error:DriveFailure) { if(error.code!=404) throw error }
        catch(_:IllegalArgumentException) { /* Keep malformed or newer-version manifests for review. */ }
        catch(_:org.json.JSONException) { /* Invalid JSON is not safe to delete. */ }
    }
    temp.delete()
    return result.sortedWith(compareByDescending<ManifestGeneration> { it.file.createdAt }.thenByDescending { it.createdAt }.thenBy { it.file.id })
}

internal fun sameBackupContent(a:BackupManifest,b:BackupManifest):Boolean {
    fun content(m:BackupManifest)=canonical(m.copy(id="00000000-0000-0000-0000-000000000000",createdAt=0,
        assets=m.assets.map { it.copy(remoteId="") }).body())
    return content(a)==content(b)
}

/** Keep current + one recoverable generation. Never garbage-collect assets here. */
internal suspend fun retainManifests(remote:DriveStore,root:String,current:String,folder:File):List<String> {
    val generations=manifestGenerations(remote,root,folder)
    require(generations.any { it.file.id==current }) { "Current manifest could not be verified; previous backups were kept." }
    val keep=generations.take(2).map { it.file.id }.toSet()+current
    val obsolete=generations.filter { it.file.id !in keep }
    if(obsolete.isEmpty()) return emptyList()
    val verified=mutableSetOf<Pair<String,String>>()
    val manifestFile=File(folder,"retention-manifest.json"); val assetFile=File(folder,"retention-asset")
    try {
        for(generation in generations.filter { it.file.id in keep }) {
            remote.download(generation.file.id,manifestFile,BackupManifest.MAX_MANIFEST_BYTES.toLong())
            require(hashFile(manifestFile)==generation.checksum) { "Retained manifest changed; cleanup stopped." }
            val manifest=BackupManifest.decode(manifestFile.readBytes())
            for(asset in manifest.assets) {
                currentCoroutineContext().ensureActive()
                if(!verified.add(asset.remoteId to asset.hash)) continue
                remote.download(asset.remoteId,assetFile,asset.bytes)
                require(assetFile.length()==asset.bytes && hashFile(assetFile)==asset.hash) { "Retained backup assets could not be verified; previous generations were kept." }
                assetFile.delete()
            }
        }
        val removed=mutableListOf<String>()
        for(generation in obsolete) {
            currentCoroutineContext().ensureActive()
            remote.deleteManifest(generation.file.id,root,generation.checksum)
            check(!remote.exists(generation.file.id)) { "Manifest deletion remains pending." }
            removed+=generation.file.id
        }
        return removed
    } finally { manifestFile.delete(); assetFile.delete() }
}
