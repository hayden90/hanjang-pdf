package com.bubigter.hanjangpdf.core

import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.PDFGraftMap
import com.artifex.mupdf.fitz.PDFObject
import com.artifex.mupdf.fitz.PDFPage

/*
 * 8-3 쪽 작업(화면 없음). PC판 pdfviewer/core/doc_ops.py와 그것이 부르는 PyMuPDF 2.0 함수
 * (Document.move_page·delete_pages·insert_pdf, Page.set_rotation)를 옮겼다. 모든 함수는 열린 문서를
 * 제자리에서 바꾸고, 사용자에게 보일 수 있는 오류는 DocOpError로 낸다. 되돌리기·실패 복구는 EditSession이 맡는다.
 *
 * PC와 일부러 다르게 한 것: PyMuPDF insert_pdf는 복사하는 동안 원본 문서의 주석·입력 칸에서 /P·/Popup을
 * 지운 채로 둔다(원본이 바뀜). 여기서는 같은 결과 파일이 나오도록 똑같이 지운 뒤 복사하고, 끝나면 원본 값을
 * 되돌려 놓는다(내보내기가 작업 문서를 바꾸지 않게). 라디오 단추 고치기(repair_flat_rbgs)는 옮기지 않았다.
 */
object PageOps {
    /** PC _check_indices: 중복을 없애고 정렬, 비었거나 범위 밖이면 오류. */
    fun checkIndices(count: Int, indices: Collection<Int>): List<Int> {
        val picked = indices.toSortedSet().toList()
        if (picked.isEmpty()) throw DocOpError("고른 쪽이 없습니다.")
        for (i in picked) if (i !in 0 until count) throw DocOpError("없는 쪽 번호입니다: ${i + 1}")
        return picked
    }

    /** PyMuPDF JM_norm_rotation: 90의 배수가 아니면 0, 0~359로. */
    private fun normRotation(r: Int): Int = if (r % 90 != 0) 0 else Math.floorMod(r, 360)

    /** PyMuPDF page.rotation(물려받은 /Rotate 포함). */
    fun rotation(doc: PDFDocument, page: Int): Int {
        val r = doc.findPage(page).getInheritable("Rotate")
        return if (r.isNumber) normRotation(r.asInteger()) else 0
    }

    /** PC rotate_pages: 90도 단위만, (지금 값 + degrees) % 360을 쪽 /Rotate에 쓴다. */
    fun rotate(doc: PDFDocument, indices: Collection<Int>, degrees: Int) {
        if (degrees % 90 != 0) throw DocOpError("회전은 90도 단위만 됩니다.")
        for (i in checkIndices(doc.countPages(), indices)) {
            val rot = normRotation(rotation(doc, i) + degrees)
            doc.findPage(i).put("Rotate", rot)
        }
    }

    /** PC delete_pages: 모든 쪽은 못 지움. 지운 쪽을 가리키는 목차·링크를 PyMuPDF처럼 정리하고 뒤에서부터 지운다. */
    fun delete(doc: PDFDocument, indices: Collection<Int>): Int {
        val picked = checkIndices(doc.countPages(), indices)
        if (picked.size >= doc.countPages()) throw DocOpError("모든 쪽을 지울 수는 없습니다. 한 쪽 이상 남겨 주세요.")
        val gone = picked.toSet()
        removeTocItems(doc, gone)
        removeLinksTo(doc, gone)
        for (i in picked.reversed()) doc.deletePage(i)
        return picked.size
    }

    /** PC move_order: 옮긴 뒤 쪽 순서와, 옮긴 묶음의 첫 쪽이 놓일 번호. [to]는 원래 번호 기준. */
    fun moveOrder(count: Int, picked: List<Int>, to: Int): Pair<List<Int>, Int> {
        if (to !in 0..count) throw DocOpError("옮길 위치가 잘못되었습니다: ${to + 1}")
        val set = picked.toSet()
        val rest = (0 until count).filter { it !in set }
        val pos = rest.count { it < to }
        return Pair(rest.take(pos) + picked + rest.drop(pos), pos)
    }

