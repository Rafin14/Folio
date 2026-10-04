package dev.folio.scanner.data

import java.util.concurrent.TimeUnit

val TRASH_RETENTION_MS: Long = TimeUnit.DAYS.toMillis(60)
val BackupRecord.driveRemovalPending: Boolean get() = state == "delete-pending" || state == "delete-review"

fun trashExpired(trashedAt: Long?, now: Long): Boolean =
    trashedAt != null && now >= trashedAt && now - trashedAt >= TRASH_RETENTION_MS

fun trashDaysRemaining(trashedAt: Long, now: Long): Long =
    ((TRASH_RETENTION_MS - (now - trashedAt).coerceAtLeast(0)).coerceAtLeast(0) + TimeUnit.DAYS.toMillis(1) - 1) / TimeUnit.DAYS.toMillis(1)

fun documentNameConflict(name: String, documents: List<Document>, except: String? = null): String? =
    documents.firstOrNull { !it.deleting && it.trashedAt == null && it.id != except && it.title.trim().equals(name.trim(), ignoreCase = true) }
        ?.let { "A document named “${it.title}” already exists. Choose another name." }

/** Called inside the transaction which marks local deletion or records an in-flight backup. */
internal suspend fun queueDriveDeletion(dao:DocumentDao,email:String,id:String,root:String) {
    if(root.isEmpty()) return
    val base="$email:delete:$id"
    val existing=dao.backupRecord(base)
    val key=if(existing==null || existing.remoteId==root) base else "$base:$root"
    if(dao.backupRecord(key)==null) dao.save(BackupRecord(key,root,id,0,System.currentTimeMillis(),"delete-pending"))
}

/** Page receipts share the existing deletion worker, with an explicit page target. */
val BackupRecord.pageRemoval: Boolean get() = key.substringAfter(":delete:", "").startsWith("page:")
internal suspend fun queueDrivePageDeletion(dao:DocumentDao,email:String,page:String,document:String,root:String) {
    if(root.isEmpty()) return
    val key="$email:delete:page:$page:$document:$root"
    if(dao.backupRecord(key)==null) dao.save(BackupRecord(key,root,page,0,System.currentTimeMillis(),"delete-pending"))
}
