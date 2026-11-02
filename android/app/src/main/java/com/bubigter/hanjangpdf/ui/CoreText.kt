package com.bubigter.hanjangpdf.ui

import android.content.Context
import android.content.res.Configuration
import com.bubigter.hanjangpdf.R
import java.util.Locale

/**
 * core(화면 없는 부분)가 던지는 한국어 알림 글을 화면 언어로 바꾼다(8-5 한국어·영어).
 * core는 PC판 시험과 같은 한국어 글을 그대로 쓰고, 화면에 띄우기 직전에 여기서 values/의 한국어 틀(core_*)로
 * 알아본 뒤 지금 언어의 같은 이름 글로 바꾼다. 틀의 %1$s 자리(안쪽 오류 글 등)도 같은 방법으로 바꾼다.
 * 모든 한국어 글이 틀을 가졌는지는 PC 시험(tests/android_compat/test_android_i18n.py)이 본다.
 */
object CoreText {
    /** 틀 이름들. R8이 R 필드를 지울 수 있어 반사(reflection) 대신 목록으로 둔다(시험이 strings.xml의 core_*와 맞춰 봄). */
    internal val IDS = intArrayOf(
        R.string.core_too_long,
        R.string.core_no_page,
        R.string.core_hl_drag,
        R.string.core_hl_color,
        R.string.core_note_empty,
        R.string.core_note_inside,
        R.string.core_not_own,
        R.string.core_note_empty_erase,
        R.string.core_not_note,
        R.string.core_erase_drag,
        R.string.core_erase_none,
        R.string.core_text_empty,
        R.string.core_text_size,
        R.string.core_align,
        R.string.core_color,
        R.string.core_text_inside,
        R.string.core_text_drag,
        R.string.core_not_text,
        R.string.core_box_invisible,
        R.string.core_width,
        R.string.core_opacity,
        R.string.core_box_drag,
        R.string.core_not_box,
        R.string.core_font_diff,
        R.string.core_font_swap,
        R.string.core_cant_open,
        R.string.core_not_pdf,
        R.string.core_tmp_not_pdf,
        R.string.core_saved_pages,
        R.string.core_swap_file,
        R.string.core_save_failed,
        R.string.core_no_space,
        R.string.core_broken,
        R.string.core_snapshot,
        R.string.core_edit_failed,
        R.string.core_history,
        R.string.core_rollback,
        R.string.core_copy_not_pdf,
        R.string.core_copy_open,
        R.string.core_keep_now,
        R.string.core_undo_failed,
        R.string.core_no_pick,
        R.string.core_rotate_90,
        R.string.core_keep_one,
        R.string.core_move_to,
        R.string.core_insert_at,
        R.string.core_insert_empty,
        R.string.core_password,
        R.string.core_saved_not_pdf,
        R.string.core_saved_password,
        R.string.core_saved_annots,
        R.string.core_saved_check,
        R.string.core_write_failed,
        R.string.core_write_differs,
        R.string.core_partial_left,
        R.string.core_partial_differs,
        R.string.core_unreadable,
        R.string.core_open_write,
        R.string.core_reread_differs,
        R.string.core_rec_dir,
        R.string.core_same_source,
        R.string.core_keep_read,
        R.string.core_keep_dir,
        R.string.core_kept_differs,
        R.string.core_keep_failed,
        R.string.core_rec_gone,
        R.string.core_rec_changed,
        R.string.core_mark_file,
        R.string.core_share_dir,
        R.string.core_share_differs,
        R.string.core_share_failed,
        R.string.core_dir_failed,
        R.string.core_work_copy,
        R.string.core_no_workdir,
    )

    private val ARG = Regex("%(\\d)\\\$s")
    private var templates: List<Pair<Int, Regex>>? = null

    /** 한국어 틀을 정규식으로(한 번만 만든다). */
    private fun templates(ctx: Context): List<Pair<Int, Regex>> = templates ?: run {
        val conf = Configuration(ctx.resources.configuration).apply { setLocale(Locale.KOREAN) }
        val ko = ctx.createConfigurationContext(conf).resources
        IDS.map { id ->
            val parts = ARG.split(ko.getString(id)).map { Regex.escape(it) }
            id to Regex(parts.joinToString("(.*)"), RegexOption.DOT_MATCHES_ALL)
        }.also { templates = it }
    }

    /** [msg]를 지금 화면 언어로. 한국어 화면이거나 아는 틀이 없으면 그대로. */
    fun localize(ctx: Context, msg: String): String {
        if (ctx.resources.configuration.locales[0].language == "ko") return msg
        for ((id, re) in templates(ctx)) {
            val m = re.matchEntire(msg) ?: continue
            val args = m.groupValues.drop(1).map { localize(ctx, it) }
            return if (args.isEmpty()) ctx.getString(id) else ctx.getString(id, *args.toTypedArray())
        }
        return msg
    }
}
