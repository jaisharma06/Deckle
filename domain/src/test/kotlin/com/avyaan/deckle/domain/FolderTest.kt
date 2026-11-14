package com.avyaan.deckle.domain

import com.avyaan.deckle.domain.document.ObserveLibraryUseCase
import com.avyaan.deckle.domain.folder.BuildFolderExportUseCase
import com.avyaan.deckle.domain.folder.CreateFolderUseCase
import com.avyaan.deckle.domain.folder.DeleteFoldersUseCase
import com.avyaan.deckle.domain.folder.FolderTree
import com.avyaan.deckle.domain.folder.MoveToFolderUseCase
import com.avyaan.deckle.domain.folder.RenameFolderUseCase
import com.avyaan.deckle.domain.fakes.FakeClock
import com.avyaan.deckle.domain.fakes.FakeDocumentRepository
import com.avyaan.deckle.domain.fakes.FakeFolderRepository
import com.avyaan.deckle.domain.fakes.FakePageRepository
import com.avyaan.deckle.domain.fakes.FakeSettingsRepository
import com.avyaan.deckle.domain.fakes.FakeTransactionRunner
import com.avyaan.deckle.domain.fakes.SequentialIds
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class FolderTest {
    private val clock = FakeClock()
    private val docs = FakeDocumentRepository(FakePageRepository())
    private val folders = FakeFolderRepository(docs)
    private val tx = FakeTransactionRunner()
    private val create = CreateFolderUseCase(folders, SequentialIds("f"), clock)
    private val move = MoveToFolderUseCase(folders, docs, tx, clock)
    private val delete = DeleteFoldersUseCase(folders, docs, tx, clock)

    private suspend fun doc(id: String, folder: String?) = docs.create(id, "Doc $id", clock.now(), folder)

    @Test fun `creating next to a same-named folder adds a suffix, blank names are refused`() = runTest {
        create(null, "Taxes")
        val second = create(null, " taxes ")!!
        assertThat(folders.get(second)!!.name).isEqualTo("taxes (2)")
        assertThat(create(null, "   ")).isNull()
        // Same name in a different parent is fine.
        val child = create(second, "Taxes")!!
        assertThat(folders.get(child)!!.name).isEqualTo("Taxes")
    }

    @Test fun `renaming applies the sibling rule`() = runTest {
        create(null, "A")
        val b = create(null, "B")!!
        val rename = RenameFolderUseCase(folders, clock)
        assertThat(rename(b, "A")).isTrue()
        assertThat(folders.get(b)!!.name).isEqualTo("A (2)")
        assertThat(rename(b, "")).isFalse()
    }

    @Test fun `a folder cannot move into itself or below itself`() = runTest {
        val a = create(null, "A")!!
        val b = create(a, "B")!!
        assertThat(move(emptyList(), listOf(a), b)).isFalse()
        assertThat(move(emptyList(), listOf(a), a)).isFalse()
        assertThat(folders.get(a)!!.parentId).isNull()
        val c = create(null, "C")!!
        assertThat(move(emptyList(), listOf(b), c)).isTrue()
        assertThat(folders.get(b)!!.parentId).isEqualTo(c)
    }

    @Test fun `moving documents into a folder and back to the root`() = runTest {
        val a = create(null, "A")!!
        doc("d1", null); doc("d2", null)
        assertThat(move(listOf("d1", "d2"), emptyList(), a)).isTrue()
        assertThat(docs.liveIdsIn(listOf(a))).containsExactly("d1", "d2")
        move(listOf("d1"), emptyList(), null)
        assertThat(docs.get("d1")!!.folderId).isNull()
        assertThat(move(listOf("d2"), emptyList(), "missing")).isFalse()
    }

    @Test fun `delete keeping contents moves documents and sub-folders up to the parent`() = runTest {
        val a = create(null, "A")!!
        val b = create(a, "B")!!
        val c = create(b, "C")!!
        doc("inB", b); doc("inC", c)
        delete(listOf(b), keepContents = true)
        assertThat(folders.get(b)).isNull()
        assertThat(docs.get("inB")!!.folderId).isEqualTo(a)
        assertThat(folders.get(c)!!.parentId).isEqualTo(a)
        assertThat(docs.get("inC")!!.folderId).isEqualTo(c)
    }

    @Test fun `delete keeping contents of nested selections hands everything to the surviving ancestor`() = runTest {
        val a = create(null, "A")!!
        val b = create(a, "B")!!
        doc("inB", b)
        delete(listOf(b, a), keepContents = true)
        assertThat(folders.all()).isEmpty()
        assertThat(docs.get("inB")!!.folderId).isNull()
        assertThat(docs.get("inB")!!.deletedAt).isNull()
    }

    @Test fun `delete with contents trashes every document below and removes the sub-tree`() = runTest {
        val a = create(null, "A")!!
        val b = create(a, "B")!!
        val other = create(null, "Other")!!
        doc("inA", a); doc("inB", b); doc("elsewhere", other)
        delete(listOf(a), keepContents = false)
        assertThat(folders.all().map { it.id }).containsExactly(other)
        assertThat(docs.get("inA")!!.deletedAt).isNotNull()
        assertThat(docs.get("inB")!!.deletedAt).isNotNull()
        // Restored documents come back at the root, never inside a folder that is gone.
        assertThat(docs.get("inB")!!.folderId).isNull()
        assertThat(docs.get("elsewhere")!!.deletedAt).isNull()
    }

    @Test fun `folder export lists every document with its sub-folder path`() = runTest {
        val a = create(null, "Taxes")!!
        val b = create(a, "2025")!!
        val c = create(b, "Q1")!!
        doc("top", a); doc("mid", b); doc("deep", c)
        docs.softDelete(listOf("mid"), clock.now())
        val export = BuildFolderExportUseCase(folders, docs)(a)!!
        assertThat(export.documentIds).containsExactly("top", "deep")
        assertThat(export.archive.name).isEqualTo("Taxes")
        assertThat(export.archive.directories).containsExactly("deep", "2025/Q1")
        val empty = create(null, "Empty")!!
        assertThat(BuildFolderExportUseCase(folders, docs)(empty)).isNull()
    }

    @Test fun `library level shows its own folders and documents with the breadcrumb path`() = runTest {
        val a = create(null, "A")!!
        val b = create(a, "B")!!
        doc("root", null); doc("inA", a); doc("inB", b)
        val observe = ObserveLibraryUseCase(docs, folders, FakeSettingsRepository(), com.avyaan.deckle.domain.fakes.FakeClock())
        val root = observe("", null).first()
        assertThat(root.folders.map { it.folder.id }).containsExactly(a)
        assertThat(root.folders.single().documentCount).isEqualTo(1)
        assertThat(root.folders.single().folderCount).isEqualTo(1)
        assertThat(root.documents.map { it.id }).containsExactly("root")
        val inA = observe("", a).first()
        assertThat(inA.path.map { it.name }).containsExactly("A")
        assertThat(inA.documents.map { it.id }).containsExactly("inA")
        // Search looks everywhere and also finds folders by name.
        val search = observe("b", null).first()
        assertThat(search.folders.map { it.folder.id }).containsExactly(b)
        assertThat(FolderTree.path(b, folders.all()).map { it.name }).containsExactly("A", "B").inOrder()
    }
}
