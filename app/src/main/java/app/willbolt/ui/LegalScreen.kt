package app.willbolt.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/** The texts shown inside the app: copies of the repository's PRIVACY.md and THIRD_PARTY_NOTICES.md, made at build time. */
enum class LegalDoc(val title: String, val assets: List<String>) {
    Privacy("Privacy policy", listOf("legal/PRIVACY.md")),
    Licences("Open-source licences", listOf("legal/THIRD_PARTY_NOTICES.md", "nsfw/LICENSE-APACHE-2.0.txt")),
}

private sealed interface Block {
    class Heading(val level: Int, val text: String) : Block
    class Para(val text: String, val quote: Boolean = false) : Block
    class Bullet(val text: String) : Block
}

/** Just enough Markdown for those files: headings, bullets, quotes and paragraphs whose lines were wrapped by hand. */
private fun parseBlocks(src: String): List<Block> {
    val out = ArrayList<Block>()
    val buf = StringBuilder()
    var kind = 0 // 1 paragraph, 2 bullet, 3 quote
    fun flush() {
        if (buf.isNotEmpty()) {
            out += when (kind) {
                2 -> Block.Bullet(buf.toString())
                3 -> Block.Para(buf.toString(), quote = true)
                else -> Block.Para(buf.toString())
            }
            buf.setLength(0)
        }
        kind = 0
    }
    for (raw in src.lines()) {
        val line = raw.trimEnd()
        when {
            line.isBlank() || line.trim() == ">" -> flush()
            line.startsWith("#") -> {
                flush()
                val level = line.takeWhile { it == '#' }.length
                out += Block.Heading(level, line.drop(level).trim())
            }
            line.startsWith("* ") || line.startsWith("- ") -> {
                flush()
                kind = 2
                buf.append(line.drop(2).trim())
            }
            line.startsWith("> ") -> {
                if (kind != 3) {
                    flush()
                    kind = 3
                }
                if (buf.isNotEmpty()) buf.append(' ')
                buf.append(line.drop(2).trim())
            }
            else -> {
                if (kind == 0) kind = 1
                if (buf.isNotEmpty()) buf.append(' ')
                buf.append(line.trim())
            }
        }
    }
    flush()
    return out
}

private val inlineMarks = Regex("""\*\*(.+?)\*\*|\*(.+?)\*|`(.+?)`|<(https?://[^>\s]+)>""")

/** **bold**, *italic*, `code` and <https://links>. */
private fun inline(text: String, linkColor: androidx.compose.ui.graphics.Color): AnnotatedString = buildAnnotatedString {
    var at = 0
    for (m in inlineMarks.findAll(text)) {
        append(text.substring(at, m.range.first))
        val (bold, italic, code, url) = m.destructured
        when {
            bold.isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(bold) }
            italic.isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(italic) }
            code.isNotEmpty() -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(code) }
            else -> withLink(
                LinkAnnotation.Url(url, TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))),
            ) { append(url) }
        }
        at = m.range.last + 1
    }
    append(text.substring(at))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LegalScreen(doc: LegalDoc, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val blocks = remember(doc) {
        val text = doc.assets.joinToString("\n\n") { name ->
            try {
                ctx.assets.open(name).bufferedReader().use { it.readText() }
            } catch (_: Throwable) {
                ""
            }
        }
        parseBlocks(text)
    }
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(doc.title) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(LineIcons.Back, contentDescription = "Back") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = cs.background),
        )
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(blocks) { b ->
                when (b) {
                    is Block.Heading -> Text(
                        inline(b.text, cs.primary),
                        style = if (b.level == 1) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = if (b.level == 1) 4.dp else 14.dp),
                    )
                    is Block.Bullet -> Row {
                        Text("•", Modifier.padding(end = 10.dp, start = 4.dp), style = MaterialTheme.typography.bodyMedium)
                        Text(inline(b.text, cs.primary), style = MaterialTheme.typography.bodyMedium)
                    }
                    is Block.Para -> Text(
                        inline(b.text, cs.primary),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (b.quote) cs.onSurfaceVariant else cs.onSurface,
                        modifier = if (b.quote) Modifier.padding(start = 12.dp) else Modifier,
                    )
                }
            }
        }
    }
}
