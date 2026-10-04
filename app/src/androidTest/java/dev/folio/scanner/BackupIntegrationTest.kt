package dev.folio.scanner

import android.graphics.Bitmap
import android.graphics.Color
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import dev.folio.scanner.backup.*
import dev.folio.scanner.data.*
import dev.folio.scanner.processing.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.util.UUID

class BackupIntegrationTest {
    @Test fun pageTrashRetainsCloudAssetsAndRestoreKeepsIdsBeforePermanentRemoval()=runBlocking {
        fixture { db,docs,backup,drive ->
            val id=docs.create("Page cloud trash")
            val a=docs.importImage(id,ByteArrayInputStream(image(Color.RED,300,400)),detectDocument=false)
            val b=docs.importImage(id,ByteArrayInputStream(image(Color.BLUE,300,400)),detectDocument=false)
            backup.execute(backup.prepare(false),drive)
            val original=drive.latest().manifest; val ids=original.assets.map { it.remoteId }.toSet()
            docs.deletePages(id,setOf(a)); backup.executeDeletions("fixture@example.invalid",drive)
            assertTrue(ids.all { remote -> drive.entries.any { it.id==remote } })
            assertTrue(db.documents().driveDeletions().none { it.hash==a })
            backup.execute(backup.prepare(false),drive)
            val trashed=drive.latest().manifest; assertNotNull(trashed.pages.first { it.page.id==a }.page.trashedAt)
            assertEquals(1,trashed.documents.single().pageCount)
            docs.restorePages(setOf(a)); backup.execute(backup.prepare(false),drive)
            assertEquals(ids,drive.latest().manifest.assets.map { it.remoteId }.toSet())
            assertEquals(listOf(a,b),db.documents().pages(id).map { it.id })
            docs.deletePages(id,setOf(a)); docs.permanentlyDeletePages(setOf(a)); backup.executeDeletions("fixture@example.invalid",drive)
            assertEquals(b,drive.latest().manifest.pages.single().page.id)
            assertEquals(id,drive.latest().manifest.documents.single().id)
        }
    }

