/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messagesearch.impl

/**
 * Words shorter than this are searched exactly. A prefix expands to every indexed term that starts
 * with it, so widening a two-letter word would sweep much of the vocabulary for nothing.
 */
private const val MIN_PREFIX_LENGTH = 3

/**
 * A stem shorter than this is not trusted. Three letters still point at the word that was typed,
 * while two point at half the dictionary: "оса" must not become "ос".
 */
private const val MIN_STEM_LENGTH = 3

/**
 * Characters that mean the user is addressing the index's query language rather than typing words.
 * Any of them and the query is passed through exactly as written.
 */
private const val QUERY_SYNTAX_CHARACTERS = "\"*:+-~^()[]{}\\"

/**
 * Russian inflectional endings, longest first so that the longest match wins.
 *
 * Only endings that inflect a word are listed: case and number for nouns and adjectives, person
 * and tense for verbs, plus the reflexive particle. Derivational suffixes, the ones that build a
 * new word rather than a form of the same one, are deliberately absent — cutting those would make
 * "работа" and "работник" collapse into one search.
 */
private val RUSSIAN_INFLECTIONAL_ENDINGS = listOf(
    // Reflexive particle.
    "ться", "тся", "ся", "сь",
    // Verbs: infinitive, person, and past tense in both genders and numbers.
    "ать", "ять", "еть", "ить", "уть", "ыть",
    "ешь", "ишь", "ете", "ите", "ают", "яют", "уют", "юют",
    "али", "яли", "ели", "или", "ыли", "ула", "ули",
    "ала", "яла", "ила", "ела", "ыла", "ило", "ало",
    // A bare "л" is deliberately absent. It would cut the past tense of a verb, but it also ends
    // a great many nouns — "узел", "стол", "отдел" — and turning those into three-letter stems
    // costs far more than the masculine past tense is worth. Its other forms are covered above.
    "ем", "им", "ут", "ют", "ат", "ят", "ла", "ло", "ли",
    // Nouns and adjectives: case and number.
    "иями", "ями", "ами", "иях", "ях", "ах", "ов", "ев", "ей", "ой",
    "ый", "ий", "ая", "яя", "ое", "ее", "ые", "ие",
    "ому", "ему", "ых", "их", "ым", "им", "ую", "юю", "ию", "ью",
    "ья", "ье", "ом", "ам", "ям",
    "а", "я", "ы", "и", "е", "о", "у", "ю", "ь", "й",
)
    // Longest first, so that "приехали" loses "али" rather than just "ли" and lands on the same
    // stem as "приехать". Sorting here rather than by hand keeps the lists above readable and
    // stops a future addition from silently landing in the wrong place.
    .sortedByDescending { it.length }

private val CYRILLIC = 'а'..'я'

/**
 * Widens a typed query so that other forms of the same word match.
 *
 * The index tokenises on word boundaries and lowercases, and nothing else: no stemmer, no n-grams.
 * Every inflected form is therefore its own token, which is unusable in an inflected language —
 * "деньгами" finds only the messages that spell it exactly that way.
 *
 * So each word is cut back to its stem and asked for as a prefix. "деньгами" becomes "деньг*" and
 * reaches "деньги", "деньгам" and "деньгах" alike. This is not a real morphological analyser: it
 * cannot follow a stem that changes shape, so "денег" stays out of reach of "деньги". It handles
 * the regular suffix inflection that makes up the bulk of the language, which is the difference
 * between a search that feels broken and one that feels ordinary.
 *
 * Words that are already short are widened without being cut, since there is no ending to remove
 * and the prefix alone already reaches the longer forms: "день" reaches "деньги".
 *
 * Queries carrying any of [QUERY_SYNTAX_CHARACTERS] are left untouched. A quoted phrase, a negated
 * word or an explicit wildcard is a deliberate instruction, and widening it would overrule it.
 *
 * @return the widened query, or the original when there is nothing worth widening.
 */
internal fun expandQueryForPrefixMatching(query: String): String {
    if (query.any { it in QUERY_SYNTAX_CHARACTERS }) return query
    var widened = false
    val result = query.split(' ').joinToString(" ") { word ->
        if (word.length < MIN_PREFIX_LENGTH) {
            word
        } else {
            widened = true
            "${stemForPrefix(word)}*"
        }
    }
    return if (widened) result else query
}

/**
 * Cuts a single word back to the stem that its other forms share.
 *
 * Non-Cyrillic words are returned unchanged: English inflects by appending, so the prefix that the
 * caller adds already reaches "cats" from "cat", and cutting would only lose precision.
 */
private fun stemForPrefix(word: String): String {
    val lowercase = word.lowercase()
    if (lowercase.none { it in CYRILLIC || it == 'ё' }) return word
    val ending = RUSSIAN_INFLECTIONAL_ENDINGS.firstOrNull { ending ->
        lowercase.endsWith(ending) && lowercase.length - ending.length >= MIN_STEM_LENGTH
    }
    return if (ending == null) word else word.dropLast(ending.length)
}
