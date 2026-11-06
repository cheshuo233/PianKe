package app.pianke.develop

import app.pianke.settings.Framing
import app.pianke.settings.GrainLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDateTime

class PendingStoreTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val dir get() = File(temp.root, "pending")

    private val shot = Shot(
        id = "1762400000000",
        filmId = "portra160",
        framing = Framing.SQUARE,
        dateStamp = false,
        grain = GrainLevel.HIGH,
        halation = false,
        capturedAt = LocalDateTime.of(2026, 11, 6, 9, 30, 15),
    )

    private fun captured(store: PendingStore, s: Shot = shot) {
        store.partFile(s.id).writeText("jpeg")
        store.commit(s)
    }

    @Test
    fun commitThenFreshInstanceRecoversTheSameShot() {
        captured(PendingStore(dir))
        assertEquals(listOf(shot), PendingStore(dir).recover())
    }

    @Test
    fun deleteLeavesNothingToRecover() {
        val store = PendingStore(dir)
        captured(store)
        store.delete(shot)
        assertTrue(PendingStore(dir).recover().isEmpty())
    }

    @Test
    fun recoverDeletesJpgWithoutMetaAndEveryPartFile() {
        val store = PendingStore(dir)
        File(dir, "1.jpg").writeText("x")
        File(dir, "2.jpg.part").writeText("x")
        File(dir, "3.meta.part").writeText("x")
        assertTrue(store.recover().isEmpty())
        assertEquals(emptyList<String>(), dir.list()!!.toList())
    }

    @Test
    fun badOrUnknownVersionMetaIsKeptAndSkipped() {
        val store = PendingStore(dir)
        File(dir, "1.jpg").writeText("x")
        File(dir, "1.meta").writeText("v=1\nfilm=a\ncapturedAt=garbage\n")
        File(dir, "2.jpg").writeText("x")
        File(dir, "2.meta").writeText("v=9\nfilm=a\n")
        assertTrue(store.recover().isEmpty())
        for (name in listOf("1.jpg", "1.meta", "2.jpg", "2.meta")) assertTrue(name, File(dir, name).exists())
        assertFalse(File(dir, "1.jpg.part").exists())
    }
}
