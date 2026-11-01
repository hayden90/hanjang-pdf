package com.bubigter.hanjangpdf

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bubigter.hanjangpdf.core.Recovery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** 8-3 판 3 LOW 2: 기록 폴더 이름이 겹치면 남의 폴더를 같이 쓰지 않고 mkdir이 참인 새 이름을 쓴다. */
@RunWith(AndroidJUnit4::class)
class NewDirTest {
    private val root = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "newdir_test")

    @Test fun newDirNeverReusesTakenOrActive() {
        root.deleteRecursively()
        val seen = ArrayList<File>()
        // 처음 고른 이름은 '이미 다른 덮어쓰기가 쓰는 중'(pick 거짓)으로 흉내 → 그 이름은 만들지도 표시를 풀지도 않는다
        var first: File? = null
        val unpicked = ArrayList<File>()
        val d = Recovery.newDir(root, "s", { f -> if (first == null) { first = f; false } else { seen.add(f); true } },
            { unpicked.add(it) })
        assertNotEquals(first, d)
        assertTrue(d.isDirectory)
        assertTrue("표시를 푼 것 없음", unpicked.isEmpty())
        // 이미 있는 폴더 이름이 나와도(mkdir 거짓) 다른 이름으로
        val made = (1..200).map { Recovery.newDir(root, "s") }
        assertEquals("모두 다른 폴더", made.size, made.map { it.name }.toSet().size)
        root.deleteRecursively()
    }
}
