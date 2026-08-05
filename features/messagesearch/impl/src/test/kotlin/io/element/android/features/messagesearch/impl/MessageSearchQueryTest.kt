/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messagesearch.impl

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MessageSearchQueryTest {
    @Test
    fun `an inflected noun is cut back to the stem its other forms share`() {
        // The whole point: what is typed is rarely the form that was written.
        assertThat(expandQueryForPrefixMatching("деньгами")).isEqualTo("деньг*")
        assertThat(expandQueryForPrefixMatching("деньги")).isEqualTo("деньг*")
        assertThat(expandQueryForPrefixMatching("деньгах")).isEqualTo("деньг*")
    }

    @Test
    fun `every form of a word reaches the same query`() {
        val forms = listOf("работа", "работы", "работе", "работу", "работой", "работах")
        val expanded = forms.map { expandQueryForPrefixMatching(it) }.distinct()
        assertThat(expanded).containsExactly("работ*")
    }

    @Test
    fun `a verb is cut back past its person and tense`() {
        assertThat(expandQueryForPrefixMatching("приехать")).isEqualTo("приех*")
        assertThat(expandQueryForPrefixMatching("приехали")).isEqualTo("приех*")
    }

    @Test
    fun `a reflexive particle is cut before anything inside it can match`() {
        assertThat(expandQueryForPrefixMatching("вернуться")).isEqualTo("верну*")
    }

    @Test
    fun `a short word is cut too, which is what reaches a stem that changes shape`() {
        // "день" -> "ден*" is the one cut that also reaches "денег", where the stem itself changes.
        assertThat(expandQueryForPrefixMatching("день")).isEqualTo("ден*")
    }

    @Test
    fun `a stem is never cut down to something that points at half the dictionary`() {
        // Cutting these would leave two letters, which matches most of the vocabulary.
        assertThat(expandQueryForPrefixMatching("оса")).isEqualTo("оса*")
        assertThat(expandQueryForPrefixMatching("узел")).isEqualTo("узел*")
    }

    @Test
    fun `a derived word is not collapsed into the word it came from`() {
        // Cutting derivational suffixes would make these one search; they are different words.
        assertThat(expandQueryForPrefixMatching("работник")).isEqualTo("работник*")
    }

    @Test
    fun `an english word is widened but not cut`() {
        // English inflects by appending, so the prefix alone already reaches "meetings".
        assertThat(expandQueryForPrefixMatching("meeting")).isEqualTo("meeting*")
        assertThat(expandQueryForPrefixMatching("cat")).isEqualTo("cat*")
    }

    @Test
    fun `every word of a phrase is widened`() {
        assertThat(expandQueryForPrefixMatching("деньги москва")).isEqualTo("деньг* москв*")
    }

    @Test
    fun `words too short to widen are left exact`() {
        assertThat(expandQueryForPrefixMatching("он")).isEqualTo("он")
        assertThat(expandQueryForPrefixMatching("он дома")).isEqualTo("он дом*")
    }

    @Test
    fun `a quoted phrase is left exactly as written`() {
        assertThat(expandQueryForPrefixMatching("\"на самом деле\"")).isEqualTo("\"на самом деле\"")
    }

    @Test
    fun `an explicit wildcard is not widened again`() {
        assertThat(expandQueryForPrefixMatching("ден*")).isEqualTo("ден*")
    }

    @Test
    fun `a negated or field scoped query is left alone`() {
        assertThat(expandQueryForPrefixMatching("деньги -налоги")).isEqualTo("деньги -налоги")
        assertThat(expandQueryForPrefixMatching("sender:alice")).isEqualTo("sender:alice")
    }

    @Test
    fun `a blank query stays blank`() {
        assertThat(expandQueryForPrefixMatching("")).isEqualTo("")
        assertThat(expandQueryForPrefixMatching("  ")).isEqualTo("  ")
    }
}