    fun isNoopMove(count: Int, indices: Collection<Int>, to: Int): Boolean {
        val picked = indices.toSortedSet().toList()
        if (picked.isEmpty() || to !in 0..count) return false
        return moveOrder(count, picked, to).first == (0 until count).toList()
    }

    /**
     * PC move_pages: 고른 쪽 묶음을 원래 번호 기준 [to] 쪽 앞으로(쪽 수면 맨 끝). 앞자리부터 하나씩 제자리로
     * 옮긴다(쪽 객체를 그대로 옮기므로 입력 서식 연결이 남는다). 옮긴 묶음 첫 쪽의 새 번호를 돌려준다.
     */
    fun move(doc: PDFDocument, indices: Collection<Int>, to: Int): Int {
        val (order, pos) = moveOrder(doc.countPages(), checkIndices(doc.countPages(), indices), to)
        val cur = (0 until doc.countPages()).toMutableList()
        for ((i, want) in order.withIndex()) {
            val j = cur.indexOf(want)
            if (j != i) { // j > i: 앞자리는 이미 맞춰 둠
                movePage(doc, j, i)
                cur.add(i, cur.removeAt(j))
            }
        }
        return pos
    }

    /** PyMuPDF move_page(j, i)(j > i, i 앞에 놓기): 같은 쪽 객체를 빼서 넣는다. */
    private fun movePage(doc: PDFDocument, from: Int, to: Int) {
        val ref = doc.findPage(from)
        doc.deletePage(from)
        doc.insertPage(to, ref)
    }

    /** PC insert_pdf: [src]의 모든 쪽을 [at](그 쪽 앞)에 넣고 넣은 쪽 수를 돌려준다. 암호는 부르는 쪽이 푼다. */
    fun insertPdf(doc: PDFDocument, src: PDFDocument, at: Int): Int {
        if (at !in 0..doc.countPages()) throw DocOpError("넣을 위치가 잘못되었습니다: ${at + 1}")
        val n = src.countPages()
        if (n == 0) throw DocOpError("넣을 PDF에 쪽이 없습니다.")
        merge(doc, src, (0 until n).toList(), at)
        return n
    }

    /** 넣을 PDF 파일을 연다(PC open_pdf_bytes와 같은 오류). 암호가 필요하면 [password]로 푼다. */
    fun openSource(path: String, password: String?): PDFDocument {
        val d = try {
            Document.openDocument(path)
        } catch (e: RuntimeException) {
            throw DocOpError("PDF를 열 수 없습니다: ${e.message}")
        }
        val pdf = d.asPDF()
        if (pdf == null) {
            d.destroy()
            throw DocOpError("PDF를 열 수 없습니다: PDF가 아닙니다.")
        }
        if (pdf.needsPassword() && (password.isNullOrEmpty() || !pdf.authenticatePassword(password))) {
            pdf.destroy()
            throw DocOpError("암호가 필요하거나 암호가 맞지 않습니다.")
        }
        return pdf
    }

    /**
     * PC export_pages: 고른 쪽을 문서 순서대로 새 PDF로 저장하고 쪽 수를 돌려준다. PC처럼 쪽마다 따로
     * insert_pdf를 부르는 꼴(쪽마다 새 복사 지도)이고, 저장은 [DocOps.atomicSave](검증 포함).
     */
    fun exportPages(doc: PDFDocument, indices: Collection<Int>, outPath: String): Int {
        val picked = checkIndices(doc.countPages(), indices)
        val out = PDFDocument()
        try {
            for (i in picked) merge(out, doc, listOf(i), out.countPages())
            DocOps.atomicSave(out, outPath)
            return picked.size
        } finally {
            out.destroy()
        }
    }

    // ---- insert_pdf 옮김: JM_merge_range(page_merge) → _do_links → _do_widgets ----