    @Test fun localPageCleanupReceiptSurvivesFailureAndDatabaseReopen()=runBlocking {
        fixture { db,docs,_,_ ->
            val id=docs.create("Local page cleanup checkpoint"); val page=docs.importImage(id,ByteArrayInputStream(image(Color.RED,300,400)),detectDocument=false)
            val before=db.documents().page(page)!!; val original=File(before.originalImageUri)
            val held=File(docs.directory(id),"held-test-original"); check(original.renameTo(held))
            check(original.mkdir()); File(original,"blocked").writeText("test")
            try { docs.deletePages(id,setOf(page)); docs.permanentlyDeletePages(setOf(page)); fail("Expected cleanup failure") } catch(_:IllegalStateException) {}
            assertNull(db.documents().page(page)); assertEquals(0,db.documents().document(id)!!.pageCount)
            assertEquals("local-page-cleanup",db.documents().backupRecord("local:delete:page:$page:$id")!!.state)
            original.deleteRecursively()
            val reopened=Room.databaseBuilder(context,FolioDatabase::class.java,db.openHelper.databaseName!!).build()
            val pipeline=ImagePipeline(context)
            try {
                val resumed=DocumentRepository(reopened,context,pipeline); resumed.recoverDeletes()
                assertEquals("local-page-deleted",reopened.documents().backupRecord("local:delete:page:$page:$id")!!.state)
                assertFalse(File(before.processedImageUri).exists()); assertFalse(File(before.thumbnailUri).exists())
                assertNull(reopened.documents().document(id)!!.trashedAt)
            } finally { pipeline.close(); reopened.close() }
        }
    }
    @Test fun permanentPageDeletionKeepsDocumentSharedAssetsAndSurvivesInterruptedDriveRemoval()=runBlocking {
        fixture { db,docs,backup,drive ->
            val id=docs.create("Page deletion remains document")
            val first=docs.importImage(id,ByteArrayInputStream(image(Color.RED,300,400)),detectDocument=false)
            val removed=docs.importImage(id,ByteArrayInputStream(image(Color.BLUE,300,400)),detectDocument=false)
            val shared=docs.duplicatePage(first)
            backup.execute(backup.prepare(false),drive); backup.execute(backup.prepare(false),drive)
            val old=db.documents().page(removed)!!
            db.documents().save(OcrResult(removed,"Deleted OCR",1))
            docs.deletePages(id,setOf(first,removed)); docs.permanentlyDeletePages(setOf(first,removed))
            assertNull(db.documents().page(removed)); assertNull(db.documents().ocr(removed))
            assertFalse(File(old.originalImageUri).exists()); assertFalse(File(old.processedImageUri).exists()); assertFalse(File(old.thumbnailUri).exists())
            assertNull(db.documents().document(id)!!.trashedAt); assertEquals(shared,db.documents().pages(id).single().id)
            assertEquals(0,db.documents().pages(id).single().position)
            drive.failDeletion=true
            try { backup.executeDeletions("fixture@example.invalid",drive); fail("Expected partial network failure") } catch(_:IOException) {}
            val reopened=Room.databaseBuilder(context,FolioDatabase::class.java,db.openHelper.databaseName!!).build()
            val pipeline=ImagePipeline(context)
            try {
                val resumed=FolioBackupRepository(backupContext,reopened,DocumentRepository(reopened,context,pipeline),FakeAuth(),pipeline)
                resumed.executeDeletions("fixture@example.invalid",drive)
            } finally { reopened.close(); pipeline.close() }
            drive.entries.filter { it.role=="manifest" }.forEach { entry -> val m=BackupManifest.decode(entry.bytes)
                assertEquals(listOf(id),m.documents.map { it.id }); assertEquals(listOf(shared),m.pages.map { it.page.id }); assertEquals(1,m.documents.single().pageCount)
                assertTrue(m.assets.all { a -> drive.entries.any { it.id==a.remoteId } })
            }
            backup.executeDeletions("fixture@example.invalid",drive)
            assertTrue(db.documents().driveDeletions().filter { it.pageRemoval && it.key.startsWith("fixture@example.invalid:") }.all { it.state=="delete-complete" })
            docs.deletePages(id,setOf(shared)); docs.permanentlyDeletePages(setOf(shared)); backup.executeDeletions("fixture@example.invalid",drive)
            assertEquals(0,db.documents().document(id)!!.pageCount); assertTrue(drive.entries.none { it.role=="asset" })
            assertTrue(drive.latest().manifest.documents.any { it.id==id && it.pageCount==0 })
        }
    }
    @Test fun pageDeletedDuringPartialUploadIsExcludedAndUncommittedAssetsAreRemoved()=runBlocking {
        fixture { db,docs,backup,drive ->
            val id=docs.create("Partial page upload"); val first=docs.importImage(id,ByteArrayInputStream(image(Color.CYAN,300,400)),detectDocument=false)
            docs.importImage(id,ByteArrayInputStream(image(Color.RED,300,400)),detectDocument=false)
            var dropped=false
            val interrupted=object:DriveStore by drive {
                override suspend fun upload(parent:String,name:String,role:String,hash:String,file:File,session:String?,checkpoint:suspend(String)->Unit):String {
                    val uploaded=drive.upload(parent,name,role,hash,file,session,checkpoint)
                    if(role=="asset" && !dropped) { dropped=true; docs.deletePages(id,setOf(first)); docs.permanentlyDeletePages(setOf(first)); throw IOException("After upload before checkpoint") }; return uploaded
                }
            }
            val operation=backup.prepare(false)
            try { backup.execute(operation,interrupted); fail("Expected partial upload") } catch(_:IOException) {}
            backup.cleanDeletedStaging(); backup.execute(operation,drive); backup.executeDeletions("fixture@example.invalid",drive)
            assertEquals(1,db.documents().pages(id).size); assertTrue(drive.latest().manifest.pages.none { it.page.id==first })
            val references=drive.entries.filter { it.role=="manifest" }.flatMap { BackupManifest.decode(it.bytes).assets.map { a -> a.remoteId } }.toSet()
            assertTrue(drive.entries.filter { it.role=="asset" }.all { it.id in references })
        }
    }
    @Test fun purgeKeepsMissingOwnershipMarkersAndResumesAfterCancellation()=runBlocking {
        fixture { db,docs,backup,drive ->
            val id=docs.create("Ambiguous cloud ownership"); docs.importImage(id,ByteArrayInputStream(image(Color.MAGENTA)),detectDocument=false)
            backup.execute(backup.prepare(false),drive)
            coroutineScope { (1..8).map { async { backup.requestCloudPurge("fixture@example.invalid","DELETE") } }.awaitAll() }
            val receipt=db.documents().backupRecord("fixture@example.invalid:cloud-purge")!!
            val interrupted=object:DriveStore by drive { override suspend fun deleteOwned(resource:DriveResource):Boolean { throw CancellationException("Process stopped") } }
            try { backup.executeCloudPurge("fixture@example.invalid",interrupted); fail("Cancellation swallowed") } catch(_:CancellationException) {}
            assertEquals(receipt.hash,db.documents().backupRecord(receipt.key)!!.hash)
            assertEquals("purge-running",db.documents().backupRecord(receipt.key)!!.state)
            val asset=drive.entries.indexOfFirst { it.role=="asset" }; val original=drive.entries[asset]; drive.entries[asset]=original.copy(role="")
            try { backup.executeCloudPurge("fixture@example.invalid",drive); fail("Missing marker deleted") } catch(_:DriveOwnershipReview) {}
            assertTrue(drive.entries.any { it.id==original.id }); assertNotNull(db.documents().document(id))
            assertTrue(backup.purgeDetails().any { it.remoteId==original.id && it.state=="review" })
            val remaining=drive.entries.indexOfFirst { it.id==original.id }; drive.entries[remaining]=original
            assertTrue(backup.executeCloudPurge("fixture@example.invalid",drive)); assertTrue(drive.entries.isEmpty())
        }
    }
    @Test fun deliberateCloudPurgePreservesLocalPagesOcrAndBlocksRecreationUntilExplicitBackupNow()=runBlocking {
        fixture { db,docs,backup,drive ->
            val id=docs.create("Purge local remains"); docs.importImage(id,ByteArrayInputStream(image(Color.BLUE)),detectDocument=false); docs.importImage(id,ByteArrayInputStream(image(Color.RED)),detectDocument=false)
            val page=db.documents().pages(id).first(); val sourceHash=hashFile(File(page.originalImageUri))
            val regions=dev.folio.scanner.ocr.regionsJson(listOf(dev.folio.scanner.ocr.TextRegion("Purge OCR fixture",listOf(.1,.1,.7,.1,.7,.2,.1,.2),.9)))
            db.documents().save(OcrResult(page.id,"Purge OCR fixture",10,regions,"complete",dev.folio.scanner.ocr.ocrRevision(page,sourceHash),sourceHash,"en","PaddleOCR",dev.folio.scanner.ocr.OCR_MODEL,dev.folio.scanner.ocr.OCR_PREPROCESS,page.width,page.height))
            val before=docs.librarySnapshot(); val hashes=db.documents().pages(id).map { hashFile(File(it.originalImageUri)) }
            val operation=backup.prepare(false); backup.execute(operation,drive)
            // Changed content does not erase positive Folio ownership; purge does not parse contents.
            val asset=drive.entries.indexOfFirst { it.role=="asset" }; drive.entries[asset]=drive.entries[asset].copy(bytes=byteArrayOf(4,5))
            backup.requestCloudPurge("fixture@example.invalid","DELETE")
            try { backup.execute(backup.prepare(false),drive); fail("Purge allowed upload recreation") } catch(_:IllegalStateException) {}
            assertTrue(backup.executeCloudPurge("fixture@example.invalid",drive)); assertTrue(drive.entries.isEmpty())
            assertEquals(before,docs.librarySnapshot()); assertEquals(hashes,db.documents().pages(id).map { hashFile(File(it.originalImageUri)) })
            assertEquals("cloud-purged",db.documents().backupRecord("fixture@example.invalid:cloud-purge")!!.state)
            docs.delete(id); docs.restore(setOf(id)); assertTrue(drive.entries.isEmpty())
            assertFalse(backup.state.value.automatic); assertNull(backup.automaticOperation("old"))
            try { backup.automatic(true); fail("Automatic recreation allowed") } catch(_:IllegalStateException) {}
            assertTrue(backup.executeCloudPurge("fixture@example.invalid",drive))
            backup.backUpNow(); backup.execute(backup.prepare(false),drive); assertTrue(drive.entries.any { it.role=="manifest" })
            assertEquals("cloud-active",db.documents().backupRecord("fixture@example.invalid:cloud-purge")!!.state)
        }
    }
    @Test fun purgePartialFailureCheckpointsAndResumesAcrossDatabaseAndRepositoryRestart()=runBlocking {
        fixture { db,docs,backup,drive ->
            val id=docs.create("Partial purge"); docs.importImage(id,ByteArrayInputStream(image(Color.GREEN)),detectDocument=false); docs.importImage(id,ByteArrayInputStream(image(Color.YELLOW)),detectDocument=false)
            backup.execute(backup.prepare(false),drive); drive.failAfterOnePurge=true
            backup.requestCloudPurge("fixture@example.invalid","DELETE")
            try { backup.executeCloudPurge("fixture@example.invalid",drive); fail("Network failure marked complete") } catch(_:IOException) {}
            val receipt=db.documents().backupRecord("fixture@example.invalid:cloud-purge")!!; assertEquals("purge-failed",receipt.state)
            val items=db.documents().backupRecords("fixture@example.invalid:purge-item:${receipt.hash}:"); assertTrue(items.any { it.state=="deleted" }); assertTrue(items.any { it.state=="failed" })
            val reopened=Room.databaseBuilder(context,FolioDatabase::class.java,db.openHelper.databaseName!!).build(); val pipeline=ImagePipeline(context)
            try {
                val resumed=FolioBackupRepository(backupContext,reopened,DocumentRepository(reopened,context,pipeline),FakeAuth(),pipeline)
                assertTrue(resumed.executeCloudPurge("fixture@example.invalid",drive)); assertTrue(drive.entries.isEmpty()); assertNotNull(db.documents().document(id))
                val completed=CloudPurgeProgress.read(reopened.documents().backupRecord("fixture@example.invalid:cloud-purge")); assertEquals(0,completed.pending+completed.failed+completed.review); assertTrue(completed.deleted>0)
            } finally { pipeline.close(); reopened.close() }
        }
    }
    @Test fun purgeKeepsUnrelatedChildrenAndAmbiguousOwnershipThenOffersRealRetry()=runBlocking {
        fixture { db,docs,backup,drive ->
            docs.create("Purge ownership"); backup.execute(backup.prepare(false),drive)
            drive.entries+=FakeDrive.Entry("unrelated","root","","",byteArrayOf(9),"Folio Backup.pdf")
            drive.entries+=FakeDrive.Entry("nested","root","root","",byteArrayOf(),"nested")
            drive.entries+=FakeDrive.Entry("nested-file","nested","asset","changed",byteArrayOf(8),"changed")
            backup.requestCloudPurge("fixture@example.invalid","DELETE")
            try { backup.executeCloudPurge("fixture@example.invalid",drive); fail("Folder with unrelated child removed") } catch(_:DriveOwnershipReview) {}
            assertEquals(setOf("root","unrelated"),drive.entries.map { it.id }.toSet())
            assertEquals("purge-review",db.documents().backupRecord("fixture@example.invalid:cloud-purge")!!.state)
            assertTrue(backup.purgeDetails().any { it.session.contains("unrelated") })
            drive.entries.removeAll { it.id=="unrelated" } // Owner moved the ambiguous child out; retry can verify empty folder.
            assertTrue(backup.executeCloudPurge("fixture@example.invalid",drive)); assertTrue(drive.entries.isEmpty())
        }
    }
    @Test fun purgeAccountMismatchAuthFailureMissingIdsAndDuplicateConfirmationAreSafe()=runBlocking {
        fixture { db,docs,backup,drive ->
            docs.create("Purge account fixture"); backup.execute(backup.prepare(false),drive)
            val remoteBefore=drive.entries.toList()
            for(value in listOf("delete"," DELETE","DELETE ","")) try { backup.requestCloudPurge("fixture@example.invalid",value); fail("Invalid confirmation accepted") } catch(_:IllegalArgumentException) {}
            assertNull(db.documents().backupRecord("fixture@example.invalid:cloud-purge"))
            backup.requestCloudPurge("fixture@example.invalid","DELETE"); val first=db.documents().backupRecord("fixture@example.invalid:cloud-purge")!!.hash
            backup.requestCloudPurge("fixture@example.invalid","DELETE"); assertEquals(first,db.documents().backupRecord("fixture@example.invalid:cloud-purge")!!.hash)
            backup.connected("other@example.invalid"); assertFalse(backup.executeCloudPurge("fixture@example.invalid",drive)); assertEquals(remoteBefore,drive.entries)
            backup.connected("fixture@example.invalid")
            val denied=object:DriveStore by drive { override suspend fun inventory():List<DriveResource> { throw BackupAuthRequired() } }
            try { backup.executeCloudPurge("fixture@example.invalid",denied); fail("Auth failure completed") } catch(_:BackupAuthRequired) {}
            assertEquals(remoteBefore,drive.entries)
            drive.entries.removeAll { it.role=="manifest" } // Simulate an externally deleted resource: 404 means absent.
            assertTrue(backup.executeCloudPurge("fixture@example.invalid",drive)); assertTrue(drive.entries.isEmpty())
        }
    }
    @Test fun explicitRetryReplacesWaitingWorkAndPurgeUsesConnectedNetworkConstraint()=runBlocking {
        fixture { _,_,backup,_ ->
            val work=WorkManager.getInstance(context); val email="fixture@example.invalid"
            val old=androidx.work.OneTimeWorkRequestBuilder<DriveDeletionWorker>().setInitialDelay(1,java.util.concurrent.TimeUnit.DAYS).build()
            work.enqueueUniqueWork("folio-drive-delete-$email",androidx.work.ExistingWorkPolicy.REPLACE,old).result.get()
            withTimeout(10000) { while(work.getWorkInfosForUniqueWork("folio-drive-delete-$email").get().any { it.state==androidx.work.WorkInfo.State.RUNNING }) delay(20) }
            backup.retryDeletions(); withTimeout(10000) { while(work.getWorkInfoById(old.id).get()?.state?.let { !it.isFinished }==true) delay(20) }
            assertTrue(work.getWorkInfosForUniqueWork("folio-drive-delete-$email").get().any { it.id!=old.id })
            backup.requestCloudPurge(email,"DELETE")
            val queued=work.getWorkInfosForUniqueWork("folio-cloud-purge-$email").get(); assertTrue(queued.isNotEmpty())
            assertEquals(androidx.work.NetworkType.CONNECTED,queued.last().constraints.requiredNetworkType)
        }
    }

