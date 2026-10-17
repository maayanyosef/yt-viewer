package local.youtubetv.ui

import local.youtubetv.data.StoredSource

/**
 * Every string the rename screen puts on the television, and the three rules behind them.
 *
 * It is kept apart from the Composable for the same reason `ui/AddSourceText.kt` is: every
 * wording rule and every fallback is then a JVM unit test with no device and no quota.
 *
 * Build ticket 27. Spec section 7.1 holds the behaviour.
 *
 * **One thing in this file is not put on the television**, and it is here because it is the
 * rename screen's other pure rule: [RENAME_FIELD], which is what the text field is called to
 * that screen's own highlight record. See `ui/HighlightRecord.kt`.
 */

/** The heading. It names what the screen does to the Source, not the Source. */
const val RENAME_TITLE = "Rename this Source"

/** What the empty field says. It is a hint and never a value: an empty field clears the name. */
const val RENAME_FIELD_HINT = "Type a name for this Source"

/**
 * What the rename screen's text field is called to that screen's own highlight record.
 *
 * **It is never drawn**, for the same reason [ADD_FIELD] is not: a record names each of its
 * nodes by something stable, and a text field has no words of its own to be named by.
 * [RENAME_FIELD_HINT] is gone the moment the household types a character, and the field starts
 * with the Source's stored name already in it more often than not.
 *
 * It is a different string from [ADD_FIELD] although the two fields never share a record,
 * because a name that reads correctly on its own is worth more than a shared constant here.
 * Build ticket 48.
 */
const val RENAME_FIELD = "the rename screen's text field"

/**
 * The button that writes what is in the field.
 *
 * The household rarely reaches it, and that is by design: the television's own keyboard covers
 * the bottom of this screen while it is up, and its action key writes the name without it. The
 * button is the way back for a household that dismissed the keyboard. The same shape as the add
 * screen's `Find it`, which `AddSourceScreen` explains.
 */
const val RENAME_SAVE = "Save this name"

/**
 * The button that clears the typed name, so the name YouTube has comes back.
 *
 * **It exists because emptying a field is a slow thing to do with a remote.** Clearing by hand
 * means holding backspace over every character of a name the household may have typed in
 * Hebrew, and the two of the household's four Sources that are named in Hebrew are the ones
 * most likely to be renamed. Emptying the field and saving does exactly the same thing, and
 * this is one press.
 *
 * It says what the household gets rather than what goes away, because "clear" alone would read
 * as leaving the card with no name at all, which is the outcome the owner rejected on 2026-09-20.
 */
const val RENAME_CLEAR = "Use the name YouTube gives it"

/** The label in front of the name the card will end up showing. See [renamePreviewName]. */
const val RENAME_PREVIEW_LABEL = "The card will show"

/**
 * What goes in the field when the screen opens.
 *
 * **The raw playlist id is never put in the field**, although it is what such a card is headed
 * by. It is not a name; it is what `sourceNameOrId` falls back to when the app has none, and
 * putting it in an editing field would make the household delete twenty-four characters before
 * they could type. They get the hint instead.
 *
 * **[sourceId] is a parameter for that rule and nothing else, and it is not a belt.**
 * `AddSourceModel.add` stores `candidate.title.ifBlank { candidate.playlistId }`, so a Source
 * YouTube answered about with a blank title really does hold its own playlist id in
 * `sources.title`. Without this test the field would open pre-filled with those twenty-four
 * characters, which is exactly the outcome the paragraph above forbids. Found by
 * `/code-review`.
 *
 * Otherwise it is the name the card is showing, so a household correcting one character of a
 * name types one character. The typed name wins over the fetched one, which is the same order
 * [sourceNameOrId] applies.
 */
fun renameFieldStart(typedName: String?, fetchedName: String?, sourceId: String): String =
    typedName?.takeIf { it.isNotBlank() }
        ?: fetchedName?.takeIf { it.isNotBlank() && it != sourceId }
        ?: ""

/**
 * What the card will be headed by if what is in the field is saved right now.
 *
 * **It is the whole point of the screen's quiet line**, because the interesting case is the
 * empty field: an empty field does not leave the card blank, it gives the card back the name
 * YouTube has, and there is nothing else on this screen that would say so.
 *
 * It goes through [sourceNameOrId], so it cannot disagree with what the rail will actually
 * draw, and through [StoredSource.typedNameOfInput], so a field holding nothing but spaces
 * previews the same thing it will store.
 */
fun renamePreviewName(typed: String, fetchedName: String?, sourceId: String): String =
    sourceNameOrId(StoredSource.typedNameOfInput(typed), fetchedName, sourceId)

/**
 * Whether [RENAME_CLEAR] is live, or drawn dim and walked past by the D-pad.
 *
 * It is live only when there is a stored typed name for it to take away. On a Source the
 * household has never renamed it would do nothing at all, and a line that takes focus and does
 * nothing is worse than a dim one: that is the rule `sourceMenuLines` already follows.
 *
 * It reads what is **stored**, not what is in the field. The field is an editing buffer that
 * the household can empty and refill, and a button appearing and vanishing under their hands as
 * they type would be a button whose place could never be learnt.
 */
fun renameClearEnabled(storedTypedName: String?): Boolean =
    !storedTypedName.isNullOrBlank()

/**
 * One logcat line per rename, saying what was written and what the card will now say.
 *
 * **The two are not the same thing and that is why both are here.** Writing a blank clears the
 * column and the card then falls back to the name YouTube has, so a line reporting only what
 * was written would say nothing about what the household is about to see. It is the same reason
 * `railLogLine` prints the rail's order rather than the rows that produced it.
 *
 * @param written what went into `sources.typed_name`, or null when it was cleared.
 * @param shown what the card is headed by now, read back from the Source's own row.
 */
fun renameLogLine(sourceId: String, written: String?, shown: String): String =
    if (written == null) {
        "Renamed $sourceId: the typed name is cleared, so its card shows $shown again."
    } else {
        "Renamed $sourceId to $written. Its card shows $shown."
    }

/** What the log says when a rename found no Source to rename. */
fun renameRefusedLine(sourceId: String): String =
    "Rename refused: $sourceId has no stored row, so there is no name to write."
