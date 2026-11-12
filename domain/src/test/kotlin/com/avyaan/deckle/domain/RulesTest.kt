package com.avyaan.deckle.domain

import com.avyaan.deckle.domain.document.DefaultTitle
import com.avyaan.deckle.domain.document.SearchQuery
import com.avyaan.deckle.domain.document.SnippetParser
import com.avyaan.deckle.domain.document.TitleRules
import com.avyaan.deckle.domain.export.PageSizePolicy
import com.avyaan.deckle.domain.export.PdfLayout
import com.avyaan.deckle.domain.geometry.RectN
import com.avyaan.deckle.domain.ocr.OcrLine
import com.avyaan.deckle.domain.page.OrderIndexAllocator
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import java.time.ZoneId
import org.junit.jupiter.api.Test

class OrderIndexAllocatorTest {
    @Test fun `initial indices step by 1000`() {
        assertThat(OrderIndexAllocator.initial(3)).containsExactly(1000L, 2000L, 3000L).inOrder()
    }

    @Test fun `between picks the midpoint`() {
        assertThat(OrderIndexAllocator.between(1000, 2000)).isEqualTo(1500)
        assertThat(OrderIndexAllocator.between(null, 1000)).isEqualTo(500)
        assertThat(OrderIndexAllocator.between(3000, null)).isEqualTo(4000)
    }

    @Test fun `no gap asks for a renumber`() {
        assertThat(OrderIndexAllocator.between(1000, 1001)).isNull()
        assertThat(OrderIndexAllocator.allocate(1000, 1002, 2)).isEqualTo(OrderIndexAllocator.Allocation.Renumber)
    }

    @Test fun `allocate spreads several pages between neighbours`() {
        val a = OrderIndexAllocator.allocate(1000, 2000, 3) as OrderIndexAllocator.Allocation.Indices
        assertThat(a.values).containsExactly(1250L, 1500L, 1750L).inOrder()
    }

    @Test fun `move to the front of a list`() {
        assertThat(OrderIndexAllocator.forMove(listOf(1000, 2000), 0)).isEqualTo(500)
    }
}

class TextRulesTest {
    @Test fun `default title format`() {
        val t = DefaultTitle.at(Instant.parse("2026-11-11T09:05:00Z"), ZoneId.of("UTC"))
        assertThat(t).isEqualTo("Scan 2026-11-11 09:05")
    }

    @Test fun `fts query quotes and prefix-matches tokens`() {
        assertThat(SearchQuery.toFts("hello wor")).isEqualTo("\"hello*\" \"wor*\"")
    }

    @Test fun `fts query strips operators and syntax`() {
        assertThat(SearchQuery.toFts("a* OR \"b\" NOT (c)")).isEqualTo("\"a*\" \"b*\" \"c*\"")
        assertThat(SearchQuery.toFts("  * - ")).isNull()
    }

    @Test fun `like escapes wildcards`() {
        assertThat(SearchQuery.toLike("50%_off")).isEqualTo("50\\%\\_off")
    }

    @Test fun `snippet markers become highlight ranges`() {
        val s = SnippetParser.parse("…the \u0002lease\u0003 starts\non \u0002May\u0003")
        assertThat(s.text).isEqualTo("…the lease starts on May")
        assertThat(s.highlights.map { s.text.substring(it) }).containsExactly("lease", "May").inOrder()
    }

    @Test fun `title cleaning`() {
        assertThat(TitleRules.clean("  Lease   agreement ")).isEqualTo("Lease agreement")
        assertThat(TitleRules.clean("   ")).isNull()
        assertThat(TitleRules.fileName("a/b:c?")).isEqualTo("a_b_c_")
    }
}

class PdfLayoutTest {
    @Test fun `auto page follows image aspect with A4 long edge`() {
        val l = PdfLayout.page(1500, 3000, PageSizePolicy.AUTO)
        assertThat(l.pageHeight).isWithin(1e-3f).of(842f)
        assertThat(l.pageWidth).isWithin(1e-3f).of(421f)
        assertThat(l.imageX).isEqualTo(0f)
    }

    @Test fun `A4 letterboxes a wide image without stretching`() {
        val l = PdfLayout.page(3000, 1000, PageSizePolicy.A4) // landscape → 842 × 595
        assertThat(l.pageWidth).isEqualTo(842f)
        assertThat(l.pageHeight).isEqualTo(595f)
        assertThat(l.imageWidth / l.imageHeight).isWithin(1e-3f).of(3f)
        assertThat(l.imageY).isGreaterThan(0f)
        assertThat(l.imageX).isWithin(1e-3f).of(0f)
    }

    @Test fun `line y is flipped and font fits the box width`() {
        val layout = PdfLayout.page(1000, 1000, PageSizePolicy.AUTO) // 842 × 842
        val line = OcrLine("Hello", RectN(0.1f, 0.1f, 0.5f, 0.15f), 0.9f)
        // Pretend each character is 500/1000 em wide → "Hello" = 2500 units.
        val placed = PdfLayout.lines(layout, listOf(line)) { it.length * 500f }.single()
        assertThat(placed.x).isWithin(1e-2f).of(84.2f)
        assertThat(placed.baselineY).isWithin(1e-2f).of(842f - 0.15f * 842f)
        val drawnWidth = 2500f / 1000f * placed.fontSize
        assertThat(drawnWidth).isWithin(1e-2f).of(0.4f * 842f)
    }

    @Test fun `tiny lines and blank lines are skipped`() {
        val layout = PdfLayout.page(1000, 1000, PageSizePolicy.AUTO)
        val tiny = OcrLine("x", RectN(0f, 0f, 0.0001f, 0.0001f), 1f)
        val blank = OcrLine("  ", RectN(0f, 0f, 0.5f, 0.1f), 1f)
        assertThat(PdfLayout.lines(layout, listOf(tiny, blank)) { 500f }).isEmpty()
    }

    @Test fun `unencodable text (null width) is skipped`() {
        val layout = PdfLayout.page(1000, 1000, PageSizePolicy.AUTO)
        val l = OcrLine("₹", RectN(0f, 0f, 0.5f, 0.1f), 1f)
        assertThat(PdfLayout.lines(layout, listOf(l)) { null }).isEmpty()
    }
}
