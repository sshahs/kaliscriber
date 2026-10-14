package com.kaliscriber.core.speakers

import kotlin.random.Random

/**
 * Friendly random names ("Amber Falcon") for speakers the app cannot identify. They are easy to
 * tell apart in a transcript and obviously placeholders, so users know to rename them.
 */
object SpeakerNames {
    private val adjectives = listOf(
        "Amber", "Azure", "Coral", "Crimson", "Golden", "Indigo", "Jade", "Lilac", "Maple", "Misty",
        "Olive", "Onyx", "Pearl", "Rusty", "Saffron", "Scarlet", "Silver", "Teal", "Velvet", "Violet",
        "Copper", "Cobalt", "Ivory", "Mint", "Sandy", "Stormy", "Sunny", "Frosty", "Hazel", "Ruby",
    )
    private val animals = listOf(
        "Falcon", "Otter", "Heron", "Lynx", "Panda", "Fox", "Owl", "Badger", "Dolphin", "Koala",
        "Raven", "Tiger", "Wolf", "Hare", "Moose", "Puffin", "Seal", "Swan", "Gecko", "Bison",
        "Crane", "Ibis", "Jaguar", "Lemur", "Marten", "Narwhal", "Orca", "Quokka", "Robin", "Yak",
    )

    fun random(taken: Collection<String>, random: Random = Random.Default): String {
        val takenLower = taken.map { it.lowercase() }.toSet()
        repeat(200) {
            val name = "${adjectives.random(random)} ${animals.random(random)}"
            if (name.lowercase() !in takenLower) return name
        }
        var n = 2
        val base = "${adjectives.random(random)} ${animals.random(random)}"
        while ("$base $n".lowercase() in takenLower) n++
        return "$base $n"
    }
}