    private val PAGE_KEYS = listOf("Contents", "Resources", "MediaBox", "CropBox", "BleedBox", "TrimBox", "ArtBox", "Rotate", "UserUnit")

    /** 지운 키를 끝나면 되돌려 놓는 기록(원본 문서를 바꾸지 않기 위해). */
    private class Restore {
        private val saved = ArrayList<Triple<PDFObject, String, PDFObject>>()
        fun del(o: PDFObject, key: String) {
            val v = o.get(key)
            if (v.isNull) return
            saved.add(Triple(o, key, v))
            o.delete(key)
        }
        fun undo() {
            for ((o, k, v) in saved.asReversed()) o.put(k, v)
            saved.clear()
        }
    }

    /** [src]의 [pages](차례대로)를 [dst]의 [startAt]부터 넣는다. insert_pdf 한 번(복사 지도 하나)에 해당. */
    private fun merge(dst: PDFDocument, src: PDFDocument, pages: List<Int>, startAt: Int) {
        val gmap = dst.newPDFGraftMap()
        val restore = Restore()
        try {
            for ((k, p) in pages.withIndex()) pageMerge(dst, src, p, startAt + k, gmap, restore)
            doLinks(dst, src, pages, startAt)
            doWidgets(dst, src, gmap, pages, startAt, restore)
        } finally {
            restore.undo()
            gmap.destroy()
        }
    }

    /** PyMuPDF page_merge(rotate=-1, copy_annots=1). */
    private fun pageMerge(dst: PDFDocument, src: PDFDocument, from: Int, to: Int, gmap: PDFGraftMap, restore: Restore) {
        val pageRef = src.findPage(from)
        val dict = dst.newDictionary()
        dict.put("Type", dst.newName("Page"))
        for (key in PAGE_KEYS) {
            val o = pageRef.getInheritable(key)
            if (!o.isNull) dict.put(key, gmap.graftObject(o))
        }
        val old = pageRef.get("Annots")
        if (old.isArray && old.size() > 0) {
            val arr = dst.newArray()
            dict.put("Annots", arr)
            for (i in 0 until old.size()) {
                val o = old.get(i)
                if (!o.isDictionary) continue
                if (!o.get("IRT").isNull) continue
                val st = o.get("Subtype")
                if (st.isName && st.asName() in setOf("Link", "Popup", "Widget")) continue
                restore.del(o, "Popup")
                restore.del(o, "P")
                val copy = gmap.graftObject(o)
                arr.push(addRef(dst, copy)) // 직접 객체로 든 주석도 새 번호로
            }
        }
        val ref = addRef(dst, dict)
        dst.insertPage(to, ref)
    }

    /** PyMuPDF _do_links: 복사한 쪽의 링크를 다시 만든다(안쪽 이동은 복사한 쪽을 가리킬 때만). */
    private fun doLinks(dst: PDFDocument, src: PDFDocument, pages: List<Int>, startAt: Int) {
        for ((k, p) in pages.withIndex()) {
            val sp = src.loadPage(p) as PDFPage
            try {
                val links = sp.links ?: continue
                if (links.isEmpty()) continue
                val ictm = PMatrix.invert(pmat(sp.transform))
                val made = ArrayList<PDFObject>()
                for (l in links) {
                    val uri = l.uri ?: continue
                    val r = rectTimes(l.bounds, ictm)
                    if (l.isExternal) {
                        // PyMuPDF는 file: 링크를 GoToR·Launch로 다르게 만든다. 여기서는 웹 주소(URI)만 옮긴다.
                        if (uri.startsWith("file:", ignoreCase = true)) continue
                        val a = dst.newDictionary()
                        a.put("S", dst.newName("URI"))
                        a.put("URI", dst.newString(uri))
                        made.add(linkDict(dst, a, r))
                    } else {
                        val dest = try { src.resolveLinkDestination(uri) } catch (e: RuntimeException) { null } ?: continue
                        val tp = src.pageNumberFromLocation(dest)
                        val idx = pages.indexOf(tp)
                        if (idx < 0) continue // 복사하지 않은 쪽으로 가는 링크
                        val pt = ptTimes(dest.x, dest.y, ictm)
                        val d = dst.newArray()
                        d.push(dst.findPage(startAt + idx))
                        d.push(dst.newName("XYZ"))
                        d.push(pt[0]); d.push(pt[1]); d.push(if (dest.zoom.isNaN()) 0f else dest.zoom)
                        val a = dst.newDictionary()
                        a.put("S", dst.newName("GoTo"))
                        a.put("D", d)
                        made.add(linkDict(dst, a, r))
                    }
                }
                if (made.isEmpty()) continue
                val page = dst.findPage(startAt + k)
                var annots = page.get("Annots")
                if (!annots.isArray) { annots = dst.newArray(); page.put("Annots", annots) }
                for (m in made) annots.push(addRef(dst, m))
            } finally {
                sp.destroy()
            }
        }
    }

