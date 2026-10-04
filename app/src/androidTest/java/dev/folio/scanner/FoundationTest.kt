package dev.folio.scanner

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.folio.scanner.data.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class FoundationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun createFavoriteSearchAndReopenDocument() {
        val title="Instrumentation receipt ${UUID.randomUUID().toString().take(8)}"
        val context=ApplicationProvider.getApplicationContext<android.content.Context>()
        val repo=dagger.hilt.android.EntryPointAccessors.fromApplication(context,dev.folio.scanner.pdf.PdfWorkerDependencies::class.java).pdfs().documents
        try {
        compose.onNodeWithText("New document", useUnmergedTree = true).performClick()
        compose.onNode(hasSetTextAction() and hasText("Document name")).performTextInput(title)
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty() && compose.onAllNodesWithContentDescription("Back").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithContentDescription("Actions for $title").performClick()
        compose.onNodeWithText("Favorite", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Favorites").performClick()
        compose.onNodeWithText(title).assertIsDisplayed()
        compose.onNode(hasSetTextAction()).performTextInput("not present")
        compose.onNodeWithText("No matches").assertIsDisplayed()
        compose.onNode(hasSetTextAction()).performTextClearance()
        compose.onNodeWithText(title).performClick()
        compose.onNodeWithText("Your document is saved. It has no pages yet.").assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText(title).assertIsDisplayed()
        } finally { runBlocking { repo.dao.allDocuments().firstOrNull { it.title==title }?.let { repo.purgeForTest(it.id) } } }
    }
    @Test fun repositoryOrderingDuplicationAndSafeDelete() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val database = Room.inMemoryDatabaseBuilder(context, FolioDatabase::class.java).build()
        try {
            val repo = DocumentRepository(database, context)
            val id = repo.create("Contract")
            val another = repo.create("Keep me")
            val originals = java.io.File(repo.directory(id), "originals").apply { mkdirs() }
            val file = java.io.File(originals, "page.jpg").apply { writeText("source") }
            val keep = java.io.File(repo.directory(another), "originals/keep.jpg").apply { parentFile!!.mkdirs(); writeText("keep") }
            val first = Page(UUID.randomUUID().toString(), id, 0, file.path, file.path, file.path, 10, 20)
            val second = first.copy(id = UUID.randomUUID().toString(), position = 1)
            repo.dao.save(first); repo.dao.save(second)
            repo.reorder(id, listOf(second.id, first.id))
            assertEquals(listOf(second.id, first.id), repo.dao.pages(id).map { it.id })
            try { repo.reorder(id, listOf(first.id, first.id)); fail("Accepted duplicate page") } catch (_: IllegalArgumentException) { }
            val copy = repo.duplicate(id)
            assertEquals(2, repo.dao.pages(copy).size)
            assertEquals("source", java.io.File(repo.dao.pages(copy)[0].originalImageUri).readText())
            repo.deletePages(id,setOf(first.id))
            assertEquals(second.id,repo.dao.pages(id).single().id)
            assertTrue("A surviving page's shared retained file must stay",file.isFile)
            repo.purgeForTest(id)
            assertFalse(repo.directory(id).exists())
            assertTrue(keep.exists())
            assertEquals(0, repo.dao.pages(id).size)
            repo.purgeForTest(copy); repo.purgeForTest(another)
            assertTrue(repo.documents.first().isEmpty())
        } finally { database.close() }
    }
}
