package com.avyaan.deckle.domain

import com.avyaan.deckle.domain.document.LibraryFilter
import com.avyaan.deckle.domain.document.ObserveLibraryUseCase
import com.avyaan.deckle.domain.document.PinUseCase
import com.avyaan.deckle.domain.document.SmartCollection
import com.avyaan.deckle.domain.fakes.FakeClock
import com.avyaan.deckle.domain.fakes.FakeDocumentRepository
import com.avyaan.deckle.domain.fakes.FakeFolderRepository
import com.avyaan.deckle.domain.fakes.FakePageRepository
import com.avyaan.deckle.domain.fakes.FakeSettingsRepository
import com.avyaan.deckle.domain.fakes.FakeTagRepository
import com.avyaan.deckle.domain.fakes.SequentialIds
import com.avyaan.deckle.domain.folder.Folder
import com.avyaan.deckle.domain.geometry.Quad
import com.avyaan.deckle.domain.geometry.RectN
import com.avyaan.deckle.domain.geometry.Rotation
import com.avyaan.deckle.domain.imaging.Adjustments
import com.avyaan.deckle.domain.imaging.EnhancementMode
import com.avyaan.deckle.domain.markup.MarkupGeometry
import com.avyaan.deckle.domain.markup.MarkupItem
import com.avyaan.deckle.domain.markup.PageMarkup
import com.avyaan.deckle.domain.markup.Redaction
import com.avyaan.deckle.domain.markup.SaveMarkupUseCase
import com.avyaan.deckle.domain.markup.SignatureInk
import com.avyaan.deckle.domain.ocr.OcrLine
import com.avyaan.deckle.domain.ocr.OcrState
import com.avyaan.deckle.domain.page.Page
import com.avyaan.deckle.domain.reminder.CheckExpiryRemindersUseCase
import com.avyaan.deckle.domain.reminder.ExpiryPolicy
import com.avyaan.deckle.domain.reminder.ReminderNotifier
import com.avyaan.deckle.domain.reminder.SetExpiryUseCase
import com.avyaan.deckle.domain.tag.CreateTagUseCase
import com.avyaan.deckle.domain.tag.EditTagUseCase
import com.avyaan.deckle.domain.tag.TagColor
import com.avyaan.deckle.domain.tag.TagDocumentsUseCase
import com.google.common.truth.Truth.assertThat
import java.time.Duration
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class OrganizeAndMarkupTest {
    private val clock = FakeClock()          // 2026-11-11T10:00Z, zone UTC
    private val pages = FakePageRepository()
    private val docs = FakeDocumentRepository(pages)
    private val folders = FakeFolderRepository(docs)
    private val tags = FakeTagRepository(docs)
    private val library = ObserveLibraryUseCase(docs, folders, FakeSettingsRepository(), clock)

    private suspend fun doc(id: String, ageDays: Long = 0, folder: String? = null) =
        docs.create(id, "Doc $id", clock.instant.minus(Duration.ofDays(ageDays)), folder)

    private fun page(id: String, doc: String, markup: PageMarkup? = null) = Page(
        id, doc, 0, null, "/b/$id", "/p/$id", "/t/$id", Quad.FULL, Rotation.R0, EnhancementMode.COLOUR, Adjustments.NEUTRAL,
        1000, 1400, OcrState.QUEUED, markup = markup,
    )

    // ---- NV-03 pin ----

    @Test
    fun `pinned documents and folders come first, keeping the sort inside each group`() = runTest {
        doc("old", ageDays = 5); doc("mid", ageDays = 3); doc("new", ageDays = 1)
        folders.create(Folder("fa", "Alpha", null, clock.instant, clock.instant))
        folders.create(Folder("fz", "Zulu", null, clock.instant, clock.instant))
        PinUseCase(docs, folders)(listOf("old", "mid"), listOf("fz"), pinned = true)

        val listing = library("", null).first()
        assertThat(listing.documents.map { it.id }).containsExactly("mid", "old", "new").inOrder()
        assertThat(listing.folders.map { it.folder.id }).containsExactly("fz", "fa").inOrder()

        PinUseCase(docs, folders)(listOf("old"), emptyList(), pinned = false)
        assertThat(library("", null).first().documents.map { it.id }).containsExactly("mid", "new", "old").inOrder()
    }

    // ---- NV-30 tags ----

    @Test
    fun `a tag name is created once whatever its case, and each new tag gets an unused colour`() = runTest {
        val create = CreateTagUseCase(tags, SequentialIds("t"), clock)
        val bills = create("  Bills ")!!
        assertThat(bills.name).isEqualTo("Bills")
        assertThat(create("bills")).isEqualTo(bills)
        val ids = create("IDs")!!
        assertThat(ids.color).isNotEqualTo(bills.color)
        assertThat(create("   ")).isNull()
        assertThat(tags.all()).hasSize(2)
    }

    @Test
    fun `renaming a tag to another tag's name is refused`() = runTest {
        val create = CreateTagUseCase(tags, SequentialIds("t"), clock)
        val a = create("Bills")!!
        create("Medical")
        val edit = EditTagUseCase(tags)
        assertThat(edit(a.id, "medical", TagColor.SKY)).isFalse()
        assertThat(edit(a.id, "Utilities", TagColor.SKY)).isTrue()
        assertThat(tags.all().first { it.id == a.id }.color).isEqualTo(TagColor.SKY)
    }

    @Test
    fun `a tag filter finds tagged documents in every folder and hides folders`() = runTest {
        folders.create(Folder("f", "Home", null, clock.instant, clock.instant))
        doc("root"); doc("inFolder", folder = "f"); doc("untagged")
        val tag = CreateTagUseCase(tags, SequentialIds("t"), clock)("Bills")!!
        TagDocumentsUseCase(tags)(listOf("root", "inFolder"), tag.id)

        val listing = library("", null, LibraryFilter(tagId = tag.id)).first()
        assertThat(listing.documents.map { it.id }).containsExactly("root", "inFolder")
        assertThat(listing.folders).isEmpty()

        tags.delete(tag.id)
        assertThat(library("", null, LibraryFilter(tagId = tag.id)).first().documents).isEmpty()
        assertThat(docs.get("root")).isNotNull()          // deleting a tag never deletes documents
    }

    // ---- NV-31 smart collections ----

    @Test
    fun `smart collections fill themselves`() = runTest {
        doc("thisMonth", ageDays = 2)
        doc("lastMonth", ageDays = 40)
        doc("signed", ageDays = 60)
        pages.insert(listOf(page("p", "signed", PageMarkup(listOf(signature())))))
        docs.setPinned(listOf("lastMonth"), true)

        suspend fun ids(c: SmartCollection) = library("", null, LibraryFilter(collection = c)).first().documents.map { it.id }
        assertThat(ids(SmartCollection.THIS_MONTH)).containsExactly("thisMonth")
        assertThat(ids(SmartCollection.SIGNED)).containsExactly("signed")
        assertThat(ids(SmartCollection.PINNED)).containsExactly("lastMonth")
    }

    @Test
    fun `expiring soon lists documents within 90 days, soonest first, expired ones included`() = runTest {
        doc("far"); doc("soon"); doc("sooner"); doc("expired"); doc("none")
        val set = SetExpiryUseCase(docs, clock)
        val today = LocalDate.of(2026, 11, 11)
        set("far", today.plusDays(200))
        set("soon", today.plusDays(60))
        set("sooner", today.plusDays(10))
        set("expired", today.minusDays(3))

        val listing = library("", null, LibraryFilter(collection = SmartCollection.EXPIRING)).first()
        assertThat(listing.documents.map { it.id }).containsExactly("expired", "sooner", "soon").inOrder()
    }

    // ---- NV-32 reminders ----

    private class RecordingNotifier : ReminderNotifier {
        val sent = mutableListOf<Pair<String, Long>>()
        override fun notifyExpiry(documentId: String, title: String, daysLeft: Long) { sent += documentId to daysLeft }
    }

    @Test
    fun `each reminder stage fires once, and not for the stage the date was set in`() = runTest {
        doc("passport")
        val notifier = RecordingNotifier()
        val check = CheckExpiryRemindersUseCase(docs, notifier, clock)
        SetExpiryUseCase(docs, clock)("passport", LocalDate.of(2026, 12, 1))     // 20 days away: inside the 30-day stage

        assertThat(check()).isEqualTo(0)                                          // just set: no reminder now
        clock.instant = clock.instant.plus(Duration.ofDays(13))                   // 7 days left
        assertThat(check()).isEqualTo(1)
        assertThat(check()).isEqualTo(0)                                          // a second daily run doesn't repeat it
        clock.instant = clock.instant.plus(Duration.ofDays(7))                    // the day itself
        assertThat(check()).isEqualTo(1)
        clock.instant = clock.instant.plus(Duration.ofDays(5))                    // after: nothing more
        assertThat(check()).isEqualTo(0)
        assertThat(notifier.sent).containsExactly("passport" to 7L, "passport" to 0L).inOrder()
    }

    @Test
    fun `a late check sends only the latest stage, not every missed one`() {
        assertThat(ExpiryPolicy.due(daysLeft = 5, lastNotified = 90)).isEqualTo(7)
        assertThat(ExpiryPolicy.due(daysLeft = 100, lastNotified = null)).isNull()
        assertThat(ExpiryPolicy.due(daysLeft = 90, lastNotified = null)).isEqualTo(90)
        assertThat(ExpiryPolicy.due(daysLeft = -2, lastNotified = 7)).isEqualTo(0)
        assertThat(ExpiryPolicy.due(daysLeft = -2, lastNotified = 0)).isNull()
    }

    @Test
    fun `clearing the expiry stops reminders`() = runTest {
        doc("ins")
        val set = SetExpiryUseCase(docs, clock)
        set("ins", LocalDate.of(2027, 6, 1))
        set("ins", null)
        assertThat(docs.get("ins")!!.expiresAt).isNull()
        assertThat(docs.withExpiry()).isEmpty()
    }

    // ---- NV-40…44 markup ----

    private fun signature() = MarkupItem.Signature("s", SignatureInk(listOf(listOf(0f, 0f, 1f, 0.2f)), 0.3f), 0.5f, 0.8f, 0.3f)

    @Test
    fun `markup round-trips as JSON with a type field the library query can look for`() {
        val markup = PageMarkup(
            listOf(
                MarkupItem.Ink("i", listOf(0.1f, 0.1f, 0.2f, 0.2f), MarkupItem.INK_BLUE, 0.004f),
                MarkupItem.Text("t", 0.1f, 0.5f, "Jai Sharma", 0.03f, MarkupItem.INK_BLACK),
                signature(),
                MarkupItem.Redaction("r", 0.2f, 0.3f, 0.6f, 0.35f),
            ),
        )
        val json = PageMarkup.encode(markup)!!
        assertThat(json).contains("\"type\":\"signature\"")                  // RoomRepositories' "signed" query
        assertThat(PageMarkup.decode(json)).isEqualTo(markup)
        assertThat(PageMarkup.encode(PageMarkup.EMPTY)).isNull()
        assertThat(PageMarkup.decode("{not json")).isNull()
    }

    @Test
    fun `a drawn signature is normalised to its own box, and an empty one is rejected`() {
        val ink = SignatureInk.from(listOf(listOf(100f, 50f, 300f, 50f), listOf(150f, 30f, 150f, 90f)))!!
        assertThat(ink.strokes[0]).comparingElementsUsing(com.google.common.truth.Correspondence.tolerance(1e-5)).containsExactly(0.0, 0.1, 1.0, 0.1).inOrder()
        assertThat(ink.aspect).isWithin(1e-4f).of(0.3f)
        assertThat(SignatureInk.from(emptyList())).isNull()
        assertThat(SignatureInk.from(listOf(listOf(5f, 5f)))).isNull()
    }

    @Test
    fun `redaction removes every OCR line it touches from the export text layer`() {
        val lines = listOf(
            OcrLine("Name: Jai", RectN(0.1f, 0.10f, 0.5f, 0.13f), 1f),
            OcrLine("PAN ABCDE1234F", RectN(0.1f, 0.20f, 0.6f, 0.23f), 1f),
            OcrLine("Signature", RectN(0.1f, 0.80f, 0.4f, 0.83f), 1f),
        )
        val markup = PageMarkup(listOf(MarkupItem.Redaction("r", 0.3f, 0.19f, 0.7f, 0.24f)))
        assertThat(Redaction.visibleLines(lines, markup).map { it.text }).containsExactly("Name: Jai", "Signature")
        assertThat(Redaction.visibleLines(lines, null)).isEqualTo(lines)
    }

    @Test
    fun `moving and pinching a signature`() {
        val s = signature()
        val moved = MarkupGeometry.translate(s, 0.1f, -0.2f) as MarkupItem.Signature
        assertThat(moved.cx).isWithin(1e-5f).of(0.6f)
        assertThat(moved.cy).isWithin(1e-5f).of(0.6f)
        val turned = MarkupGeometry.transform(s, zoom = 2f, rotationDegrees = 15f) as MarkupItem.Signature
        assertThat(turned.width).isWithin(1e-5f).of(0.6f)
        assertThat(turned.rotation).isEqualTo(15f)
        assertThat((MarkupGeometry.transform(s, zoom = 0.01f, rotationDegrees = 0f) as MarkupItem.Signature).width)
            .isEqualTo(MarkupGeometry.MIN_SIGNATURE_WIDTH)
        // Unrotated, the box is width × width·aspect, centred.
        val box = MarkupGeometry.bounds(s, pageAspect = 1f) { 0f to 0f }
        assertThat(box.width).isWithin(1e-5f).of(0.3f)
        assertThat(box.height).isWithin(1e-5f).of(0.09f)
    }

    @Test
    fun `saving markup stores it and marks the document as modified`() = runTest {
        val d = doc("d", ageDays = 3)
        pages.insert(listOf(page("p", "d")))
        clock.instant = clock.instant.plus(Duration.ofHours(1))
        SaveMarkupUseCase(pages, docs, clock)("p", PageMarkup(listOf(signature())))
        assertThat(pages.get("p")!!.markup!!.signed).isTrue()
        assertThat(docs.get("d")!!.modifiedAt).isGreaterThan(d.modifiedAt)

        SaveMarkupUseCase(pages, docs, clock)("p", PageMarkup.EMPTY)
        assertThat(pages.get("p")!!.markup).isNull()
    }

}