    /**
     * 새 객체로 넣고 간접 참조를 돌려준다. fitz Java의 addObject는 새 번호를 만들지만 참조가 아니라 값을 돌려주고,
     * 이미 간접 참조인 값(복사 지도의 결과)은 그대로 돌려준다. 새 번호는 맨 끝 번호라 표시 키로 맞는지 확인한다.
     */
    private fun addRef(doc: PDFDocument, obj: PDFObject): PDFObject {
        if (obj.isIndirect) return obj
        obj.put("HanjangTmp", true)
        doc.addObject(obj)
        val ref = doc.newIndirect(doc.countObjects() - 1, 0)
        val ok = ref.get("HanjangTmp").isBoolean
        obj.delete("HanjangTmp")
        if (!ok) throw IllegalStateException("새 객체 번호를 찾지 못함")
        return ref
    }

    private fun linkDict(doc: PDFDocument, action: PDFObject, r: FloatArray): PDFObject {
        val o = doc.newDictionary()
        o.put("A", action)
        val rect = doc.newArray()
        for (v in r) rect.push(v)
        o.put("Rect", rect)
        val bs = doc.newDictionary()
        bs.put("W", 0)
        o.put("BS", bs)
        o.put("Subtype", doc.newName("Link"))
        return o
    }

    /** PyMuPDF _do_widgets(join_duplicates=0). */
    private fun doWidgets(dst: PDFDocument, src: PDFDocument, gmap: PDFGraftMap, pages: List<Int>, startAt: Int, restore: Restore) {
        val srcAcro = src.trailer.get("Root").get("AcroForm")
        val srcFields = srcAcro.get("Fields")
        if (!srcFields.isArray || srcFields.size() == 0) return // is_form_pdf 아님
        val dstAcro0 = dst.trailer.get("Root").get("AcroForm")
        val dstFields0 = dstAcro0.get("Fields")
        val acroFields: PDFObject
        val co: PDFObject
        if (dstFields0.isArray && dstFields0.size() > 0) {
            acroFields = dstFields0
            var c = dstAcro0.get("CO")
            if (!c.isArray) { c = dst.newArray(); dstAcro0.put("CO", c) }
            co = c
        } else {
            // 원본 AcroForm을 Fields·CO 없이 복사해 붙인다(pdf_deep_copy_obj 뒤 두 키를 지우고 graft한 것과 같은 결과).
            // 원본에서 두 키를 잠시 빼고 복사한 뒤 되돌린다(Fields를 따라 칸·쪽까지 복사되지 않게).
            val tmp = Restore()
            val g = try {
                tmp.del(srcAcro, "Fields")
                tmp.del(srcAcro, "CO")
                gmap.graftObject(srcAcro.resolve())
            } finally {
                tmp.undo()
            }
            val tar = addRef(dst, g)
            tar.put("Fields", dst.newArray())
            tar.put("CO", dst.newArray())
            dst.trailer.get("Root").put("AcroForm", tar)
            acroFields = tar.get("Fields")
            co = tar.get("CO")
        }
        class Parent(val oldKids: List<Int>) { var newKids: List<Int> = emptyList() }
        val parents = LinkedHashMap<Int, Parent>()
        val widgetsOf = HashMap<Int, List<PDFObject>>()
        for (p in pages) {
            val ws = widgets(src, p)
            widgetsOf[p] = ws
            for (w in ws) {
                restore.del(w, "P")
                val par = w.get("Parent")
                val px = if (par.isIndirect) par.asIndirect() else 0
                if (px == 0) continue
                var kids = par.get("Kids")
                if (!kids.isArray) { kids = src.newArray(); par.put("Kids", kids) }
                if ((0 until kids.size()).none { kids.get(it).asIndirect() == w.asIndirect() }) kids.push(w)
                parents[px] = Parent(leafKids(par))
            }
        }
        for ((px, info) in parents) {
            // 간접 참조째 복사해야 자식 칸이 복사 지도에 올라 아래에서 같은 객체를 쓴다
            val tar = addRef(dst, gmap.graftObject(src.newIndirect(px, 0)))
            info.newKids = leafKids(tar)
            acroFields.push(tar)
        }
        for ((k, p) in pages.withIndex()) {
            val ws = widgetsOf[p] ?: continue
            if (ws.isEmpty()) continue
            val tp = dst.findPage(startAt + k)
            var tarAnnots = tp.get("Annots")
            if (!tarAnnots.isArray) { tarAnnots = dst.newArray(); tp.put("Annots", tarAnnots) }
            for (w in ws) {
                val isAac = w.get("AA").get("C").isDictionary
                val par = w.get("Parent")
                val px = if (par.isIndirect) par.asIndirect() else 0
                val ind: PDFObject
                if (px == 0) {
                    ind = try { addRef(dst, gmap.graftObject(w)) } catch (e: RuntimeException) { continue }
                    tarAnnots.push(ind)
                    acroFields.push(ind)
                } else {
                    val info = parents.getValue(px)
                    val idx = info.oldKids.indexOf(w.asIndirect())
                    ind = dst.newIndirect(info.newKids[idx], 0)
                    tarAnnots.push(ind)
                }
                if (isAac) co.push(ind)
            }
        }
        dedupNames(dst, acroFields)
    }