    @Test fun firstBackupOfflineDeletionCleansInputsWithoutInventingDriveAssociation()=runBlocking {
        fixture { db,docs,backup,drive ->
            val id=docs.create("Offline first backup"); docs.importImage(id,ByteArrayInputStream(image(Color.CYAN,300,400)),detectDocument=false)
            val offline=object:DriveStore by drive { override suspend fun folder():String { throw IOException("Offline before root lookup") } }
            val operation=backup.prepare(false)
            try { backup.execute(operation,offline); fail("Expected offline failure") } catch(_:IOException) {}
            val dir=File(context.filesDir,"drive-jobs/$operation")
            assertTrue(File(dir,"assets").listFiles()!!.isNotEmpty()); assertTrue(db.documents().documentBackups(id).isEmpty())
            docs.delete(id); docs.permanentlyDelete(setOf(id)); backup.cleanDeletedStaging()
            assertTrue(File(dir,"assets").listFiles()!!.isEmpty()); assertTrue(drive.entries.isEmpty())
            assertTrue(db.documents().driveDeletions().none { it.driveRemovalPending })
            val reopened=Room.databaseBuilder(context,FolioDatabase::class.java,db.openHelper.databaseName!!).build()
            try { assertEquals("local-deleted",reopened.documents().backupRecord("local:delete:$id")!!.state) } finally { reopened.close() }
            // A snapshot not yet committed is safely re-pinned on retry.
            File(dir,"snapshot.json").delete()
            for(name in listOf("assets","pins")) File(dir,"$name/interrupted").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1)) }
            backup.cleanDeletedStaging(); assertFalse(File(dir,"pins").exists()); assertFalse(File(dir,"assets").exists())
            backup.execute(operation,drive); assertTrue(drive.latest().manifest.documents.isEmpty()); assertTrue(drive.entries.none { it.role=="asset" })
        }
    }
    @Test fun partialUploadWithoutSavedRemoteIdIsRemovedAndOfflineStagedOriginalsAreCleaned()=runBlocking {
        fixture { db,docs,backup,drive ->
            val id=docs.create("Partial upload deletion"); docs.importImage(id,ByteArrayInputStream(image(Color.MAGENTA,300,400)),detectDocument=false)
            val interrupted=object:DriveStore by drive {
                override suspend fun upload(parent:String,name:String,role:String,hash:String,file:File,session:String?,checkpoint:suspend(String)->Unit):String {
                    val result=drive.upload(parent,name,role,hash,file,session,checkpoint)
                    if(role=="asset") throw IOException("Process died after remote commit")
                    return result
                }
            }
            val operation=backup.prepare(false)
            try { backup.execute(operation,interrupted); fail("Expected interruption") } catch(_:IOException) {}
            assertTrue(drive.entries.any { it.role=="asset" }); assertTrue(drive.entries.none { it.role=="manifest" })
            val dir=File(context.filesDir,"drive-jobs/$operation"); assertTrue(File(dir,"assets").listFiles()!!.isNotEmpty())
            docs.delete(id); docs.permanentlyDelete(setOf(id)); backup.cleanDeletedStaging()
            assertTrue(File(dir,"assets").listFiles()!!.isEmpty()); assertTrue(BackupManifest.decode(File(dir,"snapshot.json").readBytes(),false).documents.isEmpty())
            backup.executeDeletions("fixture@example.invalid",drive)
            assertTrue(drive.entries.none { it.role=="asset" }); assertEquals("delete-complete",db.documents().driveDeletions().filter { it.key.startsWith("fixture@example.invalid:delete:") }.single().state)
        }
    }
    @Test fun failedManifestUpdateNeverDeletesAssetsOrUnrelatedFiles()=runBlocking {
        fixture { db,docs,backup,drive ->
            val id=docs.create("Patch failure"); docs.importImage(id,ByteArrayInputStream(image(Color.YELLOW,300,400)),detectDocument=false)
            backup.execute(backup.prepare(false),drive)
            val unrelated=FakeDrive.Entry("unrelated","root","asset","f".repeat(64),byteArrayOf(7),"Patch failure.jpg")
            drive.entries+=unrelated
            val before=drive.entries.toList(); docs.delete(id); docs.permanentlyDelete(setOf(id))
            val unavailable=object:DriveStore by drive {
                override suspend fun replaceManifest(id:String,parent:String,oldHash:String,manifest:BackupManifest) { throw DriveFailure(503) }
            }
            try { backup.executeDeletions("fixture@example.invalid",unavailable); fail("Expected temporary failure") } catch(_:DriveFailure) {}
            assertEquals(before,drive.entries); assertEquals("delete-pending",db.documents().driveDeletions().filter { it.key.startsWith("fixture@example.invalid:delete:") }.single().state)
            backup.executeDeletions("fixture@example.invalid",drive); assertEquals(listOf(unrelated),drive.entries.filter { it.role=="asset" })
        }
    }
    @Test fun permanentDeletionRewritesEverySnapshotKeepsSharedAssetsAndRetriesAfterRestart()=runBlocking {
        fixture { db,docs,backup,drive ->
            val a=docs.create("Delete me"); val shared=image(Color.RED,300,400)
            docs.importImage(a,ByteArrayInputStream(shared),detectDocument=false)
            val b=docs.create("Keep me"); docs.importImage(b,ByteArrayInputStream(shared),detectDocument=false)
            docs.importImage(a,ByteArrayInputStream(image(Color.BLUE,300,400)),detectDocument=false)
            backup.execute(backup.prepare(false),drive)
            docs.rename(a,"Delete renamed"); backup.execute(backup.prepare(false),drive)
            val before=drive.entries.count { it.role=="asset" }
            docs.delete(a); docs.permanentlyDelete(setOf(a))
            assertNull(db.documents().document(a)); assertFalse(docs.directory(a).exists())
            assertEquals("delete-pending",db.documents().backupRecord("fixture@example.invalid:delete:$a")!!.state)
            drive.failDeletion=true
            try { backup.executeDeletions("fixture@example.invalid",drive); fail("Expected offline failure") } catch(_:IOException) {}
            assertNull(db.documents().document(a))
            assertTrue(drive.entries.filter { it.role=="manifest" }.all { entry -> BackupManifest.decode(entry.bytes).documents.none { it.id==a } })
            val pipeline=ImagePipeline(context)
            try {
                val reopened=Room.databaseBuilder(context,FolioDatabase::class.java,db.openHelper.databaseName!!).build()
                try {
                    val resumed=FolioBackupRepository(backupContext,reopened,DocumentRepository(reopened,context,pipeline),FakeAuth(),pipeline)
                    resumed.executeDeletions("fixture@example.invalid",drive)
                    assertEquals("delete-complete",reopened.documents().backupRecord("fixture@example.invalid:delete:$a")!!.state)
                } finally { reopened.close() }
            } finally { pipeline.close() }
            assertTrue(drive.entries.count { it.role=="asset" }<before)
            for(entry in drive.entries.filter { it.role=="manifest" }) {
                val manifest=BackupManifest.decode(entry.bytes); assertEquals(listOf(b),manifest.documents.map { it.id })
                assertTrue(manifest.assets.all { asset -> drive.entries.any { it.id==asset.remoteId } })
            }
            backup.executeDeletions("fixture@example.invalid",drive)
            backup.execute(backup.prepare(false),drive); assertEquals(listOf(b),drive.latest().manifest.documents.map { it.id })
        }
    }
    @Test fun absentBackupDisconnectedDeletionMissingAssetAndWrongAccountAreSafe()=runBlocking {
        fixture { db,docs,backup,drive ->
            val never=docs.create("Never backed up"); docs.delete(never); docs.permanentlyDelete(setOf(never))
            assertTrue(db.documents().driveDeletions().none { it.driveRemovalPending })
            val id=docs.create("Backed up then disconnected"); docs.importImage(id,ByteArrayInputStream(image(Color.GREEN,300,400)),detectDocument=false)
            backup.execute(backup.prepare(false),drive); backup.disconnect()
            docs.delete(id); docs.permanentlyDelete(setOf(id)); assertNull(db.documents().document(id))
            backup.executeDeletions("fixture@example.invalid",drive)
            assertEquals("delete-pending",db.documents().driveDeletions().filter { it.key.startsWith("fixture@example.invalid:delete:") }.single().state)
            backup.connected("other@example.invalid"); backup.executeDeletions("other@example.invalid",drive)
            assertTrue(drive.latest().manifest.documents.any { it.id==id })
            backup.connected("fixture@example.invalid"); drive.entries.removeAll { it.role=="asset" }
            backup.executeDeletions("fixture@example.invalid",drive)
            assertEquals("delete-complete",db.documents().driveDeletions().filter { it.key.startsWith("fixture@example.invalid:delete:") }.single().state)
        }
    }
    @Test fun deletionDuringUploadCannotPublishDeletedDocumentAndPartialAssetsAreReconciled()=runBlocking {
        fixture { db,docs,backup,drive ->
            val id=docs.create("Delete during upload"); docs.importImage(id,ByteArrayInputStream(image(Color.CYAN,300,400)),detectDocument=false)
            val entered=CompletableDeferred<Unit>(); val release=CompletableDeferred<Unit>()
            val paused=object:DriveStore by drive {
                override suspend fun upload(parent:String,name:String,role:String,hash:String,file:File,session:String?,checkpoint:suspend(String)->Unit):String {
                    if(role=="asset") { entered.complete(Unit); release.await() }
                    return drive.upload(parent,name,role,hash,file,session,checkpoint)
                }
            }
            val task=async { backup.execute(backup.prepare(false),paused) }
            try { withTimeout(15000) { entered.await() }; docs.delete(id); docs.permanentlyDelete(setOf(id)) }
            finally { release.complete(Unit) }
            task.await(); assertTrue(drive.latest().manifest.documents.isEmpty())
            backup.executeDeletions("fixture@example.invalid",drive)
            assertTrue(drive.entries.none { it.role=="asset" }); assertNull(db.documents().document(id))
        }
    }
    @Test fun structuredOcrBackupRestorePreservesRevisionAndSearchIndex()=runBlocking {
        fixture { db,docs,backup,drive ->
            val doc=docs.create("OCR backup fixture"); val id=docs.importImage(doc,ByteArrayInputStream(image()),detectDocument=false)
            docs.renamePage(id,"Newton page"); docs.edit(id,layout=PageLayout("A5"),enqueueOcr=false)
            val p=requireNotNull(db.documents().page(id)); val hash=hashFile(File(p.originalImageUri))
            val regions=listOf(dev.folio.scanner.ocr.TextRegion("Newton invoice",listOf(.1,.1,.7,.1,.7,.2,.1,.2),.8))
            val r=OcrResult(id,"Newton invoice",10,dev.folio.scanner.ocr.regionsJson(regions),"complete",dev.folio.scanner.ocr.ocrRevision(p,hash),hash,"en","PaddleOCR",dev.folio.scanner.ocr.OCR_MODEL,dev.folio.scanner.ocr.OCR_PREPROCESS,p.width,p.height)
            db.documents().save(r); backup.execute(backup.prepare(false),drive)
            val preview=drive.latest(); assertEquals(listOf(r),preview.manifest.ocr)
            docs.delete(doc)
            backup.execute(backup.prepare(false,preview),drive)
            val restored=db.documents().allDocuments().first { it.id!=doc }; val page=db.documents().pages(restored.id).single()
            assertEquals("Newton page",page.pageName); assertEquals("A5",page.pageSize)
            val text=requireNotNull(db.documents().ocr(page.id)); assertTrue(dev.folio.scanner.ocr.validOcr(text,page)); assertEquals(r.regions,text.regions)
            val hits=db.documents().searchOcr(dev.folio.scanner.ocr.searchExpression("Newton")).first()
            assertTrue(hits.any { it.pageId==page.id })
            docs.delete(restored.id); docs.permanentlyDelete(setOf(restored.id)); assertNull(db.documents().ocr(page.id))
            assertFalse(db.documents().searchOcr(dev.folio.scanner.ocr.searchExpression("Newton")).first().any { it.pageId==page.id })
        }
    }
    private lateinit var backupContext:android.content.Context
    private class FakeAuth:DriveAuth {
        var revoked=false; var expired=false
        override suspend fun selectAccount(activity:android.content.Context)="fixture@example.invalid"
        override suspend fun connect(email:String)=DriveAuthorization(null)
        override fun complete(intent:android.content.Intent) {}
        override suspend fun token(email:String):String { if(revoked) throw BackupAuthRequired(); return if(expired) "expired-test-token" else "test-token" }
        override suspend fun invalidate(token:String) { expired=false }
        override suspend fun signOut(email:String) { revoked=true }
    }
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private class FakeDrive:DriveStore {
        data class Entry(val id:String,val parent:String,val role:String,val hash:String,val bytes:ByteArray,val name:String)
        val entries=mutableListOf<Entry>(); var interrupted=false; var checkpoints=0; var resumes=0
        override suspend fun inventory()=entries.filter { it.role in listOf("root","asset","manifest") }.map { DriveResource(it.id,it.role,listOf(it.parent),it.role=="root") }
        override suspend fun metadata(id:String)=entries.firstOrNull { it.id==id }?.let { DriveResource(it.id,it.role,listOf(it.parent),it.role=="root") }
        override suspend fun children(parent:String)=entries.filter { it.parent==parent }.map { DriveResource(it.id,it.role,listOf(it.parent),it.role=="root") }
        var purgeFailure:String?=null; var failAfterOnePurge=false; var purged=0
        override suspend fun deleteOwned(resource:DriveResource):Boolean {
            if(failAfterOnePurge && purged==1) { failAfterOnePurge=false; throw IOException("Interrupted cloud purge") }
            if(purgeFailure==resource.id) { purgeFailure=null; throw IOException("Interrupted cloud purge") }
            val old=entries.firstOrNull { it.id==resource.id } ?: return false
            require(old.role==resource.role && old.role in listOf("root","asset","manifest"))
            if(resource.folder) require(entries.none { it.parent==old.id })
            entries.remove(old); purged++; return true
        }
        override suspend fun folder():String {
            if(entries.none { it.id=="root" }) entries+=Entry("root","","root","",byteArrayOf(),"Folio Backup")
            return "root"
        }
        override suspend fun exists(id:String)=entries.any { it.id==id }
        override suspend fun list(parent:String?,role:String,hash:String?,includeTrashed:Boolean)=entries.filter { (parent==null || it.parent==parent) && it.role==role && (hash==null || it.hash==hash) }.map { DriveFile(it.id,it.name,hash=it.hash) }
        override suspend fun upload(parent:String,name:String,role:String,hash:String,file:File,session:String?,checkpoint:suspend(String)->Unit):String {
            if(session!=null) resumes++ else { checkpoint("https://www.googleapis.com/upload/drive/v3/files?upload_id=fake"); checkpoints++ }
            if(interrupted) { interrupted=false; throw IOException("Simulated network loss") }
            val id="file_${UUID.randomUUID()}"; entries+=Entry(id,parent,role,hash,file.readBytes(),name); return id
        }
        override suspend fun download(id:String,target:File,limit:Long) { val entry=entries.first { it.id==id }; require(entry.bytes.size<=limit); target.parentFile!!.mkdirs(); target.writeBytes(entry.bytes) }
        var failDeletion=false
        override suspend fun replaceManifest(id:String,parent:String,oldHash:String,manifest:BackupManifest) {
            val index=entries.indexOfFirst { it.id==id }; if(index<0) return
            val old=entries[index]; require(old.parent==parent && old.role=="manifest" && sha256(old.bytes)==oldHash)
            val bytes=manifest.encode(); entries[index]=old.copy(hash=sha256(bytes),bytes=bytes)
        }
        override suspend fun deleteAsset(id:String,parent:String,hash:String) {
            if(failDeletion) { failDeletion=false; throw IOException("Offline deletion") }
            entries.firstOrNull { it.id==id }?.let { require(it.parent==parent && it.role=="asset"); entries.remove(it) }
        }
        fun latest():RestorePreview { val e=entries.last { it.role=="manifest" }; return RestorePreview(e.id,sha256(e.bytes),BackupManifest.decode(e.bytes),e.parent) }
    }
    @Test fun legacyLocalDataCompletedRestoreAndConfiguredAutomaticBackupMigrateSafely()=runBlocking {
        fixture { db,docs,_,_ ->
            val prefs=backupContext.getSharedPreferences("drive-backup",0)
            val safety=backupContext.getSharedPreferences("drive-backup-safety",0)
            val pipeline=ImagePipeline(context)
            suspend fun migrated():FolioBackupRepository {
                safety.edit().clear().commit()
                return FolioBackupRepository(backupContext,db,docs,FakeAuth(),pipeline).also { it.initializeSafety() }
            }
            try {
                prefs.edit().clear().putString("email","fixture@example.invalid").commit()
                assertFalse(migrated().state.value.backupUnlocked) // Prior sign-in alone is not upload consent.
                prefs.edit().clear().putInt("backupDataVersion",2).commit()
                assertTrue(migrated().state.value.backupUnlocked) // Earlier explicit manual upload consent.
                prefs.edit().clear().commit(); val local=docs.create("Legacy local library")
                assertTrue(migrated().state.value.backupUnlocked); docs.purgeForTest(local)
                db.documents().save(BackupRecord("restore:${UUID.randomUUID()}","","",1,1,"complete"))
                assertTrue(migrated().state.value.backupUnlocked)
                db.clearAllTables()
                prefs.edit().putString("email","fixture@example.invalid").putBoolean("automatic",true).putInt("backupDataVersion",2).commit()
                val configured=migrated(); assertTrue(configured.state.value.backupUnlocked); assertTrue(configured.state.value.automatic)
                val epoch=UUID.randomUUID().toString()
                prefs.edit().putString("epoch",epoch).putInt("backupDataVersion",1).commit()
                val oldConsent=migrated(); assertTrue(oldConsent.state.value.backupUnlocked)
                assertNull(oldConsent.automaticOperation(epoch)) // Preserve the existing extracted-text upload consent barrier.
            } finally { pipeline.close() }
        }
    }
    @Test fun freshInstallationCannotUploadUntilNonemptyRestoreCompletesAndUnlockSurvivesRestart()=runBlocking {
        fixture { db,docs,old,drive ->
            val id=docs.create("Existing cloud library")
            docs.importImage(id,ByteArrayInputStream(image(Color.WHITE,300,400)),detectDocument=false)
            old.execute(old.prepare(false),drive)
            val preview=drive.latest(); val cloud=drive.entries.map { it.id to sha256(it.bytes) }
            val queued=old.prepare(false)
            docs.purgeForTest(id); db.clearAllTables()
            backupContext.getSharedPreferences("drive-backup",0).edit().clear().commit()
            backupContext.getSharedPreferences("drive-backup-safety",0).edit().clear().commit()
            val pipeline=ImagePipeline(context)
            fun repository()=FolioBackupRepository(backupContext,db,docs,FakeAuth(),pipeline)
            val fresh=repository()
            try {
                fresh.initializeSafety(); assertFalse(fresh.state.value.backupUnlocked)
                fresh.connected("fixture@example.invalid")
                assertFalse(fresh.state.value.automatic); assertFalse(fresh.state.value.backupUnlocked)
                try { fresh.backUpNow(); fail("Fresh installation uploaded") } catch(_:IllegalStateException) {}
                try { fresh.automatic(true); fail("Fresh automatic backup enabled") } catch(_:IllegalStateException) {}
                assertNull(fresh.automaticOperation(backupContext.getSharedPreferences("drive-backup",0).getString("epoch","")!!))
                val request=File(context.filesDir,"drive-jobs/$queued/request.json")
                val json=org.json.JSONObject(request.readText()).put("epoch",backupContext.getSharedPreferences("drive-backup",0).getString("epoch",""))
                request.writeText(json.toString())
                try { fresh.execute(queued,drive); fail("Queued worker bypassed safety") } catch(_:IllegalStateException) {}
                assertEquals(cloud,drive.entries.map { it.id to sha256(it.bytes) })
                val empty=BackupManifest(java.util.UUID.randomUUID().toString(),1,emptyList(),emptyList(),emptyList(),emptyList())
                val emptyId="empty-cloud-manifest"
                drive.entries+=FakeDrive.Entry(emptyId,preview.rootId,"manifest",sha256(empty.encode()),empty.encode(),"empty")
                fresh.execute(fresh.prepare(false,RestorePreview(emptyId,sha256(empty.encode()),empty,preview.rootId)),drive)
                assertFalse(fresh.state.value.backupUnlocked)
                val cancelled=fresh.prepare(false,preview)
                try { fresh.execute(cancelled,drive) { _,_ -> throw CancellationException("User cancelled") }; fail("Cancellation ignored") } catch(_:CancellationException) {}
                assertFalse(fresh.state.value.backupUnlocked); assertTrue(db.documents().allDocuments().isEmpty())
                for(error in listOf<Exception>(IOException("Offline"),BackupAuthRequired())) {
                    val failing=object:DriveStore by drive { override suspend fun download(id:String,target:File,limit:Long) { throw error } }
                    try { fresh.execute(fresh.prepare(false,preview),failing); fail("Failed download completed") } catch(actual:Exception) { assertEquals(error,actual) }
                    assertFalse(fresh.state.value.backupUnlocked); assertFalse(fresh.state.value.automatic)
                }
                val bad=preview.copy(checksum="0".repeat(64))
                try { fresh.execute(fresh.prepare(false,bad),drive); fail("Corrupt restore accepted") } catch(_:IllegalArgumentException) {}
                assertFalse(repository().state.value.backupUnlocked)
                // Local content added after the initial lock cannot bypass the persisted requirement.
                val local=docs.create("New local document")
                fresh.refresh(); assertFalse(fresh.state.value.backupUnlocked); docs.purgeForTest(local)
                val restore=fresh.prepare(false,preview)
                try { fresh.execute(restore,drive) { done,total -> if(done==total) throw CancellationException("Death after local commit") }; fail("Expected interruption") } catch(_:CancellationException) {}
                assertFalse(fresh.state.value.backupUnlocked)
                assertEquals(1,db.documents().allDocuments().size)
                val resumed=repository(); resumed.execute(restore,drive)
                assertTrue(resumed.state.value.backupUnlocked); assertFalse(resumed.state.value.automatic)
                assertTrue(repository().state.value.backupUnlocked)
                resumed.disconnect(); assertTrue(resumed.state.value.backupUnlocked)
                resumed.connected("fixture@example.invalid"); assertTrue(resumed.state.value.backupUnlocked)
                resumed.execute(resumed.prepare(false),drive)
                assertEquals(1,drive.latest().manifest.pages.size)
            } finally { pipeline.close() }
        }
    }

    private suspend fun fixture(block:suspend(FolioDatabase,DocumentRepository,FolioBackupRepository,FakeDrive)->Unit) {
        val name="drive-test-${UUID.randomUUID()}"; val db=Room.databaseBuilder(context,FolioDatabase::class.java,name).addCallback(dev.folio.scanner.di.StorageModule.ocrCallback).build()
        val pipeline=ImagePipeline(context); val docs=DocumentRepository(db,context,pipeline)
        backupContext=object:android.content.ContextWrapper(context) {
            override fun getSharedPreferences(key:String,mode:Int)=context.getSharedPreferences("$name-$key",mode)
        }
        val prefs=backupContext.getSharedPreferences("drive-backup",0); prefs.edit().clear().putLong("lastBackup",1).commit()
        val backup=FolioBackupRepository(backupContext,db,docs,FakeAuth(),pipeline)
        try { backup.connected("fixture@example.invalid"); block(db,docs,backup,FakeDrive()) }
        finally {
            WorkManager.getInstance(context).cancelAllWorkByTag(FolioBackupRepository.TAG).result.get()
            WorkManager.getInstance(context).cancelAllWorkByTag(FolioBackupRepository.REMOVAL_TAG).result.get()
            db.documents().allDocuments().forEach { docs.purgeForTest(it.id) }
            File(context.filesDir,"drive-jobs").listFiles()?.filter { File(it,"request.json").takeIf { f -> f.exists() }?.readText()?.contains("fixture@example.invalid")==true }?.forEach { it.deleteRecursively() }
            prefs.edit().clear().commit(); pipeline.close(); db.close(); context.deleteDatabase(name)
        }
    }
    private fun image(color:Int=Color.WHITE,width:Int=1200,height:Int=1600):ByteArray {
        val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        return try { ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG,95,it); it.toByteArray() } } finally { bitmap.recycle() }
    }
    @Test fun incrementalBackupSkipsUnchangedAssetsAndUpdatesManifestOnlyForRenameAndOrder()=runBlocking {
        fixture { db,docs,backup,drive ->
            val doc=docs.create("Backup fixture"); val page=docs.importImage(doc,ByteArrayInputStream(image(Color.RED)),detectDocument=false)
            docs.duplicatePage(page); backup.refresh(); assertEquals(1,backup.state.value.pending)
            backup.execute(backup.prepare(false),drive)
            val assets=drive.entries.count { it.role=="asset" }; assertTrue(assets>0)
            assertEquals("Up to date",backup.state.value.status); assertEquals(0,backup.state.value.pending)
            docs.rename(doc,"Renamed fixture"); val ordered=db.documents().pages(doc).reversed().map { it.id }; docs.reorder(doc,ordered)
            backup.execute(backup.prepare(false),drive)
            assertEquals(assets,drive.entries.count { it.role=="asset" }); assertEquals("Renamed fixture",drive.latest().manifest.documents.single().title)
            assertEquals(ordered,drive.latest().manifest.pages.map { it.page.id })
            docs.edit(page,enhancement=Enhancement("Grayscale"))
            backup.execute(backup.prepare(false),drive); assertTrue(drive.entries.count { it.role=="asset" }>assets)
        }
    }
    @Test fun interruptedUploadResumesEncryptedSessionAfterRepositoryAndDatabaseReopen()=runBlocking {
        fixture { db,docs,backup,drive ->
            val id=docs.create("Interrupted fixture"); docs.importImage(id,ByteArrayInputStream(image()),detectDocument=false)
            val operation=backup.prepare(false); drive.interrupted=true
            try { backup.execute(operation,drive); fail("Expected simulated loss") } catch(_:IOException) {}
            assertEquals(1,drive.checkpoints)
            val records=db.openHelper.readableDatabase.query("SELECT session FROM backup_records WHERE state='uploading'")
            records.use { assertTrue(it.moveToFirst()); assertFalse(it.getString(0).contains("googleapis")) }
            val reopened=Room.databaseBuilder(context,FolioDatabase::class.java,requireNotNull(db.openHelper.databaseName)).build()
            val pipeline=ImagePipeline(context)
            try { val recreated=FolioBackupRepository(backupContext,reopened,DocumentRepository(reopened,context,pipeline),FakeAuth(),pipeline)
                recreated.execute(operation,drive); assertTrue(drive.resumes>0); assertEquals("Up to date",recreated.state.value.status)
            } finally { reopened.close(); pipeline.close() }
        }
    }
    @Test fun restoreKeepsConflictsPreservesOriginalsEditsTrashAndIsIdempotentAfterPublication()=runBlocking {
        fixture { db,docs,backup,drive ->
            docs.createFolder("Family fixture"); val folder=db.documents().allFolders().single().id
            val id=docs.create("Restore fixture",folder); val bytes=image(Color.YELLOW)
            val page=docs.importImage(id,ByteArrayInputStream(bytes),detectDocument=false)
            docs.edit(page,enhancement=Enhancement("Grayscale"),rotation=90); docs.delete(id)
            val before=db.documents().document(id)!!; val pages=db.documents().pages(id)
            backup.execute(backup.prepare(false),drive); val preview=drive.latest(); val operation=backup.prepare(false,preview)
            try { backup.execute(operation,drive) { done,total -> if(done==total) throw CancellationException("Death after commit") }; fail("Expected interruption") } catch(_:CancellationException) {}
            assertEquals(2,db.documents().allDocuments().size)
            backup.execute(operation,drive); assertEquals(2,db.documents().allDocuments().size)
            assertEquals(before,db.documents().document(id)); assertEquals(pages,db.documents().pages(id))
            val restored=db.documents().allDocuments().first { it.id!=id }; assertEquals(before.title,restored.title); assertEquals(before.trashedAt,restored.trashedAt)
            val p=db.documents().pages(restored.id).single(); assertEquals(pages.single().enhancement,p.enhancement); assertEquals(90,p.rotation)
            assertArrayEquals(bytes,File(p.originalImageUri).readBytes()); assertEquals(hashFile(File(pages.single().processedImageUri)),hashFile(File(p.processedImageUri)))
            assertTrue(File(p.thumbnailUri).length()>0)
        }
    }
    @Test fun corruptionRejectsEntireRestoreAndAccountChangeCancelsOldOperation()=runBlocking {
        fixture { db,docs,backup,drive ->
            val id=docs.create("Corruption fixture"); docs.importImage(id,ByteArrayInputStream(image()),detectDocument=false)
            backup.execute(backup.prepare(false),drive); val preview=drive.latest()
            val asset=drive.entries.indexOfFirst { it.role=="asset" }; val old=drive.entries[asset]; val corrupt=old.bytes.copyOf().apply { this[0]=(this[0].toInt() xor 1).toByte() }; drive.entries[asset]=old.copy(bytes=corrupt)
            try { backup.execute(backup.prepare(false,preview),drive); fail("Accepted corrupt asset") } catch(_:IllegalArgumentException) {}
            assertEquals(1,db.documents().allDocuments().size)
            val operation=backup.prepare(false); backup.connected("other@example.invalid")
            try { backup.execute(operation,drive); fail("Old account operation proceeded") } catch(_:CancellationException) {}
            assertEquals(1,db.documents().allDocuments().size)
        }
    }
    @Test fun snapshotKeepsOldImageReadableAfterEditingDeletesOldProcessedVersion()=runBlocking {
        fixture { db,docs,_,_ ->
            val id=docs.create("Pin fixture"); val page=docs.importImage(id,ByteArrayInputStream(image(Color.RED)),detectDocument=false)
            val dir=File(context.cacheDir,"drive-pin-${UUID.randomUUID()}")
            try {
                val (_,pinned)=docs.pinBackup(dir); val originalHash=hashFile(File(pinned.pages.single().processedImageUri))
                docs.edit(page,enhancement=Enhancement("Grayscale"))
                assertEquals(originalHash,hashFile(File(pinned.pages.single().processedImageUri)))
                assertNotEquals(originalHash,hashFile(File(db.documents().page(page)!!.processedImageUri)))
            } finally { dir.deleteRecursively() }
        }
    }
    @Test fun cleanRestorePreservesIdsAndDisconnectKeepsBothLocalAndRemoteDocuments()=runBlocking {
        fixture { db,docs,backup,drive ->
            val id=docs.create("Clean restore fixture"); val first=docs.importImage(id,ByteArrayInputStream(image(Color.BLUE)),detectDocument=false); docs.duplicatePage(first)
            // An interrupted local import may still render the EXIF-bearing retained original.
            val initial=db.documents().page(first)!!; db.documents().save(initial.copy(processedImageUri=initial.originalImageUri))
            val original=db.documents().document(id)!!; val pages=db.documents().pages(id)
            backup.execute(backup.prepare(false),drive); val preview=drive.latest(); docs.purgeForTest(id)
            db.clearAllTables() // Simulate a fresh device, rather than undoing a permanent deletion on this device.
            val restore=backup.prepare(false,preview)
            val request=File(context.filesDir,"drive-jobs/$restore/request.json")
            assertTrue(request.renameTo(File(request.path+".bak"))) // Legacy AtomicFile interruption, Android 8+.
            backup.execute(restore,drive)
            assertEquals(original,db.documents().document(id)); assertEquals(pages.map { it.id },db.documents().pages(id).map { it.id })
            val restored=db.documents().pages(id)
            assertNotEquals(restored[0].originalImageUri,restored[1].originalImageUri)
            assertNotEquals(restored[0].processedImageUri,restored[1].processedImageUri)
            assertEquals(restored[0].originalImageUri,restored[0].processedImageUri)
            docs.deletePages(id,setOf(restored[0].id)); docs.permanentlyDeletePages(setOf(restored[0].id))
            val surviving=db.documents().pages(id).single(); assertTrue(File(surviving.originalImageUri).isFile); assertTrue(File(surviving.processedImageUri).isFile)
            val afterDelete=db.documents().document(id)!!
            val remoteCount=drive.entries.size; backup.disconnect()
            assertEquals("Not connected",backup.state.value.status); assertFalse(backup.state.value.automatic)
            assertEquals(afterDelete,db.documents().document(id)); assertEquals(remoteCount,drive.entries.size)
        }
    }
    @Test fun damagedStagingCannotPublishAndNewBackupRetainsOriginalData()=runBlocking {
        fixture { db,docs,backup,drive ->
            val bytes=image(Color.CYAN); val id=docs.create("Staging integrity fixture")
            docs.importImage(id,ByteArrayInputStream(bytes),detectDocument=false)
            val operation=backup.prepare(false); drive.interrupted=true
            try { backup.execute(operation,drive); fail("Expected network loss") } catch(_:IOException) {}
            val file=File(context.filesDir,"drive-jobs/$operation/assets").listFiles()!!.first()
            val corrupted=file.readBytes().apply { this[0]=(this[0].toInt() xor 1).toByte() }; file.writeBytes(corrupted)
            try { backup.execute(operation,drive); fail("Accepted damaged staging") } catch(_:IllegalArgumentException) {}
            assertTrue(drive.entries.none { it.role=="manifest" })
            assertEquals(sha256(bytes),hashFile(File(db.documents().pages(id).single().originalImageUri)))
            backup.execute(backup.prepare(false),drive)
            assertEquals(sha256(bytes),drive.latest().manifest.pages.single().original)
        }
    }
    @Test fun explicitManualRequestReplacesPreviouslyQueuedOperation()=runBlocking {
        fixture { _,_,backup,_ ->
            val work=WorkManager.getInstance(context)
            val previous=androidx.work.OneTimeWorkRequestBuilder<BackupWorker>().setInitialDelay(1,java.util.concurrent.TimeUnit.DAYS)
                .addTag(FolioBackupRepository.TAG).build()
            work.enqueueUniqueWork("folio-drive-operation",androidx.work.ExistingWorkPolicy.REPLACE,previous).result.get()
            val requested=backup.backUpNow()
            withTimeout(10000) { while(true) { val old=work.getWorkInfoById(previous.id).get()
                if(old==null || old.state==androidx.work.WorkInfo.State.CANCELLED) break; delay(50) } }
            withTimeout(10000) { while(work.getWorkInfoById(requested).get()==null) delay(50) }
            assertEquals(androidx.work.NetworkType.UNMETERED,work.getWorkInfoById(requested).get()!!.constraints.requiredNetworkType)
        }
    }
    @Test fun accountChangeDuringUploadPreventsOldManifestAndOldStatusPublication()=runBlocking {
        fixture { db,docs,backup,drive ->
            val id=docs.create("Account change fixture"); docs.importImage(id,ByteArrayInputStream(image()),detectDocument=false)
            val entered=CompletableDeferred<Unit>(); val release=CompletableDeferred<Unit>()
            val paused=object:DriveStore by drive {
                override suspend fun upload(parent:String,name:String,role:String,hash:String,file:File,session:String?,checkpoint:suspend(String)->Unit):String {
                    entered.complete(Unit); release.await(); return drive.upload(parent,name,role,hash,file,session,checkpoint)
                }
            }
            val task=async { try { backup.execute(backup.prepare(false),paused); false } catch(_:CancellationException) { true } }
            withTimeout(30000) { entered.await() }
            try { backup.connected("other@example.invalid") } finally { release.complete(Unit) }
            assertTrue(task.await()); assertTrue(drive.entries.none { it.role=="manifest" })
            assertEquals("other@example.invalid",backup.state.value.email); assertEquals(0L,backup.state.value.lastBackup)
            assertEquals("Backup paused",backup.state.value.status); assertEquals(1,db.documents().allDocuments().size)
        }
    }
    @Test fun largeOriginalIsRetainedExactlyAndLibraryWritesContinueDuringUpload()=runBlocking {
        fixture { db,docs,backup,drive ->
            val bytes=image(Color.GREEN,3000,4000); val id=docs.create("Large original fixture")
            docs.importImage(id,ByteArrayInputStream(bytes),detectDocument=false)
            val entered=CompletableDeferred<Unit>(); val release=CompletableDeferred<Unit>()
            val paused=object:DriveStore by drive {
                override suspend fun upload(parent:String,name:String,role:String,hash:String,file:File,session:String?,checkpoint:suspend(String)->Unit):String {
                    entered.complete(Unit); release.await(); return drive.upload(parent,name,role,hash,file,session,checkpoint)
                }
            }
            val operation=backup.prepare(false); val task=async { backup.execute(operation,paused) }
            try {
                withTimeout(60000) { entered.await() }
                withTimeout(5000) { docs.rename(id,"Changed during upload"); docs.create("Local while uploading") }
                assertEquals("Changed during upload",db.documents().document(id)!!.title)
            } finally { release.complete(Unit) }
            task.await()
            val manifest=drive.latest().manifest; val asset=manifest.assets.first { it.hash==sha256(bytes) }
            assertEquals(bytes.size.toLong(),asset.bytes); assertArrayEquals(bytes,drive.entries.first { it.id==asset.remoteId }.bytes)
            assertEquals("Large original fixture",manifest.documents.single().title)
            assertEquals(2,backup.state.value.pending)
            // A concurrent automatic worker can arrive after the completed snapshot has been cleaned.
            val count=drive.entries.size; backup.execute(operation,drive); assertEquals(count,drive.entries.size)
        }
    }
}