    /** 쪽의 입력 칸(Widget) 주석들(간접 참조). */
    private fun widgets(doc: PDFDocument, page: Int): List<PDFObject> {
        val annots = doc.findPage(page).get("Annots")
        if (!annots.isArray) return emptyList()
        return (0 until annots.size()).map { annots.get(it) }.filter {
            it.isIndirect && it.get("Subtype").let { s -> s.isName && s.asName() == "Widget" }
        }
    }

    /** PyMuPDF get_kids: 잎 칸 번호들. */
    private fun leafKids(parent: PDFObject, out: MutableList<Int> = ArrayList()): List<Int> {
        val kids = parent.get("Kids")
        if (!kids.isArray) return out
        for (i in 0 until kids.size()) {
            val kid = kids.get(i)
            if (kid.get("Kids").isDictionary) leafKids(kid, out) else out.add(kid.asIndirect())
        }
        return out
    }

    /** PyMuPDF deduplicate_names(join_duplicates=False): 같은 이름 둘째 칸 이름 뒤에 " [번호]". */
    private fun dedupNames(doc: PDFDocument, fields: PDFObject) {
        val names = LinkedHashMap<String, MutableList<Int>>()
        for (i in 0 until fields.size()) {
            val o = fields.get(i)
            val t = o.get("T")
            names.getOrPut(if (t.isString) t.asString() else "") { ArrayList() }.add(o.asIndirect())
        }
        for ((name, xs) in names) {
            if (xs.size < 2) continue
            doc.newIndirect(xs[1], 0).resolve().put("T", doc.newString("$name [${xs[1]}]"))
        }
        // clean_kid_parents
        for (i in 0 until fields.size()) {
            val parent = fields.get(i)
            val kids = parent.get("Kids")
            if (!kids.isArray) continue
            for (j in 0 until kids.size()) kids.get(j).put("Parent", parent)
        }
    }

    // ---- 지울 때 목차·링크 정리(PyMuPDF delete_pages) ----

    /** PyMuPDF _remove_toc_item: 지운 쪽을 가리키는 목차 항목을 '어디도 안 가리키게'(Dest·A 지우고 회색). */
    private fun removeTocItems(doc: PDFDocument, gone: Set<Int>) {
        val outline = try { doc.loadOutline() } catch (e: RuntimeException) { null } ?: return
        val pagesOf = ArrayList<Int>()
        fun walk(items: Array<com.artifex.mupdf.fitz.Outline>) {
            for (o in items) {
                pagesOf.add(try { doc.pageNumberFromLocation(doc.resolveLink(o)) } catch (e: RuntimeException) { -1 })
                o.down?.let { walk(it) }
            }
        }
        walk(outline)
        val objs = ArrayList<PDFObject>()
        fun walkObj(first: PDFObject) {
            var cur = first
            var guard = 0
            while (!cur.isNull && guard++ < 100_000) {
                objs.add(cur)
                val child = cur.get("First")
                if (!child.isNull) walkObj(child)
                cur = cur.get("Next")
            }
        }
        walkObj(doc.trailer.get("Root").get("Outlines").get("First"))
        for (i in 0 until minOf(objs.size, pagesOf.size)) {
            if (pagesOf[i] !in gone) continue
            val item = objs[i]
            item.delete("Dest")
            item.delete("A")
            val c = doc.newArray()
            repeat(3) { c.push(0.8f) }
            item.put("C", c)
        }
    }

    /** PyMuPDF _remove_dest_range: 남는 쪽의 링크 가운데 지운 쪽으로 가는 것을 뺀다. */
    private fun removeLinksTo(doc: PDFDocument, gone: Set<Int>) {
        val n = doc.countPages()
        val pageNo = HashMap<Int, Int>()
        for (i in 0 until n) pageNo[doc.findPage(i).asIndirect()] = i
        for (i in 0 until n) {
            if (i in gone) continue
            val annots = doc.findPage(i).get("Annots")
            if (!annots.isArray) continue
            for (j in annots.size() - 1 downTo 0) {
                val o = annots.get(j)
                val st = o.get("Subtype")
                if (!st.isName || st.asName() != "Link") continue
                val action = o.get("A")
                var dest = o.get("Dest")
                if (!action.isNull) {
                    val s = action.get("S")
                    if (!s.isName || s.asName() != "GoTo") continue
                    dest = action.get("D")
                }
                val target = when {
                    dest.isArray -> dest.get(0).let { if (it.isIndirect) pageNo[it.asIndirect()] ?: -1 else -1 }
                    dest.isString -> try { doc.pageNumberFromLocation(doc.resolveLink(dest.asString())) } catch (e: RuntimeException) { -1 }
                    else -> -1
                }
                if (target in gone) annots.delete(j)
            }
        }
    }

    // ---- 좌표 ----

    private fun pmat(m: com.artifex.mupdf.fitz.Matrix) = PMatrix(m.a, m.b, m.c, m.d, m.e, m.f)

    private fun ptTimes(x: Float, y: Float, m: PMatrix) =
        floatArrayOf(x * m.a + y * m.c + m.e, x * m.b + y * m.d + m.f)

    private fun rectTimes(r: com.artifex.mupdf.fitz.Rect, m: PMatrix): FloatArray {
        val ps = listOf(ptTimes(r.x0, r.y0, m), ptTimes(r.x1, r.y0, m), ptTimes(r.x0, r.y1, m), ptTimes(r.x1, r.y1, m))
        return floatArrayOf(ps.minOf { it[0] }, ps.minOf { it[1] }, ps.maxOf { it[0] }, ps.maxOf { it[1] })
    }

}
